import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import machine.Machine;
import machine.CompilationResult;
import machine.ParseException;
import machine.SourceWorkspace;
import machine.SourceWorkspace.Document;

/**
 * Run from the repository root without using the existing machine/*.class files:
 *
 * test_build=$(mktemp -d)
 * javac -d "$test_build" -classpath "$test_build" -sourcepath . tests/SourceWorkspaceTest.java
 * java -cp "$test_build" SourceWorkspaceTest
 */
public class SourceWorkspaceTest {
    private static int passed;
    private static int failed;
    private static Path temporaryFiles;

    @FunctionalInterface
    private interface Test {
        void run() throws Exception;
    }

    public static void main(String[] args) throws Exception {
        temporaryFiles = Files.createTempDirectory("source-workspace-test-");
        try {
            runTests();
            System.out.println("SourceWorkspaceTest: " + passed + " passed, " + failed + " failed");
            if (failed != 0) {
                throw new AssertionError(failed + " source workspace regression test(s) failed");
            }
        } finally {
            try (Stream<Path> paths = Files.walk(temporaryFiles)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) {
                    Files.delete(path);
                }
            }
        }
    }

    private static void runTests() throws Exception {
        check("opening a library retains its buffer without selecting a main", () -> {
            Path directory = directory();
            String text = "// begin:\nvoid library() {\nbegin: NOP;\n}\n";
            Path library = write(directory.resolve("library.ad04"), text);
            SourceWorkspace workspace = SourceWorkspace.open(library);
            Document document = workspace.getDocuments().get(0);
            expect(text, document.getText(), "library text remains editable");
            expect(false, document.hasBeginMarker(), "commented and nested markers are ignored");
            expect(null, workspace.getMainDocument(), "library does not become main");
            ParseException failure = expectFailure(ParseException.class, () -> compile(workspace));
            expect(true, failure.getMessage().contains("main") && failure.getMessage().contains("begin:"),
                "no-main diagnostic explains the required entry marker");
            document.setText("var value=3; begin: HALT;");
            expect(true, document.hasBeginMarker(), "marker eligibility follows current buffer text");
            expect(null, workspace.getMainDocument(), "the model does not activate eligible edits implicitly");
            workspace.setMainDocument(document);
            expect(3, execute(workspace).get(1), "explicit activation compiles the edited buffer");
        });

        check("opening more source files preserves edits and never changes main automatically", () -> {
            Path directory = directory();
            Path first = write(directory.resolve("first.ad04"), "var value=1; begin: HALT;");
            Path second = write(directory.resolve("second.ad04"), "var value=2; begin: HALT;");
            Path alias = Files.createSymbolicLink(directory.resolve("alias.ad04"), second.getFileName());
            SourceWorkspace workspace = SourceWorkspace.open(first);
            Document initial = workspace.getMainDocument();
            initial.setText("var value=3; begin: HALT;");
            Document added = workspace.openDocument(second);
            expect(initial, workspace.getMainDocument(), "opening another program preserves active main");
            expect(3, execute(workspace).get(1), "main's unsaved edits are retained");
            added.setText("var value=4; begin: HALT;");
            expect(added, workspace.openDocument(alias), "opening an alias reuses the cached buffer");
            expect(List.of(initial, added), workspace.getDocuments(), "both files keep opening order");
            workspace.setMainDocument(added);
            expect(4, execute(workspace).get(1), "activated program compiles its current buffer");
            expect(second.toRealPath(), compileMapped(workspace).sourceMap().values().iterator().next().location().file(),
                "compiled source mapping uses the selected main");
            Files.delete(second);
            expect(added, workspace.openDocument(alias), "cached aliases remain usable after deletion");
            expect(4, execute(workspace).get(1), "deleted file still compiles from the active buffer");
            workspace.setMainDocument(initial);
            expect(3, execute(workspace).get(1), "switching back preserves earlier edits");
            expect("var value=1; begin: HALT;", read(first), "activation never saves source files");
        });

        check("invalid main selections preserve the current valid program", () -> {
            Path path = write(directory().resolve("main.ad04"), "begin: HALT;");
            SourceWorkspace workspace = SourceWorkspace.open(path);
            Document original = workspace.getMainDocument();
            Document invalid = workspace.newDocument();
            Document foreign = SourceWorkspace.create().getDocuments().get(0);
            foreign.setText("begin: HALT;");
            for (String text : List.of("", "// begin:", "void library() {begin:}", "begin :", "_begin:")) {
                invalid.setText(text);
                expectFailure(IllegalArgumentException.class, () -> workspace.setMainDocument(invalid));
                expect(original, workspace.getMainDocument(), "invalid marker does not replace main");
            }
            expectFailure(IllegalArgumentException.class, () -> workspace.setMainDocument(foreign));
            expectFailure(IllegalArgumentException.class, () -> workspace.setMainDocument(null));
            expect(original, workspace.getMainDocument(), "invalid ownership or null leaves main intact");
            compile(workspace);
        });

        check("removing the active marker clears main without selecting another eligible tab", () -> {
            Path path = write(directory().resolve("main.ad04"), "begin: HALT;");
            SourceWorkspace workspace = SourceWorkspace.open(path);
            Document initial = workspace.getMainDocument();
            Document alternate = workspace.newDocument();
            alternate.setText("begin: NOP; HALT;");
            initial.setText("// begin: removed while editing");
            expect(null, workspace.getMainDocument(), "getMainDocument never exposes an ineligible document");
            expectFailure(ParseException.class, () -> compile(workspace));
            initial.setText("begin: HALT;");
            expect(null, workspace.getMainDocument(), "restoring a marker requires explicit activation");
            workspace.setMainDocument(alternate);
            compile(workspace);
            alternate.setText("void library() {begin:}");
            expectFailure(ParseException.class, () -> compile(workspace));
            expect(null, workspace.getMainDocument(), "compilation also invalidates a removed top-level marker");
        });

        check("any eligible unnamed draft can be activated without reordering documents", () -> {
            SourceWorkspace workspace = SourceWorkspace.create();
            Document first = workspace.getDocuments().get(0);
            Document second = workspace.newDocument();
            second.setText("var value=8; begin: HALT;");
            expect(null, workspace.getMainDocument(), "new drafts never choose main implicitly");
            workspace.setMainDocument(second);
            expect(8, execute(workspace).get(1), "a later unnamed draft can be main");
            expect(List.of(first, second), workspace.getDocuments(), "activation does not change tab order");
            Path saved = directory().resolve("selected.ad04");
            workspace.saveAs(second, saved);
            expect(second, workspace.getMainDocument(), "first save preserves active document identity");
            expect(8, execute(workspace).get(1), "saved selected draft remains compilable");
        });

        check("openDocument preserves a missing main even when a valid program is opened", () -> {
            SourceWorkspace workspace = SourceWorkspace.create();
            Document blank = workspace.getDocuments().get(0);
            Path file = write(directory().resolve("program.ad04"), "var value=; begin: HALT;");
            Document program = workspace.openDocument(file);
            expect(true, program.hasBeginMarker(), "eligibility is lexical rather than whole-program validation");
            expect(null, workspace.getMainDocument(), "opening alone does not activate the program");
            expect(List.of(blank, program), workspace.getDocuments(), "opening appends without replacing the blank draft");
            workspace.setMainDocument(program);
            expectFailure(ParseException.class, () -> compile(workspace));
            expect(program, workspace.getMainDocument(), "an ordinary syntax error does not remove the main marker");
        });

        check("new workspaces contain one clean unnamed draft without an active main", () -> {
            SourceWorkspace workspace = SourceWorkspace.create();
            Document main = workspace.getDocuments().get(0);
            expect(null, main.getPath(), "new main has no file path");
            expect(true, main.isUntitled(), "main is unnamed");
            expect("Untitled", main.getDisplayName(), "initial tab name");
            expect("", main.getText(), "initial buffer is empty");
            expect(false, workspace.isModified(), "untouched drafts are clean");
            expect(List.of(main), workspace.getDocuments(), "draft is the first document");
            expect(null, workspace.getMainDocument(), "empty workspaces have no main program");
            workspace.saveAll();
            IOException failure = expectFailure(IOException.class, () -> workspace.save(main));
            expect(true, failure.getMessage().contains("path"), "ordinary save requires a path");
            expectFailure(ParseException.class, () -> compile(workspace));
        });

        check("repeated new documents preserve opening order and the main program", () -> {
            SourceWorkspace workspace = SourceWorkspace.create();
            Document main = workspace.getDocuments().get(0);
            main.setText("var value=2; begin: INC value; HALT;");
            workspace.setMainDocument(main);
            List<Document> initial = workspace.getDocuments();
            Document second = workspace.newDocument();
            Document third = workspace.newDocument();
            expect("Untitled 2", second.getDisplayName(), "second draft name");
            expect("Untitled 3", third.getDisplayName(), "third draft name");
            expect(List.of(main, second, third), workspace.getDocuments(), "draft opening order");
            expect(List.of(main), initial, "earlier document snapshots remain stable");
            expect(main, workspace.getMainDocument(), "new tabs never replace main");
            second.setText("this is deliberately not valid source");
            expect(3, execute(workspace).get(1), "unrelated drafts are not compiled");
            expect(true, second.isModified(), "draft edits are tracked");
            second.setText("");
            expect(false, second.isModified(), "reverting an unnamed draft clears dirty state");
        });

        check("unnamed compilation and first save preserve document and snapshot identities", () -> {
            SourceWorkspace workspace = SourceWorkspace.create();
            Document main = workspace.getDocuments().get(0);
            String text = "// Grüße 😀\r\nvar value=1;\rbegin: INC value; HALT;";
            main.setText(text);
            workspace.setMainDocument(main);
            CompilationResult unnamed = compileMapped(workspace);
            expect(0, unnamed.sources().size(), "unnamed source has no file snapshot key");
            expect(true, unnamed.sourceMap().values().stream()
                .allMatch(trace -> trace.location().file() == null), "unnamed traces carry no path");
            Path target = directory().resolve("created.ad04");
            expect(false, Files.exists(target), "compilation did not create a source file");
            workspace.saveAs(main, target);
            expect(target.toRealPath(), main.getPath(), "saved identity uses the canonical path");
            expect("created.ad04", main.getDisplayName(), "first save changes the display name");
            expect(false, main.isUntitled(), "saved main is named");
            expect(false, main.isModified(), "first save establishes a clean baseline");
            expect(text, read(target), "first save preserves UTF-8 and original line endings");
            expect(List.of(main), workspace.getDocuments(), "first save retains the same document");
            CompilationResult named = compileMapped(workspace);
            expect(unnamed.bytecode(), named.bytecode(), "naming does not alter compilation");
            expect(text.replace("\r\n", "\n").replace('\r', '\n'), named.sources().get(target.toRealPath()),
                "new source snapshots use the named identity and normalized text");
            expect(true, named.sourceMap().values().stream()
                .allMatch(trace -> trace.location().file().equals(main.getPath())), "new traces use the saved path");
            expect(true, unnamed.sourceMap().values().stream()
                .allMatch(trace -> trace.location().file() == null), "previous snapshots remain anonymous");
        });

        check("first save writes clean empty drafts and later named saves use their baseline", () -> {
            SourceWorkspace workspace = SourceWorkspace.create();
            Document main = workspace.getDocuments().get(0);
            Path target = directory().resolve("empty.ad04");
            workspace.saveAs(main, target);
            expect(true, Files.isRegularFile(target), "empty first save creates a file");
            expect("", read(target), "empty buffer is saved exactly");
            Document extra = workspace.newDocument();
            main.setText("begin: HALT;");
            workspace.saveAll();
            expect("begin: HALT;", read(target), "save all writes named edits");
            expect(null, extra.getPath(), "save all skips clean unnamed drafts");
            expect(false, workspace.isModified(), "successful saves leave a clean workspace");
            Path other = target.resolveSibling("other.ad04");
            expectFailure(IllegalArgumentException.class, () -> workspace.saveAs(main, other));
            expect(false, Files.exists(other), "save as rejects already-named documents before writing");
            expect(target.toRealPath(), main.getPath(), "rejected rename preserves identity");
        });

        check("first save follows existing symlinks and registers the draft for imports", () -> {
            Path directory = directory();
            Path target = write(directory.resolve("library.ad04"), "// old content\n");
            Path alias = Files.createSymbolicLink(directory.resolve("alias.ad04"), target.getFileName());
            SourceWorkspace workspace = SourceWorkspace.create();
            Document main = workspace.getDocuments().get(0);
            main.setText("var value=0; begin:\n" + importLine(alias) + importLine(target) + "HALT;");
            workspace.setMainDocument(main);
            Document draft = workspace.newDocument();
            draft.setText("INC value;\n");
            workspace.saveAs(draft, alias);
            expect(target.toRealPath(), draft.getPath(), "existing symlink resolves to target identity");
            expect(true, Files.isSymbolicLink(alias), "saving preserves the symlink");
            expect("INC value;\n", read(target), "saving updates the real target");
            draft.setText("INC value; INC value;\n");
            expect(4, execute(workspace).get(1), "both imports use the saved draft's edited buffer");
            expect(List.of(main, draft), workspace.getDocuments(), "imports reuse the draft tab");
            Files.delete(target);
            expect(4, execute(workspace).get(1), "registered import aliases survive file deletion");
        });

        check("new save targets resolve real parents before parent-directory components", () -> {
            Path directory = directory();
            Files.createDirectories(directory.resolve("actual/child"));
            Files.createSymbolicLink(directory.resolve("link"), Path.of("actual/child"));
            SourceWorkspace workspace = SourceWorkspace.create();
            Document main = workspace.getDocuments().get(0);
            main.setText("begin: HALT;");
            workspace.saveAs(main, directory.resolve("link/../new.ad04"));
            Path actual = directory.resolve("actual/new.ad04");
            expect(actual.toRealPath(), main.getPath(), "new files use the real parent's identity");
            expect("begin: HALT;", read(actual), "save reaches the operating system's intended target");
            expect(false, Files.exists(directory.resolve("new.ad04")), "lexical normalization did not redirect the save");
        });

        check("first save follows dangling leaf symlinks to a newly created target", () -> {
            Path directory = directory();
            Path target = directory.resolve("new.ad04");
            Path alias = Files.createSymbolicLink(directory.resolve("alias.ad04"), target.getFileName());
            SourceWorkspace workspace = SourceWorkspace.create();
            Document main = workspace.getDocuments().get(0);
            main.setText("begin: HALT;");
            workspace.saveAs(main, alias);
            expect(target.toRealPath(), main.getPath(), "dangling symlink receives the new target's identity");
            expect("begin: HALT;", read(target), "new symlink target contains the draft");
            expect(true, Files.isSymbolicLink(alias), "dangling symlink is preserved");
        });

        check("first save rejects already-open targets and aliases before writing", () -> {
            Path directory = directory();
            Path mainPath = write(directory.resolve("main.ad04"), "begin: HALT;");
            Path alias = Files.createSymbolicLink(directory.resolve("alias.ad04"), mainPath.getFileName());
            SourceWorkspace workspace = SourceWorkspace.open(mainPath);
            Document draft = workspace.newDocument();
            draft.setText("begin: NOP; HALT;");
            for (Path target : List.of(mainPath, alias)) {
                IOException failure = expectFailure(IOException.class, () -> workspace.saveAs(draft, target));
                expect(true, failure.getMessage().contains("already open"), "collision explains the open document");
                expect("begin: HALT;", read(mainPath), "collision never overwrites the open file");
                expect(null, draft.getPath(), "collision leaves draft unnamed");
                expect(true, draft.isModified(), "collision preserves unsaved changes");
            }
            Files.delete(mainPath);
            expectFailure(IOException.class, () -> workspace.saveAs(draft, mainPath));
            expect(false, Files.exists(mainPath), "an opened-but-deleted path remains reserved");
        });

        check("failed first saves preserve unnamed state and can be retried", () -> {
            Path directory = directory();
            SourceWorkspace workspace = SourceWorkspace.create();
            Document main = workspace.getDocuments().get(0);
            String edited = "// Grüße\r\nbegin: HALT;";
            main.setText(edited);
            Path blocked = Files.createDirectory(directory.resolve("blocked.ad04"));
            for (Path target : List.of(directory.resolve("missing/child.ad04"), blocked)) {
                expectFailure(IOException.class, () -> workspace.saveAs(main, target));
                expect(null, main.getPath(), "failed first save keeps its path unset");
                expect("Untitled", main.getDisplayName(), "failed save retains draft name");
                expect(edited, main.getText(), "failed save preserves exact buffer text");
                expect(true, main.isModified(), "failed save leaves edits dirty");
                expect(List.of(main), workspace.getDocuments(), "failed save does not add document entries");
            }
            IOException failure = expectFailure(IOException.class, workspace::saveAll);
            expect(true, failure.getMessage().contains("path"), "dirty unnamed buffers need a selected path");
            Path retry = directory.resolve("retry.ad04");
            workspace.saveAs(main, retry);
            expect(edited, read(retry), "retry saves retained edits");
            expect(false, workspace.isModified(), "successful retry establishes baseline");
        });

        check("first save rejects foreign documents without changing ownership or disk", () -> {
            SourceWorkspace workspace = SourceWorkspace.create();
            Document foreign = SourceWorkspace.create().getDocuments().get(0);
            foreign.setText("begin: HALT;");
            Path target = directory().resolve("foreign.ad04");
            expectFailure(IllegalArgumentException.class, () -> workspace.saveAs(foreign, target));
            expectFailure(IllegalArgumentException.class, () -> workspace.save(foreign));
            expect(false, Files.exists(target), "foreign drafts are never written");
            expect(null, foreign.getPath(), "foreign draft remains unnamed");
            expect(true, foreign.isModified(), "foreign edits remain dirty");
            expect(1, workspace.getDocuments().size(), "foreign draft is not adopted");
        });

        check("opening reads only main and preserves its exact text", () -> {
            Path directory = directory();
            Files.createDirectory(directory.resolve("unused"));
            String text = "// Grüße\r\n" + importLine(directory.resolve("missing.ad04"))
                + "var broken = ;\r\nbegin:\rHALT;";
            Path main = write(directory.resolve("main.ad04"), text);
            SourceWorkspace workspace = SourceWorkspace.open(directory.resolve("unused/../main.ad04"));
            expect(main, workspace.getMainDocument().getPath(), "normalized main path");
            expect(text, workspace.getMainDocument().getText(), "UTF-8 text and line endings");
            expect(List.of(workspace.getMainDocument()), workspace.getDocuments(), "only main is opened");
            expect(false, workspace.isModified(), "new workspace is clean");
        });

        check("main and import edits compile without writing files", () -> {
            Path directory = directory();
            String libraryText = "void bump(a) { INC a; }\n";
            Path library = write(directory.resolve("library.ad04"), libraryText);
            String mainText = importLine(library) + "var value = 1; begin: bump(value); HALT;\n";
            Path main = write(directory.resolve("main.ad04"), mainText);
            SourceWorkspace workspace = SourceWorkspace.open(main);
            expect(2, execute(workspace).get(1), "original program");
            Document imported = workspace.getDocuments().get(1);
            imported.setText("void bump(a) { INC a; INC a; }\n");
            workspace.getMainDocument().setText(mainText.replace("value = 1", "value = 3"));
            expect(5, execute(workspace).get(1), "both edited buffers are compiled");
            expect(mainText, read(main), "compilation must not save main");
            expect(libraryText, read(library), "compilation must not save import");
            expect(true, workspace.isModified(), "unsaved edits remain dirty");
        });

        check("opened buffers remain authoritative after disk changes", () -> {
            Path directory = directory();
            Path library = write(directory.resolve("library.ad04"), "void bump(a) { INC a; }\n");
            Path main = write(directory.resolve("main.ad04"),
                importLine(library) + "var value = 1; begin: bump(value); HALT;\n");
            SourceWorkspace workspace = SourceWorkspace.open(main);
            expect(2, execute(workspace).get(1), "initial execution");
            write(main, "begin: HALT;\n");
            write(library, "void bump(a) { DEC a; }\n");
            expect(2, execute(workspace).get(1), "recompile uses existing buffers");
            workspace.saveAll();
            expect("begin: HALT;\n", read(main), "clean main is not rewritten by save all");
            expect("void bump(a) { DEC a; }\n", read(library), "clean import is not rewritten");
        });

        check("normalized duplicate imports share a document but expand twice", () -> {
            Path directory = directory();
            Files.createDirectory(directory.resolve("unused"));
            Path library = write(directory.resolve("increment.ad04"), "INC value;\n");
            Path main = write(directory.resolve("main.ad04"), "var value = 0; begin:\n"
                + importLine(library) + importLine(directory.resolve("unused/../increment.ad04"))
                + "HALT;\n");
            SourceWorkspace workspace = SourceWorkspace.open(main);
            expect(2, execute(workspace).get(1), "both import occurrences execute");
            expect(2, workspace.getDocuments().size(), "only one import document is opened");
            Document imported = workspace.getDocuments().get(1);
            expect(library, imported.getPath(), "import path is normalized");
            imported.setText("INC value; INC value;\n");
            expect(4, execute(workspace).get(1), "both occurrences use the edited document");
            expect(imported, workspace.getDocuments().get(1), "document identity is stable");
            expectFailure(UnsupportedOperationException.class, () -> workspace.getDocuments().clear());
        });

        check("symlink parent paths select the same import as the file compiler", () -> {
            Path directory = directory();
            Files.createDirectories(directory.resolve("actual/child"));
            Files.createSymbolicLink(directory.resolve("link"), Path.of("actual/child"));
            Path library = write(directory.resolve("actual/library.ad04"), "var value = 7;\n");
            Path otherLibrary = write(directory.resolve("library.ad04"), "var value = 99;\n");
            Path main = write(directory.resolve("main.ad04"),
                importLine(directory.resolve("link/../library.ad04")) + "begin: HALT;\n");
            SourceWorkspace workspace = SourceWorkspace.open(main);
            expect(compile(main), compile(workspace), "workspace and file compiler must select the same file");
            expect(7, execute(workspace).get(1), "symlink is resolved before the parent component");
            Document imported = workspace.getDocuments().get(1);
            expect(library.toRealPath(), imported.getPath(), "document identity uses the real file path");
            imported.setText("var value = 8;\n");
            workspace.save(imported);
            expect("var value = 8;\n", read(library), "save updates the actual imported file");
            expect("var value = 99;\n", read(otherLibrary), "lexically normalized file is untouched");
            Files.delete(library);
            expect(8, execute(workspace).get(1), "cached symlink-parent request survives target deletion");
        });

        check("symlink aliases share edited buffers even after files are deleted", () -> {
            Path directory = directory();
            Path library = write(directory.resolve("increment.ad04"), "INC value;\n");
            Path alias = Files.createSymbolicLink(directory.resolve("alias.ad04"), library.getFileName());
            Path main = write(directory.resolve("main.ad04"), "var value = 0; begin:\n"
                + importLine(alias) + importLine(library) + "HALT;\n");
            SourceWorkspace workspace = SourceWorkspace.open(main);
            expect(2, execute(workspace).get(1), "both alias occurrences execute");
            expect(2, workspace.getDocuments().size(), "symlink aliases open one import document");
            Document imported = workspace.getDocuments().get(1);
            imported.setText("INC value; INC value;\n");
            expect(4, execute(workspace).get(1), "both aliases read one edited buffer");
            Files.delete(library);
            Files.delete(main);
            expect(4, execute(workspace).get(1), "previously opened paths compile from buffers after deletion");
            expect(imported, workspace.getDocuments().get(1), "buffer identity survives deletion");
        });

        check("recompile discovers new imports in opening order", () -> {
            Path directory = directory();
            Path first = write(directory.resolve("first.ad04"), "INC value;\n");
            Path second = write(directory.resolve("second.ad04"), "INC value; INC value;\n");
            Path main = write(directory.resolve("main.ad04"), "var value = 0; begin: HALT;\n");
            SourceWorkspace workspace = SourceWorkspace.open(main);
            compile(workspace);
            List<Document> initiallyOpened = workspace.getDocuments();
            workspace.getMainDocument().setText("var value = 0; begin:\n" + importLine(first) + "HALT;\n");
            expect(1, execute(workspace).get(1), "first newly imported buffer");
            Document firstDocument = workspace.getDocuments().get(1);
            workspace.getMainDocument().setText("var value = 0; begin:\n"
                + importLine(second) + importLine(first) + "HALT;\n");
            expect(3, execute(workspace).get(1), "both imports compile in source order");
            expect(List.of(main, first, second), workspace.getDocuments().stream()
                .map(Document::getPath).collect(Collectors.toList()), "opening order is retained");
            expect(firstDocument, workspace.getDocuments().get(1), "existing document is reused");
            expect(1, initiallyOpened.size(), "document lists are stable snapshots");
        });

        check("syntax errors retain readable imports for repair", () -> {
            Path directory = directory();
            Path library = write(directory.resolve("broken.ad04"), "var value = ;\n");
            Path main = write(directory.resolve("main.ad04"), importLine(library) + "begin: HALT;\n");
            SourceWorkspace workspace = SourceWorkspace.open(main);
            expectFailure(ParseException.class, () -> compile(workspace));
            expect(2, workspace.getDocuments().size(), "failing import remains open");
            Document imported = workspace.getDocuments().get(1);
            expect("var value = ;\n", imported.getText(), "invalid source stays editable");
            imported.setText("var value = 7;\n");
            expect(7, execute(workspace).get(1), "repair compiles from the same buffer");
            expect("var value = ;\n", read(library), "repair is not implicitly saved");
        });

        check("failed import reads retain already opened documents", () -> {
            Path directory = directory();
            Path library = write(directory.resolve("library.ad04"), "// readable import\n");
            Path missing = directory.resolve("missing.ad04");
            Path main = write(directory.resolve("main.ad04"),
                importLine(library) + importLine(missing) + "begin: HALT;\n");
            SourceWorkspace workspace = SourceWorkspace.open(main);
            IOException failure = expectFailure(IOException.class, () -> compile(workspace));
            expect(true, failure.getMessage().contains(missing.toString()), "read error identifies the file");
            expect(2, workspace.getDocuments().size(), "earlier readable import stays open");
            write(missing, "// now available\n");
            compile(workspace);
            expect(3, workspace.getDocuments().size(), "retry opens the newly available file");
        });

        check("save selected and save all preserve UTF-8 text and dirty baselines", () -> {
            Path directory = directory();
            String originalLibrary = "// Grüße\r\nvoid bump(a) { INC a; }\r\n";
            Path library = write(directory.resolve("library.ad04"), originalLibrary);
            String originalMain = importLine(library) + "var value = 0;\r\nbegin: bump(value); HALT;\r\n";
            Path main = write(directory.resolve("main.ad04"), originalMain);
            SourceWorkspace workspace = SourceWorkspace.open(main);
            compile(workspace);
            Document mainDocument = workspace.getMainDocument();
            Document imported = workspace.getDocuments().get(1);
            mainDocument.setText(originalMain + "// edit");
            expect(true, mainDocument.isModified(), "editing marks a document dirty");
            mainDocument.setText(originalMain);
            expect(false, workspace.isModified(), "reverting to saved text clears dirty status");
            String editedMain = originalMain + "// Änderung ohne Schlusszeile";
            String editedLibrary = originalLibrary + "\n// Grüße\r";
            mainDocument.setText(editedMain);
            imported.setText(editedLibrary);
            workspace.save(imported);
            expect(editedLibrary, read(library), "selected import text is saved exactly");
            expect(originalMain, read(main), "selected save leaves main alone");
            expect(false, imported.isModified(), "saved import is clean");
            expect(true, workspace.isModified(), "main remains dirty");
            workspace.saveAll();
            expect(editedMain, read(main), "save all preserves main line endings and final line");
            expect(false, workspace.isModified(), "all successful saves clear dirty status");
            mainDocument.setText(originalMain);
            expect(true, mainDocument.isModified(), "dirty status compares against the latest save");
        });

        check("save failures retain unsaved buffers and can be retried", () -> {
            Path directory = directory();
            Path main = write(directory.resolve("main.ad04"), "begin: HALT;\n");
            SourceWorkspace workspace = SourceWorkspace.open(main);
            Document document = workspace.getMainDocument();
            String edited = "begin: NOP; HALT;\n";
            document.setText(edited);
            Files.delete(main);
            Files.createDirectory(main);
            IOException failure = expectFailure(IOException.class, () -> workspace.save(document));
            expect(true, failure.getMessage().contains(main.toString()), "save error identifies the file");
            expect(edited, document.getText(), "failed save preserves the editor buffer");
            expect(true, document.isModified(), "failed save keeps the document dirty");
            Files.delete(main);
            workspace.save(document);
            expect(edited, read(main), "retry writes the retained text");
            expect(false, workspace.isModified(), "successful retry clears dirty status");
        });

        check("save all retains dirty documents after a partial write failure", () -> {
            Path directory = directory();
            Path first = write(directory.resolve("first.ad04"), "// first\n");
            Path second = write(directory.resolve("second.ad04"), "// second\n");
            Path main = write(directory.resolve("main.ad04"),
                importLine(first) + importLine(second) + "begin: HALT;\n");
            SourceWorkspace workspace = SourceWorkspace.open(main);
            compile(workspace);
            for (Document document : workspace.getDocuments()) {
                document.setText(document.getText() + "// edited\n");
            }
            Files.delete(first);
            Files.createDirectory(first);
            expectFailure(IOException.class, workspace::saveAll);
            expect(false, workspace.getMainDocument().isModified(), "successful earlier save is clean");
            expect(true, workspace.getDocuments().get(1).isModified(), "failed document stays dirty");
            expect(true, workspace.getDocuments().get(2).isModified(), "unwritten later document stays dirty");
            expect("// second\n", read(second), "later document was not saved");
        });

        check("removed dirty imports remain open without affecting compilation", () -> {
            Path directory = directory();
            Path library = write(directory.resolve("library.ad04"), "// initial import\n");
            Path main = write(directory.resolve("main.ad04"), importLine(library) + "begin: HALT;\n");
            SourceWorkspace workspace = SourceWorkspace.open(main);
            compile(workspace);
            Document imported = workspace.getDocuments().get(1);
            imported.setText("var broken = ;\n");
            workspace.getMainDocument().setText("begin: HALT;\n");
            compile(workspace);
            expect(imported, workspace.getDocuments().get(1), "removed document is retained");
            expect(true, imported.isModified(), "removed document retains unsaved edits");
            workspace.saveAll();
            expect("var broken = ;\n", read(library), "save all includes retained documents");
            expect(false, workspace.isModified(), "retained document becomes clean after saving");
        });

        check("save rejects a document owned by another workspace", () -> {
            Path main = write(directory().resolve("main.ad04"), "begin: HALT;\n");
            SourceWorkspace workspace = SourceWorkspace.open(main);
            Document foreign = SourceWorkspace.open(main).getMainDocument();
            foreign.setText("begin: NOP; HALT;\n");
            expectFailure(IllegalArgumentException.class, () -> workspace.save(foreign));
            expect("begin: HALT;\n", read(main), "foreign document must not be written");
            expect(true, foreign.isModified(), "foreign dirty state remains unchanged");
        });
    }

    private static Path directory() throws IOException {
        return Files.createTempDirectory(temporaryFiles, "case-");
    }

    private static Path write(Path path, String text) throws IOException {
        return Files.writeString(path, text, StandardCharsets.UTF_8);
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static String importLine(Path path) {
        return "import \"" + path + "\";\n";
    }

    private static List<Integer> compile(SourceWorkspace workspace) throws Exception {
        PrintStream previousOutput = System.out;
        try (PrintStream compilerOutput = new PrintStream(new ByteArrayOutputStream())) {
            System.setOut(compilerOutput);
            return workspace.compile();
        } finally {
            System.setOut(previousOutput);
        }
    }

    private static CompilationResult compileMapped(SourceWorkspace workspace) throws Exception {
        PrintStream previousOutput = System.out;
        try (PrintStream compilerOutput = new PrintStream(new ByteArrayOutputStream())) {
            System.setOut(compilerOutput);
            return workspace.compileWithSourceMap();
        } finally {
            System.setOut(previousOutput);
        }
    }

    private static List<Integer> compile(Path main) throws Exception {
        PrintStream previousOutput = System.out;
        try (PrintStream compilerOutput = new PrintStream(new ByteArrayOutputStream())) {
            System.setOut(compilerOutput);
            return machine.Compiler.compile(main.toString());
        } finally {
            System.setOut(previousOutput);
        }
    }

    private static Machine execute(SourceWorkspace workspace) throws Exception {
        Machine machine = new Machine();
        machine.load(compile(workspace));
        machine.step(10000);
        expect(true, machine.isHalted(), "program must halt within 10000 steps");
        return machine;
    }

    private static <T extends Throwable> T expectFailure(Class<T> type, Test action) throws Exception {
        try {
            action.run();
        } catch (Throwable error) {
            if (type.isInstance(error)) {
                return type.cast(error);
            }
            throw new AssertionError("expected " + type.getSimpleName() + ", got " + error, error);
        }
        throw new AssertionError("expected " + type.getSimpleName());
    }

    private static void check(String name, Test test) {
        try {
            test.run();
            ++passed;
            System.out.println("PASS " + name);
        } catch (Exception | AssertionError error) {
            ++failed;
            System.err.println("FAIL " + name + ": " + error);
            error.printStackTrace(System.err);
        }
    }

    private static void expect(Object expected, Object actual, String description) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(description + ": expected " + expected + ", got " + actual);
        }
    }
}

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import machine.Machine;
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
        if (!expected.equals(actual)) {
            throw new AssertionError(description + ": expected " + expected + ", got " + actual);
        }
    }
}

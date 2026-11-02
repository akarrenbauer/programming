package machine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

/** Standalone diagrams share the real compiler and unsaved workspace buffers, without game restrictions. */
public final class StandaloneDiagramValidationTest {
    private static int passed;
    @FunctionalInterface private interface Check { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        check("standalone previews accept full-grammar programs beyond game declaration limits", () -> {
            SourceWorkspace workspace = SourceWorkspace.create();
            SourceWorkspace.Document document = workspace.getDocuments().get(0);
            String source = "var cells[300]=0;\nvar large=1000000;\nbegin:\nvar work=5001;\nINC cells[299];\nHALT;\n";
            document.setText(source);
            workspace.setMainDocument(document);
            DiagramValidation.Result checked = validate(workspace, document, source, true);
            expect(checked.valid(), checked.message());
            expect(checked.diagram().source().equals(source) && checked.diagram().hasMain(), "exact standalone source tree");
            expect(!DiagramValidation.validate(source, EnumSet.allOf(GameFeature.class), Map.of(), LanguageVersion.STRUCTURED, true).valid(),
                    "the game's separate resource policy is retained");
            expect(document.getText().equals(source), "preview leaves the open buffer unchanged");
            String parameters = java.util.stream.IntStream.range(0, 33).mapToObj(index -> "p" + index).collect(java.util.stream.Collectors.joining(","));
            String arguments = java.util.stream.IntStream.range(0, 33).mapToObj(index -> "n").collect(java.util.stream.Collectors.joining(","));
            String wideMacro = "void wide(" + parameters + ") { NOP; }\nvar n=0; begin: wide(" + arguments + "); HALT;";
            expect(validate(workspace, document, wideMacro, true).valid(), "full compiler macro arity is not restricted by the game maximum");
        });

        check("helper-only parsing preserves exact source and its edit profile without adding begin", () -> {
            String source = "// helper only\r\nvoid bump(cell) {\r\n  INC cell;\r\n}\r\n";
            StructuredDiagram parsed = StructuredDiagram.parseDocument(source, LanguageVersion.STRUCTURED, true);
            expect(!parsed.hasMain() && parsed.main().nodes().isEmpty() && parsed.source().equals(source), "library has no invented main");
            StructuredDiagram changed = parsed.edit(parsed.preamble().nodes().get(0).body().nodes().get(0), "DEC cell;");
            expect(!changed.hasMain() && changed.source().equals(source.replace("INC cell;", "DEC cell;")), "helper edits retain optional-main parsing");
            changed = changed.insert(changed.preamble().nodes().get(0).body(), 1, "NOP;");
            changed = changed.delete(changed.preamble().nodes().get(0).body().nodes().get(1));
            expect(!changed.hasMain() && !changed.source().contains("begin:"), "insert/delete cannot silently add an entry point");
            try { StructuredDiagram.parse(source); throw new AssertionError("game parser accepted missing begin"); }
            catch (StructuredDiagram.ParseException expected) { }
            SourceWorkspace workspace = SourceWorkspace.create();
            SourceWorkspace.Document document = workspace.getDocuments().get(0);
            document.setText(source);
            DiagramValidation.Result checked = validate(workspace, document, source, false);
            expect(checked.valid() && !checked.diagram().hasMain() && document.getText().equals(source), checked.message());
            expect(workspace.getMainDocument() == null && workspace.getDocuments().size() == 1, "compiler-only helper harness cannot activate or create a buffer");
            expect(validate(workspace, document, "", false).valid(), "an empty standalone buffer has an empty helper diagram");
        });

        check("real compiler diagnostics and representation-only JNZ restrictions remain distinct", () -> {
            SourceWorkspace workspace = SourceWorkspace.create();
            SourceWorkspace.Document document = workspace.getDocuments().get(0);
            String wrong = "// comment\r\nvar n=0;\r\nbegin:\r\nINC missing;\r\nHALT;\r\n";
            DiagramValidation.Result bad = validate(workspace, document, wrong, true);
            expect(!bad.valid() && bad.message().contains("Unknown variable missing"), "real compiler rejects unknown cells");
            expect(bad.errors().get(0).startOffset() == wrong.indexOf("missing"), "diagnostic uses exact raw CRLF source offsets");
            String jump = "var n=0; begin: loop: JNZ n loop; HALT;";
            expect(validate(workspace, document, jump, true).valid(), "flowchart accepts final-grammar explicit JNZ");
            DiagramValidation.Result structured = validate(workspace, document, jump, false);
            expect(!structured.valid() && structured.message().contains("Use the flowchart") && !structured.message().contains("suspended"),
                    "NS explains its representation limitation without claiming a language lock");
            expect(!validate(workspace, document, "var n=0; begin: missing(n); HALT;", true).valid(), "actual macro expansion failures stay invalid");
            expect(!validate(workspace, document, "begin: do {", true).valid(), "incomplete source stays invalid");
        });

        check("selected imported helpers validate in active-main context without mutating candidates or disk", () -> {
            Path directory = Files.createTempDirectory("standalone-helper-context-");
            Path library = directory.resolve("helpers.a26"), main = directory.resolve("main.a26");
            String diskHelper = "void bump(cell) {\nINC cell;\n}\n";
            String helper = "// uses main globals\nvoid bump(cell) {\nINC total;\nINC cell;\n}\n";
            String program = "import \"" + library + "\";\nvar n=0;\nvar total=0;\nbegin:\nbump(n);\nHALT;\n";
            Files.writeString(library, diskHelper); Files.writeString(main, program);
            SourceWorkspace workspace = SourceWorkspace.open(main);
            SourceWorkspace.Document selected = workspace.openDocument(library);
            selected.setText(helper);
            SourceWorkspace.Document active = workspace.getMainDocument();
            DiagramValidation.Result checked = validate(workspace, selected, helper, false);
            expect(checked.valid() && !checked.diagram().hasMain(), "helper can refer to globals supplied by its importing main: " + checked.message());
            String candidate = helper.replace("INC total;", "DEC total;");
            expect(validate(workspace, selected, candidate, false).valid(), "candidate uses the same main context");
            expect(selected.getText().equals(helper) && selected.isModified(), "preview does not install or save candidate edits");
            expect(Files.readString(library).equals(diskHelper) && Files.readString(main).equals(program), "neither source file is written");
            expect(workspace.getMainDocument() == active && active.getText().equals(program), "preview never changes the active main program");
            DiagramValidation.Result bad = validate(workspace, selected, candidate.replace("DEC total;", "DEC absent;"), false);
            expect(!bad.valid() && bad.errors().get(0).startOffset() == candidate.replace("DEC total;", "DEC absent;").indexOf("absent"),
                    "a bad candidate points into the selected helper's own text");
        });

        check("main previews use unsaved import buffers and locate bad imports instead of unrelated source", () -> {
            Path directory = Files.createTempDirectory("standalone-open-import-");
            Path library = directory.resolve("helpers.a26"), main = directory.resolve("main.a26");
            String helper = "void bump(cell) {\nINC cell;\n}\n";
            String program = "// keep\nimport \"" + library + "\";\nvar n=0;\nbegin:\nbump(n);\nHALT;\n";
            Files.writeString(library, helper); Files.writeString(main, program);
            SourceWorkspace workspace = SourceWorkspace.open(main);
            SourceWorkspace.Document selected = workspace.getMainDocument();
            expect(validate(workspace, selected, program, true).valid(), "valid unopened import resolves through workspace");
            expect(workspace.getDocuments().size() == 2, "preview opens the imported source buffer");
            SourceWorkspace.Document imported = workspace.openDocument(library);
            imported.setText(helper.replace("INC cell;", "INC absent;"));
            DiagramValidation.Result bad = validate(workspace, selected, program, true);
            expect(!bad.valid() && bad.message().contains("helpers.a26") && bad.message().contains("absent"), "diagnostic identifies the unsaved bad import");
            expect(bad.errors().get(0).startOffset() == program.indexOf("import"), "selected main highlights its import declaration");
            imported.setText(helper.replace("INC cell;", "DEC cell;"));
            Files.writeString(library, "broken on disk");
            expect(validate(workspace, selected, program, true).valid(), "already-open buffers take precedence over changed disk contents");
            expect(Files.readString(library).equals("broken on disk"), "validation cannot overwrite disk changes");
        });

        check("unrelated main programs cannot prevent self-contained helper previews", () -> {
            SourceWorkspace workspace = SourceWorkspace.create();
            SourceWorkspace.Document main = workspace.getDocuments().get(0);
            main.setText("begin: INC missing; HALT;"); workspace.setMainDocument(main);
            SourceWorkspace.Document helper = workspace.newDocument();
            helper.setText("void bump(cell) { INC cell; }\n");
            DiagramValidation.Result checked = validate(workspace, helper, helper.getText(), false);
            expect(checked.valid() && !checked.diagram().hasMain(), "unimported helper uses its own compiler harness: " + checked.message());
            expect(workspace.getMainDocument() == main, "unrelated main selection remains intact");
            SourceWorkspace.Document foreign = SourceWorkspace.create().getDocuments().get(0);
            try { validate(workspace, foreign, "begin: HALT;", true); throw new AssertionError("foreign document accepted"); }
            catch (IllegalArgumentException expected) { }
        });

        check("errors in another imported buffer are attributed to that file rather than the selected helper", () -> {
            Path directory = Files.createTempDirectory("standalone-other-helper-");
            Path goodPath = directory.resolve("good.a26"), badPath = directory.resolve("bad.a26"), mainPath = directory.resolve("main.a26");
            String good = "void good(cell) { INC cell; }\n", bad = "void bad(cell) { DEC cell; }\n";
            Files.writeString(goodPath, good); Files.writeString(badPath, bad);
            Files.writeString(mainPath, "import \"" + goodPath + "\";\nimport \"" + badPath + "\";\nvar n=0; begin: good(n); bad(n); HALT;\n");
            SourceWorkspace workspace = SourceWorkspace.open(mainPath);
            SourceWorkspace.Document selected = workspace.openDocument(goodPath), other = workspace.openDocument(badPath);
            other.setText(bad.replace("DEC cell;", "DEC absent;"));
            DiagramValidation.Result result = validate(workspace, selected, good, false);
            expect(!result.valid() && result.message().startsWith("bad.a26:") && result.message().contains("absent"),
                    "the unsaved offending helper is named in the diagnostic: " + result.message());
            expect(result.errors().get(0).startOffset() == -1 && result.errors().get(0).endOffset() == -1,
                    "no misleading range points into the untouched selected helper");
            expect(selected.getText().equals(good) && Files.readString(badPath).equals(bad), "validation does not alter either buffer or file");
            other.setText(bad);
            expect(validate(workspace, selected, good, false).valid(), "unchanged selected source recovers after the other buffer is fixed");
        });

        check("preview budgets and cancellation stop excessive compilation without game feature gates", () -> {
            SourceWorkspace workspace = SourceWorkspace.create();
            SourceWorkspace.Document document = workspace.getDocuments().get(0);
            for (String source : List.of("var data[2147483647]=0; begin: HALT;",
                    "begin: var huge=2147483647; HALT;", "begin:" + "NOP;".repeat(1001))) {
                DiagramValidation.Result result = validate(workspace, document, source, true);
                expect(!result.valid() && (result.message().contains("limit") || result.message().contains("maximum")), "large preview is bounded before allocation");
            }
            StringBuilder expansion = new StringBuilder("void m0(cell) { INC cell; }\n");
            for (int index = 1; index < 35; ++index) expansion.append("void m").append(index).append("(cell) { m")
                    .append(index - 1).append("(cell); m").append(index - 1).append("(cell); }\n");
            expansion.append("var n=0; begin: m34(n); HALT;");
            expect(validate(workspace, document, expansion.toString(), true).message().contains("expansion limit"), "exponential macro expansion is rejected before preprocessing");
            StringBuilder nesting = new StringBuilder("void m0(cell) { NOP; }\n");
            for (int index = 1; index <= 65; ++index) nesting.append("void m").append(index).append("(cell) { if(cell!=0) { m")
                    .append(index - 1).append("(cell); } }\n");
            nesting.append("var n=0; begin: m65(n); HALT;");
            DiagramValidation.Result nested = validate(workspace, document, nesting.toString(), true);
            expect(!nested.valid() && nested.message().contains("limit"),
                    "expanded nested macro bodies are bounded before recursive compiler parsing: " + nested.message());
            try { DiagramValidation.validateStandalone(workspace, document, "begin: HALT;", true, () -> true); throw new AssertionError("cancellation ignored"); }
            catch (CancellationException expected) { }
        });

        System.out.println("StandaloneDiagramValidationTest: " + passed + " passed, 0 failed");
    }

    private static DiagramValidation.Result validate(SourceWorkspace workspace, SourceWorkspace.Document selected, String candidate, boolean jumps) {
        return DiagramValidation.validateStandalone(workspace, selected, candidate, jumps);
    }
    private static void check(String name, Check action) throws Exception { action.run(); ++passed; System.out.println("PASS " + name); }
    private static void expect(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}

package machine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;

/** Compile-aware diagram snapshots and localization of several unfinished source edits. */
public final class DiagramValidationTest {
    private static final Set<GameFeature> ALL = EnumSet.allOf(GameFeature.class);
    private static final String LOOP = "var x=2;\nvar out=0;\nbegin:\nwhile(x!=0) {\n  INC out;\n  DEC x;\n}\nNOP;\nHALT;\n";
    private static int passed;
    private static int failed;

    @FunctionalInterface private interface Check { void run() throws Exception; }

    public static void main(String[] args) {
        check("validates compilation without requiring a solved level or executing a loop", () -> {
            expect(validate(LOOP).valid(), "ordinary loop compiles");
            expect(validate("var x=1; begin: while(x!=0) {} HALT;").valid(), "infinite runtime behavior is not executed");
            expect(validate("begin: NOP;").valid(), "no puzzle-specific ports or output contract");
            String starter = GameCatalog.levels().stream().filter(level -> level.id().equals("while-practice")).findFirst().orElseThrow().starter();
            expect(validate(starter).valid(), "working guarded starter is a valid diagram before the required rewrite");
        });

        check("unknown names use original offsets after comments and CRLF", () -> {
            String source = "// misleading INC missing;\r\nvar x=0;\r\nbegin:\r\n  INC missing;\r\nHALT;\r\n";
            DiagramValidation.Result result = validate(source);
            expect(!result.valid() && result.diagram() != null, "structural candidate remains available");
            expect(result.message().contains("Unknown variable missing"), "semantic diagnostic");
            DiagramValidation.Diagnostic error = result.errors().get(0);
            expect(error.startOffset() == source.lastIndexOf("missing") && error.endOffset() == error.startOffset() + 7,
                    "semantic location maps normalized source back to original CRLF offsets");
        });

        check("full compiler catches an unresolved macro silently", () -> {
            String source = "// missing(x) in a comment\r\nvar x=0;\r\nbegin:\r\nmissing(x);\r\nHALT;\r\n";
            PrintStream previous = System.out;
            ByteArrayOutputStream listing = new ByteArrayOutputStream();
            DiagramValidation.Result result;
            try (PrintStream capture = new PrintStream(listing)) {
                System.setOut(capture);
                result = validate(source);
            } finally { System.setOut(previous); }
            expect(!result.valid() && result.diagram() != null && result.message().contains("Macro substitution failed"),
                    "compiler error survives structural parsing");
            expect(listing.size() == 0, "live validation prints no preprocessing listing");
            expect(result.errors().get(0).startOffset() == source.lastIndexOf("missing"),
                    "expanded compiler error maps to the original call rather than a comment or expanded line");
        });

        check("earned syntax and virtual import restrictions match the checker", () -> {
            DiagramValidation.Result locked = DiagramValidation.validate(LOOP, Set.of(GameFeature.BLOCKS), Map.of());
            expect(!locked.valid() && locked.message().contains("locked"), "unearned while is rejected");
            expect(!validate("var x=1; begin: jump: JNZ x jump;").valid(), "explicit JNZ is suspended");
            String imported = "import \"toolbox\";\nvar x=0; begin: bump(x); HALT;";
            expect(DiagramValidation.validate(imported, ALL, Map.of("toolbox", "void bump(x) {\nINC x;\n}\n")).valid(),
                    "earned library compiles from in-memory source");
            DiagramValidation.Result badLibrary = DiagramValidation.validate(imported, ALL,
                    Map.of("toolbox", "void bump(x) {\nINC unknown;\n}\n"));
            expect(!badLibrary.valid() && badLibrary.errors().get(0).startOffset() == 0, "library failures point to import");
            expect(!DiagramValidation.validate("import \"/tmp/diagram-file-must-not-be-read\";\nbegin:", ALL, Map.of()).valid(),
                    "unprovided file imports cannot touch disk");
        });

        check("source nesting node and compiler expansion limits apply before rendering", () -> {
            expect(!validate("begin:" + " ".repeat(65_537)).valid(), "source bound");
            expect(!validate("begin:" + "{".repeat(65) + "}".repeat(65)).valid(), "depth bound");
            DiagramValidation.Result many = validate("begin:" + "NOP;".repeat(1001));
            expect(!many.valid() && many.message().contains("too many blocks"), "node cap precedes full compilation");
            expect(!validate("void recurse(x) { recurse(x); } var x=0; begin: recurse(x);").valid(), "recursive expansion bound");
            try {
                DiagramValidation.validate(LOOP, ALL, Map.of(), () -> true);
                throw new AssertionError("cancelled validation completed");
            } catch (CancellationException expected) { }
        });

        check("changing a condition marks its structure without unchanged children or siblings", () -> {
            StructuredDiagram old = StructuredDiagram.parse(LOOP);
            String current = LOOP.replace("while(x!=0)", "while(missing!=0)");
            DiagramValidation.Damage damage = DiagramValidation.damage(old, current, validate(current));
            expect(damage.nodes().equals(Set.of(old.main().nodes().get(0))), "only the damaged loop header is marked");
            expect(!damage.main() && !damage.preamble(), "root sequences remain intact");
        });

        check("a broken nested statement marks itself and ancestors but not its sibling", () -> {
            StructuredDiagram old = StructuredDiagram.parse(LOOP);
            String current = LOOP.replace("INC out;", "INC missing;");
            StructuredDiagram.Node loop = old.main().nodes().get(0);
            expect(DiagramValidation.damagedNodes(old, current, validate(current)).equals(Set.of(loop, loop.body().nodes().get(0))),
                    "only the edited leaf and enclosing loop are damaged");
        });

        check("several separated edits stay marked while the unchanged middle sibling stays clear", () -> {
            String source = "var a=0; var b=0; var c=0; begin: INC a; INC b; INC c; HALT;";
            StructuredDiagram old = StructuredDiagram.parse(source);
            String current = source.replace("INC a;", "INC missingA;").replace("INC c;", "INC missingC;");
            expect(DiagramValidation.damagedNodes(old, current, validate(current)).equals(Set.of(old.main().nodes().get(0), old.main().nodes().get(2))),
                    "token alignment retains both edits without painting the whole first-to-last range");
        });

        check("a missing brace marks the enclosing block instead of an innocent EOF sibling", () -> {
            StructuredDiagram old = StructuredDiagram.parse(LOOP);
            String current = LOOP.replace("}\nNOP;", "\nNOP;");
            DiagramValidation.Result result = validate(current);
            expect(!result.valid() && result.diagram() == null, "missing brace is structurally invalid");
            expect(DiagramValidation.damagedNodes(old, current, result).equals(Set.of(old.main().nodes().get(0))),
                    "removed closing brace belongs to the loop; parser EOF fallout does not damage HALT");
        });

        check("insertions mark an enclosing structure or root without inventing an old sibling", () -> {
            StructuredDiagram old = StructuredDiagram.parse(LOOP);
            String nested = LOOP.replace("  DEC x;", "  ( ???\n  DEC x;");
            expect(DiagramValidation.damagedNodes(old, nested, validate(nested)).equals(Set.of(old.main().nodes().get(0))),
                    "nested invalid insertion marks its loop");
            String root = LOOP.replace("NOP;", "???\nNOP;");
            DiagramValidation.Damage rootDamage = DiagramValidation.damage(old, root, validate(root));
            expect(rootDamage.nodes().isEmpty() && rootDamage.main() && !rootDamage.preamble(), "root insertion marks main container only");
            String eof = LOOP + "if (";
            DiagramValidation.Damage eofDamage = DiagramValidation.damage(old, eof, validate(eof));
            expect(eofDamage.nodes().isEmpty() && eofDamage.main(), "EOF insertion does not mark the old HALT");
            String globals = LOOP.replace("begin:", "var ???=0;\nbegin:");
            DiagramValidation.Damage setupDamage = DiagramValidation.damage(old, globals, validate(globals));
            expect(setupDamage.nodes().isEmpty() && setupDamage.preamble(), "new invalid declaration marks setup container");
        });

        check("deletions remain mapped across later edits and source whitespace shifts", () -> {
            StructuredDiagram old = StructuredDiagram.parse(LOOP);
            String current = "\n\n// extra comment\n" + LOOP.replace("  INC out;\n", "").replace("DEC x;", "DEC missing;");
            StructuredDiagram.Node loop = old.main().nodes().get(0);
            expect(DiagramValidation.damagedNodes(old, current, validate(current)).equals(Set.of(loop,
                    loop.body().nodes().get(0), loop.body().nodes().get(1))), "deleted leaf and separate broken leaf both stay mapped");
        });

        check("valid recovery and whitespace-only edits clear every damage marker", () -> {
            StructuredDiagram old = StructuredDiagram.parse(LOOP);
            for (String current : List.of(LOOP, "\r\n// more explanation\r\n" + LOOP.replace("\n", "\r\n"), LOOP.replace("INC out;", "NOP;"))) {
                DiagramValidation.Result result = validate(current);
                expect(result.valid(), "replacement compiles");
                DiagramValidation.Damage damage = DiagramValidation.damage(old, current, result);
                expect(damage.nodes().isEmpty() && !damage.main() && !damage.preamble(), "recovery clears markers");
            }
        });

        check("a compilable tree with no prior good snapshot can mark its actual bad statement", () -> {
            String source = "var x=0; begin: INC missing; NOP; HALT;";
            DiagramValidation.Result result = validate(source);
            expect(result.diagram() != null, "candidate structure exists");
            expect(DiagramValidation.damagedNodes(result.diagram(), source, result).equals(Set.of(result.diagram().main().nodes().get(0))),
                    "same-source diagnostic marks only bad candidate leaf");
        });

        check("every structured starter and reference solution compiles with only already-earned tools", () -> {
            Set<GameFeature> earned = EnumSet.noneOf(GameFeature.class);
            Set<String> completed = new LinkedHashSet<>();
            Map<String, String> solutions = GameCurriculumTest.solutions();
            List<String> invalidStarters = new ArrayList<>();
            int checked = 0;
            for (GameLevel level : GameCatalog.levels()) {
                if (GameWorld.usesDiagrams(level)) {
                    Map<String, String> library = GameCatalog.library(completed);
                    DiagramValidation.Result starter = DiagramValidation.validate(level.starter(), earned, library);
                    if (!starter.valid()) invalidStarters.add(level.id() + ": " + starter.message());
                    DiagramValidation.Result solution = DiagramValidation.validate(solutions.get(level.id()), earned, library);
                    expect(solution.valid(), "reference " + level.id() + ": " + solution.message());
                    ++checked;
                }
                earned.addAll(level.unlocks());
                completed.add(level.id());
            }
            System.out.println("Structured starters with compilation errors: " + invalidStarters);
            expect(invalidStarters.isEmpty(), "all supplied starters are compiler-valid before solving");
            expect(checked == 38, "all 38 structured levels validated");
        });

        System.out.println("DiagramValidationTest: " + passed + " passed, " + failed + " failed");
        if (failed != 0) throw new AssertionError(failed + " validation checks failed");
    }

    private static DiagramValidation.Result validate(String source) { return DiagramValidation.validate(source, ALL, Map.of()); }

    private static void check(String name, Check action) {
        try { action.run(); ++passed; System.out.println("PASS " + name); }
        catch (Exception | AssertionError error) { ++failed; System.err.println("FAIL " + name + ": " + error); error.printStackTrace(); }
    }

    private static void expect(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}

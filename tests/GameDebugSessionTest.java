import java.nio.file.Path;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;

import machine.GameCatalog;
import machine.GameDebugSession;
import machine.GameEngine;
import machine.GameFeature;
import machine.GameLevel;
import machine.GameLanguage;
import machine.GameResult;
import machine.LanguageVersion;
import machine.SourceLocation;
import machine.SourceTrace;

/**
 * Run from the repository root with fresh classes, without JavaFX:
 * build=$(mktemp -d)
 * javac --release 17 -d "$build" -classpath "$build" -sourcepath . tests/GameDebugSessionTest.java
 * java -cp "$build" GameDebugSessionTest
 */
public final class GameDebugSessionTest {
    private static final Path MAIN = Path.of("solution.a26");
    private static final Set<GameFeature> ALL = EnumSet.allOf(GameFeature.class);
    private static final String GLOBALS = "var input=0; var out=0; begin:\n";
    private static int passed;
    private static int failed;
    private interface Test { void run() throws Exception; }

    public static void main(String[] arguments) throws Exception {
        test("selected case replaces input literals in memory and source snapshots", () -> {
            GameLevel level = level(List.of(example(0, 0), example(7, 0)), 100);
            String source = "var input=99; var out=0; begin: INC input; HALT;";
            GameDebugSession session = GameDebugSession.prepare(level, source, Set.of(), Map.of(), 1, () -> false);
            require(session.getCaseIndex() == 1 && value(session, "input") == 7, "selected actual input is loaded");
            require(snapshot(session).contains("input=7;"), "snapshot shows actual initializer");
            require(session.getCurrentTrace().location().file().equals(MAIN), "debug traces use the academic-year source filename");
            require(source.contains("input=99;"), "authored source stays intact");
            require(selectedText(session).equals("INC input;"), "initial trace is next instruction");
            require(session.step() && value(session, "input") == 8, "one instruction mutates selected case");
            require(session.getStatus() == GameDebugSession.Status.HALTED && session.getStepCount() == 1, "HALT is not executed/counting");
            require(selectedText(session).equals("HALT;"), "HALT retains its own source range");
            require(!session.step() && session.getStepCount() == 1, "stepping terminal session does nothing");
        });

        test("restart restores immutable memory and source snapshots", () -> {
            Map<String, String> libraries = new LinkedHashMap<>();
            libraries.put("toolbox", "void increment(cell) {\nINC cell;\n}\n");
            GameDebugSession session = GameDebugSession.prepare(level(List.of(example(3, 0)), 100),
                    "import \"toolbox\";\n" + GLOBALS + "increment(input); HALT;", ALL, libraries, 0, () -> false);
            List<Integer> original = session.getMemory();
            SourceTrace trace = session.getCurrentTrace();
            libraries.put("toolbox", "invalid edited text");
            libraries.clear();
            session.step();
            require(value(session, "input") == 4, "compiled helper still runs");
            session.restart();
            require(session.getMemory().equals(original) && session.getStepCount() == 0, "restart restores exact image");
            require(session.getCurrentTrace().equals(trace), "restart restores provenance");
            expect(UnsupportedOperationException.class, () -> original.set(0, 0));
            expect(UnsupportedOperationException.class, () -> session.getNamedAddresses().clear());
            expect(UnsupportedOperationException.class, () -> session.getOutputValues().clear());
            expect(UnsupportedOperationException.class, () -> session.getProgram().sources().clear());
            expect(UnsupportedOperationException.class, () -> session.getProgram().bytecode().clear());
        });

        test("wrong and incomplete outputs can debug while Check stays strict", () -> {
            GameLevel level = level(List.of(example(2, 2)), 100);
            GameDebugSession wrong = prepare(level, GLOBALS + "HALT;", Set.of());
            require(wrong.getStatus() == GameDebugSession.Status.HALTED && wrong.getOutputValues().equals(Map.of("out", 0)), "wrong output is visible");
            String incomplete = "var input=0; begin: INC input; HALT;";
            GameDebugSession session = prepare(level, incomplete, Set.of());
            session.step();
            require(value(session, "input") == 3 && session.getOutputValues().isEmpty(), "missing output does not prevent debugging");
            GameResult result = check(level, incomplete, Set.of());
            require(!result.passed() && result.summary().contains("Declare global output"), result.summary());
            GameLevel array = level(List.of(new GameLevel.TestCase(Map.of("input", 0), Map.of("out[2]", 0))), 100);
            GameDebugSession shortArray = prepare(array, "var input=0; var out[1]=0; begin: HALT;", ALL);
            require(shortArray.getOutputValues().isEmpty(), "unfinished output array is undefined");
            require(!check(array, "var input=0; var out[1]=0; begin: HALT;", ALL).passed(), "Check still enforces array ports");
        });

        test("inspection includes unused globals every array cell and runtime local declarations", () -> {
            String source = "var input=0; var out=0; var extra=4; var single[1]=2;\nbegin:\n"
                    + "var work=3;\nvar cells[2]=1;\nINC cells[1]; HALT;";
            GameDebugSession session = prepare(level(List.of(example(5, 0)), 100), source, ALL);
            Map<String, Integer> names = session.getNamedAddresses();
            require(names.keySet().containsAll(List.of("input", "out", "extra", "single[0]")) && names.size() == 7,
                    "all global and local source cells are named, including single-element arrays");
            String work = names.keySet().stream().filter(name -> name.startsWith("work @")).findFirst().orElseThrow();
            String first = names.keySet().stream().filter(name -> name.startsWith("cells[0] @")).findFirst().orElseThrow();
            String second = names.keySet().stream().filter(name -> name.startsWith("cells[1] @")).findFirst().orElseThrow();
            require(value(session, work) == 3 && value(session, first) == 1, "local rows expose the compiler's actual initial cell values");
            require(names.keySet().stream().allMatch(name -> session.getVariableSource(name) != null), "every declared cell has source provenance");
            require(textAt(session, session.getVariableSource(work).location()).equals("var work=3;"), "local row links to its real declaration");
            run(session);
            require(value(session, "extra") == 4 && value(session, "single[0]") == 2 && value(session, work) == 3
                    && value(session, first) == 1 && value(session, second) == 2, "inspection follows each allocated cell's executed value");
            session.restart();
            require(session.getNamedAddresses().equals(names) && value(session, work) == 3 && value(session, second) == 1,
                    "restart retains names while restoring local memory");
        });

        test("macro-local arrays and shadowed names retain separate allocations and caller provenance", () -> {
            String library = "void helper(value) {\nvar out=2;\nvar temp[1]=1;\nINC out;\nINC temp[0];\nINC value;\n}\n";
            for (boolean globalOutput : List.of(false, true)) {
                String source = "import \"toolbox\";\nvar input=0;\n" + (globalOutput ? "var out=0;\n" : "")
                        + "begin:\nhelper(input);\nhelper(input);\nHALT;";
                GameDebugSession session = GameDebugSession.prepare(level(List.of(example(5, 0)), 100), source, ALL,
                        Map.of("toolbox", library), 0, () -> false);
                List<String> outputs = session.getNamedAddresses().keySet().stream().filter(name -> name.startsWith("out @")).toList();
                List<String> arrays = session.getNamedAddresses().keySet().stream().filter(name -> name.startsWith("temp[0] @")).toList();
                require(outputs.size() == 2 && arrays.size() == 2, "each macro expansion exposes its own local scalar and one-element array");
                require(outputs.stream().map(session::getVariableSource).map(SourceTrace::callers).distinct().count() == 2,
                        "repeated calls keep distinct source caller chains");
                require(outputs.stream().allMatch(name -> session.getVariableSource(name).location().file().equals(Path.of("toolbox", "toolbox"))),
                        "local declarations retain the imported source");
                require(session.getNamedAddresses().containsKey("out") == globalOutput, "locals never impersonate a global output port");
                run(session);
                require(outputs.stream().allMatch(name -> value(session, name) == 3)
                        && arrays.stream().allMatch(name -> value(session, name) == 2) && value(session, "input") == 7,
                        "each invocation's local values follow its own physical cells");
                require(session.getOutputValues().equals(globalOutput ? Map.of("out", 0) : Map.of()),
                        "inspecting locals preserves output contracts and undefined outputs");
            }
        });

        test("syntax-practice starters debug without satisfying completion contracts", () -> {
            Set<GameFeature> earned = EnumSet.noneOf(GameFeature.class);
            for (GameLevel level : GameCatalog.levels()) {
                if (List.of("clear-block", "clear-do-while", "else-practice", "while-practice", "comparison-practice").contains(level.id())) {
                    GameDebugSession session = prepare(level, level.starter(), earned);
                    LanguageVersion expectedVersion = level.id().startsWith("clear-")
                            ? LanguageVersion.BEFORE_DO_WHILE : LanguageVersion.STRUCTURED;
                    require(session.getLanguageVersion() == expectedVersion, "working starter uses its original language");
                    run(session);
                    require(session.getStatus() == GameDebugSession.Status.HALTED, "working old form remains debuggable: " + level.id());
                    require(!check(level, level.starter(), earned).passed(), "starter still requires conversion to complete " + level.id());
                    if (!level.id().startsWith("clear-")) {
                        String decoration = switch (level.id()) {
                            case "else-practice" -> "if (n == 0) { NOP; } else { NOP; }\n";
                            case "while-practice" -> "while (n != 0) { NOP; }\n";
                            default -> "if (n == 3) { NOP; }\n";
                        };
                        String incompleteRewrite = level.starter().replace("HALT;", decoration + "HALT;");
                        GameDebugSession draft = prepare(level, incompleteRewrite, earned);
                        run(draft);
                        require(draft.getStatus() == GameDebugSession.Status.HALTED,
                                "a draft that mixes old and new algorithms can still debug");
                        require(!check(level, incompleteRewrite, earned).passed(),
                                "debugging does not bypass the structural rewrite requirement");
                    }
                }
                earned.addAll(level.unlocks());
            }
            GameLevel macro = new GameLevel("flip-bit", "test", "Test", "", "", "", List.of(), Set.of(),
                    Set.of(GameFeature.MACROS), Map.of(), List.of(new GameLevel.TestCase(Map.of("bit", 0), Map.of("bit", 1))), 100);
            GameDebugSession inline = prepare(macro, "var bit=0; begin: INC bit; HALT;", ALL);
            inline.step();
            require(inline.getOutputValues().equals(Map.of("bit", 1)), "requested named macro is unnecessary for debugging");
        });

        test("while rewrite debugging preserves guarded do/while behavior for every input", () -> {
            GameLevel rewrite = catalogLevel("while-practice");
            String converted = "var n=0; var count=0; begin: while(n!=0) { DEC n; INC count; } HALT;";
            for (int index = 0; index < rewrite.cases().size(); index++) {
                GameDebugSession before = GameDebugSession.prepare(rewrite, rewrite.starter(), ALL, Map.of(), index, () -> false);
                GameDebugSession after = GameDebugSession.prepare(rewrite, converted, ALL, Map.of(), index, () -> false);
                run(before);
                run(after);
                require(before.getStatus() == GameDebugSession.Status.HALTED && after.getStatus() == GameDebugSession.Status.HALTED,
                        "both while forms halt for case " + index);
                require(before.getOutputValues().equals(after.getOutputValues())
                        && after.getOutputValues().equals(rewrite.cases().get(index).expected()),
                        "guarded post-test loop and while have identical outputs for case " + index);
                require(before.getLanguageVersion() == LanguageVersion.STRUCTURED && after.getLanguageVersion() == LanguageVersion.STRUCTURED,
                        "both sides of this rewrite use the structured language");
            }
            require(check(rewrite, converted, ALL).passed(), "the rewritten while program meets the completion contract");
        });

        test("completed early exercises still debug with their original identifier rules", () -> {
            for (String id : List.of("first-steps", "clear-block")) {
                GameLevel level = catalogLevel(id);
                GameDebugSession session = prepare(level, level.starter(), ALL);
                require(session.getLanguageVersion() == LanguageVersion.BEFORE_DO_WHILE, id + " retains old profile");
                run(session);
                require(session.getStatus() == GameDebugSession.Status.HALTED, session.getMessage());
            }
            GameLevel custom = level(List.of(example(0, 0)), 100);
            require(prepare(custom, GLOBALS + "do: NOP; HALT;", Set.of()).getLanguageVersion() == LanguageVersion.BEFORE_DO_WHILE,
                    "custom unearned profile accepts do as label");
            preparationFails(custom, GLOBALS + "do: NOP; HALT;", ALL, "keyword");
        });

        test("conversion debugging selects old or structured forms without mixing languages", () -> {
            GameLevel rewrite = catalogLevel("clear-do-while");
            String converted = "var dummy=0; var x=0; begin: INC dummy; ; do { DEC x; } while(x!=0); HALT;";
            GameDebugSession old = prepare(rewrite, rewrite.starter(), ALL);
            GameDebugSession current = prepare(rewrite, converted, ALL);
            require(old.getLanguageVersion() == LanguageVersion.BEFORE_DO_WHILE
                    && current.getLanguageVersion() == LanguageVersion.STRUCTURED, "both actual conversion forms are debuggable");
            run(old); run(current);
            require(old.getOutputValues().equals(current.getOutputValues()), "equivalent forms preserve selected case outputs");
            require(GameLanguage.editingVersionFor(rewrite, ALL, rewrite.starter()) == old.getLanguageVersion()
                    && GameLanguage.editingVersionFor(rewrite, ALL, converted) == current.getLanguageVersion(), "editing and compiled profiles agree");
            String mixed = "var dummy=0; var x=0; begin: do: NOP; do { DEC x; } while(x!=0); HALT;";
            preparationFails(rewrite, mixed, ALL, "keyword");
            require(GameLanguage.editingVersionFor(rewrite, ALL, mixed) == LanguageVersion.STRUCTURED, "mixed source does not silently select the legacy profile");
            preparationFails(rewrite, rewrite.starter().replace("HALT;", "INC missing; HALT;"), ALL, "Unknown variable missing");
        });

        test("comments strings and longer words cannot select the old debug language", () -> {
            GameLevel rewrite = catalogLevel("clear-do-while");
            String main = "var dummy=0; var x=0; begin:\n// do: JNZ x do;\nINC dummy; HALT;";
            GameDebugSession commented = prepare(rewrite, main, ALL);
            require(commented.getLanguageVersion() == LanguageVersion.STRUCTURED, "commented old syntax does not change compilation");
            String imported = "import \"do:\";\n" + main.replace("// do: JNZ x do;", "var doing=0;");
            GameDebugSession quoted = GameDebugSession.prepare(rewrite, imported, ALL,
                    Map.of("do:", "void helper() {\nNOP;\n}\n"), 0, () -> false);
            require(quoted.getLanguageVersion() == LanguageVersion.STRUCTURED, "quoted filenames and longer identifiers do not change compilation");
            require(GameLanguage.editingVersionFor(rewrite, ALL, main) == LanguageVersion.STRUCTURED
                    && GameLanguage.editingVersionFor(rewrite, ALL, imported) == LanguageVersion.STRUCTURED, "editor uses the same token rules");
        });

        test("the selected legacy debug language still enforces import and earned-feature restrictions", () -> {
            GameLevel rewrite = catalogLevel("clear-do-while");
            preparationFails(rewrite, "import \"/tmp/private-source\";\n" + rewrite.starter(), ALL, "available");
            Set<GameFeature> noImports = EnumSet.copyOf(ALL);
            noImports.remove(GameFeature.IMPORTS);
            preparationFails(rewrite, "import \"toolbox\";\n" + rewrite.starter(), noImports, "locked");
            try {
                GameDebugSession.prepare(rewrite, "import \"toolbox\";\n" + rewrite.starter(), ALL,
                        Map.of("toolbox", "void helper(cell) {\ndo { DEC cell; } while(cell!=0);\n}\n"), 0, () -> false);
                throw new AssertionError("mixed imported language accepted");
            } catch (GameDebugSession.PreparationException failure) {
                require(failure.getMessage().contains("requires the structured language"), "import uses the same selected profile: " + failure.getMessage());
            }
        });

        test("debugging retains earned syntax import and compilation resource limits", () -> {
            GameLevel level = level(List.of(example(0, 0)), 100);
            preparationFails(level, GLOBALS + "while(input!=0) { DEC input; } HALT;", Set.of(), "locked");
            preparationFails(level, "import \"/tmp/not-a-game-library\";\n" + GLOBALS + "HALT;", ALL, "available");
            preparationFails(level, GLOBALS + "var huge[256]=4000; HALT;", ALL, "too much code");
            preparationFails(level, "var out=0; begin: HALT;", ALL, "Declare input");
            try {
                prepare(level, GLOBALS + "while(input!=0) { DEC input; } HALT;", Set.of());
                throw new AssertionError("locked syntax accepted");
            } catch (GameDebugSession.PreparationException failure) {
                require(failure.getLocation() != null && failure.getLocation().file().equals(MAIN),
                        "syntax failure retains the academic-year source location");
            }
        });

        test("cancellation and invalid case selection fail before a session opens", () -> {
            GameLevel level = level(List.of(example(0, 0)), 100);
            expect(CancellationException.class, () -> GameDebugSession.prepare(level, GLOBALS + "HALT;", Set.of(), Map.of(), 0, () -> true));
            expect(IllegalArgumentException.class, () -> GameDebugSession.prepare(level, GLOBALS + "HALT;", Set.of(), Map.of(), 1, () -> false));
            expect(IllegalArgumentException.class, () -> GameDebugSession.prepare(level, GLOBALS + "HALT;", Set.of(), Map.of(), -1, () -> false));
        });

        test("bounded loops stop at the exact instruction limit and can restart", () -> {
            GameDebugSession session = prepare(level(List.of(example(1, 0)), 20), GLOBALS + "again: JNZ input again; HALT;", Set.of());
            run(session);
            require(session.getStatus() == GameDebugSession.Status.LIMIT_REACHED && session.getStepCount() == 20, "loop stops at level budget");
            require(selectedText(session).equals("JNZ input again;"), "limit highlights loop instruction");
            require(!session.step(), "cannot exceed exhausted budget");
            session.restart();
            require(session.getStatus() == GameDebugSession.Status.READY && session.getStepCount() == 0, "restart resets budget");
        });

        test("HALT at the exact budget wins over a step-limit failure", () -> {
            GameDebugSession session = prepare(level(List.of(example(0, 0)), 1), GLOBALS + "NOP; HALT;", Set.of());
            session.step();
            require(session.getStatus() == GameDebugSession.Status.HALTED && session.getStepCount() == 1, "last permitted instruction can reach HALT");
            GameDebugSession halted = prepare(level(List.of(example(0, 0)), 1), GLOBALS + "HALT;", Set.of());
            require(halted.getStatus() == GameDebugSession.Status.HALTED && halted.getStepCount() == 0, "initial HALT is terminal");
        });

        test("overflow stops before mutation and uses substituted snapshot offsets", () -> {
            String source = "var input=00000; var out=0; begin: INC input; HALT;";
            GameLevel level = level(List.of(example(Integer.MAX_VALUE, 0)), 100);
            GameDebugSession session = prepare(level, source, Set.of());
            List<Integer> before = session.getMemory();
            require(!session.step() && session.getStatus() == GameDebugSession.Status.ERROR, "overflow instruction blocked");
            require(session.getMemory().equals(before) && session.getStepCount() == 0, "overflow leaves memory untouched");
            require(session.getMessage().contains("overflow") && selectedText(session).equals("INC input;"), "snapshot failure location is exact");
            require(session.getCurrentTrace().location().startOffset() == snapshot(session).indexOf("INC input;"), "debug offsets match selected input text");
            GameResult checked = check(level, source, Set.of());
            require(checked.location().file().equals(MAIN), "check failures use the same academic-year source filename");
            require(checked.location().startOffset() == source.indexOf("INC input;"), "Check offsets still match authored text");
        });

        test("expanded macro accesses cannot write or read outside data cells", () -> {
            for (String body : List.of("INC p[500];", "DEC p[500];", "done: JNZ p[500] done;")) {
                String source = "void unsafe(p) {\n" + body + "\n}\n" + GLOBALS + "unsafe(input); HALT;";
                GameDebugSession session = prepare(level(List.of(example(0, 0)), 100), source, ALL);
                List<Integer> before = session.getMemory();
                require(!session.step() && session.getStatus() == GameDebugSession.Status.ERROR, "unsafe parameter access blocked");
                require(session.getMemory().equals(before) && session.getMessage().contains("outside the data cells"), session.getMessage());
                require(!session.getCurrentTrace().callers().isEmpty(), "failure retains macro call provenance");
            }
        });

        test("falling outside instructions stops before unsafe pointer changes", () -> {
            GameDebugSession session = prepare(level(List.of(example(0, 0)), 100), GLOBALS + "INC input;", Set.of());
            List<Integer> before = session.getMemory();
            require(!session.step() && session.getStatus() == GameDebugSession.Status.ERROR, "missing HALT detected before step");
            require(session.getMemory().equals(before) && session.getMessage().contains("end with HALT"), session.getMessage());
            expect(IndexOutOfBoundsException.class, () -> session.getMemoryValue(-1));
            expect(IndexOutOfBoundsException.class, () -> session.getMemoryValue(session.getMemorySize()));
        });

        test("nested virtual imports retain definition and ordered caller snapshots", () -> {
            String library = "void inner(cell) {\nINC cell;\n}\nvoid outer(cell) {\ninner(cell);\n}\n";
            String source = "import \"toolbox\";\n" + GLOBALS + "outer(input); HALT;";
            GameDebugSession session = GameDebugSession.prepare(level(List.of(example(7, 0)), 100), source, ALL,
                    Map.of("toolbox", library), 0, () -> false);
            SourceTrace trace = session.getCurrentTrace();
            require(trace.location().file().equals(Path.of("toolbox", "toolbox")), "instruction is in virtual library");
            require(textAt(session, trace.location()).equals("INC cell;"), "instruction range uses original macro body");
            require(trace.callers().size() == 2, "both expansion callers retained");
            require(trace.callers().get(0).file().equals(MAIN), "imported helper caller maps back to the academic-year source");
            require(textAt(session, trace.callers().get(0)).equals("outer(input);")
                    && textAt(session, trace.callers().get(1)).equals("inner(cell);"), "callers ordered outermost first");
            require(session.getProgram().sources().size() == 2, "only compiled snapshot sources appear");
            session.step();
            require(value(session, "input") == 8 && selectedText(session).equals("HALT;"), "step follows macro back to main");
        });

        test("source stepping groups generated instructions and bounds a single-range loop", () -> {
            GameDebugSession session = prepare(level(List.of(example(0, 0)), 100), GLOBALS + "var scratch=3;\nNOP;\nHALT;", ALL);
            require(selectedText(session).equals("var scratch=3;"), "local initialization has own source range");
            int steps = session.stepStatement(100);
            require(steps > 1 && selectedText(session).equals("NOP;"), "source step completes generated initializer");
            require(session.stepStatement(100) == 1 && session.getStatus() == GameDebugSession.Status.HALTED, "source step reaches HALT");
            GameDebugSession loop = prepare(level(List.of(example(1, 0)), 100), GLOBALS + "again: JNZ input again; HALT;", Set.of());
            require(loop.stepStatement(7) == 7 && loop.getStepCount() == 7 && !loop.isStopped(), "same-range step yields after caller cap");
            expect(IllegalArgumentException.class, () -> loop.stepStatement(0));
        });

        System.out.println("GameDebugSessionTest: " + passed + " passed, " + failed + " failed");
        if (failed != 0) throw new AssertionError(failed + " game debug session tests failed");
    }

    private static GameLevel.TestCase example(int input, int output) {
        return new GameLevel.TestCase(Map.of("input", input), Map.of("out", output));
    }
    private static GameLevel level(List<GameLevel.TestCase> cases, int limit) {
        return new GameLevel("test", "test", "Test", "", "", "", List.of(), Set.of(), Set.of(), Map.of(), cases, limit);
    }
    private static GameLevel catalogLevel(String id) {
        return GameCatalog.levels().stream().filter(level -> level.id().equals(id)).findFirst().orElseThrow();
    }
    private static GameDebugSession prepare(GameLevel level, String source, Set<GameFeature> features) throws Exception {
        return GameDebugSession.prepare(level, source, features, Map.of(), 0, () -> false);
    }
    private static GameResult check(GameLevel level, String source, Set<GameFeature> features) {
        return GameEngine.check(level, source, features, Map.of(), () -> false, count -> {});
    }
    private static int value(GameDebugSession session, String name) {
        return session.getMemoryValue(session.getNamedAddresses().get(name));
    }
    private static String snapshot(GameDebugSession session) {
        return session.getProgram().sources().get(MAIN);
    }
    private static String textAt(GameDebugSession session, SourceLocation location) {
        return session.getProgram().sources().get(location.file()).substring(location.startOffset(), location.endOffset());
    }
    private static String selectedText(GameDebugSession session) { return textAt(session, session.getCurrentTrace().location()); }
    private static void run(GameDebugSession session) { while (!session.isStopped()) session.step(); }
    private static void preparationFails(GameLevel level, String source, Set<GameFeature> features, String fragment) throws Exception {
        try { prepare(level, source, features); throw new AssertionError("unsafe preparation accepted"); }
        catch (GameDebugSession.PreparationException failure) {
            require(failure.getMessage().contains(fragment), failure.getMessage());
        }
    }
    private static void expect(Class<? extends Throwable> type, Test action) throws Exception {
        try { action.run(); throw new AssertionError("Expected " + type.getSimpleName()); }
        catch (Exception failure) { if (!type.isInstance(failure)) throw failure; }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void test(String name, Test test) {
        try { test.run(); passed++; System.out.println("PASS " + name); }
        catch (Exception | AssertionError failure) { failed++; System.err.println("FAIL " + name + ": " + failure); failure.printStackTrace(); }
    }
}

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import machine.GameEngine;
import machine.GameCatalog;
import machine.GameFeature;
import machine.GameLevel;
import machine.GameLanguage;
import machine.GameResult;
import machine.LanguageVersion;

/**
 * Run from the repository root using freshly compiled sources:
 * build=$(mktemp -d)
 * javac -d "$build" -classpath "$build" -sourcepath . tests/GameEngineTest.java
 * java -cp "$build" GameEngineTest
 */
public final class GameEngineTest {
    private static final Set<GameFeature> ALL = EnumSet.allOf(GameFeature.class);
    private static final String GLOBALS = "var input=0; var out=0; begin:\n";
    private static int passed;
    private static int failed;
    private interface Test { void run() throws Exception; }

    public static void main(String[] arguments) throws Exception {
        test("base labels and all machine commands pass multiple isolated inputs", () -> {
            GameLevel level = level(List.of(example(0, 0), example(1, 1), example(7, 7)), 1000);
            String source = GLOBALS + "NOP;\ntop: JNZ input body; HALT;\n"
                + "body: DEC input; INC out; JNZ input top; HALT;";
            List<Integer> progress = new ArrayList<>();
            GameResult result = GameEngine.check(level, source, Set.of(), Map.of(), () -> false, progress::add);
            require(result.passed(), result.summary());
            require(progress.equals(List.of(1, 2, 3)), "progress reports completed checks");
            require(result.cases().get(0).steps() == 2, "zero input has its own fresh machine");
        });

        test("wrong outputs report the counterexample and stop after the failing case", () -> {
            GameResult result = check(level(List.of(example(0, 0), example(2, 2), example(3, 3)), 100), GLOBALS + "HALT;", Set.of());
            require(!result.passed() && result.cases().size() == 2, "first counterexample is retained");
            require(result.cases().get(1).actual().equals(Map.of("out", 0)), "actual values are reported");
            require(result.summary().contains("expected") && result.summary().contains("input=2"), result.summary());
        });

        test("do is an ordinary identifier before do while and a reserved keyword afterwards", () -> {
            GameLevel clear = level(List.of(new GameLevel.TestCase(Map.of("input", 0), Map.of("input", 0)),
                    new GameLevel.TestCase(Map.of("input", 7), Map.of("input", 0))), 100);
            String sourceWithLabel = GLOBALS + "do: DEC input; JNZ input do; HALT;";
            GameResult label = check(clear, sourceWithLabel, Set.of());
            require(label.passed(), label.summary());
            GameResult forward = check(clear, GLOBALS + "JNZ input do; NOP; do: DEC input; JNZ input do; HALT;",
                    Set.of(GameFeature.BLOCKS));
            require(forward.passed(), forward.summary());
            GameResult loop = check(clear, GLOBALS + "do { DEC input; } while(input!=0); HALT;", Set.of(GameFeature.BLOCKS));
            require(!loop.passed() && loop.summary().contains("locked"), loop.summary());
            for (String source : List.of(sourceWithLabel, "var do=0; begin: HALT;", "void do() {\nNOP;\n}\n" + GLOBALS + "HALT;",
                    "void helper(do) {\nNOP;\n}\n" + GLOBALS + "HALT;", GLOBALS + "INC do; HALT;",
                    GLOBALS + "while: HALT;", GLOBALS + "JNZ input while; HALT;")) {
                GameResult reserved = check(clear, source, ALL);
                require(!reserved.passed() && reserved.cases().isEmpty(), "reserved name rejected: " + source);
            }
            Set<GameFeature> before = EnumSet.copyOf(ALL);
            before.remove(GameFeature.DO_WHILE);
            for (String source : List.of("var do=0;\n" + GLOBALS + "INC do; HALT;",
                    "void do() {\nNOP;\n}\n" + GLOBALS + "do(); HALT;",
                    "void helper(do) {\nINC do;\n}\n" + GLOBALS + "helper(input); HALT;")) {
                GameResult ordinary = check(level(List.of(example(0, 0)), 100), source, before);
                require(ordinary.passed(), "ordinary identifier before do/while: " + ordinary.summary());
            }
        });

        test("early exercises retain their original language after all tools are earned", () -> {
            GameLevel first = GameCatalog.levels().get(0);
            GameLevel clear = GameCatalog.levels().get(1);
            require(GameLanguage.versionFor(first, ALL) == LanguageVersion.BEFORE_DO_WHILE
                    && GameLanguage.versionFor(clear, ALL) == LanguageVersion.BEFORE_DO_WHILE, "old completed exercises retain profile");
            GameResult firstResult = check(first, "var x=0; begin: do: INC x; HALT;", ALL);
            require(firstResult.passed(), firstResult.summary());
            GameResult grouped = check(clear, clearBlock("INC dummy; ; do: { DEC x; } JNZ x do; HALT;"), ALL);
            require(grouped.passed(), grouped.summary());
            GameResult changed = check(first, "var x=0; begin: do { INC x; } while(x==0); HALT;", ALL);
            require(!changed.passed() && changed.cases().isEmpty(), "structured loop is unavailable in the old exercise");
            require(GameLanguage.versionFor(level(List.of(example(0, 0)), 100), ALL) == LanguageVersion.STRUCTURED,
                    "generic levels follow the earned language version");
        });

        test("virtual imports use the selected language version and retain earned-feature safety", () -> {
            GameLevel level = level(List.of(example(0, 0)), 100);
            String source = "import \"toolbox\";\n" + GLOBALS + "helper(input); HALT;";
            String library = "void helper(do) {\nINC do;\n}\n";
            Set<GameFeature> before = EnumSet.copyOf(ALL);
            before.remove(GameFeature.DO_WHILE);
            GameResult legacy = GameEngine.check(level, source, before, Map.of("toolbox", library), () -> false, count -> {});
            require(legacy.passed(), legacy.summary());
            GameResult reserved = GameEngine.check(level, source, ALL, Map.of("toolbox", library), () -> false, count -> {});
            require(!reserved.passed() && reserved.summary().contains("keyword"), reserved.summary());
            GameResult tooNew = GameEngine.check(level, source, before,
                    Map.of("toolbox", "void helper(cell) {\ndo { DEC cell; } while(cell!=0);\n}\n"), () -> false, count -> {});
            require(!tooNew.passed() && tooNew.summary().contains("locked"), tooNew.summary());
        });

        test("the second lesson groups the supplied clear loop and earns do while", () -> {
            GameLevel first = GameCatalog.levels().get(0);
            GameLevel clear = GameCatalog.levels().get(1);
            require(first.id().equals("first-steps") && clear.id().equals("clear-block"), "clear-block follows first steps");
            require(clear.requiredFeatures().equals(Set.of(GameFeature.BLOCKS, GameFeature.EMPTY_STATEMENTS)),
                    "the exercise practices both new grammar additions");
            require(clear.unlocks().equals(Set.of(GameFeature.DO_WHILE)), "the block exercise earns do while");
            GameResult firstResult = check(first, "var x=0; begin: INC x; HALT;", Set.of());
            require(firstResult.passed(), firstResult.summary());
            GameResult unchanged = check(clear, clear.starter(), first.unlocks());
            require(!unchanged.passed() && unchanged.cases().isEmpty()
                    && unchanged.summary().contains("Group the clearing loop"), unchanged.summary());
            GameResult grouped = check(clear, clearBlock("INC dummy; ; do: { DEC x; } JNZ x do; HALT;"), first.unlocks());
            require(grouped.passed() && grouped.cases().size() == clear.cases().size(), grouped.summary());
            require(clear.cases().stream().anyMatch(test -> test.inputs().getOrDefault("dummy", -1) == 0
                    && test.inputs().getOrDefault("x", -1) == 0), "the contract includes all-zero inputs");
            require(clear.cases().stream().anyMatch(test -> test.inputs().getOrDefault("dummy", 0) > 0
                    && test.inputs().getOrDefault("x", 0) > 1), "the contract verifies nonzero dummy increments only once");
            for (GameResult.CaseResult result : grouped.cases()) {
                require(result.actual().get("dummy") == result.inputs().get("dummy") + 1, "dummy increments once");
                require(result.actual().get("x") == 0, "x is cleared");
            }
            Set<GameFeature> earned = EnumSet.copyOf(first.unlocks());
            earned.addAll(clear.unlocks());
            GameLevel rewrite = GameCatalog.levels().get(2);
            require(rewrite.id().equals("clear-do-while"), "the next lesson rewrites the same clearing loop");
            GameResult rewriteResult = check(rewrite, clearBlock("INC dummy; ; do { DEC x; } while(x!=0); HALT;"), earned);
            require(rewriteResult.passed(), rewriteResult.summary());
            GameLevel lap = GameCatalog.levels().get(3);
            require(lap.id().equals("mandatory-lap"), "the next lesson practices the earned do while");
            GameResult lapResult = check(lap, "var n=0; var rounds=0; begin: do { INC rounds; DEC n; } while(n!=0); HALT;", earned);
            require(lapResult.passed(), lapResult.summary());
            GameLevel next = GameCatalog.levels().get(4);
            require(next.id().equals("zero-test"), "zero recognition follows the loop practice");
            GameResult nextResult = check(next, "var n=0; var zero=0; begin: JNZ n done; INC zero; done: HALT;", earned);
            require(nextResult.passed(), nextResult.summary());
            GameLevel simple = level(List.of(new GameLevel.TestCase(Map.of("input", 3), Map.of("input", 0))), 100);
            GameResult newTool = check(simple, GLOBALS + "do { DEC input; } while(input!=0); HALT;", earned);
            require(newTool.passed(), newTool.summary());
        });

        test("clear-block groups the loop body and requires its jump outside", () -> {
            GameLevel clear = GameCatalog.levels().get(1);
            Set<GameFeature> earned = GameCatalog.levels().get(0).unlocks();
            for (String body : List.of(
                    "{} INC dummy; ; do: DEC x; JNZ x do; HALT;",
                    "INC dummy; ; do: DEC x; JNZ x do; {} HALT;",
                    "{ INC dummy; ; } do: DEC x; JNZ x do; HALT;",
                    "INC dummy; ; do: { DEC x; JNZ x do; } HALT;",
                    "INC dummy; ; do: DEC x; { JNZ x do; } HALT;",
                    "do: { INC dummy; ; DEC x; JNZ x do; } HALT;",
                    "INC dummy; ; do: { DEC x; JNZ x do; HALT; }",
                    "{ INC dummy; ; do: DEC x; JNZ x do; HALT; }",
                    "INC dummy; ; again: { DEC x; JNZ x again; } HALT;",
                    "INC dummy; ; do: { JNZ x do; DEC x; } HALT;",
                    "INC dummy; ; do: {{ DEC x; JNZ x do; }} HALT;",
                    "INC dummy; ; do: { DEC x; JNZ x do; } HALT; {}")) {
                GameResult result = check(clear, clearBlock(body), earned);
                require(!result.passed() && result.cases().isEmpty()
                        && result.summary().contains("Group the clearing loop"), body + ": " + result.summary());
                require(result.location() != null, "structural feedback points to the first differing token");
            }
        });

        test("clear-block accepts formatting and comments without counting commented braces", () -> {
            GameLevel clear = GameCatalog.levels().get(1);
            Set<GameFeature> earned = GameCatalog.levels().get(0).unlocks();
            String formatted = "var dummy = 0;\r\nvar x = 0;\r\nbegin:\r\n"
                    + "// do { and } are only a comment — keep line endings\r\n"
                    + "INC\tdummy ; // once\r\n; // empty setup placeholder\r\ndo : {\r\n DEC x ;\r\n}\r\n // jump after the block\r\n JNZ x do ;\r\nHALT ;\r\n";
            GameResult valid = check(clear, formatted, earned);
            require(valid.passed(), valid.summary());
            GameResult commented = check(clear, clearBlock("INC dummy; ; do: // {\nDEC x; JNZ x do; // }\nHALT;"), earned);
            require(!commented.passed() && commented.cases().isEmpty(), "comments cannot satisfy the block requirement");
            GameLevel ordinary = level(List.of(example(0, 0)), 100);
            GameLevel needsBlock = new GameLevel("ordinary-block", "test", "test", "test", "", "", List.of(),
                    Set.of(), Set.of(GameFeature.BLOCKS), Map.of(), ordinary.cases(), 100);
            GameResult labelOnly = check(needsBlock, GLOBALS + "do: HALT;", earned);
            require(!labelOnly.passed() && labelOnly.summary().contains("asks you to use"),
                    "the do label alone must not count as using a block");
        });

        test("clearing exercises require the standalone empty setup statement in its taught position", () -> {
            for (String id : List.of("clear-block", "clear-do-while")) {
                GameLevel challenge = GameCatalog.levels().stream().filter(candidate -> candidate.id().equals(id)).findFirst().orElseThrow();
                Set<GameFeature> earned = EnumSet.copyOf(GameCatalog.levels().get(0).unlocks());
                boolean structured = id.equals("clear-do-while");
                if (structured) earned.add(GameFeature.DO_WHILE);
                String loop = structured ? "do { DEC x; } while(x!=0);" : "do: { DEC x; } JNZ x do;";
                String omitted = clearBlock("INC dummy; " + loop + " HALT;");
                GameLevel behaviorOnly = new GameLevel("placeholder-behavior", "test", "test", "test", "", "", List.of(),
                        Set.of(), Set.of(), Map.of(), challenge.cases(), challenge.maxSteps());
                GameResult outputOnly = check(behaviorOnly, omitted, earned);
                require(outputOnly.passed(), "omitting an empty statement preserves behavior: " + outputOnly.summary());
                for (String body : List.of("INC dummy; " + loop + " HALT;",
                        "INC dummy; // ; is only a comment\n" + loop + " HALT;",
                        "INC dummy; " + loop + " HALT; ;",
                        "INC dummy; " + loop + " ; HALT;")) {
                    GameResult missing = check(challenge, clearBlock(body), earned);
                    require(!missing.passed() && missing.cases().isEmpty() && missing.location() != null
                            && missing.summary().contains("standalone ; placeholder"), id + ": " + missing.summary());
                }
                GameResult correct = check(challenge, clearBlock("INC dummy; ; " + loop + " HALT;"), earned);
                require(correct.passed(), correct.summary());
            }
        });

        test("clear-do-while requires the actual rewrite instead of an unrelated structured loop", () -> {
            GameLevel rewrite = GameCatalog.levels().get(2);
            require(rewrite.id().equals("clear-do-while") && rewrite.unlocks().isEmpty()
                    && rewrite.requiredFeatures().equals(Set.of(GameFeature.DO_WHILE)), "the rewrite practices existing tools");
            Set<GameFeature> earned = EnumSet.copyOf(GameCatalog.levels().get(0).unlocks());
            earned.add(GameFeature.DO_WHILE);
            for (String body : List.of(
                    "INC dummy; ; do: { DEC x; } JNZ x do; HALT;",
                    "INC dummy; ; do: { DEC x; } JNZ x do; do { NOP; } while(x!=0); HALT;",
                    "do { INC dummy; ; DEC x; } while(x!=0); HALT;",
                    "INC dummy; ; do { DEC x; } while(x==0); HALT;",
                    "INC dummy; ; do { DEC x; } while(x!=0); NOP; HALT;")) {
                GameResult result = check(rewrite, clearBlock(body), earned);
                require(!result.passed() && result.cases().isEmpty() && result.location() != null
                        && result.summary().contains(body.contains("do:") ? "keyword" : "Rewrite the clearing loop"), body + ": " + result.summary());
            }
            String formatted = "var dummy = 0;\r\nvar x = 0;\r\nbegin:\r\nINC dummy;\r\n; // empty setup placeholder\r\n"
                    + "do // no label now\r\n{ DEC x; } // trailing condition\r\nwhile ( x != 0 );\r\nHALT;\r\n";
            GameResult result = check(rewrite, formatted, earned);
            require(result.passed() && result.cases().size() == 6, result.summary());
            GameResult locked = check(rewrite, formatted, GameCatalog.levels().get(0).unlocks());
            require(!locked.passed() && locked.summary().contains("locked"), "do while must be earned first");
        });

        test("conditional rewrites accept reversed conditions and flexible branch bodies", () -> {
            GameLevel alternatives = catalogLevel("else-practice");
            String resultGlobals = "var n=0; var result=0; begin:\n";
            for (String body : List.of(
                    "if(n==0) { INC result; } else { INC result; INC result; } HALT;",
                    "if(n!=0) { INC result; INC result; } else { INC result; } HALT;",
                    "if (n == 0) { if(n==0) { INC result; } } else { { INC result; } INC result; } HALT;",
                    "if\t(n != 00) // } else { are comment text\r\n{ INC result; INC result; }\r\nelse { INC result; }\r\nHALT;")) {
                GameResult result = check(alternatives, resultGlobals + body, earnedBefore(alternatives));
                require(result.passed(), body + ": " + result.summary());
            }
            GameLevel comparison = catalogLevel("comparison-practice");
            String equalGlobals = "var n=0; var equal=0; begin:\n";
            for (String body : List.of(
                    "if(n==3) { INC equal; } HALT;",
                    "if(n!=3) { NOP; } else { INC equal; } HALT;",
                    "if(n==03) { { INC equal; } } else { NOP; } HALT;")) {
                GameResult result = check(comparison, equalGlobals + body, earnedBefore(comparison));
                require(result.passed(), body + ": " + result.summary());
            }
            GameResult decrement = check(comparison,
                    "var n=0; var equal=1; begin: if(n!=3) { DEC equal; } HALT;", earnedBefore(comparison));
            require(decrement.passed(), "a valid rewrite need not use INC: " + decrement.summary());
        });

        test("level seven maps zero to one and nonzero to two while level six keeps its original mapping", () -> {
            GameLevel challenge = catalogLevel("else-practice");
            require(challenge.cases().stream().map(GameLevel.TestCase::inputs).toList()
                            .equals(List.of(Map.of("n", 0), Map.of("n", 1), Map.of("n", 8))),
                    "the branch contract covers zero, one, and a larger nonzero input");
            require(challenge.cases().stream().map(GameLevel.TestCase::expected).toList()
                            .equals(List.of(Map.of("n", 0, "result", 1), Map.of("n", 1, "result", 2), Map.of("n", 8, "result", 2))),
                    "level seven requires the new mapping and preserves n");
            require(catalogLevel("two-paths").cases().stream().map(GameLevel.TestCase::expected).toList()
                            .equals(List.of(Map.of("n", 0, "result", 2), Map.of("n", 1, "result", 1), Map.of("n", 8, "result", 1))),
                    "level six retains its original mapping");
            String globals = "var n=0; var result=0; begin:\n";
            GameResult correct = check(challenge,
                    globals + "if(n==0) { INC result; } else { INC result; INC result; } HALT;", earnedBefore(challenge));
            require(correct.passed() && correct.cases().size() == 3, correct.summary());
            for (GameResult.CaseResult result : correct.cases()) {
                require(result.actual().get("n").equals(result.inputs().get("n")), "each successful branch preserves its input");
            }
            GameResult oldMapping = check(challenge,
                    globals + "if(n==0) { INC result; INC result; } else { INC result; } HALT;", earnedBefore(challenge));
            require(!oldMapping.passed() && oldMapping.cases().size() == 1
                            && oldMapping.cases().get(0).actual().equals(Map.of("n", 0, "result", 2)),
                    "the old mapping reaches execution but fails the zero-case output contract");
        });

        test("working starters cannot satisfy rewrite practice with unrelated or unreachable syntax", () -> {
            for (String id : List.of("else-practice", "comparison-practice")) {
                GameLevel challenge = catalogLevel(id);
                Set<GameFeature> earned = earnedBefore(challenge);
                GameResult unchanged = check(challenge, challenge.starter(), earned);
                require(!unchanged.passed() && unchanged.cases().isEmpty()
                                && unchanged.summary().contains("asks you to use"),
                        "missing-feature feedback is preserved: " + unchanged.summary());
                String decoration = id.equals("else-practice")
                        ? "if(n==0) { NOP; } else { NOP; }" : "if(n==3) { NOP; }";
                GameLevel behaviorOnly = new GameLevel("rewrite-behavior", "test", "test", "test", "", "", List.of(),
                        Set.of(), Set.of(), Map.of(), challenge.cases(), challenge.maxSteps());
                for (String source : List.of(challenge.starter().replace("HALT;", decoration + "\nHALT;"),
                        challenge.starter() + decoration)) {
                    GameResult behavior = check(behaviorOnly, source, earned);
                    require(behavior.passed(), "the rejected decoration preserves every output: " + behavior.summary());
                    GameResult rejected = check(challenge, source, earned);
                    require(!rejected.passed() && rejected.cases().isEmpty() && rejected.location() != null
                                    && rejected.summary().contains("one if comparing"), id + ": " + rejected.summary());
                }
            }
        });

        test("rewrite structure requires the relevant condition and directly following HALT", () -> {
            for (String id : List.of("else-practice", "comparison-practice")) {
                GameLevel challenge = catalogLevel(id);
                boolean alternatives = id.equals("else-practice");
                String globals = "var n=0; var result=0; var equal=0; begin:\n";
                String conditional = alternatives ? "if(n==0) { INC result; } else { INC result; INC result; }"
                        : "if(n==3) { INC equal; }";
                for (String body : List.of("; " + conditional + " HALT;", "NOP; " + conditional + " HALT;",
                        "start: " + conditional + " HALT;", conditional + " ; HALT;",
                        conditional + " HALT; ;", conditional + " HALT; " + conditional,
                        conditional.replace("if(n", "if(result") + " HALT;",
                        alternatives ? "if(n==0) { if(n==0) { INC result; } else { INC result; } } HALT;"
                                : "if(n==2) { INC equal; } HALT;")) {
                    GameResult rejected = check(challenge, globals + body, earnedBefore(challenge));
                    require(!rejected.passed() && rejected.cases().isEmpty() && rejected.location() != null
                                    && rejected.summary().contains("one if comparing"), body + ": " + rejected.summary());
                }
            }
        });

        test("rewrite structure leaves branch correctness to the input-output cases", () -> {
            GameLevel alternatives = catalogLevel("else-practice");
            GameResult wrongAlternatives = check(alternatives,
                    "var n=0; var result=0; begin: if(n==0) { INC result; } else { INC result; } HALT;",
                    earnedBefore(alternatives));
            require(!wrongAlternatives.passed() && !wrongAlternatives.cases().isEmpty(),
                    "a structurally valid but incorrect branch reaches the output checks");
            GameLevel comparison = catalogLevel("comparison-practice");
            GameResult wrongComparison = check(comparison,
                    "var n=0; var equal=0; begin: if(n==3) { NOP; } HALT;", earnedBefore(comparison));
            require(!wrongComparison.passed() && !wrongComparison.cases().isEmpty(),
                    "a valid comparison still must produce the expected equal flag");
        });

        test("input literals are replaced before copied global initializers", () -> {
            String source = "var input=999; var out=input; begin: HALT;";
            GameResult result = check(level(List.of(example(0, 0), example(7, 7), example(255, 255)), 100), source,
                Set.of(GameFeature.COPYING));
            require(result.passed(), result.summary());
        });

        test("input and output ports cannot be missing copied array-shaped or redeclared", () -> {
            for (String source : List.of("var out=0; begin: HALT;", "var input[2]=0; var out=0; begin: HALT;",
                    "var a=0; var input=a; var out=0; begin: HALT;", "var input=0; var input=1; var out=0; begin: HALT;",
                    GLOBALS + "var input=0; HALT;", GLOBALS + "var out=7; HALT;", "var input=0; begin: HALT;")) {
                GameResult result = check(level(List.of(example(1, 0)), 100), source, ALL);
                require(!result.passed() && result.cases().isEmpty(), "port bypass should fail before running: " + source);
            }
        });

        test("every earned syntax feature is checked structurally", () -> {
            Map<GameFeature, String> programs = new LinkedHashMap<>();
            programs.put(GameFeature.BLOCKS, GLOBALS + "{NOP;} HALT;");
            programs.put(GameFeature.EMPTY_STATEMENTS, GLOBALS + "; HALT;");
            programs.put(GameFeature.WHILE, GLOBALS + "while(input!=0) {DEC input;} HALT;");
            programs.put(GameFeature.IF, GLOBALS + "if(input==0) {NOP;} HALT;");
            programs.put(GameFeature.ELSE, GLOBALS + "if(input==0) {NOP;} else {NOP;} HALT;");
            programs.put(GameFeature.COMPARISONS, GLOBALS + "if(input==1) {NOP;} HALT;");
            programs.put(GameFeature.DO_WHILE, GLOBALS + "do {NOP;} while(input!=0); HALT;");
            programs.put(GameFeature.COPYING, "var input=0; var out=input; begin: HALT;");
            programs.put(GameFeature.LOCAL_VARIABLES, GLOBALS + "var local=0; HALT;");
            programs.put(GameFeature.ARRAYS, "var input=0; var cells[2]=0; var out=0; begin: HALT;");
            programs.put(GameFeature.MACROS, "void unused() {\nNOP;\n}\n" + GLOBALS + "HALT;");
            programs.put(GameFeature.LITERAL_PARAMETERS, "void unused(1) {\nNOP;\n}\n" + GLOBALS + "HALT;");
            programs.put(GameFeature.IMPORTS, "import \"toolbox\";\n" + GLOBALS + "HALT;");
            for (var entry : programs.entrySet()) {
                Set<GameFeature> available = EnumSet.allOf(GameFeature.class);
                available.remove(entry.getKey());
                GameResult result = GameEngine.check(level(List.of(example(0, 0)), 100), entry.getValue(), available,
                    Map.of("toolbox", "void helper() {\nNOP;\n}\n"), () -> false, count -> {});
                require(!result.passed() && result.summary().contains("locked"), entry.getKey() + ": " + result.summary());
            }
        });

        test("comments and longer identifiers do not trigger feature locks", () -> {
            String source = "// while if else void import == [ ] { ;\nvar while_count=0; var input=0; var out=0;\n"
                + "begin: INC while_count; // do { forbidden-looking words\nHALT;";
            GameResult result = check(level(List.of(example(0, 0)), 100), source, Set.of());
            require(result.passed(), result.summary());
        });

        test("both zero comparisons are available before general comparisons", () -> {
            for (String operator : List.of("==", "!=")) {
                GameResult result = check(level(List.of(example(0, 0)), 100),
                    GLOBALS + "if(input" + operator + "0) {NOP;} HALT;", Set.of(GameFeature.IF));
                require(result.passed(), result.summary());
            }
            GameResult literal = check(level(List.of(example(0, 0)), 100), GLOBALS + "if(0==0) {NOP;} HALT;", Set.of(GameFeature.IF));
            require(!literal.passed() && literal.summary().contains("locked"), "literal left operands need general comparisons");
        });

        test("array output ports use declared addresses and enforce bounds", () -> {
            GameLevel level = level(List.of(new GameLevel.TestCase(Map.of("input", 7), Map.of("cells[0]", 7, "cells[2]", 8))), 100);
            String source = "var input=0; var cells[3]=input; begin: INC cells[2]; HALT;";
            GameResult result = check(level, source, ALL);
            require(result.passed(), result.summary());
            GameLevel outside = level(List.of(new GameLevel.TestCase(Map.of("input", 7), Map.of("cells[3]", 0))), 100);
            require(!check(outside, source, ALL).passed(), "output index must be inside the array");
            require(!check(level, source.replace("INC cells[2]", "INC cells[3]"), ALL).passed(), "source index must be inside its array");
        });

        test("later structured syntax macros and virtual imports work together", () -> {
            String library = "void bump(value) {\nINC value;\n}\n";
            String source = "import \"toolbox\";\n" + GLOBALS
                + "var remaining=input;\nwhile(remaining!=0) {\nDEC remaining;\nbump(out);\n}\n"
                + "if(out==2) {INC out;} else {NOP;}\ndo {NOP;} while(0!=0); HALT;";
            GameResult result = GameEngine.check(level(List.of(example(0, 0), example(2, 3)), 3000), source, ALL,
                Map.of("toolbox", library), () -> false, count -> {});
            require(result.passed(), result.summary());
        });

        test("imports cannot read disk or smuggle locked or nested syntax", () -> {
            String source = "import \"toolbox\";\n" + GLOBALS + "HALT;";
            require(!check(level(List.of(example(0, 0)), 100), "import \"README.md\";\n" + GLOBALS + "HALT;", ALL).passed(), "disk imports are unavailable");
            for (String library : List.of("import \"other\";\n", "var hidden=0;\n", "void h() {\nwhile(0!=0) {NOP;}\n}\n")) {
                Set<GameFeature> available = EnumSet.allOf(GameFeature.class);
                available.remove(GameFeature.WHILE);
                GameResult result = GameEngine.check(level(List.of(example(0, 0)), 100), source, available,
                    Map.of("toolbox", library), () -> false, count -> {});
                require(!result.passed(), "unavailable library syntax rejected");
            }
        });

        test("malformed lists unsupported tokens and misplaced declarations are rejected", () -> {
            for (String source : List.of("void m(,a) {\nNOP;\n}\n" + GLOBALS + "HALT;",
                    "void m(a) {\nNOP;\n}\n" + GLOBALS + "m(input,); HALT;", GLOBALS + "#HALT;",
                    "begin: HALT; var out=0;", "var input=0; void m() {NOP;} var out=0; begin: HALT;",
                    GLOBALS + "if(input==0) INC out;", GLOBALS + "INC -1;")) {
                require(!check(level(List.of(example(0, 0)), 100), source, ALL).passed(), "malformed syntax rejected: " + source);
            }
        });

        test("step limits and missing HALT produce located execution failures", () -> {
            GameResult loop = check(level(List.of(example(1, 0)), 20), GLOBALS + "again: JNZ input again; HALT;", Set.of());
            require(!loop.passed() && loop.cases().get(0).steps() == 20 && loop.summary().contains("step limit"), loop.summary());
            require(loop.location() != null, "nontermination reports the executing source range");
            GameResult falloff = check(level(List.of(example(0, 0)), 100), GLOBALS + "NOP;", Set.of());
            require(!falloff.passed() && falloff.summary().contains("outside"), falloff.summary());
        });

        test("arithmetic overflow is stopped and input replacement offsets map back", () -> {
            String source = "var input=00000; var out=0; begin: INC input; HALT;";
            GameResult result = check(level(List.of(example(Integer.MAX_VALUE, 0)), 100), source, Set.of());
            require(!result.passed() && result.summary().contains("overflow"), result.summary());
            require(result.location() != null && result.location().startOffset() == source.indexOf("INC input;"), "original instruction offset survives a longer injected input");
            require(source.substring(result.location().startOffset(), result.location().endOffset()).equals("INC input;"), "feedback highlights exactly the original instruction");
        });

        test("expanded macros cannot write code or read code as jump data", () -> {
            for (String body : List.of("INC p[5];", "done: NOP;\nJNZ p[5] done;")) {
                String source = "void unsafe(p) {\n" + body + "\n}\n" + GLOBALS + "unsafe(input); HALT;";
                GameResult result = check(level(List.of(example(0, 0)), 100), source, ALL);
                require(!result.passed() && result.summary().contains("outside the data cells"), result.summary());
            }
        });

        test("resource limits precede large literal and macro expansion compilation", () -> {
            List<String> sources = List.of(GLOBALS + "var huge[256]=4000; HALT;",
                "void huge(p) {\nvar cells[256]=p;\n}\n" + GLOBALS + "huge(4000); HALT;",
                "void recurse() {\nrecurse();\n}\n" + GLOBALS + "recurse(); HALT;",
                GLOBALS + "var huge=2147483647; HALT;", "//" + "x".repeat(70_000) + "\n" + GLOBALS + "HALT;",
                GLOBALS + "{".repeat(65) + "NOP;" + "}".repeat(65) + "HALT;");
            for (String source : sources) {
                GameResult result = check(level(List.of(example(0, 0)), 100), source, ALL);
                require(!result.passed() && result.cases().isEmpty(), "oversized source fails before execution");
            }
        });

        test("cancellation is checked before validation and during execution", () -> {
            AtomicInteger calls = new AtomicInteger();
            GameLevel level = level(List.of(example(1, 0)), 100_000);
            GameResult immediate = GameEngine.check(level, GLOBALS + "HALT;", Set.of(), Map.of(), () -> true, count -> {});
            require(immediate.summary().contains("cancelled") && immediate.cases().isEmpty(), immediate.summary());
            GameResult running = GameEngine.check(level, GLOBALS + "again: JNZ input again; HALT;", Set.of(), Map.of(),
                () -> calls.incrementAndGet() > 100, count -> {});
            require(running.summary().contains("cancelled") && running.cases().isEmpty(), running.summary());
        });

        test("required features must occur in submitted code rather than only in library bodies", () -> {
            GameLevel ordinary = level(List.of(example(0, 0)), 100);
            GameLevel required = new GameLevel("required", "test", "test", "test", "", "", List.of(), Set.of(),
                Set.of(GameFeature.ARRAYS), Map.of(), ordinary.cases(), 100);
            GameResult omitted = check(required, GLOBALS + "HALT;", ALL);
            require(!omitted.passed() && omitted.summary().contains("asks you to use"), omitted.summary());
            GameResult imported = GameEngine.check(required, "import \"toolbox\";\n" + GLOBALS + "HALT;", ALL,
                Map.of("toolbox", "void arrayHelper() {\nvar cells[2]=0;\n}\n"), () -> false, count -> {});
            require(!imported.passed(), "library declarations do not fulfill the student exercise");
        });

        test("required control flow and declaration features cannot be replaced by equal outputs or imported syntax", () -> {
            Map<GameFeature, String> fragments = new LinkedHashMap<>();
            fragments.put(GameFeature.IF, "if(input==0) { NOP; }");
            fragments.put(GameFeature.ELSE, "if(input==0) { NOP; } else { NOP; }");
            fragments.put(GameFeature.WHILE, "while(out!=0) { DEC out; }");
            fragments.put(GameFeature.COMPARISONS, "if(input==3) { NOP; }");
            fragments.put(GameFeature.COPYING, "var copied=input;");
            fragments.put(GameFeature.LOCAL_VARIABLES, "var local=0;");
            GameLevel ordinary = level(List.of(example(0, 0), example(3, 0)), 100);
            require(check(ordinary, GLOBALS + "HALT;", ALL).passed(), "the control program already has all expected outputs");
            for (var entry : fragments.entrySet()) {
                GameLevel required = new GameLevel("practice-" + entry.getKey(), "test", "test", "test", "", "", List.of(),
                        Set.of(), Set.of(entry.getKey()), Map.of(), ordinary.cases(), 100);
                GameResult omitted = check(required, GLOBALS + "HALT;", ALL);
                require(!omitted.passed() && omitted.cases().isEmpty() && omitted.summary().contains("asks you to use"),
                        entry.getKey() + " must be present even when the outputs would match");
                String library = "void helper() {\n" + entry.getValue() + "\n}\n";
                String importedSource = "import \"toolbox\";\n" + GLOBALS + "helper();\nHALT;";
                GameResult imported = GameEngine.check(required, importedSource, ALL, Map.of("toolbox", library), () -> false, count -> {});
                require(!imported.passed() && imported.cases().isEmpty() && imported.summary().contains("asks you to use"),
                        entry.getKey() + " inside an imported definition does not count as student syntax");
                GameResult present = check(required, GLOBALS + entry.getValue() + "\nHALT;", ALL);
                require(present.passed(), entry.getKey() + ": " + present.summary());
                if (entry.getKey() == GameFeature.COPYING) {
                    GameResult globalCopy = check(required, "var input=0; var out=0; var copied=input; begin: HALT;", ALL);
                    require(globalCopy.passed(), "global copying also satisfies the initializer practice: " + globalCopy.summary());
                }
            }
        });

        test("the macro exercise requires an own definition rather than only a toolbox call", () -> {
            GameLevel required = new GameLevel("macro", "test", "test", "test", "", "", List.of(), Set.of(),
                Set.of(GameFeature.MACROS), Map.of(), List.of(example(0, 0)), 100);
            String library = "void helper() {\nNOP;\n}\n";
            GameResult call = GameEngine.check(required, "import \"toolbox\";\n" + GLOBALS + "helper(); HALT;", ALL,
                Map.of("toolbox", library), () -> false, count -> {});
            require(!call.passed() && call.summary().contains("asks you to use"), call.summary());
            GameResult own = check(required, "void own() {\nNOP;\n}\n" + GLOBALS + "own(); HALT;", ALL);
            require(own.passed(), own.summary());
        });

        test("ordinary global names including i never alias the instruction pointer", () -> {
            GameLevel level = level(List.of(new GameLevel.TestCase(Map.of("i", 4), Map.of("i", 5))), 100);
            GameResult result = check(level, "var i=0; begin: INC i; HALT;", Set.of());
            require(result.passed() && result.cases().get(0).steps() == 1, result.summary());
        });

        test("arity overloads compose add three four and twenty-four with flip one and eight", () -> {
            StringBuilder helpers = new StringBuilder("void add(a,b,result) {\n"
                    + "var left=a;\nvar right=b;\nwhile(left!=0) { INC result; DEC left; }\n"
                    + "while(right!=0) { INC result; DEC right; }\n}\n"
                    + "void add(a,b,carry,result) {\nadd(a,b,result);\nadd(carry,0,result);\n}\n"
                    + "void flip(bit) {\nif(bit==0) { INC bit; } else { DEC bit; }\n}\n");
            List<String> parameters = new ArrayList<>();
            List<String> addArguments = new ArrayList<>();
            List<String> flipParameters = new ArrayList<>();
            List<String> flipArguments = new ArrayList<>();
            for (String prefix : List.of("a", "b", "result")) {
                for (int index = 0; index < 8; index++) {
                    parameters.add(prefix + index);
                    addArguments.add(prefix + "[" + index + "]");
                }
            }
            helpers.append("void add(").append(String.join(",", parameters)).append(") {\n");
            for (int index = 0; index < 8; index++) {
                helpers.append("add(a").append(index).append(",b").append(index)
                        .append(",0,result").append(index).append(");\n");
                flipParameters.add("bit" + index);
                flipArguments.add("bits[" + index + "]");
            }
            helpers.append("}\nvoid flip(").append(String.join(",", flipParameters)).append(") {\n");
            for (String parameter : flipParameters) helpers.append("flip(").append(parameter).append(");\n");
            helpers.append("}\n");
            String program = "var input=0; var sum3=0; var sum4=0; var a[8]=input; var b[8]=1; "
                    + "var result[8]=0; var bit=input; var bits[8]=input; begin:\n"
                    + "add(input,1,sum3);\nadd(input,1,1,sum4);\nadd(" + String.join(",", addArguments) + ");\n"
                    + "flip(bit);\nflip(" + String.join(",", flipArguments) + ");\nHALT;";
            List<GameLevel.TestCase> examples = new ArrayList<>();
            for (int input : List.of(0, 1)) {
                Map<String, Integer> outputs = new LinkedHashMap<>();
                outputs.put("sum3", input + 1);
                outputs.put("sum4", input + 2);
                outputs.put("bit", 1 - input);
                for (int index = 0; index < 8; index++) {
                    outputs.put("result[" + index + "]", input + 1);
                    outputs.put("bits[" + index + "]", 1 - input);
                }
                examples.add(new GameLevel.TestCase(Map.of("input", input), outputs));
            }
            GameLevel level = level(examples, 5000);
            GameResult own = check(level, helpers + program, ALL);
            require(own.passed(), own.summary());
            GameResult imported = GameEngine.check(level, "import \"toolbox\";\n" + program, ALL,
                    Map.of("toolbox", helpers.toString()), () -> false, count -> {});
            require(imported.passed(), imported.summary());
            require(own.cases().stream().map(GameResult.CaseResult::actual).toList()
                    .equals(imported.cases().stream().map(GameResult.CaseResult::actual).toList()),
                    "source and virtual-library overload chains produce identical port values");
        });

        test("macro arity remains bounded at thirty-two parameters and arguments", () -> {
            List<String> parameters = new ArrayList<>();
            for (int index = 0; index < 32; index++) parameters.add("p" + index);
            String definition = "void wide(" + String.join(",", parameters) + ") {\nINC p31;\n}\n";
            String wideArguments = String.join(",", java.util.Collections.nCopies(32, "out"));
            GameLevel level = level(List.of(example(0, 1)), 100);
            GameResult boundary = check(level, definition + GLOBALS + "wide(" + wideArguments + ");\nHALT;", ALL);
            require(boundary.passed(), boundary.summary());
            for (String source : List.of(definition.replace("p31)", "p31,p32)") + GLOBALS + "HALT;",
                    definition + GLOBALS + "wide(" + wideArguments + ",out);\nHALT;")) {
                GameResult excessive = check(level, source, ALL);
                require(!excessive.passed() && excessive.cases().isEmpty()
                        && excessive.summary().contains("maximum 32"), excessive.summary());
            }
        });

        test("direct and cross-arity macro cycles remain rejected before compilation", () -> {
            for (String definitions : List.of(
                    "void loop(a) {\nloop(a);\n}\n",
                    "void loop(a) {\nloop(a,a);\n}\nvoid loop(a,b) {\nloop(a);\n}\n",
                    "void first(a) {\nsecond(a,a);\n}\nvoid second(a,b) {\nfirst(a);\n}\n")) {
                GameResult result = check(level(List.of(example(0, 0)), 100), definitions + GLOBALS + "HALT;", ALL);
                require(!result.passed() && result.cases().isEmpty()
                        && result.summary().contains("Recursive macro"), result.summary());
            }
        });

        test("overload expansion and literal-specialization estimates stay conservative", () -> {
            StringBuilder definitions = new StringBuilder();
            for (int arity = 16; arity >= 1; arity--) {
                List<String> parameters = new ArrayList<>();
                for (int index = 0; index < arity; index++) parameters.add("p" + index);
                definitions.append("void grow(").append(String.join(",", parameters)).append(") {\n");
                if (arity == 16) definitions.append("INC p0;\n");
                else {
                    String invocation = "grow(" + String.join(",", java.util.Collections.nCopies(arity + 1, "p0")) + ");\n";
                    definitions.append(invocation).append(invocation);
                }
                definitions.append("}\n");
            }
            GameResult expanding = check(level(List.of(example(0, 0)), 100), definitions + GLOBALS + "grow(out);\nHALT;", ALL);
            require(!expanding.passed() && expanding.cases().isEmpty()
                    && expanding.summary().contains("resource limit"), expanding.summary());
            String specialized = "void choose(0,p) {\nvar cells[256]=4000;\n}\nvoid choose(1,p) {\nNOP;\n}\n"
                    + GLOBALS + "choose(1,out);\nHALT;";
            GameResult worstCase = check(level(List.of(example(0, 0)), 100), specialized, ALL);
            require(!worstCase.passed() && worstCase.cases().isEmpty(), "all literal variants retain their code-size bounds");
        });

        test("costs follow reachable arguments without charging unused helper expansions", () -> {
            String definitions = "void leaf(n) {\nvar cells[256]=n;\n}\n"
                    + "void forward(n) {\nleaf(n);\n}\n"
                    + "void unused() {\nforward(4000);\n}\n"
                    + "void marker(n) {\nNOP;\n}\n";
            GameLevel level = level(List.of(example(0, 0), example(2, 0)), 100_000);
            for (String calls : List.of("", "forward(2);\n", "marker(4000);\nforward(2);\n", "forward(input);\n")) {
                GameResult result = check(level, definitions + GLOBALS + calls + "HALT;", ALL);
                require(result.passed(), calls + ": " + result.summary());
            }
            GameResult oversized = check(level, definitions + GLOBALS + "forward(4000);\nHALT;", ALL);
            require(!oversized.passed() && oversized.cases().isEmpty()
                    && oversized.summary().contains("too much code"), oversized.summary());
        });

        test("the complete earned toolbox permits HALT and a reachable nested byte helper", () -> {
            Set<String> completed = new java.util.LinkedHashSet<>();
            GameCatalog.levels().forEach(level -> completed.add(level.id()));
            Map<String, String> libraries = GameCatalog.library(completed);
            for (String path : List.of("toolbox", "macros.a26")) {
                GameResult unused = GameEngine.check(level(List.of(example(0, 0)), 100),
                        "import \"" + path + "\";\n" + GLOBALS + "HALT;", ALL, libraries, () -> false, count -> {});
                require(unused.passed(), path + ": " + unused.summary());
            }
            List<GameLevel.TestCase> examples = new ArrayList<>();
            for (int input : List.of(0, 170, 256)) {
                Map<String, Integer> expected = new LinkedHashMap<>();
                expected.put("input", input);
                for (int bit = 0; bit < 8; bit++) expected.put("bits[" + bit + "]", (input >> bit) & 1);
                examples.add(new GameLevel.TestCase(Map.of("input", input), expected));
            }
            String source = "import \"macros.a26\";\nvar input=0; var bits[8]=0; begin:\n"
                    + "lowestByte(input,bits[0],bits[1],bits[2],bits[3],bits[4],bits[5],bits[6],bits[7]);\nHALT;";
            GameResult expanded = GameEngine.check(level(examples, 500_000), source, ALL, libraries, () -> false, count -> {});
            require(expanded.passed() && expanded.cases().size() == examples.size(), expanded.summary());
        });

        test("macro expansion nesting is bounded before the compiler runs", () -> {
            String source = "void first() {\n" + "{\n".repeat(32) + "second();\n" + "}\n".repeat(33)
                + "void second() {\n" + "{\n".repeat(32) + "NOP;\n" + "}\n".repeat(33)
                + GLOBALS + "first(); HALT;";
            GameResult result = check(level(List.of(example(0, 0)), 100), source, ALL);
            require(!result.passed() && result.summary().contains("nested too deeply"), result.summary());
            require(result.cases().isEmpty(), "expanded nesting is rejected before execution");
        });

        System.out.println("GameEngineTest: " + passed + " passed, " + failed + " failed");
        if (failed != 0) throw new AssertionError(failed + " game engine tests failed");
    }

    private static GameLevel.TestCase example(int input, int output) {
        return new GameLevel.TestCase(Map.of("input", input), Map.of("out", output));
    }
    private static String clearBlock(String body) {
        return "var dummy=0; var x=0; begin: " + body;
    }
    private static GameLevel catalogLevel(String id) {
        return GameCatalog.levels().stream().filter(candidate -> candidate.id().equals(id)).findFirst().orElseThrow();
    }
    private static Set<GameFeature> earnedBefore(GameLevel target) {
        Set<GameFeature> earned = EnumSet.noneOf(GameFeature.class);
        for (GameLevel candidate : GameCatalog.levels()) {
            if (candidate.id().equals(target.id())) return earned;
            earned.addAll(candidate.unlocks());
        }
        throw new AssertionError("Unknown catalog level " + target.id());
    }
    private static GameLevel level(List<GameLevel.TestCase> cases, int maxSteps) {
        return new GameLevel("test", "test", "Test", "Test goal", "", "", List.of(), Set.of(), Set.of(), Map.of(), cases, maxSteps);
    }
    private static GameResult check(GameLevel level, String source, Set<GameFeature> features) {
        return GameEngine.check(level, source, features, Map.of(), () -> false, count -> {});
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void test(String name, Test test) {
        try { test.run(); ++passed; System.out.println("PASS " + name); }
        catch (Exception | AssertionError error) { ++failed; System.err.println("FAIL " + name + ": " + error); error.printStackTrace(); }
    }
}

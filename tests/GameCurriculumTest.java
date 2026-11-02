package machine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntBinaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Verified reference programs are test data, not hints displayed by the game.
 * Run from the repository root in a fresh directory:
 *
 * test_build=$(mktemp -d)
 * javac --release 17 -d "$test_build" -classpath "$test_build" -sourcepath . tests/GameCurriculumTest.java tests/GameBookLevelsTest.java
 * java -cp "$test_build" machine.GameCurriculumTest
 */
public final class GameCurriculumTest {
    private static int passed;
    private static int failed;

    @FunctionalInterface
    private interface Check {
        void run() throws Exception;
    }

    public static void main(String[] args) {
        List<GameLevel> levels = GameCatalog.levels();
        Map<String, String> solutions = solutions();
        check("catalog has a bounded, complete progression and varied contracts", () -> {
            expect(levels.size() == 46, "expected 46 short levels");
            expect(levels.stream().limit(21).map(GameLevel::id).toList().equals(List.of(
                            "first-steps", "clear-block", "clear-do-while", "mandatory-lap", "zero-test", "two-paths",
                            "else-practice", "countdown", "while-practice", "equals-three", "comparison-practice", "preserve-copy",
                            "reset-scratch", "three-cells", "array-practice", "repeated-work", "macro-practice",
                            "literal-dispatch", "specialization-practice", "compose-helpers", "import-practice")),
                    "each language extension is immediately followed by required practice");
            expect(levels.get(1).unlocks().equals(Set.of(GameFeature.DO_WHILE))
                            && levels.get(1).requiredFeatures().equals(Set.of(GameFeature.BLOCKS, GameFeature.EMPTY_STATEMENTS))
                            && levels.get(4).unlocks().equals(Set.of(GameFeature.IF))
                            && levels.get(5).unlocks().equals(Set.of(GameFeature.ELSE))
                            && level("countdown").unlocks().equals(Set.of(GameFeature.WHILE)),
                    "the labeled block earns do/while, and conditionals precede while");
            expect(level("clear-do-while").unlocks().isEmpty()
                            && level("clear-do-while").requiredFeatures().equals(Set.of(GameFeature.DO_WHILE)),
                    "the next lesson rewrites the same loop using its newly earned syntax");
            expect(level("mandatory-lap").unlocks().isEmpty()
                            && level("mandatory-lap").requiredFeatures().equals(Set.of(GameFeature.DO_WHILE)),
                    "mandatory-lap practices the previously earned do/while syntax");
            expect(level("while-practice").unlocks().isEmpty()
                            && level("while-practice").requiredFeatures().equals(Set.of(GameFeature.WHILE)),
                    "the newly unlocked while is practiced by rewriting its guarded do/while construction");
            Set<String> ids = new HashSet<>();
            Set<String> worlds = new HashSet<>();
            Set<GameFeature> earned = EnumSet.noneOf(GameFeature.class);
            for (GameLevel level : levels) {
                expect(ids.add(level.id()), "duplicate level id " + level.id());
                worlds.add(level.world());
                expect(!level.title().isBlank() && !level.goal().isBlank() && !level.lesson().isBlank(),
                        "every level needs student-facing instructions");
                expect(level.hints().size() == 3 && level.hints().stream().noneMatch(String::isBlank),
                        "each level has three graduated hints: " + level.id());
                expect(level.maxSteps() > 0 && level.maxSteps() <= 1_000_000, "bounded steps: " + level.id());
                expect(level.cases().size() >= 2, "multiple inputs: " + level.id());
                expect(level.cases().stream().map(GameLevel.TestCase::inputs).distinct().count() == level.cases().size(),
                        "test inputs must be distinct: " + level.id());
                expect(earned.containsAll(level.requiredFeatures()), "practice follows its unlock: " + level.id());
                expect(Collections.disjoint(earned, level.unlocks()), "each feature is introduced once: " + level.id());
                earned.addAll(level.unlocks());
                expect(solutions.containsKey(level.id()), "missing reference for " + level.id());
            }
            expect(earned.equals(EnumSet.allOf(GameFeature.class)), "all language features are earned");
            expect(worlds.size() == 6, "six curriculum worlds");
            expect(level("countdown").requiredFeatures().equals(Set.of(GameFeature.IF, GameFeature.DO_WHILE)),
                    "derive while from if plus do/while as in the book");
            expect(solutions.size() == levels.size(), "no stale reference solutions");
            expect(new ArrayList<>(solutions.keySet()).equals(levels.stream().map(GameLevel::id).toList()),
                    "reference solutions follow the curriculum order");
        });

        check("every grammar unlock is required by the immediately following level", () -> {
            Set<GameFeature> practiced = EnumSet.noneOf(GameFeature.class);
            for (int index = 0; index < levels.size(); index++) {
                GameLevel current = levels.get(index);
                if (current.unlocks().isEmpty()) continue;
                expect(index + 1 < levels.size(), "unlock must have a following practice level: " + current.id());
                GameLevel next = levels.get(index + 1);
                expect(next.requiredFeatures().containsAll(current.unlocks()),
                        current.id() + " unlocks " + current.unlocks() + " but " + next.id() + " requires " + next.requiredFeatures());
                practiced.addAll(current.unlocks());
            }
            expect(practiced.equals(EnumSet.allOf(GameFeature.class)), "all thirteen feature unlocks receive immediate practice");
        });

        check("catalog and libraries are immutable and rewards stay in curriculum order", () -> {
            expectUnsupported(() -> levels.clear());
            expectUnsupported(() -> levels.get(0).hints().add("extra"));
            expectUnsupported(() -> levels.get(0).cases().get(0).inputs().put("x", 99));
            expect(GameCatalog.library(Set.of()).get("toolbox").isEmpty(), "nothing earned means an empty toolbox");
            expect(GameCatalog.library(Set.of("not-a-level")).get("toolbox").isEmpty(), "unknown ids earn no helper");
            Set<String> reverseOrder = new LinkedHashSet<>();
            StringBuilder expected = new StringBuilder();
            for (GameLevel level : levels) {
                level.rewards().values().forEach(helper -> expected.append(helper).append('\n'));
            }
            for (int index = levels.size() - 1; index >= 0; index--) {
                reverseOrder.add(levels.get(index).id());
            }
            Map<String, String> library = GameCatalog.library(reverseOrder);
            expect(expected.toString().equals(library.get("toolbox")), "helper dependency order must ignore completion-set order");
            expect(library.get("toolbox").equals(library.get("macros.a26")), "book import names the same earned library");
            expectUnsupported(() -> library.put("disk-file", "text"));
        });

        check("clear-block groups DEC while setup and the repeating jump stay outside", () -> {
            GameLevel challenge = level("clear-block");
            Set<GameFeature> initialTools = levels.get(0).unlocks();
            GameLevel behaviorOnly = new GameLevel("clear-behavior", challenge.world(), challenge.title(),
                    challenge.goal(), challenge.lesson(), challenge.starter(), challenge.hints(), Set.of(), Set.of(),
                    Map.of(), challenge.cases(), challenge.maxSteps());
            GameResult starterBehavior = GameEngine.check(behaviorOnly, challenge.starter(), initialTools,
                    Map.of(), () -> false, count -> { });
            expect(starterBehavior.passed(), "the supplied unbraced starter must already work: " + starterBehavior.summary());
            GameResult missingBlock = GameEngine.check(challenge, challenge.starter(), initialTools,
                    Map.of(), () -> false, count -> { });
            expect(!missingBlock.passed(), "working output alone must not satisfy the block exercise");
            String unrelatedBlock = challenge.starter().replace("INC dummy;", "{ INC dummy; }");
            expect(!GameEngine.check(challenge, unrelatedBlock, initialTools, Map.of(), () -> false, count -> { }).passed(),
                    "braces around setup must not satisfy the required do-labeled block");
            String repeatedSetup = program("clear-block", false, "", "do: {\nINC dummy;\nDEC x;\n}\nJNZ x do;\n");
            expect(!GameEngine.check(behaviorOnly, repeatedSetup, initialTools, Map.of(), () -> false, count -> { }).passed(),
                    "the nonzero cases must detect setup repeated inside the clearing loop");
            String jumpInside = program("clear-block", false, "", "INC dummy;\n;\ndo: {\nDEC x;\nJNZ x do;\n}\n");
            expect(!GameEngine.check(challenge, jumpInside, initialTools, Map.of(), () -> false, count -> { }).passed(),
                    "the exercise requires the repeating jump after the closing brace");
            String withoutEmpty = solutions.get("clear-block").replace("INC dummy;\n;\n", "INC dummy;\n");
            expect(!GameEngine.check(challenge, withoutEmpty, initialTools, Map.of(), () -> false, count -> { }).passed(),
                    "a working block alone cannot skip practice of the newly earned empty statement");
            GameResult correctBlock = GameEngine.check(challenge, solutions.get("clear-block"), initialTools,
                    Map.of(), () -> false, count -> { });
            expect(correctBlock.passed(), correctBlock.summary());
            Set<GameFeature> structuredTools = EnumSet.copyOf(initialTools);
            structuredTools.add(GameFeature.DO_WHILE);
            String structured = program("clear-block", false, "", "INC dummy;\n;\ndo {\nDEC x;\n} while (x != 0);\n");
            GameResult structuredResult = GameEngine.check(behaviorOnly, structured, structuredTools,
                    Map.of(), () -> false, count -> { });
            expect(structuredResult.passed(), structuredResult.summary());
            expect(correctBlock.cases().stream().map(GameResult.CaseResult::actual).toList()
                            .equals(structuredResult.cases().stream().map(GameResult.CaseResult::actual).toList()),
                    "the external jump and structured do/while agree for zero and positive x and dummy inputs");
        });

        check("clear-do-while rewrites a working labeled loop with identical outputs", () -> {
            GameLevel challenge = level("clear-do-while");
            expect(challenge.cases().equals(level("clear-block").cases()), "the rewrite keeps every preceding input and output case");
            Set<GameFeature> tools = EnumSet.copyOf(levels.get(0).unlocks());
            tools.addAll(level("clear-block").unlocks());
            GameLevel behaviorOnly = new GameLevel("rewrite-behavior", challenge.world(), challenge.title(),
                    challenge.goal(), challenge.lesson(), challenge.starter(), challenge.hints(), Set.of(), Set.of(),
                    Map.of(), challenge.cases(), challenge.maxSteps());
            Set<GameFeature> earlierTools = EnumSet.copyOf(tools);
            earlierTools.remove(GameFeature.DO_WHILE);
            GameResult starter = GameEngine.check(behaviorOnly, challenge.starter(), earlierTools, Map.of(), () -> false, count -> { });
            expect(starter.passed(), "the supplied labeled-block starter already clears x: " + starter.summary());
            expect(!GameEngine.check(challenge, challenge.starter(), tools, Map.of(), () -> false, count -> { }).passed(),
                    "working original notation alone does not complete the rewrite");
            GameResult rewritten = GameEngine.check(challenge, solutions.get(challenge.id()), tools, Map.of(), () -> false, count -> { });
            expect(rewritten.passed(), rewritten.summary());
            expect(starter.cases().stream().map(GameResult.CaseResult::actual).toList()
                            .equals(rewritten.cases().stream().map(GameResult.CaseResult::actual).toList()),
                    "rewriting preserves zero handling and increments dummy exactly once");
        });

        check("new practice starters work but cannot bypass their newly earned syntax", () -> {
            for (String id : List.of("else-practice", "while-practice", "comparison-practice")) {
                GameLevel challenge = level(id);
                Set<GameFeature> tools = earnedBefore(id);
                GameLevel behaviorOnly = new GameLevel("practice-behavior", challenge.world(), challenge.title(),
                        challenge.goal(), challenge.lesson(), challenge.starter(), challenge.hints(), Set.of(), Set.of(),
                        Map.of(), challenge.cases(), challenge.maxSteps());
                GameResult prior = GameEngine.check(behaviorOnly, challenge.starter(), tools, Map.of(), () -> false, count -> { });
                expect(prior.passed(), id + " starter must retain the previous working behavior: " + prior.summary());
                GameResult missingSyntax = GameEngine.check(challenge, challenge.starter(), tools, Map.of(), () -> false, count -> { });
                expect(!missingSyntax.passed() && missingSyntax.cases().isEmpty()
                                && missingSyntax.summary().contains("asks you to use"),
                        id + " must reject the working old syntax specifically for missing feature usage: " + missingSyntax.summary());
                GameResult rewritten = GameEngine.check(challenge, solutions.get(id), tools, Map.of(), () -> false, count -> { });
                expect(rewritten.passed(), rewritten.summary());
                expect(prior.cases().stream().map(GameResult.CaseResult::actual).toList()
                                .equals(rewritten.cases().stream().map(GameResult.CaseResult::actual).toList()),
                        id + " rewrite preserves the full input/output contract");
            }
        });

        check("while practice replaces the guarded loop instead of decorating the old construction", () -> {
            GameLevel challenge = level("while-practice");
            Set<GameFeature> tools = earnedBefore(challenge.id());
            expect(challenge.cases().equals(level("countdown").cases()), "rewrite retains countdown inputs and outputs");
            expect(challenge.cases().get(0).inputs().get("n") == 0
                            && challenge.cases().get(0).expected().get("count") == 0,
                    "zero input exercises the zero-iteration behavior");
            expect(GameWorld.forLevel(challenge) == GameWorld.STRUCTURED_PROGRAMMING,
                    "the direct rewrite introduces the structured world");
            GameLevel behaviorOnly = new GameLevel("while-behavior", challenge.world(), challenge.title(),
                    challenge.goal(), challenge.lesson(), challenge.starter(), challenge.hints(), Set.of(), Set.of(),
                    Map.of(), challenge.cases(), challenge.maxSteps());
            String loop = "while (n != 0) {\n  DEC n;\n  INC count;\n}\n";
            List<String> decorations = List.of(
                    challenge.starter().replace("HALT;", "while (n != 0) { NOP; }\nHALT;"),
                    program(challenge.id(), false, "", "if (n != 0) {\n" + loop + "}\n"),
                    program(challenge.id(), false, "", "while (n != 0) {\ndo {\nDEC n;\nINC count;\n} while (n != 0);\n}\n"),
                    program(challenge.id(), false, "", loop + "while (n != 0) { NOP; }\n"));
            for (String source : decorations) {
                GameResult behavior = GameEngine.check(behaviorOnly, source, tools, Map.of(), () -> false, count -> { });
                expect(behavior.passed(), "counterexample preserves the output contract: " + behavior.summary());
                GameResult rejected = GameEngine.check(challenge, source, tools, Map.of(), () -> false, count -> { });
                expect(!rejected.passed() && rejected.cases().isEmpty() && rejected.location() != null,
                        "a redundant while or remaining guard/do must fail before executing: " + rejected.summary());
            }
            String unguarded = program(challenge.id(), false, "", "do { DEC n; INC count; } while (n != 0);\n");
            GameResult zero = GameEngine.check(behaviorOnly, unguarded, tools, Map.of(), () -> false, count -> { });
            expect(!zero.passed() && zero.cases().get(0).actual().get("count") == 1,
                    "an unguarded do/while executes once at zero and changes behavior");
            String withComments = solutions.get(challenge.id()).replace("while (", "while // test before the body\n(")
                    .replace("DEC n;", "DEC\t n; // transfer one unit\n");
            GameResult rewritten = GameEngine.check(challenge, withComments, tools, Map.of(), () -> false, count -> { });
            expect(rewritten.passed(), "comments and whitespace do not change the required rewrite: " + rewritten.summary());
        });

        check("moved practice uses only tools available before imports and macros", () -> {
            for (String id : List.of("array-practice", "macro-practice", "specialization-practice")) {
                expect(!earnedBefore(id).contains(GameFeature.IMPORTS), id + " precedes imports");
                expect(!level(id).starter().contains("import ") && !solutions.get(id).contains("import "),
                        id + " has no prematurely available import");
            }
            expect(!earnedBefore("array-practice").contains(GameFeature.MACROS), "arrays are practiced before macros");
            expect(!solutions.get("array-practice").contains("copyTo"), "array reference needs no unavailable helper");
        });

        check("strengthened existing lessons reject correct output without their newly earned construct", () -> {
            Map<String, String> oldForms = new LinkedHashMap<>();
            oldForms.put("two-paths", program("two-paths", false, "",
                    "INC result;\nJNZ n done;\nINC result;\ndone:\n"));
            oldForms.put("equals-three", program("equals-three", false, "",
                    "if(n!=0) {\ndo {\nDEC n;\nINC saved;\nINC work;\n} while(n!=0);\n}\n"
                    + "if(saved!=0) {\ndo {\nDEC saved;\nINC n;\n} while(saved!=0);\n}\n"
                    + "if(work!=0) {\ndo {\nif(budget==0) {\nINC excess;\n} else {\nDEC budget;\n}\nDEC work;\n} while(work!=0);\n}\n"
                    + "if(budget==0) {\nif(excess==0) {\nINC equal;\n}\n}\n"));
            oldForms.put("reset-scratch", program("reset-scratch", false, "",
                    "while(scratch!=0) {\nDEC scratch;\n}\nwhile(n!=0) {\nDEC n;\nINC result;\n}\n"));
            oldForms.put("three-cells", program("three-cells", false, "", "")
                    .replace("var first = 0;", "var first = b;")
                    .replace("var second = 0;", "var second = c;")
                    .replace("var third = 0;", "var third = a;"));
            for (Map.Entry<String, String> entry : oldForms.entrySet()) {
                GameLevel challenge = level(entry.getKey());
                Set<GameFeature> tools = earnedBefore(challenge.id());
                GameLevel behaviorOnly = new GameLevel("practice-behavior", challenge.world(), challenge.title(),
                        challenge.goal(), challenge.lesson(), entry.getValue(), challenge.hints(), Set.of(), Set.of(),
                        Map.of(), challenge.cases(), challenge.maxSteps());
                GameResult behavior = GameEngine.check(behaviorOnly, entry.getValue(), tools, Map.of(), () -> false, count -> { });
                expect(behavior.passed(), challenge.id() + " alternative must first satisfy the output contract: " + behavior.summary());
                GameResult practice = GameEngine.check(challenge, entry.getValue(), tools, Map.of(), () -> false, count -> { });
                expect(!practice.passed() && practice.cases().isEmpty() && practice.summary().contains("asks you to use"),
                        challenge.id() + " must require its newly earned syntax: " + practice.summary());
            }
        });

        Set<GameFeature> available = EnumSet.noneOf(GameFeature.class);
        Set<String> completed = new LinkedHashSet<>();
        for (GameLevel level : levels) {
            check("reference passes with only previously earned tools: " + level.id(), () -> {
                Map<String, String> libraries = GameCatalog.library(completed);
                GameResult result = GameEngine.check(level, solutions.get(level.id()), available, libraries,
                        () -> false, count -> { });
                expect(result.passed(), result.summary());
                expect(result.cases().size() == level.cases().size(), "all cases must execute");
                expect(result.cases().stream().allMatch(test -> test.steps() < level.maxSteps()),
                        "reference must finish within its per-case budget");
                GameResult starter = GameEngine.check(level, level.starter(), available, libraries,
                        () -> false, count -> { });
                expect(!starter.passed(), "starter already solves " + level.id());
            });
            // Continue checking independent references even if one group fails.
            available.addAll(level.unlocks());
            completed.add(level.id());
        }

        check("every earned helper is executable, including literal specializations and output reuse", () -> {
            String source = "import \"toolbox\";\n"
                    + "var n = 0;\nvar copied = 0;\nvar doubled = 0;\nvar first = 0;\nvar second = 0;\nvar advanced = 0;\n"
                    + "begin:\ncopyTo(n, copied);\ncopyTo(copied, copied);\ntwiceTo(n, doubled);\n"
                    + "step(0, first);\nstep(1, second);\ncopyTo(n, advanced);\nadvanceThree(advanced);\nHALT;\n";
            GameLevel helperCheck = new GameLevel("helper-contracts", "Tests", "Helpers", "", "", source,
                    List.of(), Set.of(), Set.of(), Map.of(), List.of(
                        new GameLevel.TestCase(Map.of("n", 0), Map.of("n", 0, "copied", 0, "doubled", 0, "first", 1, "second", 2, "advanced", 3)),
                        new GameLevel.TestCase(Map.of("n", 5), Map.of("n", 5, "copied", 5, "doubled", 10, "first", 1, "second", 2, "advanced", 8))), 500_000);
            GameResult result = GameEngine.check(helperCheck, source, available, GameCatalog.library(completed),
                    () -> false, count -> { });
            expect(result.passed(), result.summary());
        });

        check("documented in-place helpers preserve the input values before replacing outputs", () -> {
            Map<String, String> library = GameCatalog.library(completed);
            int[][] copies = {{0, 0}, {1, 7}, {9, 2}};
            assertAlias("copyTo(a, a);", "a", copies, (a, b) -> a, library);
            assertAlias("twiceTo(a, a);", "a", copies, (a, b) -> 2 * a, library);
            int[][] arithmetic = {{0, 0}, {0, 6}, {7, 0}, {3, 4}, {5, 5}};
            for (String output : List.of("a", "b")) {
                assertAlias("addTo(a, b, " + output + ");", output, arithmetic, (a, b) -> a + b, library);
                assertAlias("subtractTo(a, b, " + output + ");", output, arithmetic,
                        (a, b) -> Math.max(0, a - b), library);
                assertAlias("multiplyTo(a, b, " + output + ");", output, arithmetic, (a, b) -> a * b, library);
                assertAlias("gcdTo(a, b, " + output + ");", output,
                        new int[][] {{0, 0}, {0, 9}, {8, 0}, {12, 18}, {7, 5}}, GameCurriculumTest::gcd, library);
            }
            assertAlias("copy(a, a);", "a", copies, (a, b) -> a, library);
            for (String output : List.of("a", "b")) {
                assertAlias("add(a, b, " + output + ");", output, arithmetic, (a, b) -> a + b, library);
                assertAlias("sub(a, b, " + output + ");", output, arithmetic, (a, b) -> Math.max(0, a - b), library);
                assertAlias("mul(a, b, " + output + ");", output, arithmetic, (a, b) -> a * b, library);
                int[][] divisors = {{0, 1}, {1, 1}, {1, 3}, {17, 4}, {9, 9}};
                assertAlias("div(a, b, " + output + ");", output, divisors, (a, b) -> a / b, library);
                assertAlias("mod(a, b, " + output + ");", output, divisors, (a, b) -> a % b, library);
                assertAlias("gcd(a, b, " + output + ");", output,
                        new int[][] {{0, 0}, {0, 9}, {8, 0}, {12, 18}, {7, 5}}, GameCurriculumTest::gcd, library);
            }
            assertAlias("wrap4(a, a);", "a", new int[][] {{0, 3}, {15, 0}, {16, 2}, {31, 1}, {255, 4}},
                    (a, b) -> a % 16, library);
            assertAlias("wrap8(a, a);", "a", new int[][] {{0, 3}, {255, 0}, {256, 2}, {511, 1}},
                    (a, b) -> a % 256, library);
            int[][] words = {{0, 3}, {7, 0}, {8, 2}, {15, 1}};
            assertAlias("not4(a, a);", "a", words, (a, b) -> 15 - a, library);
            assertAlias("negate4(a, a);", "a", words, (a, b) -> (16 - a) % 16, library);
            assertAlias("extend4(a, a);", "a", words, (a, b) -> a >= 8 ? a + 240 : a, library);
        });

        check("four-bit unary contracts cover every encoding and signed boundaries are tested", () -> {
            for (String id : List.of("decompose-four", "decode-four", "not-four", "negate-four", "extend-four")) {
                Set<Integer> encodings = new HashSet<>();
                for (GameLevel.TestCase test : level(id).cases()) {
                    encodings.add(test.inputs().get("word"));
                }
                expect(encodings.size() == 16 && Collections.min(encodings) == 0 && Collections.max(encodings) == 15,
                        "complete four-bit domain for " + id);
            }
            expect(hasInputs("gcd", Map.of("a", 0, "b", 0)), "gcd(0,0) must be defined and checked");
            expect(level("divmod").cases().stream().allMatch(test -> test.inputs().get("divisor") > 0),
                    "divmod contract excludes a zero divisor");
            expect(hasInputs("add-four", Map.of("a", 7, "b", 1)), "positive signed overflow boundary");
            expect(hasInputs("add-four", Map.of("a", 8, "b", 8)), "negative signed overflow boundary");
            expect(hasInputs("subtract-four", Map.of("a", 0, "b", 8)), "subtracting the most negative word");
            expect(hasInputs("transfer-eight", Map.of("a", 127, "b", 1)), "eight-bit positive overflow");
            expect(hasInputs("transfer-eight", Map.of("a", 128, "b", 255)), "eight-bit negative overflow");
            expect(hasInputs("transfer-eight", Map.of("a", 255, "b", 1)), "eight-bit wrap without signed overflow");
        });

        check("all instructional source literals and array sizes stay small", () -> {
            Pattern numbers = Pattern.compile("\\b[0-9]+\\b");
            Pattern arrays = Pattern.compile("\\bvar\\s+\\w+\\s*\\[\\s*([0-9]+)\\s*\\]");
            List<String> sources = new ArrayList<>(solutions.values());
            levels.forEach(level -> {
                sources.add(level.starter());
                sources.addAll(level.rewards().values());
            });
            for (String source : sources) {
                Matcher number = numbers.matcher(source);
                while (number.find()) {
                    expect(Integer.parseInt(number.group()) <= 1024, "literal exceeds curriculum limit");
                }
                Matcher array = arrays.matcher(source);
                while (array.find()) {
                    expect(Integer.parseInt(array.group(1)) <= 256, "array exceeds curriculum limit");
                }
            }
        });

        System.out.println("GameCurriculumTest: " + passed + " passed, " + failed + " failed");
        if (failed != 0) {
            throw new AssertionError(failed + " curriculum checks failed");
        }
    }

    /** Test-only full programs, also usable by the separate GUI regression suite. */
    public static Map<String, String> solutions() {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("first-steps", program("first-steps", false, "", "INC x;\n"));
        result.put("clear-block", program("clear-block", false, "", "INC dummy;\n;\ndo: {\nDEC x;\n}\nJNZ x do;\n"));
        result.put("clear-do-while", program("clear-do-while", false, "", "INC dummy;\n;\ndo {\nDEC x;\n} while (x != 0);\n"));
        result.put("mandatory-lap", program("mandatory-lap", false, "",
                "do {\nINC rounds;\nDEC n;\n} while (n != 0);\n"));
        result.put("zero-test", program("zero-test", false, "", "JNZ n done;\nINC zero;\ndone:\n"));
        result.put("two-paths", program("two-paths", false, "", "INC result;\nif (n == 0) {\nINC result;\n}\n"));
        result.put("else-practice", program("else-practice", false, "",
                "if (n == 0) {\nINC result;\n} else {\nINC result;\nINC result;\n}\n"));
        result.put("countdown", program("countdown", false, "",
                "if (n != 0) {\ndo {\nDEC n;\nINC count;\n} while (n != 0);\n}\n"));
        result.put("while-practice", program("while-practice", false, "",
                "while (n != 0) {\n  DEC n;\n  INC count;\n}\n"));
        result.put("equals-three", program("equals-three", false, "",
                "while (n != 0) {\nDEC n;\nINC saved;\nINC work;\n}\n"
                + "while (saved != 0) {\nDEC saved;\nINC n;\n}\n"
                + "while (work != 0) {\nif (budget == 0) {\nINC excess;\n} else {\nDEC budget;\n}\nDEC work;\n}\n"
                + "if (budget == 0) {\nif (excess == 0) {\nINC equal;\n}\n}\n"));
        result.put("comparison-practice", program("comparison-practice", false, "", "if (n == 3) {\nINC equal;\n}\n"));
        result.put("preserve-copy", program("preserve-copy", false, "",
                "if (n != 0) {\ndo {\nDEC n;\nINC copy;\nINC saved;\n} while (n != 0);\n}\n"
                + "while (saved != 0) {\nDEC saved;\nINC n;\n}\n"));
        result.put("reset-scratch", program("reset-scratch", false, "",
                "while (scratch != 0) {\nDEC scratch;\n}\nwhile (n != 0) {\nDEC n;\n}\n")
                .replace("var result = 0;", "var result = n;"));
        result.put("three-cells", program("three-cells", false, "",
                "var fromB = b;\nwhile (fromB != 0) {\nINC first;\nDEC fromB;\n}\n"
                + "var fromC = c;\nwhile (fromC != 0) {\nINC second;\nDEC fromC;\n}\n"
                + "var fromA = a;\nwhile (fromA != 0) {\nINC third;\nDEC fromA;\n}\n"));
        result.put("array-practice", program("array-practice", false, "",
                "INC cells[1];\nINC cells[2];\nINC cells[2];\n").replace("var cells[3] = 0;", "var cells[3] = n;"));
        result.put("repeated-work", program("repeated-work", false, "",
                "var fromA = a;\nwhile (fromA != 0) {\nINC left;\nINC left;\nDEC fromA;\n}\n"
                + "var fromB = b;\nwhile (fromB != 0) {\nINC right;\nINC right;\nDEC fromB;\n}\n"));
        result.put("macro-practice", program("macro-practice", false,
                "void addTwo(out) {\nINC out;\nINC out;\n}\n", "addTwo(result);\n")
                .replace("var result = 0;", "var result = n;"));
        result.put("literal-dispatch", program("literal-dispatch", false,
                "void choose(mode, out) {\nif (mode == 0) {\nINC out;\n} else {\nINC out;\nINC out;\n}\n}\n",
                "choose(mode, result);\n"));
        result.put("specialization-practice", program("specialization-practice", false,
                "void choose(0, out) {\nINC out;\n}\nvoid choose(1, out) {\nINC out;\nINC out;\n}\n",
                "choose(0, first);\nchoose(1, second);\n")
                .replace("var first = 0;", "var first = n;").replace("var second = 0;", "var second = n;"));
        Set<String> beforeImports = new LinkedHashSet<>();
        for (GameLevel level : GameCatalog.levels()) {
            if (level.id().equals("compose-helpers")) {
                break;
            }
            beforeImports.add(level.id());
        }
        result.put("compose-helpers", program("compose-helpers", false,
                GameCatalog.library(beforeImports).get("toolbox") + ownRewards("compose-helpers"),
                "copyTo(n, result);\nadvanceThree(result);\n"));
        result.put("import-practice", program("import-practice", true, "", "twiceTo(n, result);\nbump(result);\n"));
        Map<String, String> book = GameBookLevelsTest.solutions();
        for (GameLevel level : GameBookLevels.transferLevels()) result.put(level.id(), book.get(level.id()));
        addRewardSolution(result, "preserving-add", "addTo(a, b, result);\n");
        addRewardSolution(result, "saturating-subtract", "subtractTo(a, b, result);\n");
        addRewardSolution(result, "multiply", "multiplyTo(a, b, result);\n");
        addRewardSolution(result, "divmod", "divideTo(numerator, divisor, quotient, remainder);\n");
        addRewardSolution(result, "gcd", "gcdTo(a, b, result);\n");
        addRewardSolution(result, "normalize-four", "wrap4(n, word);\n");
        addRewardSolution(result, "decompose-four", "bits4(word, bits);\n");
        addRewardSolution(result, "decode-four", "decode4(word, negative, magnitude);\n");
        addRewardSolution(result, "encode-four", "encode4(negative, magnitude, word);\n");
        addRewardSolution(result, "not-four", "not4(word, result);\n");
        addRewardSolution(result, "negate-four", "negate4(word, result);\n");
        addRewardSolution(result, "add-four", "add4(a, b, word, overflow);\n");
        addRewardSolution(result, "subtract-four", "subtract4(a, b, word, overflow);\n");
        addRewardSolution(result, "extend-four", "extend4(word, wide);\n");
        addRewardSolution(result, "transfer-eight", "add8(a, b, word, overflow);\ndecode8(word, negative, magnitude);\n");
        for (GameLevel level : GameBookLevels.bitLevels()) result.put(level.id(), book.get(level.id()));
        return Collections.unmodifiableMap(result);
    }

    private static String program(String id, boolean imported, String definitions, String body) {
        StringBuilder globals = new StringBuilder();
        String starter = level(id).starter();
        for (String line : starter.substring(0, starter.indexOf("begin:")).lines().toList()) {
            if (line.startsWith("var ")) {
                globals.append(line).append('\n');
            }
        }
        return (imported ? "import \"toolbox\";\n" : "") + definitions + globals + "begin:\n" + body + "HALT;\n";
    }

    private static String ownRewards(String id) {
        return String.join("\n", level(id).rewards().values());
    }

    private static void addRewardSolution(Map<String, String> solutions, String id, String body) {
        solutions.put(id, program(id, true, ownRewards(id), body));
    }

    private static GameLevel level(String id) {
        return GameCatalog.levels().stream().filter(level -> level.id().equals(id)).findFirst().orElseThrow();
    }

    private static Set<GameFeature> earnedBefore(String id) {
        Set<GameFeature> features = EnumSet.noneOf(GameFeature.class);
        for (GameLevel level : GameCatalog.levels()) {
            if (level.id().equals(id)) return features;
            features.addAll(level.unlocks());
        }
        throw new AssertionError("unknown level " + id);
    }

    private static boolean hasInputs(String id, Map<String, Integer> inputs) {
        return level(id).cases().stream().anyMatch(test -> test.inputs().equals(inputs));
    }

    private static void assertAlias(String call, String output, int[][] inputs, IntBinaryOperator calculation,
            Map<String, String> library) {
        List<GameLevel.TestCase> cases = new ArrayList<>();
        for (int[] input : inputs) {
            Map<String, Integer> expected = new LinkedHashMap<>(Map.of("a", input[0], "b", input[1]));
            expected.put(output, calculation.applyAsInt(input[0], input[1]));
            cases.add(new GameLevel.TestCase(Map.of("a", input[0], "b", input[1]), expected));
        }
        String source = "import \"toolbox\";\nvar a = 0;\nvar b = 0;\nbegin:\n" + call + "\nHALT;\n";
        GameLevel aliasCheck = new GameLevel("alias-contract", "Tests", "Aliases", "", "", source,
                List.of(), Set.of(), Set.of(), Map.of(), cases, 500_000);
        GameResult result = GameEngine.check(aliasCheck, source, EnumSet.allOf(GameFeature.class), library,
                () -> false, count -> { });
        expect(result.passed(), call + " " + result.summary());
    }

    private static int gcd(int a, int b) {
        while (b != 0) {
            int remainder = a % b;
            a = b;
            b = remainder;
        }
        return a;
    }

    private static void expectUnsupported(Runnable action) {
        try {
            action.run();
            throw new AssertionError("expected an immutable collection");
        } catch (UnsupportedOperationException expected) {
            // Expected.
        }
    }

    private static void check(String name, Check test) {
        try {
            test.run();
            passed++;
            System.out.println("PASS " + name);
        } catch (Exception | AssertionError failure) {
            failed++;
            System.err.println("FAIL " + name + ": " + failure);
            failure.printStackTrace(System.err);
        }
    }

    private static void expect(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}

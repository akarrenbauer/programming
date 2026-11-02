package machine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Book-aligned reference solutions and helper contracts; no JavaFX or disk imports.
 *
 * test_build=$(mktemp -d)
 * javac --release 17 -d "$test_build" -classpath "$test_build" -sourcepath . tests/GameBookLevelsTest.java
 * java -cp "$test_build" machine.GameBookLevelsTest
 */
public final class GameBookLevelsTest {
    private static int passed;
    private static int failed;

    @FunctionalInterface
    private interface Check { void run() throws Exception; }

    public static void main(String[] args) {
        Map<String, String> solutions = solutions();
        check("ten book exercises retain short scaffolds and scalar test inputs", () -> {
            expect(GameBookLevels.transferLevels().size() == 2 && GameBookLevels.bitLevels().size() == 8,
                    "two transfer exercises and eight bit/byte exercises");
            expect(GameCatalog.levels().size() == 46, "all 45 previous levels remain alongside the direct while rewrite");
            List<String> order = GameCatalog.levels().stream().map(GameLevel::id).toList();
            int importIndex = order.indexOf("import-practice");
            expect(order.subList(importIndex + 1, importIndex + 3).equals(List.of("shift-macro", "move-macro")),
                    "transfer exercises follow the import practice");
            expect(order.get(order.indexOf("transfer-eight") + 1).equals("split-lowest-bit"),
                    "bit-cell exercises follow the existing scalar-word journey");
            for (GameLevel level : ownLevels()) {
                expect(level.hints().size() == 3 && level.hints().stream().noneMatch(String::isBlank), "three hints: " + level.id());
                expect(level.requiredFeatures().equals(Set.of(GameFeature.MACROS)), "own macro definition required: " + level.id());
                expect(level.maxSteps() == 500_000, "bounded execution: " + level.id());
                expect(level.cases().size() >= 2, "multiple inputs: " + level.id());
                expect(level.cases().stream().map(GameLevel.TestCase::inputs).distinct().count() == level.cases().size(),
                        "unique cases: " + level.id());
                expect(level.cases().stream().allMatch(test -> test.inputs().keySet().stream().noneMatch(name -> name.contains("["))),
                        "the engine receives scalar input ports: " + level.id());
                expect(level.starter().contains("void ") && level.starter().contains("// Complete this macro"),
                        "macro parameter boilerplate is supplied: " + level.id());
                GameBookLevels.MacroRequirement required = GameBookLevels.requiredMacro(level.id());
                expect(required != null && level.starter().contains("void " + required.name() + "("),
                        "the named macro requirement agrees with the scaffold: " + level.id());
            }
            expect(GameBookLevels.requiredMacro("first-steps") == null, "original levels gain no book-specific macro rule");
            expect(solutions.size() == 10, "all ten reference programs are available");
            expectUnsupported(() -> GameBookLevels.transferLevels().clear());
            expectUnsupported(() -> GameBookLevels.bitLevels().clear());
        });

        Set<GameFeature> available = EnumSet.noneOf(GameFeature.class);
        Set<String> completed = new LinkedHashSet<>();
        for (GameLevel level : GameCatalog.levels()) {
            if (solutions.containsKey(level.id())) {
                check("book reference uses only previously earned helpers: " + level.id(), () -> {
                    Map<String, String> library = GameCatalog.library(completed);
                    GameResult result = GameEngine.check(level, solutions.get(level.id()), available, library,
                            () -> false, count -> { });
                    expect(result.passed(), result.summary());
                    expect(result.cases().size() == level.cases().size(), "every case must execute");
                    expect(result.cases().stream().allMatch(test -> test.steps() < level.maxSteps()), "reference stays within its budget");
                    GameResult starter = GameEngine.check(level, level.starter(), available, library,
                            () -> false, count -> { });
                    expect(!starter.passed(), "unfinished macro scaffold must not pass");
                });
            }
            completed.add(level.id());
            available.addAll(level.unlocks());
        }
        Map<String, String> library = GameCatalog.library(completed);

        check("named macro exercises reject a different name arity or an unused definition", () -> {
            GameLevel challenge = level("flip-bit");
            GameLevel behaviorOnly = new GameLevel("flip-behavior", challenge.world(), challenge.title(),
                    challenge.goal(), challenge.lesson(), challenge.starter(), challenge.hints(), Set.of(), Set.of(),
                    Map.of(), challenge.cases(), challenge.maxSteps());
            String body = "if (b == 0) {\nINC b;\n} else {\nDEC b;\n}\n";
            List<String> wrong = List.of(
                    "void unused(b) {\n" + body + "}\nvar b = 0;\nbegin:\nunused(b);\nHALT;\n",
                    "void flip(b, other) {\n" + body + "}\nvar b = 0;\nvar spare = 0;\nbegin:\nflip(b, spare);\nHALT;\n",
                    "void flip(b) {\n" + body + "}\nvar b = 0;\nbegin:\n" + body + "HALT;\n");
            for (String source : wrong) {
                GameResult baseline = GameEngine.check(behaviorOnly, source, EnumSet.allOf(GameFeature.class), Map.of(),
                        () -> false, count -> { });
                expect(baseline.passed(), "the counterexample must compute the right outputs: " + baseline.summary());
                GameResult rejected = GameEngine.check(challenge, source, EnumSet.allOf(GameFeature.class), Map.of(),
                        () -> false, count -> { });
                expect(!rejected.passed(), "a correct output cannot replace the requested macro definition and call");
            }
        });

        check("full-adder cases cover every input and carry combination", () -> {
            GameLevel fullAdder = level("full-adder");
            expect(fullAdder.cases().size() == 8, "all eight full-adder truth rows are present");
            for (int a = 0; a <= 1; a++) {
                for (int b = 0; b <= 1; b++) {
                    for (int u = 0; u <= 1; u++) {
                        Map<String, Integer> inputs = values("a", a, "b", b, "u", u);
                        GameLevel.TestCase row = fullAdder.cases().stream().filter(test -> test.inputs().equals(inputs)).findFirst().orElseThrow();
                        expect(row.expected().equals(values("a", a, "b", b, "u", (a + b + u) / 2, "c", (a + b + u) % 2)),
                                "truth row must retain a,b and split the sum: " + inputs);
                    }
                }
            }
            assertHelper("full-adder-table", globalDeclarations(fullAdder), "add(a, b, u, c);\n", fullAdder.cases(), library);
        });

        check("binary decomposition and reconstruction round-trip across byte boundaries", () -> {
            List<GameLevel.TestCase> cases = new ArrayList<>();
            for (int value : new int[] {0, 1, 127, 128, 255, 256, 511, 1024}) {
                Map<String, Integer> expected = values("a", value, "result", value % 256);
                expected.putAll(bits("b", value));
                cases.add(new GameLevel.TestCase(values("a", value), expected));
            }
            String globals = "var a = 0;\nvar result = 0;\n" + declarations(names("b", 8));
            String body = call("lowestByte", concat(List.of("a"), names("b", 8))) + "\n"
                    + call("valueOfByte", concat(names("b", 8), List.of("result"))) + "\n";
            assertHelper("binary-round-trip", globals, body, cases, library);
        });

        check("byte addition snapshots inputs before overlapping result cells", () -> assertOverlappingBytes("add", false, library));
        check("byte subtraction snapshots inputs before overlapping result cells", () -> assertOverlappingBytes("sub", true, library));

        check("literal shift specializations preserve an occupied destination", () -> {
            List<GameLevel.TestCase> cases = List.of(
                    new GameLevel.TestCase(values("b", 0), values("b", 3)),
                    new GameLevel.TestCase(values("b", 7), values("b", 10)));
            assertHelper("literal-shift", "var b = 0;\n", "shift(0, b);\nshift(1, b);\nshift(2, b);\n", cases, library);
        });

        check("the eight-bit flip overload complements all eight cells", () -> {
            List<GameLevel.TestCase> cases = new ArrayList<>();
            for (int value : new int[] {0, 85, 127, 128, 255}) {
                cases.add(new GameLevel.TestCase(bits("b", value), bits("b", 255 - value)));
            }
            assertHelper("flip-eight", declarations(names("b", 8)), call("flip", names("b", 8)) + "\n", cases, library);
        });

        check("the cumulative toolbox remains within macro-count and literal limits", () -> {
            String source = library.get("toolbox");
            long macroCount = Pattern.compile("(?m)^void\\s").matcher(source).results().count();
            expect(macroCount < 64, "the earned library must fit the checker macro limit: " + macroCount);
            for (GameLevel level : ownLevels()) {
                for (String reward : level.rewards().values()) {
                    expect(reward.startsWith("// "), "each helper has a visible contract");
                    Pattern.compile("\\b[0-9]+\\b").matcher(reward).results().forEach(number ->
                            expect(Integer.parseInt(number.group()) <= 1024, "helper literal exceeds the curriculum bound"));
                }
            }
        });

        System.out.println("GameBookLevelsTest: " + passed + " passed, " + failed + " failed");
        if (failed != 0) throw new AssertionError(failed + " book curriculum checks failed");
    }

    /** Full solutions remain test fixtures; the UI receives only scaffolds and earned helpers. */
    public static Map<String, String> solutions() {
        Map<String, String> solutions = new LinkedHashMap<>();
        for (GameLevel level : ownLevels()) {
            String definitions = String.join("\n", level.rewards().values());
            String body = level.starter().substring(level.starter().indexOf("begin:") + "begin:".length());
            solutions.put(level.id(), "import \"toolbox\";\n" + definitions + globalDeclarations(level) + "begin:" + body);
        }
        return Collections.unmodifiableMap(solutions);
    }

    private static List<GameLevel> ownLevels() {
        List<GameLevel> levels = new ArrayList<>(GameBookLevels.transferLevels());
        levels.addAll(GameBookLevels.bitLevels());
        return levels;
    }

    private static GameLevel level(String id) {
        return ownLevels().stream().filter(level -> level.id().equals(id)).findFirst().orElseThrow();
    }

    private static String globalDeclarations(GameLevel level) {
        String beforeMain = level.starter().substring(0, level.starter().indexOf("begin:"));
        return String.join("\n", beforeMain.lines().filter(line -> line.startsWith("var ")).toList()) + "\n";
    }

    private static void assertOverlappingBytes(String name, boolean subtract, Map<String, String> library) {
        List<String> a = names("a", 8);
        List<String> b = names("b", 8);
        List<String> reversedMixed = List.of("b7", "a6", "b5", "a4", "b3", "a2", "b1", "a0");
        for (List<String> outputs : List.of(a, b, reversedMixed)) {
            List<GameLevel.TestCase> cases = new ArrayList<>();
            for (int[] pair : new int[][] {{0, 0}, {0, 1}, {1, 255}, {127, 1}, {128, 128}, {128, 1}, {255, 255}, {170, 85}}) {
                Map<String, Integer> inputs = bits("a", pair[0]);
                inputs.putAll(bits("b", pair[1]));
                Map<String, Integer> expected = new LinkedHashMap<>(inputs);
                int word = Math.floorMod(pair[0] + (subtract ? -pair[1] : pair[1]), 256);
                for (int bit = 0; bit < 8; bit++) expected.put(outputs.get(bit), (word >>> bit) & 1);
                cases.add(new GameLevel.TestCase(inputs, expected));
            }
            assertHelper(name + "-overlap-" + outputs.get(0), declarations(concat(a, b)),
                    call(name, concat(a, b, outputs)) + "\n", cases, library);
        }
    }

    private static void assertHelper(String id, String globals, String body, List<GameLevel.TestCase> cases,
            Map<String, String> library) {
        String source = "import \"macros.a26\";\n" + globals + "begin:\n" + body + "HALT;\n";
        GameLevel probe = new GameLevel(id, "Tests", id, "", "", source, List.of(), Set.of(), Set.of(), Map.of(), cases, 500_000);
        GameResult result = GameEngine.check(probe, source, EnumSet.allOf(GameFeature.class), library,
                () -> false, count -> { });
        expect(result.passed(), id + ": " + result.summary());
        expect(result.cases().size() == cases.size(), "all helper cases execute: " + id);
    }

    private static Map<String, Integer> values(Object... entries) {
        Map<String, Integer> values = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) values.put((String) entries[index], (Integer) entries[index + 1]);
        return values;
    }

    private static Map<String, Integer> bits(String prefix, int word) {
        Map<String, Integer> bits = new LinkedHashMap<>();
        for (int index = 0; index < 8; index++) bits.put(prefix + index, (word >>> index) & 1);
        return bits;
    }

    private static List<String> names(String prefix, int length) {
        List<String> names = new ArrayList<>();
        for (int index = 0; index < length; index++) names.add(prefix + index);
        return names;
    }

    @SafeVarargs
    private static List<String> concat(List<String>... lists) {
        List<String> combined = new ArrayList<>();
        for (List<String> list : lists) combined.addAll(list);
        return combined;
    }

    private static String declarations(List<String> names) {
        StringBuilder source = new StringBuilder();
        names.forEach(name -> source.append("var ").append(name).append(" = 0;\n"));
        return source.toString();
    }

    private static String call(String name, List<String> arguments) {
        return name + "(" + String.join(", ", arguments) + ");";
    }

    private static void expectUnsupported(Runnable action) {
        try {
            action.run();
            throw new AssertionError("expected an immutable collection");
        } catch (UnsupportedOperationException expected) { }
    }

    private static void check(String name, Check check) {
        try {
            check.run();
            passed++;
            System.out.println("PASS " + name);
        } catch (Exception | AssertionError failure) {
            failed++;
            System.err.println("FAIL " + name + ": " + failure);
            failure.printStackTrace(System.err);
        }
    }

    private static void expect(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

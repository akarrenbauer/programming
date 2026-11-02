package machine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** World transitions, source instruction policy, and persistence without JavaFX. */
public final class GameWorldTest {
    private static final Set<GameFeature> ALL = EnumSet.allOf(GameFeature.class);
    private static final String GLOBALS = "var input=0; var out=0; begin:\n";
    private static int passed;
    private static int failed;
    private interface Test { void run() throws Exception; }

    public static void main(String[] args) {
        test("world boundary follows the final control-flow unlock and every world is contiguous", () -> {
            Set<GameFeature> earned = EnumSet.noneOf(GameFeature.class);
            Set<GameWorld> seen = new LinkedHashSet<>();
            GameWorld previous = null;
            for (GameLevel level : GameCatalog.levels()) {
                GameWorld world = GameWorld.forLevel(level);
                expect(world != null, "catalog world is known: " + level.id());
                if (world != previous) {
                    expect(seen.add(world), "world must not be split into separate groups");
                    if (world == GameWorld.STRUCTURED_PROGRAMMING) {
                        expect(level.id().equals("while-practice"), "enter by rewriting the guarded do/while as while");
                        expect(GameWorld.hasStructuredControls(earned), "all four controls earned on entry");
                    }
                }
                expect(GameWorld.usesDiagrams(level) == GameWorld.hasStructuredControls(earned),
                        "Nassi–Shneiderman diagrams start immediately after all control structures: " + level.id());
                expect(GameWorld.usesFlowcharts(level) == (world == GameWorld.MACHINE_FOUNDATIONS),
                        "foundations use flowcharts, later worlds use structured diagrams: " + level.id());
                earned.addAll(level.unlocks());
                previous = world;
            }
            expect(List.copyOf(seen).equals(List.of(GameWorld.values())), "all six worlds in enum order");
        });

        test("progress unlocks the structured world after countdown and retains drafts on reload", () -> {
            Path directory = Files.createTempDirectory("game-world-progress");
            Path path = directory.resolve("progress.properties");
            try {
                List<GameLevel> levels = GameCatalog.levels();
                GameProgress progress = new GameProgress(path, levels);
                for (int index = 0; index < 8; index++) {
                    expect(!progress.isAvailable(8), "structured world is locked before countdown");
                    progress.complete(levels.get(index), "// saved " + levels.get(index).id());
                }
                expect(progress.isAvailable(8), "countdown opens structured world");
                progress.setSelectedIndex(8);
                progress.setDraft(levels.get(8).id(), "// structured draft");
                progress.save();
                GameProgress loaded = new GameProgress(path, levels);
                expect(loaded.selectedIndex() == 8 && loaded.isAvailable(8), "world selection restored");
                expect(loaded.draft(levels.get(8)).equals("// structured draft"), "draft restored");
                expect(loaded.isAvailable(4), "foundations remain available for replay");
                expect(!GameWorld.usesDiagrams(levels.get(4)), "replay retains foundational policy");
            } finally {
                Files.deleteIfExists(path);
                Files.deleteIfExists(directory);
            }
        });

        test("checker and debugger reject explicit jumps including unused macro bodies and imports", () -> {
            GameLevel structured = probe(GameWorld.STRUCTURED_PROGRAMMING);
            List<String> sources = List.of(
                    GLOBALS + "again: JNZ input again; HALT;",
                    GLOBALS + "HALT; again: JNZ input again;",
                    "void unused(x) {\nagain: JNZ x again;\n}\n" + GLOBALS + "HALT;",
                    "import \"toolbox\";\n" + GLOBALS + "HALT;");
            Map<String, String> libraries = Map.of("toolbox", "void unused(x) {\nagain: JNZ x again;\n}\n");
            for (String source : sources) {
                GameResult result = check(structured, source, ALL, libraries);
                expect(!result.passed() && result.summary().contains("JNZ is suspended"), result.summary());
                expect(result.location() != null, "jump is located for source highlighting");
                try {
                    GameDebugSession.prepare(structured, source, ALL, libraries, 0, () -> false);
                    throw new AssertionError("debugger accepted an explicit jump");
                } catch (GameDebugSession.PreparationException expected) {
                    expect(expected.getMessage().contains("JNZ is suspended"), expected.getMessage());
                }
            }
        });

        test("all subsequent worlds suspend explicit jumps and require the complete control vocabulary", () -> {
            for (GameWorld world : GameWorld.values()) {
                if (!world.structured()) continue;
                GameLevel level = probe(world);
                expect(!check(level, GLOBALS + "again: JNZ input again; HALT;", ALL, Map.of()).passed(), world.title());
                for (GameFeature missing : world.requiredFeatures()) {
                    Set<GameFeature> available = EnumSet.copyOf(ALL);
                    available.remove(missing);
                    GameResult result = check(level, GLOBALS + "HALT;", available, Map.of());
                    expect(!result.passed() && result.summary().contains("before entering"), "missing " + missing);
                    try {
                        GameDebugSession.prepare(level, GLOBALS + "HALT;", available, Map.of(), 0, () -> false);
                        throw new AssertionError("debugger entered world before " + missing);
                    } catch (GameDebugSession.PreparationException expected) {
                        expect(expected.getMessage().contains("before entering"), expected.getMessage());
                    }
                }
            }
        });

        test("structured commands still compile to working internal jumps and comments do not trigger the policy", () -> {
            String source = GLOBALS + "// JNZ input again;\n"
                    + "while (input != 0) { DEC input; }\n"
                    + "if (input == 0) { NOP; } else { INC out; }\n"
                    + "do { NOP; } while (input != 0);\nHALT;";
            GameLevel level = probe(GameWorld.STRUCTURED_PROGRAMMING);
            GameResult result = check(level, source, ALL, Map.of());
            expect(result.passed(), result.summary());
            GameDebugSession debug = GameDebugSession.prepare(level, source, ALL, Map.of(), 1, () -> false);
            expect(debug.getProgram().sourceMap().keySet().stream().anyMatch(address -> debug.getProgram().bytecode().get(address) == 4),
                    "compiled control flow retains machine JNZ instructions");
            while (!debug.isStopped()) debug.step();
            expect(debug.getOutputValues().equals(Map.of("out", 0)), "structured debug execution works");
        });

        test("foundations can still execute explicit JNZ after later tools are unlocked", () -> {
            String source = GLOBALS + "again: DEC input; JNZ input again; HALT;";
            GameLevel foundation = probe(GameWorld.MACHINE_FOUNDATIONS);
            expect(check(foundation, source, ALL, Map.of()).passed(), "foundation jump is allowed");
            GameDebugSession session = GameDebugSession.prepare(foundation, source, ALL, Map.of(), 1, () -> false);
            while (!session.isStopped()) session.step();
            expect(session.getStatus() == GameDebugSession.Status.HALTED, "foundation jump debugs normally");
        });

        test("world grammar removes the jump command while preserving structured syntax and replay grammar", () -> {
            GameGrammar.Model model = GameGrammar.describe(ALL, Set.of(GameFeature.WHILE), false);
            var commands = model.productions().stream().filter(rule -> rule.name().equals("Command")).findFirst().orElseThrow();
            expect(commands.alternatives().stream().noneMatch(alternative -> alternative.id().equals("jump")), "no explicit jump production");
            String structured = GameGrammar.render(ALL, false);
            expect(structured.contains("while ( Condition ) Block") && structured.contains("do Block while")
                    && structured.contains("[ else Block ]"), "earned control structures remain available");
            expect(structured.contains("JNZ is suspended"), "grammar explains selected world policy");
            expect(GameGrammar.render(ALL, true).contains("JNZ Variable Label"), "foundations grammar keeps jumps");
        });

        System.out.println("GameWorldTest: " + passed + " passed, " + failed + " failed");
        if (failed != 0) throw new AssertionError(failed + " world checks failed");
    }

    private static GameLevel probe(GameWorld world) {
        return new GameLevel("world-probe", world.title(), "Probe", "", "", GLOBALS + "HALT;", List.of(),
                Set.of(), Set.of(), Map.of(), List.of(
                    new GameLevel.TestCase(Map.of("input", 0), Map.of("out", 0)),
                    new GameLevel.TestCase(Map.of("input", 3), Map.of("out", 0))), 1000);
    }

    private static GameResult check(GameLevel level, String source, Set<GameFeature> available, Map<String, String> libraries) {
        return GameEngine.check(level, source, available, libraries, () -> false, count -> {});
    }

    private static void expect(boolean value, String message) { if (!value) throw new AssertionError(message); }

    private static void test(String name, Test action) {
        try { action.run(); passed++; System.out.println("PASS " + name); }
        catch (Exception | AssertionError error) { failed++; System.err.println("FAIL " + name); error.printStackTrace(); }
    }
}

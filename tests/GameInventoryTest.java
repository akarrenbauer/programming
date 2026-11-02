import machine.GameFeature;
import machine.GameLevel;
import machine.GameProgress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Unread rewards survive saving; acknowledging them never changes earned tools. */
public final class GameInventoryTest {
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("game-inventory-");
        Path file = directory.resolve("progress.properties");
        List<GameLevel> levels = List.of(
                level("blocks", Set.of(GameFeature.BLOCKS), Map.of()),
                level("loops", Set.of(GameFeature.DO_WHILE), Map.of("clear", "void clear(x) {}")),
                level("practice", Set.of(), Map.of()));
        GameProgress progress = new GameProgress(file, levels);
        expect(progress.unreadGrammarFeatures().isEmpty() && !progress.hasUnreadToolbox(), "new profiles have no unread rewards");
        progress.complete(levels.get(0), "first");
        progress.complete(levels.get(1), "second");
        progress.save();
        progress = new GameProgress(file, levels);
        expect(progress.unreadGrammarFeatures().equals(Set.of(GameFeature.BLOCKS, GameFeature.DO_WHILE))
                && progress.hasUnreadToolbox(), "unread additions accumulate across unlocks and survive reload");
        progress.markGrammarRead();
        expect(progress.unreadGrammarFeatures().isEmpty() && progress.hasUnreadToolbox()
                && progress.features().size() == 2, "reading grammar leaves tools and toolbox notice intact");
        progress.markToolboxRead();
        progress.complete(levels.get(1), "revisited");
        progress.complete(levels.get(2), "practice");
        progress.save();
        progress = new GameProgress(file, levels);
        expect(progress.unreadGrammarFeatures().isEmpty() && !progress.hasUnreadToolbox(),
                "revisiting or completing a practice level does not resurrect notices");
        progress.reset();
        progress.complete(levels.get(0), "again");
        expect(progress.unreadGrammarFeatures().equals(Set.of(GameFeature.BLOCKS)), "tools become new again after reset");
        Path blocked = directory.resolve("blocked");
        GameProgress rollback = new GameProgress(blocked, levels);
        rollback.complete(levels.get(0), "first");
        rollback.complete(levels.get(1), "second");
        Files.createDirectory(blocked);
        Files.writeString(blocked.resolve("keep"), "keep");
        try {
            rollback.reset();
            throw new AssertionError("reset should fail to replace a nonempty directory");
        } catch (java.io.IOException expected) { }
        expect(rollback.unreadGrammarFeatures().size() == 2 && rollback.hasUnreadToolbox()
                && rollback.completedCount() == 2, "failed reset restores unread notices with completions");
        Files.writeString(file, "version=2\ncompleted.blocks=true\nunread.grammar.WHILE=true\nunread.toolbox=true\n");
        GameProgress legacy = new GameProgress(file, levels);
        expect(legacy.features().equals(Set.of(GameFeature.BLOCKS)) && legacy.unreadGrammarFeatures().isEmpty()
                && !legacy.hasUnreadToolbox(), "old saves and forged notices cannot create rewards or unread locked syntax");
        progress.reset();
        GameProgress reset = new GameProgress(file, levels);
        expect(reset.completedCount() == 0 && reset.unreadGrammarFeatures().isEmpty() && !reset.hasUnreadToolbox(),
                "saved reset clears notices");
        System.out.println("GameInventoryTest: 8 passed, 0 failed");
    }

    private static GameLevel level(String id, Set<GameFeature> unlocks, Map<String, String> rewards) {
        return new GameLevel(id, "Test", id, "", "", "HALT;", List.of(), unlocks, Set.of(), rewards, List.of(new GameLevel.TestCase(Map.of(), Map.of())), 100);
    }

    private static void expect(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

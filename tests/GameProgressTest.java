import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import machine.GameFeature;
import machine.GameCatalog;
import machine.GameLevel;
import machine.GameProgress;
import machine.GameTaskLanguage;

/** Standalone progress checks; every file and directory belongs to a temporary fixture. */
public final class GameProgressTest {
    private static final List<GameLevel> LEVELS = List.of(
            level("first", Set.of(GameFeature.BLOCKS)),
            level("second", Set.of(GameFeature.WHILE)),
            level("third", Set.of(GameFeature.IF, GameFeature.ELSE)),
            level("fourth", Set.of(GameFeature.IMPORTS)));
    // Historical fixtures stay fixed when new lessons are added to the live catalog.
    private static final List<String> ORIGINAL_VERSION_ONE_IDS = List.of(
            "first-steps", "countdown", "zero-test", "two-paths", "equals-three", "mandatory-lap",
            "preserve-copy", "reset-scratch", "three-cells", "repeated-work", "literal-dispatch",
            "compose-helpers", "array-practice", "macro-practice", "specialization-practice", "import-practice",
            "preserving-add", "saturating-subtract", "multiply", "divmod", "gcd", "normalize-four",
            "decompose-four", "decode-four", "encode-four", "not-four", "negate-four", "add-four",
            "subtract-four", "extend-four", "transfer-eight");
    private static final List<String> ORIGINAL_VERSION_TWO_IDS = List.of(
            "first-steps", "clear-block", "zero-test", "two-paths", "countdown", "equals-three", "mandatory-lap",
            "preserve-copy", "reset-scratch", "three-cells", "repeated-work", "literal-dispatch",
            "compose-helpers", "array-practice", "macro-practice", "specialization-practice", "import-practice",
            "preserving-add", "saturating-subtract", "multiply", "divmod", "gcd", "normalize-four",
            "decompose-four", "decode-four", "encode-four", "not-four", "negate-four", "add-four",
            "subtract-four", "extend-four", "transfer-eight");
    private static final List<String> ORIGINAL_FORTY_TWO_IDS = List.of(
            "first-steps", "clear-block", "zero-test", "two-paths", "countdown", "equals-three", "mandatory-lap",
            "preserve-copy", "reset-scratch", "three-cells", "repeated-work", "literal-dispatch",
            "compose-helpers", "array-practice", "macro-practice", "specialization-practice", "import-practice",
            "shift-macro", "move-macro", "preserving-add", "saturating-subtract", "multiply", "divmod", "gcd",
            "normalize-four", "decompose-four", "decode-four", "encode-four", "not-four", "negate-four", "add-four",
            "subtract-four", "extend-four", "transfer-eight", "split-lowest-bit", "lowest-byte", "value-of-byte",
            "full-adder", "byte-add", "flip-bit", "negate-byte", "subtract-byte");
    private static final List<String> ORIGINAL_FORTY_THREE_IDS = List.of(
            "first-steps", "clear-block", "clear-do-while", "mandatory-lap", "zero-test", "two-paths", "countdown",
            "equals-three", "preserve-copy", "reset-scratch", "three-cells", "repeated-work", "literal-dispatch",
            "compose-helpers", "array-practice", "macro-practice", "specialization-practice", "import-practice",
            "shift-macro", "move-macro", "preserving-add", "saturating-subtract", "multiply", "divmod", "gcd",
            "normalize-four", "decompose-four", "decode-four", "encode-four", "not-four", "negate-four", "add-four",
            "subtract-four", "extend-four", "transfer-eight", "split-lowest-bit", "lowest-byte", "value-of-byte",
            "full-adder", "byte-add", "flip-bit", "negate-byte", "subtract-byte");
    private static final List<String> ORIGINAL_FORTY_FIVE_IDS = List.of(
            "first-steps", "clear-block", "clear-do-while", "mandatory-lap", "zero-test", "two-paths",
            "else-practice", "countdown", "equals-three", "comparison-practice", "preserve-copy", "reset-scratch",
            "three-cells", "array-practice", "repeated-work", "macro-practice", "literal-dispatch", "specialization-practice",
            "compose-helpers", "import-practice", "shift-macro", "move-macro", "preserving-add", "saturating-subtract",
            "multiply", "divmod", "gcd", "normalize-four", "decompose-four", "decode-four", "encode-four", "not-four",
            "negate-four", "add-four", "subtract-four", "extend-four", "transfer-eight", "split-lowest-bit", "lowest-byte",
            "value-of-byte", "full-adder", "byte-add", "flip-bit", "negate-byte", "subtract-byte");
    private static int passed;
    private static int failed;

    @FunctionalInterface
    private interface Check { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("language-game-progress-test-");

        check("new progress is locked sequentially and does not create files", () -> {
            Path path = directory.resolve("new/subdirectory/progress.properties");
            GameProgress progress = new GameProgress(path, LEVELS);
            expect(progress.completedCount() == 0 && progress.completedIds().isEmpty(), "new game has no completions");
            expect(progress.features().isEmpty() && progress.selectedIndex() == 0, "new game starts with base grammar and first selection");
            expect(progress.isAvailable(0) && !progress.isAvailable(1) && !progress.isAvailable(-1)
                    && !progress.isAvailable(LEVELS.size()), "only first level is available");
            expect(progress.draft(LEVELS.get(0)).equals(LEVELS.get(0).starter()), "new draft uses its starter");
            expect(!Files.exists(path.getParent()), "loading absent progress does not create directories");
        });

        check("new task language follows display locale rather than formatting locale", () -> {
            withDisplayLocale(Locale.GERMANY, () -> {
                Path path = directory.resolve("locale-german/progress.properties");
                GameProgress progress = new GameProgress(path, LEVELS);
                expect(progress.taskLanguage() == GameTaskLanguage.GERMAN, "German display locale selects German");
                expect(!Files.exists(path.getParent()), "locale selection does not create a saved game");
            });
            withDisplayLocale(Locale.FRANCE, () -> {
                GameProgress progress = new GameProgress(directory.resolve("locale-fallback.properties"), LEVELS);
                expect(progress.taskLanguage() == GameTaskLanguage.ENGLISH, "unsupported display locale falls back to English");
            });
        });

        check("old progress and invalid optional language preferences retain progress and use the locale", () -> {
            for (String version : List.of("1", "2")) {
                for (String code : List.of("missing", "", "fr", "de-DE", "unknown")) {
                    Path path = directory.resolve("language-v" + version + "-" + code + ".properties");
                    Properties properties = validProperties();
                    properties.setProperty("version", version);
                    properties.setProperty("completed.first", "true");
                    properties.setProperty("selected", "second");
                    properties.setProperty("draft.second", "// Grüße 🌱\nHALT;\n");
                    if (!code.equals("missing")) properties.setProperty("task.language", code);
                    write(path, properties);
                    for (Locale locale : List.of(Locale.GERMANY, Locale.US)) {
                        withDisplayLocale(locale, () -> {
                            GameProgress progress = new GameProgress(path, LEVELS);
                            expect(progress.taskLanguage() == GameTaskLanguage.fromLocale(locale),
                                    "missing or unknown language falls back without rejecting v" + version + " save");
                            expect(progress.completedIds().equals(Set.of("first")) && progress.selectedIndex() == 1
                                            && progress.draft(LEVELS.get(1)).equals("// Grüße 🌱\nHALT;\n"),
                                    "optional language preference cannot discard completions, selection, or drafts");
                            expect(read(path).equals(properties), "loading optional preference does not rewrite the save");
                        });
                    }
                }
            }
        });

        check("explicit task language is saved independently of the next launch locale", () -> {
            Path path = directory.resolve("language-preference.properties");
            withDisplayLocale(Locale.GERMANY, () -> {
                GameProgress progress = new GameProgress(path, LEVELS);
                progress.complete(LEVELS.get(0), "// canonical source\nHALT;\n");
                progress.setSelectedIndex(1);
                progress.setDraft("second", "// draft remains unchanged\n");
                progress.setTaskLanguage(GameTaskLanguage.ENGLISH);
                expect(progress.taskLanguage() == GameTaskLanguage.ENGLISH && !Files.exists(path),
                        "language changes in memory until explicitly saved");
                progress.save();
                expect(read(path).getProperty("task.language").equals("en"), "stable English code is persisted");
                expect(new GameProgress(path, LEVELS).taskLanguage() == GameTaskLanguage.ENGLISH,
                        "saved English overrides German locale");
                progress.setTaskLanguage(GameTaskLanguage.GERMAN);
                expect(read(path).getProperty("task.language").equals("en"), "changing language does not write implicitly");
                progress.save();
                expect(read(path).getProperty("task.language").equals("de"), "stable German code is persisted");
            });
            withDisplayLocale(Locale.US, () -> {
                GameProgress restored = new GameProgress(path, LEVELS);
                expect(restored.taskLanguage() == GameTaskLanguage.GERMAN, "saved German overrides English locale after restart");
                expect(restored.completedIds().equals(Set.of("first")) && restored.selectedIndex() == 1
                                && restored.draft(LEVELS.get(0)).equals("// canonical source\nHALT;\n")
                                && restored.draft(LEVELS.get(1)).equals("// draft remains unchanged\n"),
                        "language preference does not change progress or source drafts");
            });
        });

        check("whole-game reset retains the chosen task language on disk and after restart", () -> {
            for (GameTaskLanguage language : GameTaskLanguage.values()) {
                Locale opposite = language == GameTaskLanguage.GERMAN ? Locale.US : Locale.GERMANY;
                withDisplayLocale(opposite, () -> {
                    Path path = directory.resolve("language-reset-" + language.code() + ".properties");
                    GameProgress progress = new GameProgress(path, LEVELS);
                    progress.setTaskLanguage(language);
                    progress.complete(LEVELS.get(0), "completed answer");
                    progress.setDraft("third", "unfinished draft");
                    progress.setSelectedIndex(1);
                    progress.save();
                    progress.reset();
                    expectPristine(progress);
                    expect(progress.taskLanguage() == language, "reset preserves explicit language in memory");
                    expect(read(path).getProperty("task.language").equals(language.code()), "reset keeps preference in saved file");
                    GameProgress restored = new GameProgress(path, LEVELS);
                    expectPristine(restored);
                    expect(restored.taskLanguage() == language, "reset preference survives restart under the opposite locale");
                });
            }
        });

        check("completion cannot skip levels or create unknown progress", () -> {
            GameProgress progress = new GameProgress(directory.resolve("locks.properties"), LEVELS);
            expectIllegal(() -> progress.complete(LEVELS.get(1), "skipped"));
            expectIllegal(() -> progress.setSelectedIndex(1));
            expectIllegal(() -> progress.setDraft("future", "unknown"));
            expectIllegal(() -> progress.complete(level("future", Set.of(GameFeature.IMPORTS)), "unknown"));
            expect(progress.completedCount() == 0 && !progress.isCompleted("future"), "rejected actions preserve progress");
            progress.complete(LEVELS.get(0), "first answer");
            expect(progress.completedCount() == 1 && progress.isAvailable(0) && progress.isAvailable(1)
                    && !progress.isAvailable(2), "completion opens exactly the next level");
            progress.complete(LEVELS.get(0), "improved answer");
            expect(progress.completedCount() == 1 && progress.draft(LEVELS.get(0)).equals("improved answer"), "rechecking a completed lesson updates its draft without extra credit");
        });

        check("drafts preserve empty Unicode and multiline text independently", () -> {
            Path path = directory.resolve("drafts/progress.properties");
            GameProgress progress = new GameProgress(path, LEVELS);
            String unicode = "// Grüße 🌱\nvar résultat = 1;\n\tINC result;\n\\literal = : # !\n";
            progress.setDraft("first", unicode);
            progress.setDraft("second", "");
            progress.setDraft("fourth", "// still locked\n");
            progress.save();
            GameProgress restored = new GameProgress(path, LEVELS);
            expect(unicode.equals(restored.draft(LEVELS.get(0))), "Unicode newlines tabs and property delimiters round-trip");
            expect(restored.draft(LEVELS.get(1)).isEmpty(), "empty saved draft does not fall back to starter");
            expect(restored.draft(LEVELS.get(2)).equals(LEVELS.get(2).starter()), "untouched draft still uses starter");
            expect(restored.draft(LEVELS.get(3)).equals("// still locked\n") && !restored.isAvailable(3), "storing a locked draft does not unlock its level");
            expect(Files.readString(path, StandardCharsets.UTF_8).contains("Grüße"), "saved file is UTF-8 text");
        });

        check("partial completions selected lesson and earned features round-trip", () -> {
            Path path = directory.resolve("partial.properties");
            GameProgress progress = new GameProgress(path, LEVELS);
            progress.complete(LEVELS.get(0), "first answer");
            progress.complete(LEVELS.get(1), "second answer");
            progress.setSelectedIndex(1);
            progress.save();
            GameProgress restored = new GameProgress(path, LEVELS);
            expect(restored.completedCount() == 2 && restored.completedIds().equals(Set.of("first", "second")), "partial completions persist");
            expect(restored.selectedIndex() == 1 && restored.isAvailable(2) && !restored.isAvailable(3), "selection and next-level boundary persist");
            expect(restored.features().equals(Set.of(GameFeature.BLOCKS, GameFeature.WHILE)), "only completed catalog rewards unlock syntax");
            expect(restored.draft(LEVELS.get(1)).equals("second answer"), "checked solution persists with completion");
        });

        check("completion sets cannot be modified by callers", () -> {
            GameProgress progress = new GameProgress(directory.resolve("immutable.properties"), LEVELS);
            progress.complete(LEVELS.get(0), "answer");
            Set<String> completed = progress.completedIds();
            Set<GameFeature> features = progress.features();
            expectUnsupported(() -> completed.add("second"));
            expectUnsupported(() -> features.add(GameFeature.IMPORTS));
            progress.complete(LEVELS.get(1), "answer two");
            expect(completed.equals(Set.of("first")) && features.equals(Set.of(GameFeature.BLOCKS)), "returned snapshots remain independent of later progress");
        });

        check("unknown serialized features and noncontiguous completions cannot unlock levels", () -> {
            Path path = directory.resolve("untrusted.properties");
            Properties properties = validProperties();
            properties.setProperty("completed.first", "true");
            properties.setProperty("completed.third", "true");
            properties.setProperty("completed.future-level", "true");
            properties.setProperty("features", "IMPORTS,MACROS,ARRAYS");
            properties.setProperty("selected", "third");
            properties.setProperty("draft.future-level", "future source");
            write(path, properties);
            GameProgress restored = new GameProgress(path, LEVELS);
            expect(restored.completedCount() == 1 && !restored.isCompleted("third") && !restored.isCompleted("future-level"), "only contiguous known completions are honored");
            expect(restored.features().equals(Set.of(GameFeature.BLOCKS)), "serialized feature flags have no authority");
            expect(restored.selectedIndex() == 1 && !restored.isAvailable(2), "invalid saved selection falls back to first unfinished level");
        });

        check("catalog rewards determine restored features", () -> {
            Path path = directory.resolve("catalog.properties");
            GameProgress progress = new GameProgress(path, LEVELS);
            progress.complete(LEVELS.get(0), "answer");
            progress.save();
            List<GameLevel> changedCatalog = List.of(level("first", Set.of(GameFeature.EMPTY_STATEMENTS)),
                    LEVELS.get(1), LEVELS.get(2), LEVELS.get(3));
            GameProgress restored = new GameProgress(path, changedCatalog);
            expect(restored.features().equals(Set.of(GameFeature.EMPTY_STATEMENTS)), "restored syntax comes from current catalog rather than saved flags");
        });

        check("all completed levels remain selectable after restart", () -> {
            Path path = directory.resolve("finished.properties");
            GameProgress progress = new GameProgress(path, LEVELS);
            for (GameLevel level : LEVELS) progress.complete(level, "answer " + level.id());
            progress.setSelectedIndex(LEVELS.size() - 1);
            progress.save();
            GameProgress restored = new GameProgress(path, LEVELS);
            expect(restored.completedCount() == LEVELS.size() && restored.selectedIndex() == LEVELS.size() - 1, "finished game restores valid selection");
            for (int index = 0; index < LEVELS.size(); index++) expect(restored.isAvailable(index), "completed lesson stays available");
            expect(!restored.isAvailable(LEVELS.size()), "no nonexistent next level is unlocked");
        });

        check("unsupported versions malformed properties and invalid flags fail visibly", () -> {
            Path path = directory.resolve("invalid.properties");
            for (String contents : List.of("version=999\n", "completed.first=true\n", "version=1\ndraft.first=\\uZZZZ\n",
                    "version=1\ncompleted.first=maybe\n")) {
                Files.writeString(path, contents);
                expectIOException(() -> new GameProgress(path, LEVELS));
                expect(Files.readString(path).equals(contents), "failed load never overwrites existing progress");
            }
            expectIOException(() -> new GameProgress(directory, LEVELS));
        });

        check("failed save retains in-memory drafts completion and selection", () -> {
            Path blockedParent = directory.resolve("blocked-parent");
            GameProgress progress = new GameProgress(blockedParent.resolve("progress.properties"), LEVELS);
            progress.complete(LEVELS.get(0), "validated answer");
            progress.setDraft("second", "unsaved draft\n");
            progress.setSelectedIndex(1);
            progress.setTaskLanguage(GameTaskLanguage.GERMAN);
            Files.writeString(blockedParent, "keep this file");
            expectIOException(progress::save);
            expect(progress.completedCount() == 1 && progress.selectedIndex() == 1, "save failure does not roll back earned in-memory progress");
            expect(progress.draft(LEVELS.get(1)).equals("unsaved draft\n"), "save failure preserves edited draft");
            expect(progress.taskLanguage() == GameTaskLanguage.GERMAN, "save failure preserves chosen task language");
            expect(Files.readString(blockedParent).equals("keep this file"), "failed directory creation does not replace existing files");

            Path moveParent = Files.createDirectory(directory.resolve("failed-replacement"));
            Path target = moveParent.resolve("progress.properties");
            GameProgress blockedMove = new GameProgress(target, LEVELS);
            blockedMove.setDraft("first", "keep in memory");
            Files.createDirectory(target);
            Files.writeString(target.resolve("keep.txt"), "existing content");
            expectIOException(blockedMove::save);
            expect(blockedMove.draft(LEVELS.get(0)).equals("keep in memory")
                    && Files.readString(target.resolve("keep.txt")).equals("existing content"), "failed replacement preserves draft and existing target");
            try (var files = Files.list(moveParent)) {
                expect(files.toList().equals(List.of(target)), "failed replacement removes its temporary file");
            }
        });

        check("successful replacement leaves a complete readable file without temporary debris", () -> {
            Path parent = directory.resolve("replacement");
            Path path = parent.resolve("progress.properties");
            GameProgress progress = new GameProgress(path, LEVELS);
            progress.setDraft("first", "old draft");
            progress.save();
            progress.complete(LEVELS.get(0), "new answer\n");
            progress.save();
            GameProgress restored = new GameProgress(path, LEVELS);
            expect(restored.completedCount() == 1 && restored.draft(LEVELS.get(0)).equals("new answer\n"), "replacement contains the complete latest state");
            try (var files = Files.list(parent)) {
                expect(files.toList().equals(List.of(path)), "successful atomic save leaves no temporary files");
            }
        });

        check("whole-game reset clears partial progress and all persisted drafts", () -> {
            Path path = directory.resolve("reset-partial.properties");
            GameProgress progress = new GameProgress(path, LEVELS);
            progress.complete(LEVELS.get(0), "first completed answer");
            progress.complete(LEVELS.get(1), "second completed answer");
            progress.setDraft("third", "unfinished Unicode draft 🌱\n");
            progress.setDraft("fourth", "saved while locked\n");
            progress.setSelectedIndex(1);
            progress.save();

            progress.reset();
            expectPristine(progress);
            expectPristine(new GameProgress(path, LEVELS));
            Properties saved = new Properties();
            try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                saved.load(reader);
            }
            expect(saved.stringPropertyNames().stream().noneMatch(key -> key.startsWith("draft.")
                    || key.startsWith("completed.")), "reset removes old draft and completion entries from disk");
            expect(saved.getProperty("selected").equals(LEVELS.get(0).id()), "reset persists first-level selection");
        });

        check("whole-game reset also clears a fully completed game", () -> {
            Path path = directory.resolve("reset-complete.properties");
            GameProgress progress = new GameProgress(path, LEVELS);
            for (GameLevel level : LEVELS) progress.complete(level, "completed " + level.id());
            progress.setSelectedIndex(LEVELS.size() - 1);
            progress.save();
            progress.reset();
            expectPristine(progress);
            expectPristine(new GameProgress(path, LEVELS));
            progress.complete(LEVELS.get(0), "new first solution");
            expect(progress.completedCount() == 1 && !progress.isAvailable(2), "restarted game earns levels sequentially again");
        });

        check("failed whole-game reset restores completions features selection and drafts", () -> {
            Path blockedParent = directory.resolve("reset-blocked-parent");
            GameProgress progress = new GameProgress(blockedParent.resolve("progress.properties"), LEVELS);
            progress.complete(LEVELS.get(0), "first answer\n");
            progress.complete(LEVELS.get(1), "second answer\n");
            progress.setDraft("third", "unfinished draft 🌱\n");
            progress.setDraft("fourth", "");
            progress.setSelectedIndex(1);
            progress.setTaskLanguage(GameTaskLanguage.GERMAN);
            Set<String> completed = progress.completedIds();
            Set<GameFeature> features = progress.features();
            List<String> drafts = LEVELS.stream().map(progress::draft).toList();
            Files.writeString(blockedParent, "keep this unrelated file");

            expectIOException(progress::reset);
            expect(progress.taskLanguage() == GameTaskLanguage.GERMAN, "failed reset preserves task language alongside the previous game");
            expect(progress.completedIds().equals(completed) && progress.completedCount() == 2,
                    "failed reset restores all completion credit");
            expect(progress.features().equals(features) && progress.selectedIndex() == 1,
                    "failed reset restores earned syntax and selection");
            expect(LEVELS.stream().map(progress::draft).toList().equals(drafts),
                    "failed reset restores every draft including empty and locked drafts");
            expect(Files.readString(blockedParent).equals("keep this unrelated file"),
                    "failed reset does not replace existing filesystem content");
        });

        check("version-one countdown credit survives the reordered foundation lessons", () -> {
            List<GameLevel> reordered = reorderedFoundations();
            Path path = directory.resolve("legacy-foundations.properties");
            Properties legacy = validProperties();
            legacy.setProperty("completed.first-steps", "true");
            legacy.setProperty("completed.countdown", "true");
            legacy.setProperty("draft.countdown", "my earned loop\n");
            legacy.setProperty("draft.zero-test", "unfinished zero test 🌱\n");
            legacy.setProperty("draft.equals-three", "saved later draft\n");
            legacy.setProperty("selected", "zero-test");
            write(path, legacy);

            GameProgress progress = new GameProgress(path, reordered);
            expect(progress.completedIds().equals(Set.of("first-steps", "countdown"))
                    && progress.completedCount() == 2, "migration preserves old completed IDs without awarding new ones");
            expect(progress.features().equals(Set.of(GameFeature.BLOCKS, GameFeature.WHILE)),
                    "migration keeps earned while syntax without giving if or else credit");
            expect(progress.selectedIndex() == 1 && progress.isAvailable(1) && progress.isAvailable(3)
                    && !progress.isAvailable(2) && !progress.isAvailable(4),
                    "zero-test is next while completed countdown stays selectable");
            expectIllegal(() -> progress.complete(reordered.get(2), "skip zero-test"));
            progress.setSelectedIndex(3);
            progress.save();
            Properties saved = read(path);
            expect("2".equals(saved.getProperty("version")), "next save upgrades progress to version two");
            GameProgress restored = new GameProgress(path, reordered);
            expect(restored.selectedIndex() == 3 && restored.completedIds().equals(progress.completedIds())
                    && restored.features().equals(progress.features()), "version-two reload retains noncontiguous migrated credit");
            expect(restored.draft(reordered.get(3)).equals("my earned loop\n")
                    && restored.draft(reordered.get(1)).equals("unfinished zero test 🌱\n")
                    && restored.draft(reordered.get(4)).equals("saved later draft\n"),
                    "migration and reload preserve drafts by level ID");
            restored.complete(reordered.get(1), "zero-test solution");
            expect(restored.isAvailable(2) && !restored.isAvailable(4), "zero-test opens only two-paths");
            restored.complete(reordered.get(2), "two-paths solution");
            expect(restored.completedCount() == 4 && restored.isAvailable(4),
                    "completing missing foundation lessons advances past previously completed countdown");
            restored.reset();
            GameProgress restarted = new GameProgress(path, reordered);
            expect(restarted.completedIds().isEmpty() && restarted.features().isEmpty()
                    && restarted.selectedIndex() == 0 && restarted.isAvailable(0) && !restarted.isAvailable(1),
                    "whole-game reset removes migrated credits and restores initial locks");
            for (GameLevel level : reordered) {
                expect(restarted.draft(level).equals(level.starter()), "reset clears migrated draft for " + level.id());
            }
        });

        check("version-one foundation gaps are still rejected in their original order", () -> {
            Path path = directory.resolve("legacy-foundation-gap.properties");
            Properties legacy = validProperties();
            legacy.setProperty("completed.first-steps", "true");
            legacy.setProperty("completed.zero-test", "true");
            legacy.setProperty("completed.two-paths", "true");
            legacy.setProperty("completed.unknown-future", "true");
            legacy.setProperty("features", "WHILE,IF,ELSE");
            legacy.setProperty("selected", "two-paths");
            legacy.setProperty("draft.two-paths", "preserve even uncredited drafts\n");
            write(path, legacy);
            List<GameLevel> reordered = reorderedFoundations();
            GameProgress progress = new GameProgress(path, reordered);
            expect(progress.completedIds().equals(Set.of("first-steps"))
                    && progress.features().equals(Set.of(GameFeature.BLOCKS)),
                    "missing legacy countdown rejects all later version-one credit");
            expect(progress.selectedIndex() == 1 && !progress.isAvailable(2), "invalid legacy selection falls back to earliest unfinished");
            expect(progress.draft(reordered.get(2)).equals("preserve even uncredited drafts\n"),
                    "discarding invalid completion credit does not discard written work");
        });

        check("version two ignores unknown completed IDs and serialized feature flags", () -> {
            Path path = directory.resolve("version-two-unknown.properties");
            Properties properties = validProperties();
            properties.setProperty("version", "2");
            properties.setProperty("completed.first", "true");
            properties.setProperty("completed.future-level", "true");
            properties.setProperty("features", "IMPORTS,MACROS");
            properties.setProperty("selected", "future-level");
            write(path, properties);
            GameProgress progress = new GameProgress(path, LEVELS);
            expect(progress.completedIds().equals(Set.of("first")) && progress.completedCount() == 1,
                    "unknown version-two IDs cannot earn credit");
            expect(progress.features().equals(Set.of(GameFeature.BLOCKS)) && progress.selectedIndex() == 1,
                    "catalog rewards and earliest unfinished selection remain authoritative");
        });

        check("immediate practice preserves progress from the historical thirty-one thirty-two forty-two and forty-three levels", () -> {
            List<GameLevel> catalog = GameCatalog.levels();
            expect(ORIGINAL_VERSION_ONE_IDS.size() == 31 && ORIGINAL_VERSION_TWO_IDS.size() == 32
                            && ORIGINAL_FORTY_TWO_IDS.size() == 42 && ORIGINAL_FORTY_THREE_IDS.size() == 43,
                    "fixtures describe the published historical catalogs");
            expect(catalog.size() == 46
                            && catalog.stream().filter(level -> !ORIGINAL_FORTY_THREE_IDS.contains(level.id())).map(GameLevel::id).toList()
                                    .equals(List.of("else-practice", "while-practice", "comparison-practice")),
                    "three practice lessons preserve all forty-three existing identities");
            List<List<String>> historicalCatalogs = List.of(ORIGINAL_VERSION_ONE_IDS, ORIGINAL_VERSION_TWO_IDS,
                    ORIGINAL_FORTY_TWO_IDS, ORIGINAL_FORTY_THREE_IDS);
            List<String> nextIds = List.of("clear-block", "clear-do-while", "clear-do-while", "else-practice");
            List<String> followingIds = List.of("clear-do-while", "else-practice", "else-practice", "while-practice");
            for (int fixture = 0; fixture < historicalCatalogs.size(); fixture++) {
                String version = fixture == 0 ? "1" : "2";
                List<String> historicalIds = historicalCatalogs.get(fixture);
                Set<String> originalIds = Set.copyOf(historicalIds);
                String nextId = nextIds.get(fixture);
                String selectedId = historicalIds.get(historicalIds.size() - 1);
                GameLevel nextLesson = catalog.stream().filter(level -> level.id().equals(nextId)).findFirst().orElseThrow();
                Path path = directory.resolve("historical-" + originalIds.size() + "-levels.properties");
                Properties legacy = validProperties();
                legacy.setProperty("version", version);
                for (String id : originalIds) {
                    legacy.setProperty("completed." + id, "true");
                    legacy.setProperty("draft." + id, "// original v" + version + " solution for " + id + "\n// Grüße 🌱\n");
                }
                legacy.setProperty("selected", selectedId);
                write(path, legacy);
                GameProgress restored = new GameProgress(path, catalog);
                expect(restored.completedCount() == originalIds.size() && restored.completedIds().equals(originalIds),
                        "every original v" + version + " completion survives without any new lesson credit");
                expect(restored.features().equals(EnumSet.allOf(GameFeature.class)),
                        "the relocated do/while reward stays earned with every other old feature");
                expect(catalog.get(restored.selectedIndex()).id().equals(selectedId),
                        "the saved completed selection remains selected after migration");
                expect(!restored.isCompleted("else-practice") && !restored.isCompleted("while-practice")
                                && !restored.isCompleted("comparison-practice"),
                        "new practice is never silently awarded to a historical save");
                for (int index = 0; index < catalog.size(); index++) {
                    GameLevel level = catalog.get(index);
                    expect(restored.isAvailable(index) == (originalIds.contains(level.id()) || level.id().equals(nextId)),
                            "only old completed lessons and earliest unfinished " + nextId + " are selectable: " + level.id());
                    expect(restored.draft(level).equals(originalIds.contains(level.id())
                                    ? legacy.getProperty("draft." + level.id()) : level.starter()),
                            "each old draft survives and new lesson drafts start pristine: " + level.id());
                }
                restored.save();
                GameProgress reloaded = new GameProgress(path, catalog);
                expect(reloaded.completedIds().equals(originalIds) && reloaded.features().contains(GameFeature.DO_WHILE)
                                && reloaded.selectedIndex() == restored.selectedIndex(),
                        "version-two round-trip preserves credit rewards and selection without filling new gaps");
                for (GameLevel level : catalog) {
                    expect(reloaded.draft(level).equals(restored.draft(level)), "round-trip preserves draft for " + level.id());
                }
                reloaded.complete(nextLesson, "new solution for " + nextId);
                expect(reloaded.completedCount() == originalIds.size() + 1 && reloaded.isCompleted(nextId)
                                && reloaded.completedIds().containsAll(originalIds),
                        "solving the earliest new lesson adds exactly one completion");
                String followingId = followingIds.get(fixture);
                GameLevel following = catalog.stream().filter(level -> level.id().equals(followingId)).findFirst().orElseThrow();
                expect(reloaded.isAvailable(catalog.indexOf(following)) && !reloaded.isCompleted(followingId),
                        "the next missing lesson unlocks without receiving completion credit");
                if (fixture == 3) {
                    expect(reloaded.completedCount() == 44, "finishing else practice leaves while and comparison practice outstanding");
                    reloaded.complete(following, "new while practice solution");
                    GameLevel comparison = catalog.stream().filter(level -> level.id().equals("comparison-practice")).findFirst().orElseThrow();
                    expect(reloaded.completedCount() == 45 && reloaded.isAvailable(catalog.indexOf(comparison)),
                            "finishing while practice opens the remaining comparison rewrite");
                    reloaded.complete(comparison, "new comparison practice solution");
                    expect(reloaded.completedCount() == catalog.size(), "the three new practices complete the expanded historical journey");
                    for (int index = 0; index < catalog.size(); index++) {
                        expect(reloaded.isAvailable(index), "every completed lesson remains selectable");
                    }
                }
                reloaded.reset();
                GameProgress reset = new GameProgress(path, catalog);
                expect(reset.completedIds().isEmpty() && reset.completedCount() == 0 && reset.features().isEmpty()
                                && reset.selectedIndex() == 0,
                        "reset removes every historical and newly completed reward and returns to level one");
                for (int index = 0; index < catalog.size(); index++) {
                    expect(reset.isAvailable(index) == (index == 0) && reset.draft(catalog.get(index)).equals(catalog.get(index).starter()),
                            "reset restores initial locks and draft for " + catalog.get(index).id());
                }
                expect(read(path).stringPropertyNames().stream().noneMatch(key -> key.startsWith("draft.")
                                || key.startsWith("completed.")), "reset removes migrated draft and completion entries from disk");
            }
        });

        check("the new while rewrite preserves every credit draft and selection from the historical forty-five levels", () -> {
            List<GameLevel> catalog = GameCatalog.levels();
            Set<String> originalIds = Set.copyOf(ORIGINAL_FORTY_FIVE_IDS);
            expect(originalIds.size() == 45 && catalog.size() == 46,
                    "the fixed historical fixture describes the immediately previous curriculum");
            expect(catalog.stream().filter(level -> !originalIds.contains(level.id())).map(GameLevel::id).toList()
                            .equals(List.of("while-practice")),
                    "the while rewrite is the only added identity");
            GameLevel rewrite = catalog.stream().filter(level -> level.id().equals("while-practice")).findFirst().orElseThrow();
            int rewriteIndex = catalog.indexOf(rewrite);
            expect(rewriteIndex == 8 && catalog.get(rewriteIndex - 1).id().equals("countdown"),
                    "the rewrite immediately follows the while unlock");
            Path path = directory.resolve("historical-45-levels.properties");
            Properties legacy = validProperties();
            legacy.setProperty("version", "2");
            legacy.setProperty("selected", "equals-three");
            legacy.setProperty("task.language", "de");
            for (String id : ORIGINAL_FORTY_FIVE_IDS) {
                legacy.setProperty("completed." + id, "true");
                legacy.setProperty("draft." + id, "// original answer for " + id + "\n// Grüße 🌱\n");
            }
            write(path, legacy);

            GameProgress restored = new GameProgress(path, catalog);
            expect(restored.completedCount() == 45 && restored.completedIds().equals(originalIds),
                    "all previous credits survive and no rewrite credit is invented");
            expect(restored.features().equals(EnumSet.allOf(GameFeature.class))
                            && restored.taskLanguage() == GameTaskLanguage.GERMAN,
                    "earned syntax and task language survive");
            expect(catalog.get(restored.selectedIndex()).id().equals("equals-three")
                            && restored.isAvailable(restored.selectedIndex()),
                    "the saved completed lesson remains selected at its shifted index");
            expect(restored.isAvailable(rewriteIndex) && !restored.isCompleted(rewrite.id())
                            && restored.draft(rewrite).equals(rewrite.starter()),
                    "the sole unfinished rewrite opens with its working starter");
            for (GameLevel level : catalog) {
                if (originalIds.contains(level.id())) {
                    expect(restored.isAvailable(catalog.indexOf(level))
                                    && restored.draft(level).equals(legacy.getProperty("draft." + level.id())),
                            "completed lesson and source remain available: " + level.id());
                }
            }
            expect(read(path).equals(legacy), "loading does not rewrite the historical save");

            Properties withoutSelection = new Properties();
            withoutSelection.putAll(legacy);
            withoutSelection.remove("selected");
            Path fallback = directory.resolve("historical-45-no-selection.properties");
            write(fallback, withoutSelection);
            expect(new GameProgress(fallback, catalog).selectedIndex() == rewriteIndex,
                    "the new rewrite is the first unfinished lesson when no saved selection exists");

            restored.setSelectedIndex(rewriteIndex);
            String answer = "var n=0;\nvar count=0;\nbegin:\nwhile(n!=0) {\n  DEC n;\n  INC count;\n}\nHALT;\n";
            restored.setDraft(rewrite.id(), answer);
            restored.save();
            GameProgress resumed = new GameProgress(path, catalog);
            expect(resumed.selectedIndex() == rewriteIndex && resumed.completedIds().equals(originalIds)
                            && resumed.draft(rewrite).equals(answer),
                    "selecting and drafting the new level round-trip without changing old credit");
            resumed.complete(rewrite, answer);
            resumed.save();
            GameProgress completed = new GameProgress(path, catalog);
            expect(completed.completedCount() == 46 && completed.completedIds().containsAll(originalIds)
                            && completed.isCompleted(rewrite.id()),
                    "solving the rewrite adds exactly one completion and fills the historical gap");
            expect(completed.selectedIndex() == rewriteIndex && completed.draft(rewrite).equals(answer),
                    "the new completed draft and selected level survive restart");
            for (GameLevel level : catalog) {
                expect(completed.isAvailable(catalog.indexOf(level)), "every completed level remains available");
                if (originalIds.contains(level.id())) {
                    expect(completed.draft(level).equals(legacy.getProperty("draft." + level.id())),
                            "solving the new level leaves old draft untouched: " + level.id());
                }
            }
        });

        check("invalid catalogs cannot create ambiguous persisted identities", () -> {
            Path path = directory.resolve("bad-catalog.properties");
            expectIllegal(() -> new GameProgress(path, List.of()));
            expectIllegal(() -> new GameProgress(path, List.of(LEVELS.get(0), LEVELS.get(0))));
        });

        System.out.println("GameProgressTest: " + passed + " passed, " + failed + " failed");
        if (failed != 0) throw new AssertionError(failed + " progress check(s) failed");
    }

    private static GameLevel level(String id, Set<GameFeature> unlocks) {
        return new GameLevel(id, "Test world", id, "Goal", "Lesson", "// starter " + id + "\n",
                List.of("Hint"), unlocks, Set.of(), Map.of(),
                List.of(new GameLevel.TestCase(Map.of(), Map.of())), 100);
    }

    private static List<GameLevel> reorderedFoundations() {
        return List.of(level("first-steps", Set.of(GameFeature.BLOCKS)),
                level("zero-test", Set.of(GameFeature.IF)), level("two-paths", Set.of(GameFeature.ELSE)),
                level("countdown", Set.of(GameFeature.WHILE)), level("equals-three", Set.of(GameFeature.COMPARISONS)));
    }

    private static void expectPristine(GameProgress progress) {
        expect(progress.completedCount() == 0 && progress.completedIds().isEmpty()
                && progress.features().isEmpty(), "reset clears completion credit and syntax rewards");
        expect(progress.selectedIndex() == 0 && progress.isAvailable(0) && !progress.isAvailable(1),
                "reset restores first-level-only availability and selection");
        for (GameLevel level : LEVELS) {
            expect(progress.draft(level).equals(level.starter()), "reset restores starter for " + level.id());
        }
    }

    private static Properties validProperties() {
        Properties properties = new Properties();
        properties.setProperty("version", "1");
        return properties;
    }

    private static void withDisplayLocale(Locale display, Check action) throws Exception {
        Locale oldDisplay = Locale.getDefault(Locale.Category.DISPLAY);
        Locale oldFormat = Locale.getDefault(Locale.Category.FORMAT);
        try {
            Locale.setDefault(Locale.Category.DISPLAY, display);
            Locale.setDefault(Locale.Category.FORMAT, display.getLanguage().equals("de") ? Locale.US : Locale.GERMANY);
            action.run();
        } finally {
            Locale.setDefault(Locale.Category.DISPLAY, oldDisplay);
            Locale.setDefault(Locale.Category.FORMAT, oldFormat);
        }
    }

    private static void write(Path path, Properties properties) throws IOException {
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            properties.store(writer, "Test fixture");
        }
    }

    private static Properties read(Path path) throws IOException {
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }

    private static void expectIOException(Check action) throws Exception {
        try { action.run(); } catch (IOException expected) { return; }
        throw new AssertionError("Expected IOException");
    }

    private static void expectIllegal(Check action) throws Exception {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected IllegalArgumentException");
    }

    private static void expectUnsupported(Check action) throws Exception {
        try { action.run(); } catch (UnsupportedOperationException expected) { return; }
        throw new AssertionError("Expected UnsupportedOperationException");
    }

    private static void check(String name, Check action) {
        try {
            action.run(); passed++; System.out.println("PASS " + name);
        } catch (Exception | AssertionError error) {
            failed++; System.err.println("FAIL " + name + ": " + error); error.printStackTrace(System.err);
        }
    }

    private static void expect(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

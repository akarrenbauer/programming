import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import machine.GameCatalog;
import machine.GameLevel;
import machine.GameTaskLanguage;
import machine.GameTranslations;

/** Task translations are presentation only; source programs and level identities stay canonical. */
public final class GameTranslationsTest {
    private static int passed;
    private static int failed;

    @FunctionalInterface
    private interface Check { void run() throws Exception; }

    public static void main(String[] args) {
        check("task-language codes and display names are stable", () -> {
            expect(GameTaskLanguage.ENGLISH.code().equals("en"), "English persists as en");
            expect(GameTaskLanguage.GERMAN.code().equals("de"), "German persists as de");
            expect(GameTaskLanguage.ENGLISH.toString().equals("English"), "English menu label");
            expect(GameTaskLanguage.GERMAN.toString().equals("Deutsch"), "German menu label");
            expect(GameTaskLanguage.fromCode("en") == GameTaskLanguage.ENGLISH, "read English preference");
            expect(GameTaskLanguage.fromCode("de") == GameTaskLanguage.GERMAN, "read German preference");
            expect(GameTaskLanguage.fromCode(null) == null, "missing preference has no explicit language");
            for (String unsupported : List.of("", "fr", "de-DE", "Deutsch", "unknown")) {
                expect(GameTaskLanguage.fromCode(unsupported) == null, "unsupported preference: " + unsupported);
            }
        });

        check("German locale variants select German and other locales fall back to English", () -> {
            for (String tag : List.of("de", "de-DE", "de-AT", "de-CH", "de-Latn-CH-u-ca-gregory")) {
                expect(GameTaskLanguage.fromLocale(Locale.forLanguageTag(tag)) == GameTaskLanguage.GERMAN,
                        "German language is independent of region, script, and extensions: " + tag);
            }
            for (Locale locale : List.of(Locale.ENGLISH, Locale.US, Locale.FRENCH,
                    Locale.forLanguageTag("en-DE"), Locale.forLanguageTag("zh-Hant-TW"), Locale.ROOT)) {
                expect(GameTaskLanguage.fromLocale(locale) == GameTaskLanguage.ENGLISH,
                        "unsupported display language falls back to English: " + locale);
            }
        });

        check("English preserves every canonical task field exactly", () -> {
            for (GameLevel level : GameCatalog.levels()) {
                var text = GameTranslations.text(level, GameTaskLanguage.ENGLISH);
                expect(text.title().equals(level.title()), level.id() + " English title");
                expect(text.goal().equals(level.goal()), level.id() + " English goal");
                expect(text.lesson().equals(level.lesson()), level.id() + " English lesson");
                expect(text.hints().equals(level.hints()), level.id() + " English hint order and content");
                expectUnsupported(() -> text.hints().add("changed"));
            }
        });

        check("all 46 catalog tasks have complete German text and independent immutable hints", () -> {
            List<GameLevel> catalog = GameCatalog.levels();
            expect(catalog.size() == 46, "translation coverage includes the complete current curriculum");
            for (GameLevel level : catalog) {
                var text = GameTranslations.text(level, GameTaskLanguage.GERMAN);
                expectTranslated(text.title(), level.title(), level.id() + " title");
                expectTranslated(text.goal(), level.goal(), level.id() + " goal");
                expectTranslated(text.lesson(), level.lesson(), level.id() + " lesson");
                expect(text.hints().size() == level.hints().size() && text.hints().size() == 3,
                        level.id() + " retains all three graduated hints");
                for (int index = 0; index < text.hints().size(); index++) {
                    expectTranslated(text.hints().get(index), level.hints().get(index),
                            level.id() + " hint " + (index + 1));
                }
                expectUnsupported(() -> text.hints().set(0, "changed"));
                var again = GameTranslations.text(level, GameTaskLanguage.GERMAN);
                expect(again.equals(text), level.id() + " has stable translation lookup");
            }
        });

        check("all six worlds translate without changing the canonical catalog", () -> {
            List<GameLevel> before = List.copyOf(GameCatalog.levels());
            Set<String> worlds = new java.util.LinkedHashSet<>();
            for (GameLevel level : before) worlds.add(level.world());
            expect(worlds.size() == 6, "coverage includes every curriculum world");
            for (String world : worlds) {
                expect(GameTranslations.world(world, GameTaskLanguage.ENGLISH).equals(world), "canonical English world");
                expectTranslated(GameTranslations.world(world, GameTaskLanguage.GERMAN), world, world);
            }
            expect(GameCatalog.levels().equals(before), "translation lookup leaves canonical levels and code unchanged");
        });

        check("custom levels and worlds fall back to their supplied English text", () -> {
            GameLevel custom = new GameLevel("custom-untranslated", "Custom world", "Custom title", "Custom goal",
                    "Custom lesson", "var x=0; begin: HALT;", List.of("First hint", "Second hint"),
                    Set.of(), Set.of(), Map.of(), List.of(new GameLevel.TestCase(Map.of(), Map.of())), 10);
            for (GameTaskLanguage language : GameTaskLanguage.values()) {
                var text = GameTranslations.text(custom, language);
                expect(text.title().equals(custom.title()) && text.goal().equals(custom.goal())
                                && text.lesson().equals(custom.lesson()) && text.hints().equals(custom.hints()),
                        "unknown level preserves every supplied field in " + language);
                expectUnsupported(() -> text.hints().clear());
                expect(GameTranslations.world(custom.world(), language).equals(custom.world()),
                        "unknown world remains readable in " + language);
            }
        });

        System.out.println("GameTranslationsTest: " + passed + " passed, " + failed + " failed");
        if (failed != 0) throw new AssertionError(failed + " translation check(s) failed");
    }

    private static void expectTranslated(String translated, String english, String field) {
        expect(translated != null && !translated.isBlank() && !translated.equals(english),
                field + " must have its own nonblank German text");
    }

    private static void expectUnsupported(Check action) throws Exception {
        try { action.run(); } catch (UnsupportedOperationException expected) { return; }
        throw new AssertionError("Expected immutable hint list");
    }

    private static void expect(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void check(String name, Check action) {
        try { action.run(); passed++; System.out.println("PASS " + name); }
        catch (Exception | AssertionError error) {
            failed++; System.err.println("FAIL " + name + ": " + error); error.printStackTrace(System.err);
        }
    }
}

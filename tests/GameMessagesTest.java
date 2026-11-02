import machine.GameCatalog;
import machine.GameFeature;
import machine.GameMessages;
import machine.GameMessages.Key;
import machine.GameTaskLanguage;
import machine.GameTranslations;

/** Complete bilingual status templates and language-independent source substitutions. */
public final class GameMessagesTest {
    private static int passed;
    private static int failed;
    @FunctionalInterface private interface Check { void run() throws Exception; }

    public static void main(String[] args) {
        check("every status key formats in both languages with distinct text", () -> {
            Object[] arguments = {"1", "2", "3", "4", "5", "6", "7", "8"};
            for (Key key : Key.values()) {
                String english = GameMessages.text(GameTaskLanguage.ENGLISH, key, arguments);
                String german = GameMessages.text(GameTaskLanguage.GERMAN, key, arguments);
                require(!english.isBlank() && !german.isBlank(), key + " has complete text");
                require(!english.equals(german), key + " has a German translation");
                require(!english.contains("%s") && !german.contains("%s"), key + " substitutions resolve");
            }
        });

        check("completion and the next task are consistently in the selected task language", () -> {
            var next = GameCatalog.levels().stream().filter(level -> level.id().equals("equals-three")).findFirst().orElseThrow();
            String german = GameMessages.text(GameTaskLanguage.GERMAN, Key.LEVEL_COMPLETE) + " "
                    + GameMessages.text(GameTaskLanguage.GERMAN, Key.NEXT_CHALLENGE,
                            GameTranslations.text(next, GameTaskLanguage.GERMAN).title());
            require(german.equals("Aufgabe abgeschlossen! Als Nächstes: Einen Gleichheitstest aufbauen."), german);
            String english = GameMessages.text(GameTaskLanguage.ENGLISH, Key.LEVEL_COMPLETE) + " "
                    + GameMessages.text(GameTaskLanguage.ENGLISH, Key.NEXT_CHALLENGE, next.title());
            require(english.equals("Level complete! Next: Build an equality test."), english);
        });

        check("all earned feature titles localize while executable names remain exact", () -> {
            for (GameFeature feature : GameFeature.values()) {
                require(GameMessages.featureTitle(GameTaskLanguage.ENGLISH, feature).equals(feature.title()), "English title preserved");
                String german = GameMessages.featureTitle(GameTaskLanguage.GERMAN, feature);
                require(!german.isBlank() && !german.equals(feature.title()), "German feature title for " + feature);
            }
            require(GameMessages.featureTitle(GameTaskLanguage.GERMAN, GameFeature.WHILE).equals("while-Schleifen"), "keyword spelling preserved");
            require(GameMessages.text(GameTaskLanguage.GERMAN, Key.HELPER, "clear").equals("Hilfsmakro clear"), "macro name remains code");
            String path = "/tmp/Next: progress%20.properties";
            require(GameMessages.text(GameTaskLanguage.GERMAN, Key.RECOVERY, "Fehler", path).endsWith(path), "paths are not translated or reformatted");
        });

        check("language switches rerender progress counts and statuses without changing substitutions", () -> {
            require(GameMessages.text(GameTaskLanguage.GERMAN, Key.WORLD_PROGRESS, 3, 8).equals("3 / 8 Aufgaben dieser Welt abgeschlossen"), "German world progress");
            require(GameMessages.text(GameTaskLanguage.ENGLISH, Key.WORLD_PROGRESS, 3, 8).equals("3 / 8 levels completed in this world"), "English world progress");
            require(GameMessages.text(GameTaskLanguage.GERMAN, Key.PASSED_ALL, 4).equals("Alle 4 Testfälle bestanden."), "success status");
            require(GameMessages.text(GameTaskLanguage.GERMAN, Key.HINT, 2, 3, "Verwende while.")
                    .equals("Hinweis 2/3: Verwende while."), "hint wrapper shares task language");
            require(GameMessages.text(GameTaskLanguage.GERMAN, Key.CHECK_CANCELLED).startsWith("Prüfung abgebrochen."), "cancellation status");
            require(GameMessages.text(GameTaskLanguage.GERMAN, Key.SAVED).equals("Fortschritt und Entwürfe lokal gespeichert."), "save status");
        });

        check("diagnostic entry point shares translation while English remains exact", () -> {
            String raw = "This level asks you to use While loops in your solution.";
            require(GameMessages.diagnostic(GameTaskLanguage.ENGLISH, raw).equals(raw), "English diagnostic preserved");
            String german = GameMessages.diagnostic(GameTaskLanguage.GERMAN, raw);
            require(!german.equals(raw) && german.contains("while-Schleifen"), "required feature uses the same German title");
            require(!GameMessages.diagnostic(GameTaskLanguage.GERMAN, "Unknown variable counter.").contains("Unknown variable"), "identifier error translated");
        });

        System.out.println("GameMessagesTest: " + passed + " passed, " + failed + " failed");
        if (failed != 0) throw new AssertionError(failed + " message checks failed");
    }

    private static void check(String name, Check action) {
        try { action.run(); passed++; System.out.println("PASS " + name); }
        catch (Exception | AssertionError error) { failed++; System.err.println("FAIL " + name); error.printStackTrace(); }
    }

    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}

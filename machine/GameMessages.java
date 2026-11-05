package machine;

import java.util.Locale;
import java.util.Objects;

/** Task-language presentation messages; source text and persisted progress remain language independent. */
public final class GameMessages {
    public enum Key {
        WORLD_TITLE("World %s · %s", "Welt %s · %s"),
        WORLD_PROGRESS("%s / %s levels completed in this world", "%s / %s Aufgaben dieser Welt abgeschlossen"),
        SCORE("%s / %s completed  ·  %s syntax unlocks", "%s / %s abgeschlossen  ·  %s Spracherweiterungen freigeschaltet"),
        LEVEL_POSITION("Level %s / %s", "Aufgabe %s / %s"),
        HINT("Hint %s/%s: %s", "Hinweis %s/%s: %s"),
        CONTRACT("%s execution checks · %s steps per case · input initializers are replaced for each check. Scratch variables are yours to add.",
                "%s Ausführungstests · %s Schritte pro Testfall · Die Eingabewerte werden für jeden Test ersetzt. Eigene Hilfsvariablen kannst du ergänzen."),
        NEXT_CHALLENGE("Next: %s.", "Als Nächstes: %s."),
        NEXT_WORLD("Next world: %s.", "Nächste Welt: %s."),
        WORLD_UNLOCKED("World unlocked: %s.", "Welt freigeschaltet: %s."),
        HELPER("%s helper", "Hilfsmakro %s"),
        COMPLETE_TO_UNLOCK("Complete this level to unlock: %s", "Schließe diese Aufgabe ab, um Folgendes freizuschalten: %s"),
        NEW_TOOLS_UNLOCKED("New tools unlocked: %s.", "Neue Werkzeuge freigeschaltet: %s."),
        UNLOCKED_BY_LEVEL("Unlocked by this level: %s.", "Durch diese Aufgabe freigeschaltet: %s."),
        UNLOCKED("Unlocked: %s.", "Freigeschaltet: %s."),
        PASSED_ALL("Passed all %s test cases.", "Alle %s Testfälle bestanden."),
        PREPARING_DEBUG("Preparing debug mode for case %s…", "Die Fehlersuche für Testfall %s wird vorbereitet…"),
        DEBUGGING("Debugging case %s in a separate window. Use Check solution to complete the level.",
                "Testfall %s wird in einem eigenen Fenster untersucht. Prüfe deine Lösung, um die Aufgabe abzuschließen."),
        DEBUG_FAILED("Could not start debugging: %s", "Die Fehlersuche konnte nicht gestartet werden: %s"),
        CHECK_FAILED("Could not complete the check: %s", "Die Prüfung konnte nicht abgeschlossen werden: %s"),
        SAVE_FAILED("Progress is not saved: %s", "Der Fortschritt wurde nicht gespeichert: %s"),
        RESET_FAILED("Could not reset the game: %s", "Das Spiel konnte nicht zurückgesetzt werden: %s"),
        RESET_NOT_SAVED("Game was not reset: %s", "Das Spiel wurde nicht zurückgesetzt: %s"),
        RECOVERY("Saved progress could not be read: %s. The original file is untouched; this session saves to %s",
                "Der gespeicherte Fortschritt konnte nicht gelesen werden: %s. Die ursprüngliche Datei bleibt unverändert; diese Sitzung speichert unter %s"),
        WORLD_LOCKED("Complete the earlier world to enter this one.", "Schließe die vorherige Welt ab, um diese Welt zu betreten."),
        LEVEL_LOCKED("Complete the current level to open the next challenge.", "Schließe die aktuelle Aufgabe ab, um die nächste Aufgabe zu öffnen."),
        LOCKED("Locked", "Gesperrt"),
        COMPLETED("Completed. You can revisit your solution or continue.", "Abgeschlossen. Du kannst deine Lösung erneut ansehen oder weitermachen."),
        READY("Use the available grammar. Passing every check earns the next tools.", "Verwende die verfügbare Grammatik. Bestehe alle Tests, um die nächsten Werkzeuge freizuschalten."),
        FIRST_PUZZLE("Use the starter and the input/output contract to solve the first puzzle.", "Löse die erste Aufgabe mithilfe der Vorlage und der Anforderungen an Ein- und Ausgabe."),
        NEXT_CHALLENGE_REWARD("the next challenge", "die nächste Aufgabe"),
        LEVEL_COMPLETE("Level complete!", "Aufgabe abgeschlossen!"),
        JOURNEY_COMPLETE("The journey is complete.", "Du hast alle Aufgaben abgeschlossen."),
        JOURNEY_COMPLETE_LONG("Journey complete! You built the full language, arithmetic, gcd, and fixed-width signed operations.",
                "Alle Aufgaben abgeschlossen! Du hast die vollständige Sprache, Arithmetik, den ggT und Operationen mit vorzeichenbehafteten Zahlen fester Breite aufgebaut."),
        INVENTORY_PROMPT("See Unlocked grammar and Earned toolbox to use your rewards.", "Deine neuen Möglichkeiten findest du in der freigeschalteten Grammatik und bei deinen erworbenen Hilfsmakros."),
        STRUCTURED_WORLD_INTRO("Edit code beside a Nassi–Shneiderman diagram or flowchart. Choose the view under Diagram. Explicit JNZ is suspended.",
                "Bearbeite Code neben einem Nassi–Shneiderman-Diagramm oder Ablaufdiagramm. Wähle die Ansicht unter Diagram. Direkte JNZ-Anweisungen sind ausgesetzt."),
        CHECKING("Checking fresh inputs… You can cancel at any time.", "Neue Eingaben werden geprüft… Du kannst jederzeit abbrechen."),
        CHECK_CANCELLED("Check canceled. Your draft is unchanged.", "Prüfung abgebrochen. Dein Entwurf bleibt unverändert."),
        DEBUG_CANCELLED("Debug preparation canceled. Your draft is unchanged.", "Vorbereitung der Fehlersuche abgebrochen. Dein Entwurf bleibt unverändert."),
        NEXT_UNLOCKED("Next challenge unlocked.", "Nächste Aufgabe freigeschaltet."),
        SAVE_INITIAL("Drafts and completed levels are saved on this computer.", "Entwürfe und abgeschlossene Aufgaben werden auf diesem Computer gespeichert."),
        SAVED("Progress and drafts saved locally.", "Fortschritt und Entwürfe lokal gespeichert."),
        RESET_DONE("Game reset. Start again from the first challenge.", "Spiel zurückgesetzt. Beginne wieder mit der ersten Aufgabe."),
        RESET_SAVED("Game reset and saved.", "Spiel zurückgesetzt und gespeichert."),
        NEW("New", "Neu"),
        SHOW_HINT("Show hint", "Hinweis anzeigen"),
        NEXT_HINT("Next hint", "Nächster Hinweis"),
        NEXT_LEVEL("Next level →", "Nächste Aufgabe →"),
        ENTER_WORLD("Enter world %s →", "Welt %s betreten →"),
        STRUCTURED_EDITOR("Structured world: use do/while, if/else, and while. Explicit JNZ is suspended. Code and diagram share one draft.",
                "Strukturierte Welt: Verwende do/while, if/else und while. Direkte JNZ-Anweisungen sind ausgesetzt. Code und Diagramm teilen denselben Entwurf."),
        FLOWCHART_EDITOR("Code and flowchart share one draft. Arrows show execution paths, including JNZ jumps.",
                "Code und Ablaufdiagramm teilen denselben Entwurf. Pfeile zeigen die Ausführungspfade einschließlich der JNZ-Sprünge."),
        EARLY_EDITOR("Before do/while: do is an identifier.", "Vor do/while: do ist ein Bezeichner."),
        STRUCTURED_VERSION("Structured: do is a loop keyword.", "Strukturiert: do ist ein Schleifenschlüsselwort."),
        EMPTY_TOOLBOX("// Solved challenges will add reusable helpers here.\n", "// Gelöste Aufgaben ergänzen hier wiederverwendbare Hilfsmakros.\n"),
        GRAMMAR_NEW("New grammar is available. Open this tab to read the highlighted additions.", "Neue Grammatik ist verfügbar. Öffne diesen Reiter, um die markierten Ergänzungen zu lesen."),
        GRAMMAR_EARNED("The grammar you have unlocked", "Deine freigeschaltete Grammatik"),
        TOOLBOX_NEW("New helpers are available. Open this tab to read them.", "Neue Hilfsmakros sind verfügbar. Öffne diesen Reiter, um sie zu lesen."),
        TOOLBOX_EARNED("The helpers you have earned", "Deine freigeschalteten Hilfsmakros"),
        EXAMPLE("Example", "Beispiel"),
        RESULT_CASE("Case %s: %s Inputs %s; expected %s; actual %s.", "Testfall %s: %s Eingaben %s; erwartet %s; tatsächlich %s."),
        INPUTS("Inputs", "Eingaben"),
        EXPECTED("Expected", "Erwartet"),
        ACTUAL("Actual", "Tatsächlich"),
        STEPS("Steps", "Schritte"),
        RESULT("Result", "Ergebnis"),
        CASE_INPUTS("Case %s: %s", "Testfall %s: %s"),
        TASK_LANGUAGE_HELP("Translate tasks, hints, and status messages; source code and grammar stay in English.",
                "Übersetzt Aufgaben, Hinweise und Statusmeldungen; Quelltext und Grammatik bleiben auf Englisch."),
        RESTORE_QUESTION("Replace this level's draft with its starter? Earned tools stay unlocked.", "Den Entwurf dieser Aufgabe durch die Vorlage ersetzen? Freigeschaltete Werkzeuge bleiben verfügbar."),
        RESET_QUESTION("This clears all completed levels, unlocked features, earned helpers, and every saved draft. You will start again at the first level. This cannot be undone.",
                "Alle abgeschlossenen Aufgaben, freigeschalteten Sprachmittel, erworbenen Hilfsmakros und gespeicherten Entwürfe werden gelöscht. Du beginnst wieder bei der ersten Aufgabe. Dies kann nicht rückgängig gemacht werden."),
        RESET_TITLE("Reset whole game?", "Das ganze Spiel zurücksetzen?"),
        RESET_HEADER("Start the whole game again?", "Das ganze Spiel neu beginnen?"),
        RESET_CONFIRM("Reset game", "Spiel zurücksetzen"),
        CLOSE_UNSAVED("Progress could not be saved. Close anyway?", "Der Fortschritt konnte nicht gespeichert werden. Trotzdem schließen?");

        private final String english;
        private final String german;

        Key(String english, String german) {
            this.english = english;
            this.german = german;
        }
    }

    private GameMessages() { }

    public static String text(GameTaskLanguage language, Key key, Object... arguments) {
        Objects.requireNonNull(language, "language");
        Objects.requireNonNull(key, "key");
        return String.format(Locale.ROOT, language == GameTaskLanguage.GERMAN ? key.german : key.english, arguments);
    }

    public static String featureTitle(GameTaskLanguage language, GameFeature feature) {
        Objects.requireNonNull(language, "language");
        Objects.requireNonNull(feature, "feature");
        if (language != GameTaskLanguage.GERMAN) return feature.title();
        return switch (feature) {
            case BLOCKS -> "Blöcke";
            case EMPTY_STATEMENTS -> "Leere Anweisungen";
            case WHILE -> "while-Schleifen";
            case IF -> "if-Anweisungen";
            case ELSE -> "else-Zweige";
            case COMPARISONS -> "Allgemeine Vergleiche";
            case DO_WHILE -> "do/while-Schleifen";
            case COPYING -> "Initialisierungen mit Variablen";
            case LOCAL_VARIABLES -> "Deklarationen zur Laufzeit";
            case ARRAYS -> "Datenfelder";
            case MACROS -> "Makros";
            case LITERAL_PARAMETERS -> "Literale Makroparameter";
            case IMPORTS -> "Importe";
        };
    }

    /** Shared diagnostic translation for checker results and diagram validation. */
    public static String diagnostic(GameTaskLanguage language, String rawEnglish) {
        return GameDiagnostics.translate(language, rawEnglish);
    }
}

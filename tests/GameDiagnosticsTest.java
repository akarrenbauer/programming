package machine;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Diagnostics follow task language while program names, values and execution remain unchanged. */
public final class GameDiagnosticsTest {
    private static final GameTaskLanguage DE = GameTaskLanguage.GERMAN;
    private static final GameTaskLanguage EN = GameTaskLanguage.ENGLISH;
    private static int passed;
    private static int failed;
    @FunctionalInterface private interface Check { void run() throws Exception; }

    public static void main(String[] args) {
        check("English diagnostics remain byte-for-byte unchanged", () -> {
            for (String raw : List.of("", "Passed all 0 test cases.", "Unknown variable expected.",
                    "Macro substitution failed!\nhelper(expected, actual)", "A platform error: /tmp/Inputs/actual.file")) {
                expect(raw.equals(GameDiagnostics.translate(EN, raw)), "English identity for " + raw);
            }
            expect(GameDiagnostics.translate(EN, null).equals("Unknown error."), "null English exception message");
            expect(GameDiagnostics.translate(DE, null).equals("Unbekannter Fehler."), "null German exception message");
            expect(de("Example").equals("Beispiel"), "initial table row");
        });

        check("all earned feature names use the same German titles as the status messages", () -> {
            for (GameFeature feature : GameFeature.values()) {
                String title = GameMessages.featureTitle(DE, feature);
                expect(de("This level asks you to use " + feature.title() + " in your solution.")
                                .equals("Verwende in deiner Lösung für diese Aufgabe: " + title + "."),
                        "required feature " + feature);
                expect(de(feature.title() + " is still locked for this submission.")
                                .equals(title + " sind für diese Lösung noch nicht freigeschaltet."),
                        "locked feature " + feature);
            }
        });

        check("unknown-variable and parser diagnostics preserve identifiers and line numbers", () -> {
            expect(de("Unknown variable expected.").equals("Unbekannte Variable expected."), "identifier named expected");
            expect(de("Unknown variable Inputs in line 12").equals("Unbekannte Variable Inputs in Zeile 12."), "compiler name and line");
            expect(de("Expected }, found HALT. (line 7)").equals("Erwartet: }; gefunden: HALT. (Zeile 7)"), "diagram line suffix");
            expect(de("Expected an identifier, found actual.").equals("Bezeichner erwartet; gefunden: actual."), "reserved presentation word as code");
            expect(de("Unsupported source character: @").equals("Nicht unterstütztes Zeichen: @"), "unsupported character");
        });

        check("case summaries translate prose while retaining input expected and actual maps exactly", () -> {
            String raw = "Case 2: The program halted with different output values. Inputs {Inputs=0, expected=3}; expected {actual=4}; actual {actual=2}.";
            expect(de(raw).equals("Testfall 2: Das Programm wurde mit abweichenden Ausgabewerten angehalten. Eingaben {Inputs=0, expected=3}; erwartet {actual=4}; erhalten {actual=2}."),
                    "only template prose changes");
            String limited = "Case 1: Execution reached the step limit. Inputs {n=0}; expected {count=0}, actual {count=1}.";
            expect(de(limited).equals("Testfall 1: Die Ausführung hat das Schrittlimit erreicht. Eingaben {n=0}; erwartet {count=0}; erhalten {count=1}."),
                    "runtime limit summary");
            GameLevel first = level("first-steps");
            GameResult actual = GameEngine.check(first, first.starter(), before(first), Map.of(), () -> false, count -> {});
            expect(!actual.passed() && de(actual.summary()).startsWith("Testfall 1:"), "real engine separator format");
            expect(de(actual.summary()).contains(actual.cases().get(0).inputs().toString())
                            && de(actual.summary()).contains(actual.cases().get(0).expected().toString()), "real result maps preserved");
        });

        check("zero singular and plural result counts are translated", () -> {
            expect(de("Passed all 0 test cases.").equals("Alle 0 Testfälle bestanden."), "zero count");
            expect(de("Passed all 1 test cases.").equals("1 Testfall bestanden."), "singular count");
            expect(de("Passed all 6 test cases.").equals("Alle 6 Testfälle bestanden."), "plural count");
            expect(de("Passed.").equals("Bestanden."), "per-case success");
            expect(de("Check cancelled.").equals("Prüfung abgebrochen."), "cancellation");
        });

        check("actual while-practice failures provide a complete German rewrite instruction", () -> {
            GameLevel level = level("while-practice");
            String misplaced = level.starter().replace("begin:\n", "begin:\nwhile(n!=0) {}\n");
            GameResult result = GameEngine.check(level, misplaced, before(level), Map.of(), () -> false, count -> {});
            expect(!result.passed(), "working old starter still requires the rewrite");
            String translated = de(result.summary());
            expect(translated.startsWith("Ersetze das äußere if und die do/while-Schleife"), "actual diagnostic is translated");
            expect(translated.contains("while (n != 0) { DEC n; INC count; }") && translated.contains("HALT;"), "source syntax stays exact");
            expect(!translated.contains("Replace") && !translated.contains("followed") && !translated.contains("Technische Meldung"),
                    "ordinary exercise guidance has no English fallback");
        });

        check("other rewrite exercises translate their exact emitted instructions", () -> {
            for (String id : List.of("clear-block", "clear-do-while", "else-practice", "comparison-practice")) {
                GameLevel level = level(id);
                String source = id.equals("clear-do-while") ? "var dummy=0; var x=0; begin: INC dummy; HALT;" : level.starter();
                if (id.equals("else-practice")) source = source.replace("begin:\n", "begin:\nif(n==0) {} else {}\n");
                if (id.equals("comparison-practice")) source = source.replace("begin:\n", "begin:\nif(n==3) {}\n");
                GameResult result = GameEngine.check(level, source, before(level), Map.of(), () -> false, count -> {});
                expect(!result.passed(), "unfinished rewrite " + id);
                expect(!de(result.summary()).startsWith("Technische Meldung:"), "exact emitted guidance translated for " + id + ": " + result.summary());
                expect(!de(result.summary()).equals(result.summary()), "German guidance differs for " + id);
            }
        });

        check("diagram semantic and compiler failures use German context without translating source", () -> {
            DiagramValidation.Result names = DiagramValidation.validate("var x=0; begin: INC missing; HALT;", EnumSet.allOf(GameFeature.class), Map.of());
            expect(de(names.message()).equals("Unbekannte Variable missing."), "actual semantic error");
            DiagramValidation.Result macro = DiagramValidation.validate("var x=0; begin: unknown(x); HALT;", EnumSet.allOf(GameFeature.class), Map.of());
            expect(de(macro.message()).startsWith("Die Makroersetzung ist fehlgeschlagen."), "actual compiler error translated");
            expect(de(macro.message()).contains("unknown(x)"), "compiler source excerpt stays unchanged");
            expect(de("Edit not applied: Unknown variable actual.").equals("Änderung nicht übernommen: Unbekannte Variable actual."), "nested diagram rejection");
            expect(de("Choose a JNZ arrow to retarget.").equals("Wähle einen JNZ-Pfeil aus, dessen Ziel du ändern möchtest."), "flowchart arrow selection");
            expect(de("Edit not applied: Choose an editable statement entry as the jump target.")
                    .equals("Änderung nicht übernommen: Wähle den Eingang einer bearbeitbaren Anweisung als Sprungziel aus."), "flowchart destination rejection");
            expect(de("Only do/while conditions of the form variable != 0 can be retargeted as JNZ.")
                    .equals("Nur bei do/while-Bedingungen der Form variable != 0 lässt sich das Sprungziel als JNZ ändern."), "structured loop destination constraint");
            expect(de("Toolbox library is not available: /tmp/expected/Inputs.a26").equals("Die Werkzeugbibliothek ist nicht verfügbar: /tmp/expected/Inputs.a26"),
                    "paths are not word-translated");
        });

        check("normal limits declarations and execution failures are translated without fallback", () -> {
            for (String raw : List.of("Source text exceeds the game's resource limit.", "Conditions use == or !=.",
                    "Global variable expected is declared more than once.", "Array index is outside actual.",
                    "Declare input count as a scalar global with a numeric initializer.", "Declare global output count.",
                    "Define helper with 24 parameters in your own source for this level.",
                    "Call helper with 24 parameters from the main program for this level.",
                    "Too many macro arguments for a game check (maximum 32).", "Game arrays need 1 to 256 cells.",
                    "Runtime initializers are limited to 4096 in game checks.", "Use a nonnegative integer no larger than 2147483647.",
                    "This program has too many blocks to display at once (maximum 1000).", "The diagram can display at most 1000 blocks.",
                    "Execution would jump or fall outside the compiled instructions; end with HALT.",
                    "Arithmetic would overflow the nonnegative integer range.", "An instruction would write outside the data cells (or change code/the instruction pointer).")) {
                expect(!de(raw).startsWith("Technische Meldung:") && !de(raw).equals(raw), "normal diagnostic: " + raw);
            }
        });

        check("unknown low-level exceptions are preserved as a clearly labeled technical excerpt", () -> {
            String raw = "AccessDeniedException: /tmp/Inputs/expected.a26";
            expect(de(raw).equals("Technische Meldung: „" + raw + "“"), "unknown details remain exact");
            expect(de("Could not check this program: " + raw).equals("Das Programm konnte nicht geprüft werden: Technische Meldung: „" + raw + "“"),
                    "outer gameplay context is German");
        });

        System.out.println("GameDiagnosticsTest: " + passed + " passed, " + failed + " failed");
        if (failed != 0) throw new AssertionError(failed + " diagnostic translation checks failed");
    }

    private static String de(String text) { return GameDiagnostics.translate(DE, text); }
    private static GameLevel level(String id) { return GameCatalog.levels().stream().filter(level -> level.id().equals(id)).findFirst().orElseThrow(); }
    private static Set<GameFeature> before(GameLevel target) {
        Set<GameFeature> earned = EnumSet.noneOf(GameFeature.class);
        for (GameLevel level : GameCatalog.levels()) { if (level.id().equals(target.id())) break; earned.addAll(level.unlocks()); }
        return earned;
    }
    private static void check(String name, Check action) {
        try { action.run(); ++passed; System.out.println("PASS " + name); }
        catch (Exception | AssertionError error) { ++failed; System.err.println("FAIL " + name + ": " + error); error.printStackTrace(); }
    }
    private static void expect(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}

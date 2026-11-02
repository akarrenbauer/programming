package machine;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Presentation-only translations of game diagnostics; source tokens and values are never rewritten. */
public final class GameDiagnostics {
    private static final Map<String, String> GERMAN = messages();
    private static final Map<String, String> FEATURES = featureTitles();

    private GameDiagnostics() { }

    public static String translate(GameTaskLanguage language, String diagnostic) {
        Objects.requireNonNull(language, "language");
        if (diagnostic == null) return language == GameTaskLanguage.GERMAN ? "Unbekannter Fehler." : "Unknown error.";
        if (language != GameTaskLanguage.GERMAN || diagnostic.isEmpty()) return diagnostic;
        String exact = GERMAN.get(diagnostic);
        if (exact != null) return exact;
        Matcher match;
        if ((match = match("(.+) \\(line ([0-9]+)\\)", diagnostic)) != null) {
            return translate(language, match.group(1)) + " (Zeile " + match.group(2) + ")";
        }
        if ((match = match("Case ([0-9]+): (.+) Inputs (.+); expected (.+)[;,] actual (.+)\\.", diagnostic)) != null) {
            return "Testfall " + match.group(1) + ": " + translate(language, match.group(2))
                    + " Eingaben " + match.group(3) + "; erwartet " + match.group(4) + "; erhalten " + match.group(5) + ".";
        }
        if ((match = match("Passed all ([0-9]+) test cases\\.", diagnostic)) != null) {
            return match.group(1).equals("1") ? "1 Testfall bestanden." : "Alle " + match.group(1) + " Testfälle bestanden.";
        }
        if ((match = match("This level asks you to use (.+) in your solution\\.", diagnostic)) != null && FEATURES.containsKey(match.group(1))) {
            return "Verwende in deiner Lösung für diese Aufgabe: " + FEATURES.get(match.group(1)) + ".";
        }
        if ((match = match("(.+) is still locked for this submission\\.", diagnostic)) != null && FEATURES.containsKey(match.group(1))) {
            return FEATURES.get(match.group(1)) + " sind für diese Lösung noch nicht freigeschaltet.";
        }
        if ((match = match("Unknown variable ([A-Za-z_][A-Za-z_0-9]*)(?: in line ([0-9]+))?\\.?", diagnostic)) != null) {
            return "Unbekannte Variable " + match.group(1) + (match.group(2) == null ? "." : " in Zeile " + match.group(2) + ".");
        }
        if ((match = match("Expected an identifier, found (.+)\\.", diagnostic)) != null) {
            return "Bezeichner erwartet; gefunden: " + match.group(1) + ".";
        }
        if ((match = match("Expected (.+), found (.+)\\.", diagnostic)) != null) {
            return "Erwartet: " + match.group(1) + "; gefunden: " + match.group(2) + ".";
        }
        if ((match = match("After begin:, use one if comparing n == ([0-9]+) or n != ([0-9]+), (with an else branch|optionally with an else branch), followed directly by HALT;\\. Put all work inside the branches, with no other top-level statements\\.", diagnostic)) != null) {
            return "Verwende nach begin: genau ein if mit n == " + match.group(1) + " oder n != " + match.group(2)
                    + (match.group(3).startsWith("optionally") ? ", wahlweise mit einem else-Zweig" : ", mit einem else-Zweig")
                    + ", unmittelbar gefolgt von HALT;. Alle Arbeit gehört in die Zweige; weitere Anweisungen außerhalb sind nicht erlaubt.";
        }
        if ((match = match("(Define|Call) ([A-Za-z_][A-Za-z_0-9]*) with ([0-9]+) parameters (in your own source|from the main program) for this level\\.", diagnostic)) != null) {
            return match.group(1).equals("Define")
                    ? "Definiere für diese Aufgabe " + match.group(2) + " mit " + match.group(3) + " Parametern in deinem eigenen Quelltext."
                    : "Rufe für diese Aufgabe " + match.group(2) + " mit " + match.group(3) + " Parametern aus dem Hauptprogramm auf.";
        }
        if ((match = match("Declare input (.+) as a scalar global with a numeric initializer\\.", diagnostic)) != null) {
            return "Deklariere die Eingabe " + match.group(1) + " als einzelne globale Variable mit einem Zahlenliteral als Anfangswert.";
        }
        if ((match = match("Input (.+) must be a scalar global with a numeric initializer\\.", diagnostic)) != null) {
            return "Die Eingabe " + match.group(1) + " muss eine einzelne globale Variable mit einem Zahlenliteral als Anfangswert sein.";
        }
        if ((match = match("Declare global output (.+)\\.", diagnostic)) != null) return "Deklariere die globale Ausgabe " + match.group(1) + ".";
        if ((match = match("Invalid output port (.+)\\.", diagnostic)) != null) return "Ungültige Ausgabevariable " + match.group(1) + ".";
        if ((match = match("Output port (.+) is outside the declared array\\.", diagnostic)) != null) {
            return "Die Ausgabe " + match.group(1) + " liegt außerhalb des deklarierten Arrays.";
        }
        if ((match = match("Array index is outside (.+)\\.", diagnostic)) != null) return "Der Arrayindex liegt außerhalb von " + match.group(1) + ".";
        if ((match = match("Global variable (.+) is declared more than once\\.", diagnostic)) != null) {
            return "Die globale Variable " + match.group(1) + " wurde mehrfach deklariert.";
        }
        if ((match = match("Local variable (.+) redeclares an existing variable or test port\\.", diagnostic)) != null) {
            return "Die lokale Variable " + match.group(1) + " verwendet den Namen einer bereits vorhandenen Variablen oder Testein-/ausgabe.";
        }
        if ((match = match("Use a nonnegative integer no larger than ([0-9]+)\\.", diagnostic)) != null) {
            return "Verwende eine nichtnegative ganze Zahl bis höchstens " + match.group(1) + ".";
        }
        if ((match = match("Game arrays need 1 to ([0-9]+) cells\\.", diagnostic)) != null) {
            return "Arrays im Spiel benötigen 1 bis " + match.group(1) + " Zellen.";
        }
        if ((match = match("(Runtime initializers|Numeric macro arguments) are limited to ([0-9]+) in game checks\\.", diagnostic)) != null) {
            return (match.group(1).equals("Runtime initializers") ? "Anfangswerte zur Laufzeit" : "Numerische Makroargumente")
                    + " sind bei Prüfungen im Spiel auf " + match.group(2) + " begrenzt.";
        }
        if ((match = match("Too many macro (parameters|arguments) for a game check \\(maximum ([0-9]+)\\)\\.", diagnostic)) != null) {
            return "Zu viele Makro" + (match.group(1).equals("parameters") ? "parameter" : "argumente")
                    + " für eine Prüfung im Spiel (höchstens " + match.group(2) + ").";
        }
        if ((match = match("This program has too many blocks to display at once \\(maximum ([0-9]+)\\)\\.", diagnostic)) != null
                || (match = match("Too many blocks to display \\(maximum ([0-9]+)\\)\\.", diagnostic)) != null) {
            return "Das Programm enthält zu viele Blöcke für die Anzeige (höchstens " + match.group(1) + ").";
        }
        if ((match = match("The diagram can display at most ([0-9]+) blocks\\.", diagnostic)) != null) {
            return "Das Diagramm kann höchstens " + match.group(1) + " Blöcke anzeigen.";
        }
        if ((match = match("Toolbox library is not available: (.+)", diagnostic)) != null) {
            return "Die Werkzeugbibliothek ist nicht verfügbar: " + match.group(1);
        }
        if ((match = match("Unsupported (?:source )?character: (.+)", diagnostic)) != null) {
            return "Nicht unterstütztes Zeichen: " + match.group(1);
        }
        if ((match = match("Unknown test case ([0-9]+)", diagnostic)) != null) return "Unbekannter Testfall " + match.group(1);
        if ((match = match("Macro substitution failed!\\s*(.*)", diagnostic)) != null) {
            return "Die Makroersetzung ist fehlgeschlagen. Prüfe den Namen, die Parameterzahl und passende Spezialisierungen."
                    + (match.group(1).isEmpty() ? "" : " Quelltext: „" + match.group(1) + "“");
        }
        if ((match = match("Unresolved labels: (.+)", diagnostic)) != null) return "Nicht aufgelöste Sprungmarken: " + match.group(1);
        for (String prefix : new String[] { "Could not check this program: ", "Could not prepare debugging: ", "Edit not applied: " }) {
            if (diagnostic.startsWith(prefix)) {
                String german = switch (prefix) {
                    case "Could not check this program: " -> "Das Programm konnte nicht geprüft werden: ";
                    case "Could not prepare debugging: " -> "Die Fehlersuche konnte nicht vorbereitet werden: ";
                    default -> "Änderung nicht übernommen: ";
                };
                return german + translate(language, diagnostic.substring(prefix.length()));
            }
        }
        // Unrecognized platform/compiler details remain verbatim, clearly identified
        // as a technical excerpt. Never translate identifiers or paths by word replacement.
        return "Technische Meldung: „" + diagnostic + "“";
    }

    private static Matcher match(String pattern, String value) {
        Matcher result = Pattern.compile(pattern, Pattern.DOTALL).matcher(value);
        return result.matches() ? result : null;
    }

    private static Map<String, String> featureTitles() {
        Map<String, String> result = new LinkedHashMap<>();
        for (GameFeature feature : GameFeature.values()) {
            result.put(feature.title(), GameMessages.featureTitle(GameTaskLanguage.GERMAN, feature));
        }
        return Map.copyOf(result);
    }

    private static Map<String, String> messages() {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put("Passed.", "Bestanden.");
        entries.put("Example", "Beispiel");
        entries.put("Unknown error.", "Unbekannter Fehler.");
        entries.put("The program halted with different output values.", "Das Programm wurde mit abweichenden Ausgabewerten angehalten.");
        entries.put("Check cancelled.", "Prüfung abgebrochen.");
        entries.put("This level requires tools that are still locked.", "Diese Aufgabe benötigt Werkzeuge, die noch nicht freigeschaltet sind.");
        entries.put("Unlock do/while, if/else, and while before entering this world.", "Schalte do/while, if/else und while frei, bevor du diese Welt betrittst.");
        entries.put("This level exceeds the game's checking resource limits.", "Diese Aufgabe überschreitet die Ressourcenlimits für Prüfungen im Spiel.");
        entries.put("Source text exceeds the game's resource limit.", "Der Quelltext überschreitet das Ressourcenlimit des Spiels.");
        entries.put("Identifier exceeds the game's resource limit.", "Der Bezeichner überschreitet das Ressourcenlimit des Spiels.");
        entries.put("Identifier exceeds the game's size limit.", "Der Bezeichner überschreitet die Größenbegrenzung des Spiels.");
        entries.put("Compiled program exceeds the game's memory limit.", "Das kompilierte Programm überschreitet das Speicherlimit des Spiels.");
        entries.put("An output port lies outside the compiled data segment.", "Eine Ausgabevariable liegt außerhalb des kompilierten Datenbereichs.");
        entries.put("Test inputs must be nonnegative integers.", "Testeingaben müssen nichtnegative ganze Zahlen sein.");
        entries.put("Recursive macro expansion exceeds the game's resource limits.", "Die rekursive Makroerweiterung überschreitet die Ressourcenlimits des Spiels.");
        entries.put("Macro expansion exceeds the game's resource limit.", "Die Makroerweiterung überschreitet das Ressourcenlimit des Spiels.");
        entries.put("This program would generate too much code for a game check.", "Dieses Programm würde zu viel Code für eine Prüfung im Spiel erzeugen.");
        entries.put("Expanded macro blocks are nested too deeply for a game check.", "Erweiterte Makroblöcke sind für eine Prüfung im Spiel zu tief verschachtelt.");
        entries.put("Blocks are nested too deeply for a game check.", "Blöcke sind für eine Prüfung im Spiel zu tief verschachtelt.");
        entries.put("Too many tokens for a game check.", "Zu viele Sprachelemente für eine Prüfung im Spiel.");
        entries.put("Close the quoted import path on the same line.", "Schließe den Importpfad in derselben Zeile mit einem Anführungszeichen ab.");
        entries.put("Close the quoted import path.", "Schließe den Importpfad mit einem Anführungszeichen ab.");
        entries.put("Use != for a comparison.", "Verwende != für einen Vergleich.");
        entries.put("Conditions use == or !=.", "Bedingungen verwenden == oder !=.");
        entries.put("do is a keyword in the structured language version; choose an ordinary identifier.", "do ist in der strukturierten Sprachversion ein Schlüsselwort; wähle einen gewöhnlichen Bezeichner.");
        entries.put("Expected a variable, label, or macro name.", "Der Name einer Variablen, Sprungmarke oder eines Makros wird erwartet.");
        entries.put("Expected a numeric literal.", "Ein Zahlenliteral wird erwartet.");
        entries.put("Expected a nonnegative number.", "Eine nichtnegative Zahl wird erwartet.");
        entries.put("Import needs a quoted toolbox filename.", "import benötigt einen Dateinamen der Werkzeugbibliothek in Anführungszeichen.");
        entries.put("Use a quoted import path.", "Verwende einen Importpfad in Anführungszeichen.");
        entries.put("Put each import on its own line, ending with \"; and no trailing comment.", "Schreibe jeden Import in eine eigene Zeile, abgeschlossen mit \"; und ohne nachfolgenden Kommentar.");
        entries.put("Toolbox source exceeds the game's resource limit.", "Der Quelltext der Werkzeugbibliothek überschreitet das Ressourcenlimit des Spiels.");
        entries.put("Toolbox libraries may contain macro definitions only; nested imports are not supported.", "Werkzeugbibliotheken dürfen nur Makrodefinitionen enthalten; verschachtelte Importe werden nicht unterstützt.");
        entries.put("Too many macro definitions for a game check.", "Zu viele Makrodefinitionen für eine Prüfung im Spiel.");
        entries.put("Macro parameter names must be unique.", "Die Namen der Makroparameter müssen eindeutig sein.");
        entries.put("Too many declared cells for a game check.", "Zu viele deklarierte Zellen für eine Prüfung im Spiel.");
        entries.put("Global initializers may copy only earlier globals.", "Globale Anfangswerte dürfen nur zuvor deklarierte globale Variablen kopieren.");
        entries.put("Array initializer index is out of bounds.", "Der Arrayindex im Anfangswert liegt außerhalb des gültigen Bereichs.");
        entries.put("Explicit JNZ is suspended in this world. Use if/else, while, or do/while; the compiler still generates jumps internally.", "Die direkte Verwendung von JNZ ist in dieser Welt ausgesetzt. Verwende if/else, while oder do/while; der Compiler erzeugt die Sprünge weiterhin intern.");
        entries.put("do/while requires the structured language version.", "do/while benötigt die strukturierte Sprachversion.");
        entries.put("Ready to step.", "Bereit für den nächsten Schritt.");
        entries.put("HALT reached.", "HALT erreicht.");
        entries.put("Execution reached the step limit.", "Die Ausführung hat das Schrittlimit erreicht.");
        entries.put("An instruction would write outside the data cells (or change code/the instruction pointer).", "Eine Anweisung würde außerhalb der Datenzellen schreiben oder Code beziehungsweise den Befehlszeiger ändern.");
        entries.put("Arithmetic would overflow the nonnegative integer range.", "Die Rechnung würde den Bereich nichtnegativer ganzer Zahlen überschreiten.");
        entries.put("Execution encountered an invalid instruction.", "Die Ausführung ist auf eine ungültige Anweisung gestoßen.");
        entries.put("Execution would jump or fall outside the compiled instructions; end with HALT.", "Die Ausführung würde den Bereich der kompilierten Anweisungen verlassen; beende das Programm mit HALT.");
        entries.put("Execution left the compiled instruction addresses; end with HALT.", "Die Ausführung hat die Adressen der kompilierten Anweisungen verlassen; beende das Programm mit HALT.");
        entries.put("A positive instruction limit is required", "Eine positive Anweisungsgrenze ist erforderlich.");
        entries.put("Code and diagram are synchronized.", "Code und Diagramm sind synchronisiert.");
        entries.put("The diagram follows the code.", "Das Diagramm folgt dem Code.");
        entries.put("Edit pending — press Enter to apply.", "Änderung noch offen — mit Enter übernehmen.");
        entries.put("Source exceeds the diagram editor's size limit.", "Der Quelltext überschreitet die Größenbegrenzung des Diagrammeditors.");
        entries.put("Diagram blocks are nested too deeply.", "Die Diagrammblöcke sind zu tief verschachtelt.");
        entries.put("Too many tokens for the diagram editor.", "Zu viele Sprachelemente für den Diagrammeditor.");
        entries.put("Use a nonnegative 32-bit integer with at most ten digits.", "Verwende eine nichtnegative 32-Bit-Ganzzahl mit höchstens zehn Ziffern.");
        entries.put("JNZ is suspended here; use if/else, while or do/while for the diagram.", "JNZ ist hier ausgesetzt; verwende if/else, while oder do/while für das Diagramm.");
        entries.put("Use a sequence from the current diagram.", "Verwende eine Folge aus dem aktuellen Diagramm.");
        entries.put("Use a node from the current diagram.", "Verwende einen Block aus dem aktuellen Diagramm.");
        entries.put("Choose a JNZ arrow to retarget.", "Wähle einen JNZ-Pfeil aus, dessen Ziel du ändern möchtest.");
        entries.put("Choose an editable statement entry as the jump target.", "Wähle den Eingang einer bearbeitbaren Anweisung als Sprungziel aus.");
        entries.put("Update the jump target to match its label before moving the arrow.", "Passe das Sprungziel an die Sprungmarke an, bevor du den Pfeil verschiebst.");
        entries.put("Only do/while conditions of the form variable != 0 can be retargeted as JNZ.", "Nur bei do/while-Bedingungen der Form variable != 0 lässt sich das Sprungziel als JNZ ändern.");
        entries.put("Select an if block without an else branch.", "Wähle einen if-Block ohne else-Zweig aus.");
        entries.put("Empty statements are still locked.", "Leere Anweisungen sind noch nicht freigeschaltet.");
        entries.put("Else branches are still locked.", "else-Zweige sind noch nicht freigeschaltet.");
        entries.put("Declare a condition variable in the code before inserting a control structure.", "Deklariere im Code eine Variable für die Bedingung, bevor du eine Kontrollstruktur einfügst.");
        entries.put("Group the clearing loop as do: { DEC x; } JNZ x do;, keeping the jump outside the block. Add a standalone ; placeholder immediately after INC dummy; and before the block. Keep HALT; after the jump and preserve the instruction order.",
                "Gruppiere die Löschschleife als do: { DEC x; } JNZ x do;, sodass der Sprung außerhalb des Blocks bleibt. Füge unmittelbar nach INC dummy; und vor dem Block eine einzelne leere Anweisung ; ein. Lasse HALT; nach dem Sprung stehen und behalte die Reihenfolge der Anweisungen bei.");
        entries.put("Rewrite the clearing loop as do { DEC x; } while (x != 0);, keeping the standalone ; placeholder immediately after INC dummy; before the loop, and HALT; after it. Replace the label and jump rather than adding another loop.",
                "Schreibe die Löschschleife als do { DEC x; } while (x != 0); um. Die einzelne leere Anweisung ; bleibt unmittelbar nach INC dummy; vor der Schleife; HALT; bleibt dahinter. Ersetze die Sprungmarke und den Sprung, statt eine weitere Schleife hinzuzufügen.");
        entries.put("Replace the outer if and do/while with while (n != 0) { DEC n; INC count; }, followed directly by HALT;. Keep DEC n before INC count inside the loop, with no other main-program statements. Rewrite the original loop rather than adding another one.",
                "Ersetze das äußere if und die do/while-Schleife durch while (n != 0) { DEC n; INC count; }, unmittelbar gefolgt von HALT;. DEC n steht innerhalb der Schleife vor INC count; weitere Anweisungen im Hauptprogramm sind nicht erlaubt. Schreibe die ursprüngliche Schleife um, statt eine weitere hinzuzufügen.");
        return Map.copyOf(entries);
    }
}

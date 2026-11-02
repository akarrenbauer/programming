package machine;

import java.util.Map;
import java.util.Objects;

/** Short task objectives; complete instructions and contracts remain in Task details. */
public final class GameTaskSummaries {
    private record Summary(String english, String german) { }
    private static final Map<String, Summary> SUMMARIES = Map.ofEntries(
            entry("first-steps", "Increase x by one and halt.",
                    "Erhöhe x um eins und halte an."),
            entry("clear-block", "Add ; after INC dummy and braces around only DEC x; keep the instruction order and trailing JNZ.",
                    "Füge ; nach INC dummy und Klammern nur um DEC x ein; behalte die Reihenfolge und den nachfolgenden JNZ bei."),
            entry("clear-do-while", "Replace the labeled clearing loop and its jump with do/while, keeping the setup, loop body and final HALT.",
                    "Ersetze die markierte Löschschleife samt Sprung durch do/while; behalte Vorbereitung, Schleifenrumpf und HALT bei."),
            entry("mandatory-lap", "Use do/while to reduce n to zero and count max(1, original n) rounds, including one round when n starts at zero.",
                    "Verbrauche n mit do/while und zähle max(1, ursprüngliches n) Runden; auch n = 0 muss eine Runde durchlaufen."),
            entry("zero-test", "Keep n unchanged and set zero to 1 if n is zero, or 0 otherwise.",
                    "Lass n unverändert und setze zero bei n = 0 auf 1, sonst auf 0."),
            entry("two-paths", "Use if without changing n: result must be 2 for zero and 1 for every nonzero input.",
                    "Verwende if und lass n unverändert: result soll bei n = 0 den Wert 2 erhalten, sonst 1."),
            entry("else-practice", "Replace the starter with one if/else and HALT: preserve n; result is 1 for zero and 2 otherwise.",
                    "Ersetze den Startcode durch ein if/else und HALT: n bleibt erhalten; result wird bei n = 0 zu 1, sonst zu 2."),
            entry("countdown", "Guard a do/while loop with if to consume n and count its original value, allowing zero iterations.",
                    "Sichere do/while mit if ab, verbrauche n und zähle seinen Anfangswert; bei n = 0 darf keine Runde laufen."),
            entry("while-practice", "Replace the guarded do/while with while (n != 0) { DEC n; INC count; }, then HALT.",
                    "Ersetze das abgesicherte do/while durch while (n != 0) { DEC n; INC count; } und anschließend HALT."),
            entry("equals-three", "Use while and zero tests to set equal to 1 only for n = 3; restore n and leave equal at 0 for other inputs.",
                    "Setze equal mit while und Nulltests nur bei n = 3 auf 1, sonst auf 0; stelle n wieder her."),
            entry("comparison-practice", "Replace the counting loops with one if comparing n with 3, then HALT; preserve n and the equality result.",
                    "Ersetze das Zählen durch ein if mit Vergleich von n und 3 sowie HALT; n und das Gleichheitsergebnis bleiben erhalten."),
            entry("preserve-copy", "Keep n unchanged and copy its value into copy.",
                    "Kopiere den Wert von n nach copy, ohne n zu verändern."),
            entry("reset-scratch", "Initialize result from n, then clear n and scratch without changing the copied result.",
                    "Initialisiere result mit n und lösche danach n und scratch, ohne die Kopie zu verändern."),
            entry("three-cells", "Use runtime declarations to copy b, c and a into first, second and third while preserving all three inputs.",
                    "Kopiere b, c und a mit Laufzeitdeklarationen nach first, second und third; alle drei Eingaben bleiben erhalten."),
            entry("array-practice", "Keep n unchanged and fill a three-element cells array with n, n+1 and n+2.",
                    "Fülle das Array cells mit drei Elementen n, n+1 und n+2; n bleibt unverändert."),
            entry("repeated-work", "Double a into left and b into right without consuming either input.",
                    "Verdopple a nach left und b nach right, ohne eine der Eingaben zu verbrauchen."),
            entry("macro-practice", "Define and call a macro to produce result = n+2 while keeping n unchanged.",
                    "Definiere und verwende ein Makro für result = n+2; lass n unverändert."),
            entry("literal-dispatch", "Use a macro to set result = mode+1 for mode 0 or 1, preserving mode.",
                    "Berechne result = mode+1 mit einem Makro für mode 0 oder 1; mode bleibt erhalten."),
            entry("specialization-practice", "Use literal-parameter macros to produce first = n+1 and second = n+2 without changing n.",
                    "Erzeuge first = n+1 und second = n+2 mit Makros mit Literalparametern; n bleibt erhalten."),
            entry("compose-helpers", "Combine macros to produce result = n+3, preserving n.",
                    "Kombiniere Makros für result = n+3, ohne n zu verändern."),
            entry("import-practice", "Import helpers to compute result = 2*n+1 without changing n.",
                    "Importiere Hilfsmakros für result = 2*n+1, ohne n zu verändern."),
            entry("shift-macro", "Define shift(a,b) to transfer all of a into b, adding to b's old value and leaving a at zero.",
                    "Definiere shift(a,b): Übertrage a zusätzlich zum bisherigen Wert nach b und lasse a auf null zurück."),
            entry("move-macro", "Define move(a,b) to replace b with a's original value and leave a at zero.",
                    "Definiere move(a,b): Ersetze b durch den Anfangswert von a und lasse a auf null zurück."),
            entry("preserving-add", "Add a and b into result without changing either input.",
                    "Addiere a und b nach result, ohne die Eingaben zu verändern."),
            entry("saturating-subtract", "Subtract b from a into result, stopping at zero and preserving both inputs.",
                    "Berechne a minus b in result, mindestens null; beide Eingaben bleiben erhalten."),
            entry("multiply", "Multiply a by b into result while preserving both factors.",
                    "Multipliziere a mit b nach result und erhalte beide Faktoren."),
            entry("divmod", "Preserve numerator and positive divisor; compute their integer quotient and remainder smaller than divisor.",
                    "Berechne Quotient und Rest für numerator und divisor > 0; erhalte beide Eingaben und halte den Rest kleiner als divisor."),
            entry("gcd", "Find the greatest common divisor of a and b without changing them, with gcd(0,0) = 0.",
                    "Bestimme den größten gemeinsamen Teiler von a und b, ohne sie zu verändern; gcd(0,0) ist 0."),
            entry("normalize-four", "Reduce n modulo 16 into word to obtain a four-bit encoding; preserve n.",
                    "Bilde aus n die Vier-Bit-Kodierung word = n mod 16; erhalte n."),
            entry("decompose-four", "Split word into bits[0] through bits[3], lowest bit first, while preserving word.",
                    "Zerlege word in bits[0] bis bits[3], beginnend mit dem niedrigsten Bit; word bleibt erhalten."),
            entry("decode-four", "Decode a four-bit word into negative and magnitude without changing word.",
                    "Dekodiere das Vier-Bit-Wort word in negative und magnitude, ohne word zu verändern."),
            entry("encode-four", "Encode negative and magnitude as a signed four-bit word, preserving both inputs and mapping either signed zero to 0.",
                    "Kodiere negative und magnitude als Vier-Bit-Wort; erhalte beide Eingaben und bilde jede vorzeichenbehaftete Null auf 0 ab."),
            entry("not-four", "Invert all four bits of word into result, preserving word.",
                    "Invertiere alle vier Bits von word nach result; word bleibt erhalten."),
            entry("negate-four", "Write the four-bit two's-complement negation of word into result, preserving word.",
                    "Schreibe das Vier-Bit-Zweierkomplement von word nach result; word bleibt erhalten."),
            entry("add-four", "Preserve a and b; add their four-bit words modulo 16 and flag signed overflow outside -8..7.",
                    "Addiere die Vier-Bit-Wörter a und b modulo 16 und melde vorzeichenbehafteten Überlauf außerhalb -8..7; erhalte a und b."),
            entry("subtract-four", "Preserve a and b; subtract their four-bit words modulo 16 and flag signed overflow outside -8..7.",
                    "Subtrahiere b von a modulo 16 und melde vorzeichenbehafteten Überlauf außerhalb -8..7; beide Vier-Bit-Eingaben bleiben erhalten."),
            entry("extend-four", "Extend word from four to eight bits in wide, preserving its signed value and the original word.",
                    "Erweitere word von vier auf acht Bits nach wide; der vorzeichenbehaftete Wert und word bleiben erhalten."),
            entry("transfer-eight", "Preserve a and b; add their bytes modulo 256 into word, report sign and magnitude, and flag signed overflow.",
                    "Addiere a und b modulo 256 nach word, ermittle Vorzeichen und Betrag und melde vorzeichenbehafteten Überlauf; erhalte a und b."),
            entry("split-lowest-bit", "Define splitLowestBit(n,b) to put n's lowest bit into b and replace n with its integer quotient by 2.",
                    "Definiere splitLowestBit(n,b): Schreibe das niedrigste Bit von n nach b und ersetze n durch seinen ganzzahligen Quotienten durch 2."),
            entry("lowest-byte", "Define lowestByte to split a's lowest eight bits into b0..b7, lowest first, without changing a.",
                    "Definiere lowestByte: Verteile die niedrigsten acht Bits von a auf b0..b7, niedrigstes zuerst; erhalte a."),
            entry("value-of-byte", "Define valueOfByte to combine b0..b7 into unsigned a (0..255), preserving all input bits.",
                    "Definiere valueOfByte: Setze b0..b7 zum vorzeichenlosen Wert a (0..255) zusammen und erhalte die Eingabebits."),
            entry("full-adder", "Define add(a,b,u,c): preserve a and b, add them with carry u, write the sum bit to c and the new carry to u.",
                    "Definiere add(a,b,u,c): Erhalte a und b, addiere sie mit Übertrag u und schreibe Summenbit und neuen Übertrag nach c und u."),
            entry("byte-add", "Define 24-parameter add to sum two input bytes modulo 256 into c0..c7, preserving both inputs.",
                    "Definiere add mit 24 Parametern: Addiere zwei Eingabebytes modulo 256 nach c0..c7 und erhalte beide Eingaben."),
            entry("flip-bit", "Define flip(b) to invert the bit in b: 0 becomes 1 and 1 becomes 0.",
                    "Definiere flip(b), das das Bit in b umkehrt: Aus 0 wird 1, aus 1 wird 0."),
            entry("negate-byte", "Define negate to replace b0..b7 with their two's-complement negation modulo 256.",
                    "Definiere negate: Ersetze b0..b7 durch ihre Zweierkomplement-Negation modulo 256."),
            entry("subtract-byte", "Define 24-parameter sub to compute (a-b) modulo 256 into c0..c7, preserving both input bytes.",
                    "Definiere sub mit 24 Parametern: Berechne (a-b) modulo 256 in c0..c7 und erhalte beide Eingabebytes."));

    private GameTaskSummaries() { }

    /** Custom tasks retain their complete goal instead of losing instructions through truncation. */
    public static String text(GameLevel level, GameTaskLanguage language) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(language, "language");
        Summary summary = SUMMARIES.get(level.id());
        if (summary == null) return GameTranslations.text(level, language).goal();
        return language == GameTaskLanguage.GERMAN ? summary.german() : summary.english();
    }

    private static Map.Entry<String, Summary> entry(String id, String english, String german) {
        return Map.entry(id, new Summary(english, german));
    }
}

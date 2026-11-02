package machine;

import java.util.List;
import java.util.Map;

/** German task text for arithmetic and bit exercises; program source remains unchanged. */
final class GameGermanArithmetic {
    private GameGermanArithmetic() {}

    static Map<String, GameTranslations.TaskText> entries() {
        return Map.ofEntries(
            Map.entry("shift-macro", new GameTranslations.TaskText(
                "In eine bereits belegte Zelle übertragen",
                "Definiere shift(a,b). Übertrage alle Einheiten von a nach b: Danach muss a null sein und b die Summe der beiden ursprünglichen Eingaben enthalten.",
                "Das Makro shift aus dem Buch überträgt Einheiten in eine vorhandene Summe. Jedes Paar DEC a; INC b; lässt a+b unverändert. Sobald a null ist, enthält b die ursprüngliche Summe. Die Eingabezellen müssen verschieden sein; shift(a,a) würde keinen Fortschritt machen. Neben der allgemeinen Definition erhältst du auch Spezialisierungen für Zahlenliterale.",
                List.of("Die Zielzelle kann bereits einen Wert ungleich null enthalten. Lösche diesen Wert nicht.",
                        "Verringere a und erhöhe b wiederholt jeweils um eins, bis a null ist.",
                        "Verwende eine while-Schleife, damit bei a = 0 der vorhandene Wert von b erhalten bleibt. Die Invariante lautet a+b = ursprüngliche Summe."))),
            Map.entry("move-macro", new GameTranslations.TaskText(
                "Den Inhalt der Zielzelle ersetzen",
                "Definiere move(a,b). Am Ende soll a = 0 gelten und b den ursprünglichen Wert von a enthalten, unabhängig vom vorherigen Wert von b.",
                "Durch das Zusammensetzen von Hilfsmakros ändert sich ihre Wirkung. shift addiert zum Inhalt der Zielzelle; move setzt ihn zuerst auf null und überträgt dann die Einheiten. Die beiden Variablenargumente müssen verschiedene Zellen bezeichnen. Anders als beim Hilfsmakro copy bleibt der Inhalt der Quellzelle hier nicht erhalten.",
                List.of("Berücksichtige auch a = 0 bei einem Wert von b ungleich null: Die Zielzelle muss trotzdem auf null gesetzt werden.",
                        "Die Hilfsmakros clear und shift stehen dir bereits in der freigeschalteten Sammlung zur Verfügung.",
                        "Setze zuerst die Zielzelle auf null und übertrage danach die Einheiten aus der Quellzelle."))),
            Map.entry("preserving-add", new GameTranslations.TaskText(
                "Addieren und beide Eingaben erhalten",
                "Berechne result = a+b. Die Werte von a und b müssen unverändert bleiben.",
                "Kopiere eine Eingabe in das Ergebnis und übertrage dann die Einheiten aus einer temporären Kopie der anderen Eingabe. Für die geprüfte Lösung erhältst du addTo(a, b, out). Die Hilfsmakros setzen ihre Ausgabe auf null, bevor sie sie neu füllen.",
                List.of("Beginne mit einer Kopie von a in result.",
                        "Initialisiere eine temporäre Variable mit b, damit die Schleife diese Kopie verbrauchen kann.",
                        "Erhöhe result für jede Einheit der temporären Variable um eins und verringere dabei die temporäre Variable um eins."))),
            Map.entry("saturating-subtract", new GameTranslations.TaskText(
                "Mit natürlichen Zahlen subtrahieren",
                "Lasse a und b unverändert. Berechne result = max(0, a-b).",
                "Speicherzellen der Maschine werden nie negativ. Beginne mit einer Kopie von a und führe für jede kopierte Einheit von b einmal DEC aus. So entsteht eine Subtraktion, die bei null stehen bleibt. Nach Abschluss erhältst du subtractTo(a, b, out).",
                List.of("Beginne mit dem Wert von a in result.",
                        "Verbrauche eine temporäre Variable, die mit b initialisiert wurde.",
                        "Verringere result für jede Einheit der temporären Variable einmal, auch wenn result bereits null ist."))),
            Map.entry("multiply", new GameTranslations.TaskText(
                "Durch wiederholte Addition multiplizieren",
                "Lasse a und b unverändert. Berechne result = a*b.",
                "Jede der b Wiederholungen trägt a Einheiten zum Ergebnis bei. Halte die Schleifenzähler von den Eingaben getrennt und berücksichtige auch null Wiederholungen. Nach Abschluss erhältst du multiplyTo(a, b, out).",
                List.of("Ist eine Eingabe null, muss auch das Ergebnis null sein.",
                        "Verwende eine mit b initialisierte temporäre Variable als Zähler der äußeren Schleife.",
                        "Addiere in jedem Durchlauf a zu result. Das freigeschaltete addTo erlaubt result als Ausgabe, auch wenn dieselbe Zelle zugleich eine Eingabe ist."))),
            Map.entry("divmod", new GameTranslations.TaskText(
                "Vollständige Gruppen und Reste zählen",
                "Lasse numerator und divisor unverändert. Berechne für divisor > 0 die Werte quotient und remainder so, dass numerator = quotient*divisor + remainder und remainder < divisor gelten.",
                "Verbrauche eine Kopie von numerator Einheit für Einheit. Sobald ein mit divisor gestarteter Zähler null erreicht, ist eine Gruppe vollständig. Die Testfälle schließen einen Divisor von null aus. Nach Abschluss erhältst du divideTo(n, d, quotient, remainder).",
                List.of("Ist numerator kleiner als divisor, bleibt quotient null und alle Einheiten bilden remainder.",
                        "Verwende einen mit divisor initialisierten Zähler, der rückwärts zählt. Jede verbrauchte Einheit erhöht außerdem remainder.",
                        "Wenn der Zähler null erreicht, erhöhe quotient, setze remainder auf null und starte den Zähler erneut mit divisor."))),
            Map.entry("gcd", new GameTranslations.TaskText(
                "Der euklidische Algorithmus",
                "Lasse a und b unverändert. Berechne ihren größten gemeinsamen Teiler; dabei gilt gcd(0,0) = 0.",
                "Für y ungleich null ist gcd(x,y) gleich gcd(y,x mod y). Das freigeschaltete divideTo liefert den Rest. Prüfe y immer vor der Division. Nach Abschluss erhältst du gcdTo(a, b, out).",
                List.of("Beginne mit den Kopien x = a und y = b.",
                        "Solange y nicht null ist, dividiere x durch y und speichere Quotient und Rest in temporären Zellen.",
                        "Ersetze x durch den alten Wert von y und y durch den Rest. Sobald y null wird, ist x das Ergebnis."))),
            Map.entry("normalize-four", new GameTranslations.TaskText(
                "Nur vier Bits behalten",
                "Lasse n unverändert und berechne word = n mod 16. Das ist ein codiertes Wort mit vier Bits und einem Wert von 0 bis 15.",
                "Die Maschine speichert weiterhin natürliche Zahlen. Die feste Bitbreite ist eine Regel, die dein Programm umsetzt: Nach 15 führt die nächste Erhöhung wieder zu 0. Nach Abschluss erhältst du wrap4(value, out).",
                List.of("Für 0 und 16 ist die Ausgabe jeweils null.",
                        "Du kannst mit divideTo durch 16 dividieren und den Rest behalten.",
                        "Alternativ kannst du in word hochzählen und word immer dann auf null setzen, wenn es 16 erreicht."))),
            Map.entry("decompose-four", new GameTranslations.TaskText(
                "Die vier Bits auslesen",
                "Lasse word (0..15) unverändert. Speichere seine Bits in bits[0] bis bits[3], beginnend mit dem niederwertigsten Bit.",
                "Bei der Division durch zwei erhältst du das niederwertigste Bit als Rest und die verbleibenden höherwertigen Bits als Quotienten. Vier Wiederholungen ergeben das gesamte Wort. Nach Abschluss erhältst du bits4(word, bits).",
                List.of("Für word mit dem Wert 10 lautet die Ausgabe [0,1,0,1].",
                        "Das erste Bit ist word mod 2.",
                        "Dividiere eine Arbeitskopie viermal durch 2. Speichere jeweils den Rest und verwende den Quotienten für den nächsten Schritt."))),
            Map.entry("decode-four", new GameTranslations.TaskText(
                "Ein Wort mit Vorzeichen decodieren",
                "Lasse word (0..15) unverändert. Setze negative = 1 für die Wörter 8..15, sonst auf 0. Berechne außerdem den nichtnegativen Betrag magnitude des dargestellten Werts.",
                "Das Zweierkomplement mit vier Bits stellt -8..7 dar: 0..7 behalten ihren Wert, während 8..15 für word-16 stehen. Negative Ergebnisse werden durch eine Vorzeichenmarkierung und einen natürlichen Betrag ausgegeben; die Maschine speichert niemals eine negative ganze Zahl.",
                List.of("Das Wort 8 bedeutet negative = 1 und magnitude = 8; das Wort 15 bedeutet negative = 1 und magnitude = 1.",
                        "Ein bei 8 beginnender Rückwärtszähler erreicht genau dann null, wenn die Eingabe mindestens 8 ist.",
                        "In der negativen Hälfte ist magnitude gleich 16-word, sonst gleich word."))),
            Map.entry("encode-four", new GameTranslations.TaskText(
                "Vorzeichen und Betrag codieren",
                "Lasse negative und magnitude unverändert. Codiere beide in word: Für nichtnegative Werte liegt magnitude in 0..7, für negative Werte in 0..8. Der Betrag null wird bei beiden Vorzeichen als 0 codiert.",
                "Im Zweierkomplement gibt es nur eine Darstellung der Null. Ein negativer Wert mit dem Betrag m wird durch (16-m) mod 16 codiert. Nach Abschluss erhältst du encode4(negative, magnitude, out).",
                List.of("Ein negativer Wert mit dem Betrag 1 wird als 15 codiert.",
                        "Bei einem nichtnegativen Wert kopierst du einfach den Betrag.",
                        "Bei einem negativen Wert subtrahierst du den Betrag von 16 und begrenzt das Ergebnis mit wrap4 auf vier Bits."))),
            Map.entry("not-four", new GameTranslations.TaskText(
                "Genau vier Bits umkehren",
                "Lasse word (0..15) unverändert und berechne result, indem du alle vier Bits umkehrst.",
                "Bei vier Bits ordnet das bitweise NOT dem Muster 0000 das Muster 1111 zu und 0101 das Muster 1010. Das entspricht der Operation 15-word mit natürlichen Zahlen, nicht dem Negieren einer ganzen Zahl der Maschine. Nach Abschluss erhältst du not4(word, out).",
                List.of("Die Ausgaben für 0 und 15 sind 15 und 0.",
                        "Jedes Paar aus Eingabe und Ausgabe ergibt zusammen 15.",
                        "Verwende das freigeschaltete subtractTo mit 15 als linker Eingabe."))),
            Map.entry("negate-four", new GameTranslations.TaskText(
                "Bei fester Bitbreite negieren",
                "Lasse word (0..15) unverändert und berechne result = (-word) mod 16.",
                "Kehre die Bits um, addiere eins und behalte dann nur vier Bits. Null bleibt null. Das Wort 8 (-8) wird auf sich selbst abgebildet, weil +8 mit vier Bits und Vorzeichen nicht darstellbar ist. Nach Abschluss erhältst du negate4(word, out).",
                List.of("Die Paare 1/15 und 7/9 sind jeweils die Negationen voneinander.",
                        "not4 liefert das Komplement, aber eine Erhöhung um eins fehlt noch.",
                        "Wende nach dieser Erhöhung wrap4 an, damit die Negation von null wieder null ergibt."))),
            Map.entry("add-four", new GameTranslations.TaskText(
                "Addieren und Vorzeichenüberlauf erkennen",
                "Lasse a und b (0..15) unverändert. Berechne word = (a+b) mod 16 und setze overflow = 1 genau dann, wenn ihre Summe mit Vorzeichen außerhalb von -8..7 liegt.",
                "Ein Übertrag über Bit 3 hinaus ist etwas anderes als ein Vorzeichenüberlauf. Ein Vorzeichenüberlauf tritt auf, wenn die Eingaben dasselbe Vorzeichen haben und das Ergebnis ein anderes. Nach Abschluss erhältst du add4(a, b, out, overflow).",
                List.of("7+1 verursacht einen Vorzeichenüberlauf; codiertes -1 plus 1 ergibt durch den Umlauf null, ohne Vorzeichenüberlauf.",
                        "Verwende decode4 für die Vorzeichen der beiden Eingaben und des auf vier Bits begrenzten Ergebnisses.",
                        "Melde einen Überlauf nur dann, wenn zwei nichtnegative Eingaben ein negatives Ergebnis liefern oder zwei negative Eingaben ein nichtnegatives Ergebnis."))),
            Map.entry("subtract-four", new GameTranslations.TaskText(
                "Wörter mit Vorzeichen subtrahieren",
                "Lasse a und b (0..15) unverändert. Berechne word = (a-b) mod 16 und markiere einen Vorzeichenüberlauf außerhalb von -8..7.",
                "Subtrahiere durch Addition der Negation bei fester Bitbreite, bestimme den Überlauf aber anhand der ursprünglichen Eingaben. Die kleinste negative Eingabe lässt sich nicht in einen darstellbaren positiven Wert negieren. Nach Abschluss erhältst du subtract4(a, b, out, overflow).",
                List.of("Mit diesen Codierungen entspricht 0-8 einem nicht darstellbaren +8, während 8-8 null ohne Überlauf ergibt.",
                        "Verwende negate4 für b und add4 für das Ergebniswort, übernimm aber nicht einfach die Überlaufmarkierung dieser Addition.",
                        "Bei der Subtraktion entsteht ein Überlauf, wenn die ursprünglichen Eingaben unterschiedliche Vorzeichen haben und das Vorzeichen des Ergebnisses vom Vorzeichen von a abweicht."))),
            Map.entry("extend-four", new GameTranslations.TaskText(
                "Mit Vorzeichenerweiterung von vier auf acht Bits",
                "Lasse word (0..15) unverändert. Berechne wide (0..255), das mit acht Bits denselben Wert mit Vorzeichen darstellt.",
                "Die Vorzeichenerweiterung wiederholt das Vorzeichenbit an den neuen höherwertigen Stellen. Positive Wörter bleiben unverändert. Negative Wörter erhalten vier führende Einsen; dadurch erhöht sich ihre natürliche Codierung um 240. Nach Abschluss erhältst du extend4(word, out).",
                List.of("Das Wort 15 mit vier Bits (-1) muss zum Wort 255 mit acht Bits (-1) werden.",
                        "Prüfe mit decode4, ob das Vorzeichenbit gesetzt ist.",
                        "Kopiere das ursprüngliche Wort und addiere nur im negativen Fall 240."))),
            Map.entry("transfer-eight", new GameTranslations.TaskText(
                "Das Verfahren auf acht Bits übertragen",
                "Lasse a und b (0..255) unverändert. Addiere modulo 256 nach word, decodiere word in negative und magnitude und markiere einen Vorzeichenüberlauf außerhalb von -128..127.",
                "Übertrage die Algorithmen für vier Bits, indem du den Modulus auf 256 und die Vorzeichengrenze auf 128 änderst. Dieselbe Vorzeichenregel erkennt den Überlauf. Negative Maschinenwerte oder bitweise Befehle sind dafür nicht nötig.",
                List.of("Mit acht Bits wird -128 als 128 codiert und -1 als 255.",
                        "Passe wrap4 und decode4 an den Modulus 256 und die Grenze 128 an.",
                        "Begrenze die Summe auf acht Bits, ermittle alle drei Vorzeichenmarkierungen und wende dieselbe Überlaufregel an: gleiche Vorzeichen der Eingaben, anderes Vorzeichen des Ergebnisses."))),
            Map.entry("split-lowest-bit", new GameTranslations.TaskText(
                "Das niederwertigste Bit abtrennen",
                "Definiere splitLowestBit(n,b). Ersetze n durch seinen ursprünglichen Wert geteilt durch 2 und abgerundet. Ersetze b durch den Rest des ursprünglichen Werts von n modulo 2.",
                "Eine in einer einzelnen Zelle gespeicherte Codierung lässt sich in einzelne Bitzellen zerlegen. Die Division durch zwei trennt das niederwertigste Bit von den verbleibenden höherwertigen Bits. Bestimme den Rest, bevor du n überschreibst. Anders als die bisherigen Rechenhilfen verändert dieses Hilfsmakro n absichtlich; n und b müssen verschiedene Zellen sein.",
                List.of("Das ausgegebene Bit ist immer 0 oder 1, auch wenn b anfangs einen anderen Wert enthielt.",
                        "Die Hilfsmakros div und mod mit den Namen aus dem Buch erhalten ihre Eingaben, sofern die Ausgabe nicht dieselbe Zelle ist.",
                        "Berechne zuerst mod(n,2,b) und danach div(n,2,n), damit beide Ergebnisse den ursprünglichen Wert von n verwenden."))),
            Map.entry("lowest-byte", new GameTranslations.TaskText(
                "Das niederwertigste Byte zerlegen",
                "Definiere lowestByte(a,b0,...,b7). Lasse a unverändert und schreibe die acht Bits von a mod 256 nach b0 bis b7, beginnend mit dem niederwertigsten Bit.",
                "Das Buch verwendet acht einzelne Zellen statt einer Codierung in einer einzigen Zelle. Wiederhole das Abtrennen eines Bits achtmal an einer Arbeitskopie. Verbleibende höherwertige Bits werden verworfen; jede Ausgabezelle muss am Ende 0 oder 1 enthalten.",
                List.of("Arbeite mit einer Kopie, damit die Eingabe a erhalten bleibt.",
                        "Beim ersten Abtrennen wird b0 geschrieben, beim nächsten b1 und so weiter.",
                        "Rufe splitLowestBit achtmal mit derselben Arbeitszelle auf und ändere dabei jeweils nur die Zielzelle für das Bit."))),
            Map.entry("value-of-byte", new GameTranslations.TaskText(
                "Ein Byte wieder zusammensetzen",
                "Definiere valueOfByte(b0,...,b7,a). Lasse die acht Eingabebits unverändert und schreibe ihren vorzeichenlosen Wert von 0..255 nach a.",
                "Kehre die Zerlegung um und arbeite vom höchstwertigen zum niederwertigsten Bit. Beginne mit b7; für jedes weitere Bit verdoppelst du den bisher berechneten Wert und addierst dieses Bit. Das ist eine Stellenwertdarstellung, keine Decodierung des Vorzeichens. Nach Abschluss erhältst du zusätzlich das Hilfsmakro joinBit(b,a).",
                List.of("Verarbeite b7 zuerst und b0 zuletzt.",
                        "Nach dem höchstwertigen Bit verdoppelst du in jedem Schritt a und addierst das nächste Bit.",
                        "Du kannst vor valueOfByte ein eigenes Hilfsmakro joinBit definieren; add(a,a,a) verdoppelt a sicher."))),
            Map.entry("full-adder", new GameTranslations.TaskText(
                "Ein Bit und seinen Übertrag addieren",
                "Definiere add(a,b,u,c) mit vier Parametern. Lasse die Eingabebits a und b unverändert, ersetze u durch den ausgehenden Übertrag und schreibe das Summenbit nach c. Alle Eingaben sind 0 oder 1.",
                "Ein Volladdierer verbindet zwei Bits mit einem eingehenden Übertrag. Ihre Summe liegt in 0..3: Ihr Rest modulo 2 ist das Ausgabebit, ihr ganzzahliger Quotient bei Division durch 2 der Übertrag. Die Makrosignatur unterscheidet dieses add mit vier Parametern von der freigeschalteten Addition natürlicher Zahlen mit drei Parametern.",
                List.of("Eine Summe von 2 bedeutet Summenbit 0 und Übertrag 1; bei einer Summe von 3 sind beide Ausgaben 1.",
                        "Verwende add(a,b,s) mit einer temporären Variable s und addiere anschließend den ursprünglichen Übertrag zu s.",
                        "shift(u,s) verbraucht den alten Übertrag. Anschließend liefern mod(s,2,c) und div(s,2,u) die beiden Ausgaben."))),
            Map.entry("byte-add", new GameTranslations.TaskText(
                "Den Übertrag durch acht Stellen weitergeben",
                "Definiere add mit 24 Parametern: acht a-Bits, acht b-Bits und danach acht c-Ausgabebits. Lasse beide Eingabebytes unverändert und schreibe ihre Summe modulo 256 nach c0..c7.",
                "Verwende an jeder Stelle den Volladdierer mit vier Parametern, beginnend bei Bit 0 mit Übertrag 0. Jede Stelle gibt ihren Übertrag an die nächste weiter. Verwirf den letzten Übertrag über Bit 7 hinaus. Hier geht es um die Übertragsweitergabe ohne Vorzeichen; die bisherige Markierung eines Vorzeichenüberlaufs ist ein anderes Konzept.",
                List.of("Initialisiere eine Übertragszelle mit null und verwende sie an allen acht Stellen wieder.",
                        "Rufe add(a0,b0,carry,c0) auf und fahre dann bis Bit 7 fort.",
                        "Damit auch Eingabezellen als Ausgaben wiederverwendet werden können, kopiere alle Eingabebits, bevor du das erste Ergebnisbit schreibst."))),
            Map.entry("flip-bit", new GameTranslations.TaskText(
                "Ein Bit umkehren",
                "Definiere flip(b). Ändere in derselben Zelle 0 zu 1 und 1 zu 0.",
                "Ein Bit hat nur zwei gültige Werte. Eine kurze if/else-Verzweigung bildet sein Komplement. Als Belohnung erhältst du außerdem eine überladene Variante von flip mit acht Parametern, die deine Operation für ein Bit auf ein ganzes Byte anwendet.",
                List.of("Unterscheide die beiden Fälle, indem du b mit null vergleichst.",
                        "Erhöhe im Fall null den Wert um eins und verringere ihn im Fall eins um eins.",
                        "Verwende if/else, damit ein von 0 auf 1 geändertes Bit nicht sofort wieder zurückgeändert wird."))),
            Map.entry("negate-byte", new GameTranslations.TaskText(
                "Ein Byte in seinen Zellen negieren",
                "Definiere negate(b0,...,b7). Ersetze die acht Eingabebits durch ihre Negation im Zweierkomplement modulo 256.",
                "Beim Negieren werden alle Bits umgekehrt und anschließend wird eins addiert. Beim Byteaddierer dürfen sich Ausgabezellen mit einem Eingabebyte überschneiden. Das Ergebnis kann daher die ursprünglichen Bits ersetzen. Null bleibt null, und die Codierung 128 des kleinsten Werts mit Vorzeichen bleibt bei dieser Bitbreite 128.",
                List.of("Verwende zuerst das freigeschaltete flip mit acht Parametern.",
                        "Die Bits der Zahl eins lauten von der niederwertigsten zur höchstwertigen Stelle 1,0,0,0,0,0,0,0.",
                        "Addiere dieses Byte zu den umgekehrten Bits und verwende dieselben b-Zellen als Ausgaben. Das Verwerfen des letzten Übertrags behandelt den Fall null."))),
            Map.entry("subtract-byte", new GameTranslations.TaskText(
                "Mit Komplement und Übertrag subtrahieren",
                "Definiere sub mit 24 Parametern: acht a-Bits, acht b-Bits und danach acht c-Ausgaben. Lasse die Eingaben unverändert und schreibe (a-b) modulo 256 nach c0..c7.",
                "Subtraktion ist a plus das bitweise Komplement von b plus eins. Kopiere beide Eingabebytes, kehre nur die kopierten b-Bits um und beginne mit Übertrag 1. Durch die Kopien können Ergebniszellen auch sicher ursprüngliche Eingabezellen überschreiben.",
                List.of("Kehre nicht die ursprünglichen b-Zellen um, wenn die Aufgabe verlangt, sie unverändert zu lassen.",
                        "Kopiere alle sechzehn Eingabebits, bevor du Ausgaben schreibst. Kehre dann das kopierte b-Byte um.",
                        "Beginne mit carry bei 1 und rufe für jedes Bit von der niederwertigsten zur höchstwertigen Stelle einmal add mit vier Parametern auf. Verwirf den letzten Übertrag.")))
        );
    }
}

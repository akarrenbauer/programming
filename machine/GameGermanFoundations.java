package machine;

import java.util.List;
import java.util.Map;

/** German task descriptions for the first twenty language challenges. */
final class GameGermanFoundations {
    private GameGermanFoundations() {
    }

    static Map<String, GameTranslations.TaskText> entries() {
        return Map.ofEntries(
                Map.entry("first-steps", new GameTranslations.TaskText(
                        "Ein Schritt vorwärts",
                        "Erhöhe x um eins und halte dann an. Die Prüfung verwendet mehrere Anfangswerte.",
                        "Die Maschine speichert Zahlen in den Zellen S[0], S[1] und so weiter. S[0] = i bedeutet, dass die nächste Anweisung an Adresse i beginnt. Bei benannten Variablen wählt der Compiler die Adressen. Schreibe hier A26-Quelltext und keine direkte Auflistung des Speicherinhalts. INC erhöht um eins; DEC bleibt bei null stehen. HALT beendet die Ausführung, und NOP tut nichts. Deine ersten Werkzeuge sind Befehle, Sprungmarken und JNZ. Das Ablaufdiagramm neben deinem Code zeigt Befehle als Kästen und Entscheidungen als Rauten, verbunden durch Pfeile für den Ausführungsablauf. Änderungen in einer Ansicht aktualisieren auch die andere. Wenn du die Aufgabe löst, schaltest du Blöcke und leere Anweisungen frei.",
                        List.of(
                                "Betrachte x als wechselnde Eingabe, nicht als fest vorgegebene Antwort.",
                                "INC erhöht den Wert der angegebenen Zelle um eins.",
                                "Setze die Erhöhung vor HALT; Anweisungen nach HALT werden nie ausgeführt."))),

                Map.entry("clear-block", new GameTranslations.TaskText(
                        "Die Löschschleife in einen Block fassen",
                        "Behalte die Anweisungen der Vorlage und ihre Reihenfolge bei. Füge direkt nach der vollständigen Anweisung INC dummy; eine zusätzliche leere Anweisung ein: ein einzelnes ; in einer eigenen Zeile. Setze dann unmittelbar nach do: geschweifte Klammern nur um die Anweisung DEC x; und lasse JNZ x do; hinter der schließenden Klammer stehen. Am Ende muss dummy einmal erhöht worden sein und x = 0 gelten.",
                        "Die Vorlage setzt x bereits auf null. Fasse den Schleifenrumpf als do: { DEC x; } zusammen und prüfe dann mit JNZ x do; hinter der schließenden Klammer, ob er wiederholt werden soll. So trennst du den Rumpf von seiner nachfolgenden Bedingung. Lass INC dummy; vor dem Block stehen, gefolgt von einer zusätzlichen leeren Anweisung: einem eigenständigen ; in einer eigenen Zeile. Diese leere Anweisung hat keine Wirkung. Ihr Semikolon kommt zusätzlich zu dem Semikolon hinzu, das INC dummy; abschließt. Hier ist do ein gewöhnlicher Bezeichner; die nächste Sprachversion reserviert es als Schleifenschlüsselwort. Nach dieser Aufgabe drückt do { DEC x; } while (x != 0); dieselbe Schleife ohne ausdrückliche Sprungmarke und ohne Sprung aus.",
                        List.of(
                                "Lass die vollständige Anweisung INC dummy; an erster Stelle stehen. Füge direkt danach eine zusätzliche leere Anweisung ein: ein einzelnes ; in einer eigenen Zeile. Beide Anweisungen bleiben außerhalb des Blocks.",
                                "Setze die öffnende Klammer unmittelbar nach do:, vor DEC x. Setze die schließende Klammer vor JNZ x do;.",
                                "Der Block enthält nur DEC x;. JNZ x do; und HALT; folgen hinter der schließenden Klammer. Auch der Anfangswert x = 0 ist unproblematisch, weil DEC null unverändert lässt."))),

                Map.entry("clear-do-while", new GameTranslations.TaskText(
                        "Den Sprung durch do/while ersetzen",
                        "Schreibe den markierten Löschblock mit seinem nachfolgenden Sprung als do { DEC x; } while (x != 0); um. Lass INC dummy und die anschließende leere Anweisung vor der Schleife stehen und HALT dahinter.",
                        "Die Vorlage ist deine funktionierende Blocklösung in der früheren Sprachversion, in der do ein Bezeichner ist. Die neue Version reserviert do als Schleifenschlüsselwort. Der Debugger kann beide Formen ausführen. Ersetze do: durch do und JNZ x do; durch while (x != 0);. Der Rumpf wird weiterhin ausgeführt, bevor seine Bedingung geprüft wird. Deshalb setzt er x auch dann auf null, wenn x bereits bei null beginnt. Es ändert sich nur die Schreibweise; dummy muss weiterhin genau einmal erhöht werden.",
                        List.of(
                                "Lass den Blockrumpf unverändert: Er enthält nur DEC x;.",
                                "Entferne den Doppelpunkt nach do. Die strukturierte Schleife benötigt keine ausdrückliche Sprungmarke und keinen Sprung mehr.",
                                "Schreibe hinter die schließende Klammer while (x != 0);. INC dummy; und das folgende eigenständige ; bleiben vor do, und HALT; bleibt hinter dem abschließenden Semikolon."))),

                Map.entry("mandatory-lap", new GameTranslations.TaskText(
                        "Mindestens ein Durchlauf",
                        "Verwende eine do/while-Schleife, um n auf null herunterzuzählen und rounds auf max(1, original n) zu setzen, also mindestens auf 1 und sonst auf den Anfangswert von n. Auch bei n = 0 muss ein Durchlauf stattfinden.",
                        "Du hast do/while freigeschaltet, indem du eine Löschschleife mit Sprüngen in einen Block gefasst hast. Ihr Rumpf läuft, bevor die Bedingung geprüft wird. Null auf null zu setzen ist unproblematisch. Wird dabei aber auch eine andere Zelle erhöht, ändert sich deren Wert einmal. Deshalb addiert eine Übertragung mit do/while den Wert null nur dann korrekt, wenn eine vorgeschaltete Prüfung den ersten Durchlauf verhindert.",
                        List.of(
                                "Auch wenn die Eingabe anfangs null ist, muss rounds einmal erhöht werden.",
                                "Setze INC rounds und DEC n in den do-Rumpf. DEC bei null ist unproblematisch, weil die Subtraktion bei null stehen bleibt.",
                                "Prüfe hinter der schließenden Klammer des Rumpfs while (n != 0);, damit nur dann ein weiterer Durchlauf stattfindet, wenn n noch ungleich null ist."))),

                Map.entry("zero-test", new GameTranslations.TaskText(
                        "Null erkennen",
                        "Lass n unverändert. Setze zero genau dann auf 1, wenn n null ist; andernfalls bleibt zero bei 0.",
                        "Ein Vorwärtssprung JNZ n beyond überspringt einen Block, wenn n ungleich null ist. Dieser Block läuft also genau dann, wenn n == 0 gilt. Das ist die Grundidee von if (n == 0). Um den Test umzukehren, beginne mit einer Markierungsvariable bei null, erhöhe sie bei n == 0 und führe die andere Arbeit nur aus, wenn flag == 0 gilt. Löse diese Entscheidung mit JNZ, um beide Formen des Nulltests mit if freizuschalten.",
                        List.of(
                                "Du musst n nicht herunterzählen.",
                                "JNZ kann direkt zu einem HALT mit Sprungmarke springen.",
                                "Nur auf dem Weg ohne Sprung soll zero erhöht werden."))),

                Map.entry("two-paths", new GameTranslations.TaskText(
                        "Zwischen zwei Ergebnissen wählen",
                        "Verwende eine if-Anweisung. Lass n unverändert und erzeuge result = 2 für n = 0 sowie result = 1 für n ungleich null.",
                        "Ein if-Block kann auf einem Weg zusätzliche Arbeit ausführen. Kombiniere gemeinsame und bedingte Arbeit, um beide Ergebnisse zu erzeugen, bevor du else freischaltest. Zwei unabhängige if-Tests sind nicht immer Alternativen: Bei if (f == 0) { INC f; } gefolgt von if (f != 0) { DEC f; } laufen beide Rümpfe, wenn f bei null beginnt. Ein if/else wählt anhand eines einzigen Tests genau einen Zweig aus.",
                        List.of(
                                "Beide Ergebnisse benötigen eine Erhöhung.",
                                "Im Nullfall ist eine zusätzliche Erhöhung nötig.",
                                "Führe die gemeinsame Erhöhung aus und verwende if (n == 0) für die zusätzliche."))),

                Map.entry("else-practice", new GameTranslations.TaskText(
                        "Die Alternativen ausdrücklich formulieren",
                        "Schreibe die funktionierende Vorlage als ein einziges if/else um, das n mit null vergleicht, gefolgt von HALT. Lass n unverändert; result muss 1 sein, wenn n null ist, und sonst 2.",
                        "Die Vorlage verwendet eine gemeinsame Erhöhung für beide Ergebnisse. Mit deinem neuen else-Zweig kann jeder Weg sein vollständiges Ergebnis ausdrücken. Verschiebe die Erhöhungen in die beiden Zweige und behalte das Verhalten für Eingaben gleich und ungleich null bei. Das Hauptprogramm soll nur dieses if/else und HALT enthalten.",
                        List.of(
                                "Die Vorlage liefert bereits die richtigen Ergebnisse, verwendet aber noch kein else.",
                                "Prüfe n einmal mit if (n == 0), gefolgt von einem else-Block.",
                                "Setze eine Erhöhung in den Nullzweig und zwei in den else-Zweig; entferne die gemeinsame Erhöhung vor dem if."))),

                Map.entry("countdown", new GameTranslations.TaskText(
                        "while aus if und do/while aufbauen",
                        "Zähle n auf null herunter und hinterlasse seinen ursprünglichen Wert in count.",
                        "Der Rumpf einer do/while-Schleife läuft mindestens einmal. Sichere ihn mit if (n != 0) ab und wiederhole ihn dann mit while (n != 0), damit auch null Durchläufe möglich sind. Wenn das äußere if und das nachfolgende while dieselbe Bedingung verwenden, bilden sie die Grundform einer while-Schleife. Verwende hier beide Konstrukte, um diese kürzere Form freizuschalten.",
                        List.of(
                                "Jede von n entfernte Einheit muss zu einer Einheit in count werden.",
                                "Sichere den ersten Durchlauf mit if (n != 0) ab: n kann bei null beginnen.",
                                "Verwende im if-Block do { DEC n; INC count; } while (n != 0);. Die vorgeschaltete Prüfung und die Schleifenbedingung müssen übereinstimmen."))),

                Map.entry("while-practice", new GameTranslations.TaskText(
                        "if und do/while durch while ersetzen",
                        "Schreibe die funktionierende Vorlage als while (n != 0) { DEC n; INC count; } um, gefolgt von HALT. Behalte DEC n vor INC count im Schleifenrumpf. Entferne das äußere if und die do/while-Form; zähle n auf null herunter und hinterlasse seinen ursprünglichen Wert in count.",
                        "Willkommen in der strukturierten Programmierung. Du hast do/while, if/else und while freigeschaltet. Direkte JNZ-Anweisungen sind ab jetzt ausgesetzt: Verwende die Kontrollstrukturen und überlasse die Sprünge dem Compiler. Das Nassi–Shneiderman-Diagramm neben deinem Code zeigt Anweisungen als gestapelte Kästen, Entscheidungen als Verzweigungen und Schleifen um ihren Rumpf. Änderungen in einer Ansicht aktualisieren die andere. Die Vorlage ist dein funktionierender Countdown mit if und do/while. Die vorgeschaltete Prüfung und die nachfolgende Schleifenbedingung stimmen überein. Ersetze beide durch ein einziges while (n != 0) vor dem unveränderten Rumpf. Eine while-Schleife prüft vor jedem Durchlauf, auch vor dem ersten: Bei n = 0 muss count = 0 bleiben.",
                        List.of(
                                "Die Vorlage liefert bereits die richtigen Ergebnisse. Diese Aufgabe verlangt, denselben Algorithmus mit deiner neuen while-Schleife auszudrücken.",
                                "Ersetze if (n != 0) durch while (n != 0) und entferne dann do { sowie das zugehörige } while (n != 0);.",
                                "Nach begin: bleiben genau while (n != 0) { DEC n; INC count; } und danach HALT; übrig. Die Verringerung und die Erhöhung bleiben in dieser Reihenfolge im Schleifenrumpf."))),

                Map.entry("equals-three", new GameTranslations.TaskText(
                        "Einen Gleichheitstest aufbauen",
                        "Verwende eine while-Schleife zum Zählen und stelle n anschließend wieder her. Setze equal genau dann auf 1, wenn n den Wert 3 hat; für alle anderen Eingaben muss equal = 0 gelten.",
                        "Bisher stehen nur Vergleiche mit null zur Verfügung. Zähle gegen ein Budget von drei Einheiten und merke dir, ob die Eingabe es überschritten hat. Damit schaltest du Vergleiche zwischen Werten frei: Variablen oder Literale natürlicher Zahlen auf beiden Seiten.",
                        List.of(
                                "Übertrage n sowohl nach saved als auch nach work und übertrage saved anschließend zurück nach n.",
                                "Verringere budget für jede Einheit von work, solange das möglich ist; zähle andernfalls in excess weiter.",
                                "Gleichheit liegt nur vor, wenn sowohl budget als auch excess null sind. Würdest du nur budget prüfen, würdest du auch Eingaben über drei akzeptieren."))),

                Map.entry("comparison-practice", new GameTranslations.TaskText(
                        "Direkt mit drei vergleichen",
                        "Ersetze den Zählalgorithmus durch ein einziges if, das n mit dem Literal 3 vergleicht, gefolgt von HALT. Lass n unverändert und setze equal genau dann auf 1, wenn n den Wert 3 hat; andernfalls muss equal den Wert 0 haben.",
                        "Du hast Gleichheit aus Nulltests und Zählen aufgebaut. Die neu freigeschalteten Vergleiche drücken diese Arbeit direkt aus. Behalte die Anforderungen an Ein- und Ausgabe bei und ersetze die bisherige Umsetzung durch eine Bedingung wie n == 3. Das Hauptprogramm soll nur dieses if mit einem optionalen else und HALT enthalten; unbenutzte Hilfsvariablendeklarationen kannst du entfernen.",
                        List.of(
                                "Die Vorlage funktioniert bereits; sie besteht diese Aufgabe noch nicht, weil alle Vergleiche weiterhin null verwenden.",
                                "Ersetze die Schleifen und verschachtelten Nulltests durch eine einzige if-Anweisung, die n mit 3 vergleicht.",
                                "Lass equal bei null beginnen und erhöhe es innerhalb von if (n == 3). Der Vergleich lässt n unverändert."))),

                Map.entry("preserve-copy", new GameTranslations.TaskText(
                        "Kopieren und das Original erhalten",
                        "Lass n unverändert und speichere denselben Wert in copy.",
                        "Eine Übertragungsschleife verbraucht den Wert ihrer Quelle. Zähle jede entfernte Einheit zusätzlich in einer zweiten Hilfszelle und stelle danach die Quelle wieder her. Damit schaltest du Initialisierungen mit Variablen wie var temporary = n; frei.",
                        List.of(
                                "Für jede von n entfernte Einheit kannst du sowohl copy als auch saved erhöhen.",
                                "Nach dieser Übertragung hat copy den richtigen Wert, aber n ist null.",
                                "Übertrage saved zurück nach n und lass copy dabei unverändert."))),

                Map.entry("reset-scratch", new GameTranslations.TaskText(
                        "Hilfsspeicher für die nächste Verwendung leeren",
                        "Kopiere n mit einer Variableninitialisierung nach result. Am Ende müssen n = 0 und scratch = 0 gelten, unabhängig vom Anfangswert von scratch.",
                        "Deine neuen Variableninitialisierungen können einen Wert kopieren: Deklariere result = n vor begin und setze n und scratch anschließend mit Schleifen auf null. Der kopierte Wert in result bleibt bei Änderungen an n erhalten. Die hier freigeschalteten var-Deklarationen zur Laufzeit erlauben dir, Hilfszellen dort zu initialisieren, wo ihre Arbeit beginnt.",
                        List.of(
                                "Berücksichtige auch den Fall, dass n null ist, scratch aber nicht.",
                                "Eine while-Schleife mit DEC scratch setzt den alten Wert auf null.",
                                "Ändere die globale Deklaration vor begin zu var result = n;. Setze danach n auf null, ohne result erneut zu erhöhen."))),

                Map.entry("three-cells", new GameTranslations.TaskText(
                        "Drei getrennte Zellen anordnen",
                        "Verwende Variablendeklarationen zur Laufzeit. Lass a, b und c unverändert; speichere b in first, c in second und a in third.",
                        "Mehrere benannte Zellen können eine kleine Sammlung darstellen. Kopiere über Hilfsvariablen zur Laufzeit, um ihre Werte zyklisch zu versetzen. Als Belohnung schaltest du Array-Deklarationen und feste Zahlenindizes frei.",
                        List.of(
                                "Zähle nicht direkt die Eingabezellen herunter.",
                                "Eine Deklaration zur Laufzeit kann eine Hilfsvariable mit einem Eingabewert initialisieren.",
                                "Verwende für jede Übertragung eine Hilfsvariable: Zähle von b nach first, von c nach second und von a nach third."))),

                Map.entry("array-practice", new GameTranslations.TaskText(
                        "Drei Plätze in einem Array",
                        "Lass n unverändert. Deklariere cells mit drei Elementen und fülle sie mit n, n+1 und n+2.",
                        "Eine Array-Deklaration initialisiert jedes Element. Die Indizes sind feste Zahlen: cells[0], cells[1], cells[2]. Initialisiere alle drei Zellen mit n und passe dann die letzten beiden mit indizierten INC-Befehlen an.",
                        List.of(
                                "Verwende die Indizes 0, 1 und 2.",
                                "Ändere die Array-Deklaration zu var cells[3] = n;, damit jedes Element mit einer Kopie von n beginnt.",
                                "Erhöhe nach dem Kopieren das Element 1 einmal und das Element 2 zweimal."))),

                Map.entry("repeated-work", new GameTranslations.TaskText(
                        "Eine wiederverwendbare Operation erkennen",
                        "Lass a und b unverändert. Am Ende sollen left = 2*a und right = 2*b gelten.",
                        "Derselbe Algorithmus kann auf verschiedenen Zellen arbeiten. Schreibe jetzt beide Übertragungen. Wenn du die Aufgabe löst, schaltest du Makros frei, mit denen spätere Programme diese Arbeit benennen und wiederverwenden können.",
                        List.of(
                                "Verwende Kopien von a und b, damit die Eingaben erhalten bleiben.",
                                "Erhöhe eine Ausgabe für jede aus ihrer Hilfsvariable entfernte Einheit zweimal.",
                                "Verwende denselben Schleifenaufbau einmal für left und einmal für right."))),

                Map.entry("macro-practice", new GameTranslations.TaskText(
                        "Ein eigenes Makro schreiben und aufrufen",
                        "Lass n unverändert und erzeuge result = n + 2. Dein Quelltext muss eine Makrodefinition enthalten.",
                        "Ein Makro wird an jeder Aufrufstelle durch seine Anweisungen ersetzt. Seine Parameter benennen die Zellen, auf denen es arbeitet. Definiere ein kurzes Hilfsmakro mit zwei Erhöhungen und rufe es nach dem Kopieren von n auf.",
                        List.of(
                                "Dein Hilfsmakro benötigt einen benannten Ausgabeparameter.",
                                "Sein Rumpf kann zwei INC-Befehle enthalten.",
                                "Verwende bei den globalen Deklarationen var result = n; und rufe dann dein eigenes Hilfsmakro mit result auf. Imports sind noch gesperrt."))),

                Map.entry("literal-dispatch", new GameTranslations.TaskText(
                        "Das Verhalten eines Makros auswählen",
                        "Lass mode unverändert; sein Wert ist 0 oder 1. Verwende ein Makro, um result = mode + 1 zu erzeugen.",
                        "Ein benannter Makroparameter nimmt eine Zelle oder ein Literal entgegen. Beginne mit einem gewöhnlichen Makro, das intern eine Entscheidung trifft. Als Belohnung erhältst du Literalparameter wie in void step(0, out), die anhand des geschriebenen Arguments eine Definition auswählen.",
                        List.of(
                                "Verwende in dieser Aufgabe einen benannten Parameter für mode.",
                                "Der Nullzweig benötigt eine Erhöhung, der andere Zweig zwei.",
                                "Ein if/else innerhalb des Makros kann zwischen diesen Wegen wählen, ohne mode zu verändern."))),

                Map.entry("specialization-practice", new GameTranslations.TaskText(
                        "Literale als Argumente zuordnen",
                        "Lass n unverändert. Erzeuge first = n+1 und second = n+2 mithilfe von Makrodefinitionen mit Literalparametern.",
                        "Ein Literalparameter prüft die Schreibweise an der Aufrufstelle. Er untersucht keine Zelle zur Laufzeit. Definiere zwei Spezialisierungen und rufe sie mit den geschriebenen Literalen 0 und 1 auf.",
                        List.of(
                                "Verwende in den beiden Definitionen unterschiedliche Literalparameter.",
                                "Initialisiere first und second in ihren globalen Deklarationen mit n. Definiere beide Spezialisierungen in diesem Quelltext; Imports sind noch gesperrt.",
                                "Rufe die Spezialisierung mit einer Erhöhung mit dem Literal 0 auf und die mit zwei Erhöhungen mit dem Literal 1."))),

                Map.entry("compose-helpers", new GameTranslations.TaskText(
                        "Eine kleine Bibliothek zusammenstellen",
                        "Lass n unverändert. Verwende Makros, um result = n + 3 zu erzeugen.",
                        "Hilfsmakros können zuvor definierte Hilfsmakros aufrufen. Kopiere die freigeschalteten Definitionen von clear, copyTo und bump aus deinem Werkzeugkasten an den Anfang dieses Quelltexts und kombiniere sie. Wenn du die Aufgabe löst, schaltest du virtuelle Imports frei, darunter import \"toolbox\";.",
                        List.of(
                                "Das Ergebnis beginnt als Kopie von n.",
                                "Ein Hilfsmakro kann bump dreimal aufrufen; setze jeden Aufruf in eine eigene Zeile.",
                                "Definiere Hilfsmakros vor den Makros, die sie aufrufen. Setze die geschweiften Makroklammern in eigene Zeilen, getrennt von den Anweisungen ihres Rumpfs."))),

                Map.entry("import-practice", new GameTranslations.TaskText(
                        "Den freigeschalteten Werkzeugkasten importieren",
                        "Lass n unverändert und erzeuge result = 2*n + 1 unter Verwendung eines Imports.",
                        "Das Spiel stellt eine virtuelle Bibliothek namens toolbox bereit, die nur freigeschaltete Hilfsmakros enthält. Es liest keine Dateien von der Festplatte. Setze den Import vor die Deklarationen und kombiniere twiceTo mit bump.",
                        List.of(
                                "Lass import \"toolbox\"; in einer eigenen Zeile stehen.",
                                "twiceTo(n, result) erzeugt den verdoppelten Wert, ohne n zu verändern.",
                                "Rufe bump(result) nach dem Hilfsmakro zum Verdoppeln auf und halte dann an."))));
    }
}

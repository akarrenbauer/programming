package machine;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Teaching text only: canonical levels, source, grammar, and execution remain unchanged. */
public final class GameTranslations {
    public record TaskText(String title, String goal, String lesson, List<String> hints) {
        public TaskText {
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(goal, "goal");
            Objects.requireNonNull(lesson, "lesson");
            hints = List.copyOf(hints);
        }
    }

    private static final Map<String, TaskText> GERMAN = german();
    private static final Map<String, String> WORLDS = Map.of(
            "Machine foundations", "Grundlagen der Maschine",
            "Structured programming", "Strukturierte Programmierung",
            "Growing the language", "Die Sprache erweitern",
            "Natural-number toolbox", "Werkzeuge für natürliche Zahlen",
            "Signed words", "Wörter mit Vorzeichen",
            "Bits and bytes", "Bits und Bytes");

    private GameTranslations() { }

    public static TaskText text(GameLevel level, GameTaskLanguage language) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(language, "language");
        if (language == GameTaskLanguage.GERMAN) {
            TaskText translated = GERMAN.get(level.id());
            if (translated != null) return translated;
        }
        return new TaskText(level.title(), level.goal(), level.lesson(), level.hints());
    }

    public static String world(String name, GameTaskLanguage language) {
        return language == GameTaskLanguage.GERMAN ? WORLDS.getOrDefault(name, name) : name;
    }

    private static Map<String, TaskText> german() {
        Map<String, TaskText> entries = new LinkedHashMap<>(GameGermanFoundations.entries());
        GameGermanArithmetic.entries().forEach((id, text) -> {
            if (entries.putIfAbsent(id, text) != null) {
                throw new IllegalStateException("Duplicate German task translation: " + id);
            }
        });
        return Map.copyOf(entries);
    }
}

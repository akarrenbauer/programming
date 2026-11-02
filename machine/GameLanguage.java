package machine;

import java.util.Objects;
import java.util.Set;

/** Selects the source language taught by a level, independently of later earned tools. */
public final class GameLanguage {
    private GameLanguage() {}

    public static LanguageVersion versionFor(GameLevel level, Set<GameFeature> available) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(available, "available");
        if (level.id().equals("first-steps") || level.id().equals("clear-block")) {
            return LanguageVersion.BEFORE_DO_WHILE;
        }
        return available.contains(GameFeature.DO_WHILE)
                ? LanguageVersion.STRUCTURED : LanguageVersion.BEFORE_DO_WHILE;
    }

    /** While rewriting the old loop, color its original identifiers until a structured do block appears. */
    public static LanguageVersion editingVersionFor(GameLevel level, Set<GameFeature> available, String source) {
        Objects.requireNonNull(source, "source");
        LanguageVersion version = versionFor(level, available);
        return version == LanguageVersion.STRUCTURED && level.id().equals("clear-do-while")
                && GameSyntax.usesLegacyDo(source, () -> false) ? LanguageVersion.BEFORE_DO_WHILE : version;
    }
}

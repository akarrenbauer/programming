package machine;

import java.util.Set;

/** The curriculum's worlds and their source-level control-flow policy. */
public enum GameWorld {
    MACHINE_FOUNDATIONS("Machine foundations", false),
    STRUCTURED_PROGRAMMING("Structured programming", true),
    GROWING_LANGUAGE("Growing the language", true),
    NATURAL_NUMBERS("Natural-number toolbox", true),
    SIGNED_WORDS("Signed words", true),
    BITS_AND_BYTES("Bits and bytes", true);

    private static final Set<GameFeature> STRUCTURED_CONTROLS = Set.of(
            GameFeature.DO_WHILE, GameFeature.IF, GameFeature.ELSE, GameFeature.WHILE);
    private final String title;
    private final boolean structured;

    GameWorld(String title, boolean structured) {
        this.title = title;
        this.structured = structured;
    }

    public String title() { return title; }
    public boolean structured() { return structured; }
    public Set<GameFeature> requiredFeatures() { return structured ? STRUCTURED_CONTROLS : Set.of(); }

    /** Custom exercises retain their supplied world name and original instruction policy. */
    public static GameWorld forLevel(GameLevel level) {
        for (GameWorld world : values()) if (world.title.equals(level.world())) return world;
        return null;
    }

    /** Nassi–Shneiderman diagrams belong to worlds using structured control flow only. */
    public static boolean usesDiagrams(GameLevel level) {
        GameWorld world = forLevel(level);
        return world != null && world.structured;
    }

    /** Flowcharts are available throughout the curriculum, independently of jump policy. */
    public static boolean usesFlowcharts(GameLevel level) {
        return forLevel(level) != null;
    }

    public static boolean hasStructuredControls(Set<GameFeature> available) {
        return available.containsAll(STRUCTURED_CONTROLS);
    }
}

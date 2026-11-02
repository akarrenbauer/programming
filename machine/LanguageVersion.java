package machine;

/** Per-compilation profiles for the point at which do becomes a reserved keyword. */
public enum LanguageVersion {
    /** do is an ordinary identifier; post-tested loop syntax is not available. */
    BEFORE_DO_WHILE("Before do/while"),
    /** do is reserved for post-tested loops and cannot be used as an identifier. */
    STRUCTURED("Structured");

    private final String title;

    LanguageVersion(String title) { this.title = title; }

    public String title() { return title; }

    @Override
    public String toString() { return title; }
}

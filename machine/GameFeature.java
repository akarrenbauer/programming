package machine;

/** Syntax earned by solving Language Game levels; worlds separately govern explicit machine jumps. */
public enum GameFeature {
    BLOCKS("Blocks"), EMPTY_STATEMENTS("Empty statements"), WHILE("While loops"),
    IF("If statements"), ELSE("Else branches"), COMPARISONS("General comparisons"),
    DO_WHILE("Do/while loops"), COPYING("Variable initializers"),
    LOCAL_VARIABLES("Runtime declarations"), ARRAYS("Arrays"), MACROS("Macros"),
    LITERAL_PARAMETERS("Literal macro parameters"), IMPORTS("Imports");

    private final String title;

    GameFeature(String title) {
        this.title = title;
    }

    public String title() {
        return title;
    }
}

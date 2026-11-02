import machine.GameCatalog;
import machine.GameLevel;
import machine.GameTaskLanguage;
import machine.GameTaskSummaries;
import machine.GameTranslations;

/** Concise bilingual objectives, key exercise constraints and lossless custom-task fallback. */
public final class GameTaskSummariesTest {
    private static int passed;
    @FunctionalInterface private interface Check { void run(); }

    public static void main(String[] args) {
        check("all 46 catalog tasks have curated single-line objectives in both languages", () -> {
            require(GameCatalog.levels().size() == 46, "coverage follows the complete curriculum");
            for (GameLevel level : GameCatalog.levels()) {
                String english = GameTaskSummaries.text(level, GameTaskLanguage.ENGLISH);
                String german = GameTaskSummaries.text(level, GameTaskLanguage.GERMAN);
                for (String text : new String[] {english, german}) {
                    require(!text.isBlank() && text.length() <= 150, level.id() + " summary length: " + text.length());
                    require(!text.contains("\n") && !text.contains("\r"), level.id() + " stays on one logical line");
                    require(!text.endsWith("…") && !text.endsWith("..."), level.id() + " is authored rather than truncated");
                }
                require(!english.equals(german), level.id() + " has a German summary");
                require(!english.equals(level.goal()), level.id() + " has a curated objective rather than a missing-entry fallback");
                require(!german.equals(GameTranslations.text(level, GameTaskLanguage.GERMAN).goal()),
                        level.id() + " has a curated German objective");
            }
        });
        check("syntax rewrites retain the operation and important ordering in the short objective", () -> {
            for (GameTaskLanguage language : GameTaskLanguage.values()) {
                String block = summary("clear-block", language);
                require(block.contains("INC dummy") && block.contains("DEC x") && block.contains("JNZ"), "block rewrite retains its important boundaries");
                String loop = summary("while-practice", language);
                require(loop.contains("while (n != 0) { DEC n; INC count; }") && loop.contains("HALT"), "while rewrite retains its exact required body order");
                require(summary("mandatory-lap", language).contains("max(1,"), "post-test exercise keeps the zero-input round");
                require(summary("comparison-practice", language).contains("3"), "literal comparison remains explicit");
            }
        });
        check("arithmetic summaries distinguish saturation fixed widths and carry operations", () -> {
            require(summary("saturating-subtract", GameTaskLanguage.ENGLISH).contains("stopping at zero"), "natural subtraction still saturates");
            require(summary("saturating-subtract", GameTaskLanguage.GERMAN).contains("mindestens null"), "German subtraction still saturates");
            for (GameTaskLanguage language : GameTaskLanguage.values()) {
                require(summary("add-four", language).contains("modulo 16") && summary("add-four", language).contains("-8..7"), "word width and signed overflow remain distinct");
                require(summary("byte-add", language).contains("24") && summary("byte-add", language).contains("modulo 256"), "byte macro arity and width are visible");
                require(summary("full-adder", language).contains("add(a,b,u,c)"), "bit-adder overload remains identifiable");
                require(summary("gcd", language).contains("gcd(0,0)"), "gcd zero edge case remains visible");
            }
        });
        check("custom tasks retain the complete goal without silent truncation", () -> {
            GameLevel base = GameCatalog.levels().get(0);
            String goal = "Keep this custom objective intact. ".repeat(8) + "\nThe final condition must remain available.";
            GameLevel custom = new GameLevel("custom-summary-fixture", "Custom", "Custom task", goal, base.lesson(),
                    base.starter(), base.hints(), base.unlocks(), base.requiredFeatures(), base.rewards(), base.cases(), base.maxSteps());
            for (GameTaskLanguage language : GameTaskLanguage.values()) {
                require(GameTaskSummaries.text(custom, language).equals(goal), "fallback preserves every custom instruction");
            }
        });
        System.out.println("GameTaskSummariesTest: " + passed + " passed, 0 failed");
    }
    private static String summary(String id, GameTaskLanguage language) {
        return GameTaskSummaries.text(GameCatalog.levels().stream().filter(level -> level.id().equals(id)).findFirst().orElseThrow(), language);
    }
    private static void check(String name, Check action) { action.run(); passed++; System.out.println("PASS " + name); }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}

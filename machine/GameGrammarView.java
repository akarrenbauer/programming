package machine;

import java.util.Set;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontPosture;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;

/** A wrapping, accessible grammar display using the JLS notation without a browser. */
public final class GameGrammarView extends ScrollPane {
    private static final Font ORDINARY = Font.font("System", 14);
    private static final Font NONTERMINAL = Font.font("System", FontPosture.ITALIC, 14);
    private static final Font TERMINAL = Font.font("Monospaced", 14);
    private static final String TERMINAL_STYLE = "-fx-background-color: #eeeeee; -fx-background-radius: 3;"
            + " -fx-padding: 2 4 2 4; -fx-text-fill: #202020;";
    private static final String NEW_COLOR = "#20713a";

    private final VBox content = new VBox(16);
    private GameGrammar.Model model;
    private String text;
    private Set<GameFeature> displayedFeatures;
    private Set<GameFeature> displayedAdditions;
    private boolean displayedExplicitJumps;
    private Node firstNewProduction;

    public GameGrammarView() {
        getStyleClass().add("game-grammar-view");
        setStyle("-fx-background: white; -fx-background-color: white;");
        content.setStyle("-fx-background-color: white;");
        setFitToWidth(true);
        setHbarPolicy(ScrollBarPolicy.NEVER);
        content.setPadding(new Insets(16));
        content.setFillWidth(true);
        setContent(content);
        update(Set.of(), Set.of());
    }

    /** The caller owns unread state; passing an empty new set removes all change markers. */
    public void update(Set<GameFeature> available, Set<GameFeature> newlyEarned) {
        update(available, newlyEarned, true);
    }

    public void update(Set<GameFeature> available, Set<GameFeature> newlyEarned, boolean explicitJumps) {
        available = Set.copyOf(available);
        newlyEarned = Set.copyOf(newlyEarned);
        if (available.equals(displayedFeatures) && newlyEarned.equals(displayedAdditions)
                && explicitJumps == displayedExplicitJumps) return;
        displayedFeatures = available;
        displayedAdditions = newlyEarned;
        displayedExplicitJumps = explicitJumps;
        firstNewProduction = null;
        model = GameGrammar.describe(available, newlyEarned, explicitJumps);
        text = GameGrammar.render(available, explicitJumps);
        setAccessibleText("Unlocked grammar. " + text);
        content.getChildren().clear();
        content.getChildren().add(legend());
        for (GameGrammar.Production production : model.productions()) {
            VBox rule = new VBox(5);
            rule.getStyleClass().add("grammar-production");
            rule.setUserData(production.name());
            FlowPane heading = row();
            Text name = nonterminal(production.name());
            name.getStyleClass().add("grammar-rule-name");
            heading.getChildren().addAll(name, notation(":"));
            if (production.isNew(newlyEarned)) {
                if (firstNewProduction == null) firstNewProduction = rule;
                Label badge = new Label("New");
                badge.getStyleClass().add("grammar-new-badge");
                badge.setStyle("-fx-text-fill: " + NEW_COLOR + "; -fx-font-weight: bold; -fx-padding: 1 6;");
                badge.setAccessibleText("New syntax in " + production.name());
                heading.getChildren().add(badge);
            }
            rule.getChildren().add(heading);
            for (GameGrammar.Alternative alternative : production.alternatives()) {
                if (alternative.fragments().size() == 1
                        && alternative.fragments().get(0).kind() == GameGrammar.Kind.TEXT) {
                    Label explanation = prose(alternative.fragments().get(0).text());
                    explanation.getStyleClass().add("grammar-alternative");
                    explanation.setUserData(alternative.id());
                    explanation.setPadding(new Insets(0, 0, 0, 22));
                    rule.getChildren().add(explanation);
                    continue;
                }
                FlowPane row = row();
                row.getStyleClass().add("grammar-alternative");
                row.setUserData(alternative.id());
                row.setPadding(new Insets(0, 0, 0, 22));
                for (GameGrammar.Fragment fragment : alternative.fragments()) {
                    Node symbol = symbol(fragment);
                    if (fragment.isNew(newlyEarned)) {
                        highlight(symbol, fragment.kind());
                        symbol.getStyleClass().add("grammar-new");
                        symbol.setAccessibleText("New " + fragment.kind().name().toLowerCase() + ": " + fragment.text());
                        Tooltip.install(symbol, new Tooltip("Newly unlocked syntax"));
                    }
                    row.getChildren().add(symbol);
                }
                rule.getChildren().add(row);
            }
            content.getChildren().add(rule);
        }
        VBox notes = new VBox(7);
        notes.getStyleClass().add("grammar-notes");
        model.notes().forEach(note -> notes.getChildren().add(prose(note)));
        content.getChildren().add(notes);
    }

    public String getText() { return text; }

    public GameGrammar.Model getModel() { return model; }

    /** Called when opening an unread grammar tab; ordinary inventory refreshes do not scroll. */
    public void revealNewAdditions() {
        Node target = firstNewProduction;
        if (target == null) return;
        Platform.runLater(() -> {
            // A reset or another grammar update may have replaced the queued target.
            if (target.getParent() != content || getScene() == null) return;
            applyCss();
            layout();
            content.layout();
            double distance = content.getLayoutBounds().getHeight() - getViewportBounds().getHeight();
            double fraction = distance <= 0 ? 0
                    : Math.max(0, Math.min(1, (target.getBoundsInParent().getMinY() - 8) / distance));
            setVvalue(getVmin() + fraction * (getVmax() - getVmin()));
        });
    }

    private static Node legend() {
        VBox legend = new VBox(7);
        legend.getStyleClass().add("grammar-legend");
        FlowPane examples = row();
        examples.getChildren().addAll(terminal("literal"), nonterminal("Nonterminal"), notation("[ optional ]"),
                notation("{ repeated }"));
        legend.getChildren().addAll(examples, prose("Gray monospace symbols are written literally. Italic names refer to"
                + " another rule. Unshaded [ ] mean optional and { } mean zero or more repetitions."
                + " Separate lines are alternatives. Bold green text and New labels mark newly unlocked syntax."));
        return legend;
    }

    private static FlowPane row() {
        FlowPane row = new FlowPane(6, 5);
        row.setAlignment(Pos.TOP_LEFT);
        row.setRowValignment(VPos.BASELINE);
        row.setMinHeight(Region.USE_PREF_SIZE);
        return row;
    }

    private static Node symbol(GameGrammar.Fragment fragment) {
        return switch (fragment.kind()) {
            case TERMINAL -> terminal(fragment.text());
            case NONTERMINAL -> nonterminal(fragment.text());
            case NOTATION -> notation(fragment.text());
            case TEXT -> prose(fragment.text());
        };
    }

    private static void highlight(Node symbol, GameGrammar.Kind kind) {
        if (symbol instanceof Text text) {
            FontPosture posture = kind == GameGrammar.Kind.NONTERMINAL ? FontPosture.ITALIC : FontPosture.REGULAR;
            text.setFont(Font.font(text.getFont().getFamily(), FontWeight.BOLD, posture, text.getFont().getSize()));
            text.setFill(Color.web(NEW_COLOR));
        } else if (symbol instanceof Label label) {
            label.setFont(Font.font(label.getFont().getFamily(), FontWeight.BOLD, label.getFont().getSize()));
            label.setStyle(label.getStyle() + " -fx-text-fill: " + NEW_COLOR + ";");
        }
    }

    private static Label terminal(String text) {
        Label label = new Label(text);
        label.setFont(TERMINAL);
        label.setStyle(TERMINAL_STYLE);
        label.getStyleClass().add("grammar-terminal");
        label.setMinWidth(Region.USE_PREF_SIZE);
        label.setAccessibleText("Literal " + text);
        return label;
    }

    private static Text nonterminal(String text) {
        Text node = new Text(text);
        node.setFont(NONTERMINAL);
        node.getStyleClass().add("grammar-nonterminal");
        node.setAccessibleText("Nonterminal " + text);
        return node;
    }

    private static Text notation(String text) {
        Text node = new Text(text);
        node.setFont(ORDINARY);
        node.getStyleClass().add("grammar-notation");
        node.setAccessibleText(text);
        return node;
    }

    private static Label prose(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setFont(ORDINARY);
        label.setMaxWidth(Double.MAX_VALUE);
        label.getStyleClass().add("grammar-prose");
        return label;
    }
}

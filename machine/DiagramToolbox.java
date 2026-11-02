package machine;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.AccessibleAction;
import javafx.scene.AccessibleRole;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DataFormat;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Line;
import javafx.scene.shape.Rectangle;

/** Three earned control structures, inserted by dragging or selecting a diagram symbol. */
public final class DiagramToolbox extends FlowPane {
    public enum Kind { IF, WHILE, DO_WHILE }

    /** The drag carries a controlled kind name, never arbitrary executable source text. */
    public static final DataFormat FORMAT = new DataFormat("application/x-language-game-structure");
    private static final Color INK = Color.web("#456777");
    private final Map<Kind, Tile> tiles = new EnumMap<>(Kind.class);
    private final ReadOnlyObjectWrapper<Kind> selected = new ReadOnlyObjectWrapper<>();
    private Set<GameFeature> features = Set.of();

    public DiagramToolbox() {
        setId("diagram-toolbox");
        getStyleClass().add("diagram-toolbox");
        setHgap(8);
        setVgap(6);
        setAccessibleText("Control structure toolbox. Drag a symbol into a diagram gap, or select a symbol and activate a gap.");
        for (Kind kind : Kind.values()) {
            Tile tile = new Tile(kind);
            tiles.put(kind, tile);
            getChildren().add(tile);
        }
        selected.addListener((observable, before, after) -> tiles.values().forEach(Tile::refreshStyle));
        refreshAvailability();
    }

    public void setFeatures(Set<GameFeature> available) {
        features = Set.copyOf(Objects.requireNonNull(available, "available"));
        if (selected.get() != null && !available(selected.get(), features)) clearSelection();
        refreshAvailability();
    }

    public Kind selectedKind() { return selected.get(); }
    public ReadOnlyObjectProperty<Kind> selectedKindProperty() { return selected.getReadOnlyProperty(); }
    public void clearSelection() { selected.set(null); }

    public static boolean available(Kind kind, Set<GameFeature> features) {
        if (kind == null) return false;
        return features.contains(switch (kind) {
            case IF -> GameFeature.IF;
            case WHILE -> GameFeature.WHILE;
            case DO_WHILE -> GameFeature.DO_WHILE;
        });
    }

    /** Rejects unrelated drags and malformed payloads. The receiving view also checks earned features. */
    public static Kind read(Dragboard board) {
        if (board == null || !board.hasContent(FORMAT)) return null;
        return decode(board.getContent(FORMAT));
    }

    public static Kind decode(Object payload) {
        if (!(payload instanceof String text)) return null;
        try { return Kind.valueOf(text); }
        catch (IllegalArgumentException invalid) { return null; }
    }

    /** Initial bodies are harmless placeholders; the condition uses only an earned zero test. */
    public static String template(Kind kind, String variable) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(variable, "variable");
        if (!variable.matches("[A-Za-z_][A-Za-z_0-9]*(\\[[0-9]+\\])?")) {
            throw new IllegalArgumentException("A structure template needs a variable or literal array index.");
        }
        return switch (kind) {
            case IF -> "if (" + variable + " == 0) {\n  ;\n}";
            case WHILE -> "while (" + variable + " != 0) {\n  ;\n}";
            case DO_WHILE -> "do {\n  ;\n} while (" + variable + " != 0);";
        };
    }

    private void refreshAvailability() {
        tiles.forEach((kind, tile) -> {
            boolean unlocked = available(kind, features);
            tile.setVisible(unlocked);
            tile.setManaged(unlocked);
            tile.setDisable(!unlocked);
        });
    }

    private static String title(Kind kind) {
        return switch (kind) {
            case IF -> "if";
            case WHILE -> "while";
            case DO_WHILE -> "do / while";
        };
    }

    private final class Tile extends VBox {
        private final Kind kind;

        Tile(Kind kind) {
            super(3);
            this.kind = kind;
            setId("diagram-tool-" + kind.name().toLowerCase().replace('_', '-'));
            getStyleClass().add("diagram-tool");
            setUserData(kind);
            setAlignment(Pos.CENTER);
            setPadding(new Insets(5, 7, 4, 7));
            setFocusTraversable(true);
            setAccessibleRole(AccessibleRole.BUTTON);
            setAccessibleText("Insert " + title(kind) + " structure. Drag to a diagram gap, or press Enter then activate a gap.");
            setCursor(Cursor.HAND);
            Label caption = new Label(title(kind));
            caption.setStyle("-fx-font-family: monospace; -fx-font-size: 11px; -fx-text-fill: #365367;");
            getChildren().addAll(symbol(kind), caption);
            Tooltip.install(this, new Tooltip("Drag " + title(kind) + " into a diagram gap. Or select this symbol, then click a gap or press Enter there. Escape cancels selection."));
            focusedProperty().addListener((observable, before, after) -> refreshStyle());
            setOnMouseClicked(event -> {
                if (!isDisabled()) { selected.set(kind); requestFocus(); }
            });
            setOnKeyPressed(event -> {
                if (isDisabled()) return;
                if (event.getCode() == KeyCode.ENTER || event.getCode() == KeyCode.SPACE) {
                    selected.set(kind);
                    event.consume();
                } else if (event.getCode() == KeyCode.ESCAPE) {
                    clearSelection();
                    event.consume();
                }
            });
            setOnDragDetected(event -> {
                if (isDisabled() || !available(kind, features)) return;
                selected.set(kind);
                Dragboard board = startDragAndDrop(TransferMode.COPY);
                ClipboardContent content = new ClipboardContent();
                content.put(FORMAT, kind.name());
                board.setContent(content);
                event.consume();
            });
            refreshStyle();
        }

        @Override public void executeAccessibleAction(AccessibleAction action, Object... parameters) {
            if (action == AccessibleAction.FIRE && !isDisabled()) selected.set(kind);
            else super.executeAccessibleAction(action, parameters);
        }

        void refreshStyle() {
            boolean active = selected.get() == kind;
            setStyle("-fx-background-radius: 4; -fx-border-radius: 4; -fx-border-width: 1;"
                    + " -fx-background-color: " + (active ? "#dceef1" : "#ffffff") + ";"
                    + " -fx-border-color: " + (active || isFocused() ? "#157f83" : "#b9cbd3") + ";");
        }
    }

    private static Node symbol(Kind kind) {
        Pane icon = new Pane();
        icon.getStyleClass().add("diagram-symbol-" + kind.name().toLowerCase().replace('_', '-'));
        icon.setMinSize(68, 40);
        icon.setPrefSize(68, 40);
        icon.setMaxSize(68, 40);
        icon.setMouseTransparent(true);
        Rectangle frame = rectangle(1, 1, 66, 38, "#ffffff");
        icon.getChildren().add(frame);
        if (kind == Kind.IF) {
            Rectangle header = rectangle(1, 1, 66, 20, "#f4f0e2");
            icon.getChildren().addAll(header, line(1, 1, 34, 21), line(67, 1, 34, 21), line(34, 21, 34, 39));
        } else if (kind == Kind.WHILE) {
            icon.getChildren().addAll(rectangle(1, 1, 66, 11, "#e7f2f1"),
                    rectangle(1, 12, 10, 27, "#e7f2f1"));
        } else {
            icon.getChildren().addAll(rectangle(1, 1, 10, 27, "#e7f2f1"),
                    rectangle(1, 28, 66, 11, "#e7f2f1"));
        }
        return icon;
    }

    private static Rectangle rectangle(double x, double y, double width, double height, String color) {
        Rectangle rectangle = new Rectangle(x, y, width, height);
        rectangle.setFill(Color.web(color));
        rectangle.setStroke(INK);
        return rectangle;
    }

    private static Line line(double x1, double y1, double x2, double y2) {
        Line line = new Line(x1, y1, x2, y2);
        line.setStroke(INK);
        return line;
    }
}

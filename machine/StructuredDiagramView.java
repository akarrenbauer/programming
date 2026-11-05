package machine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javafx.application.Platform;
import javafx.beans.property.DoubleProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.AccessibleAction;
import javafx.scene.AccessibleRole;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.IndexRange;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Line;
import javafx.scene.shape.Rectangle;

/** Source-backed Nassi–Shneiderman editing with keyboard insertion and symbolic drag tools. */
public final class StructuredDiagramView extends BorderPane {
    private static final int MAX_RENDERED_NODES = 1000;
    private static final String OUTLINE = "-fx-border-color: #567080; -fx-border-width: 1;";
    private static final String PAPER = "-fx-background-color: white;";
    private static final String DAMAGED = "-fx-border-color: #bd352d; -fx-border-width: 2; -fx-background-color: #fff0ee;";
    private final Label status = new Label();
    private final VBox diagrams = new VBox(10);
    private final DiagramZoomPane zoomPane = new DiagramZoomPane(diagrams);
    private final ScrollPane scroll = new ScrollPane(diagrams);
    private final DiagramToolbox toolbox = new DiagramToolbox();
    private final Label title = new Label("Nassi–Shneiderman\ndiagram");
    private final HBox toolbar = new HBox(8);
    private final Map<StructuredDiagram.Node, TextInputControl> fields = new IdentityHashMap<>();
    private final Map<StructuredDiagram.Node, Region> boxes = new IdentityHashMap<>();
    private StructuredDiagram document;
    private String source;
    private Set<GameFeature> features = Set.of();
    private Map<String, String> libraries = Map.of();
    private Set<StructuredDiagram.Node> damaged = Set.of();
    private boolean damagedMain;
    private boolean damagedPreamble;
    private boolean synchronizedSource;
    private boolean preambleExpanded;
    private Consumer<String> onSourceChanged = ignored -> { };
    private Function<String, DiagramValidation.Result> validator;
    private Consumer<IndexRange> onSourceFocused = ignored -> { };
    private final List<SourceLink> sourceLinks = new ArrayList<>();
    private final Map<Node, SourceLink> focusLinks = new IdentityHashMap<>();
    private Region highlightedElement;
    private IndexRange highlightedRange;
    private int highlightedOffset = -1;
    private long highlightRevision;
    private GameTaskLanguage taskLanguage = GameTaskLanguage.ENGLISH;
    private String statusMessage = "";

    public StructuredDiagramView() {
        setId("structured-diagram");
        getStyleClass().add("structured-diagram-view");
        setMinWidth(240);
        title.setWrapText(true);
        title.setMinWidth(115);
        title.setPrefWidth(115);
        title.setMaxWidth(115);
        title.setStyle("-fx-font-size: 10px; -fx-font-weight: bold; -fx-text-fill: #193747;");
        title.setTooltip(new Tooltip("Drag a symbol into a gap. Enter at a box's start/end inserts a statement; inside it applies edits. Shift+Enter keeps a line break."));
        toolbar.getChildren().setAll(title, toolbox);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(toolbox, Priority.ALWAYS);
        toolbox.setMinWidth(0);
        status.setId("diagram-status");
        status.setWrapText(true);
        status.setMaxWidth(Double.MAX_VALUE);
        VBox header = new VBox(4, toolbar, status);
        header.setPadding(new Insets(8));
        setTop(header);
        diagrams.setPadding(new Insets(8));
        diagrams.setFillWidth(true);
        zoomPane.installIn(scroll);
        scroll.setStyle("-fx-background: #f3f6fa; -fx-background-color: #f3f6fa;");
        setCenter(scroll);
        resetContext();
    }

    /** Hosts a format selector in place of the title; null restores the standard label. */
    public void setTitleControl(Node control) {
        Node chosen = control == null ? title : control;
        if (toolbar.getChildren().get(0) != chosen) toolbar.getChildren().set(0, chosen);
    }

    /** Clears the prior level's cached tree before opening a different draft. */
    public void resetContext() {
        clearSourceHighlight(); sourceLinks.clear(); focusLinks.clear();
        document = null;
        source = null;
        synchronizedSource = false;
        damaged = Set.of();
        damagedMain = damagedPreamble = false;
        fields.clear();
        boxes.clear();
        diagrams.getChildren().clear();
        toolbox.clearSelection();
        toolbox.setDisable(true);
        showStatus("The diagram follows the code.", false);
    }

    /** Invalid source retains the last usable tree, marked and read-only, without changing the draft. */
    public void setSource(String value) {
        Objects.requireNonNull(value, "source");
        if (value.equals(source)) return;
        clearSourceHighlight();
        source = value;
        refreshSource();
    }

    private void refreshSource() {
        if (source == null) return;
        refreshSource(validate(source));
    }

    private void refreshSource(DiagramValidation.Result result) {
        if (result.valid() && result.diagram().nodeCount() <= MAX_RENDERED_NODES) {
            document = result.diagram();
            synchronizedSource = true;
            damaged = Set.of();
            damagedMain = damagedPreamble = false;
            render();
            showStatus("Code and diagram are synchronized.", false);
            return;
        }
        synchronizedSource = false;
        clearSourceHighlight(); sourceLinks.clear(); focusLinks.clear();
        boolean oversized = result.diagram() != null && result.diagram().nodeCount() > MAX_RENDERED_NODES;
        boolean hadDiagram = document != null;
        if (document == null && result.diagram() != null && !oversized) document = result.diagram();
        if (document != null) {
            DiagramValidation.Damage damage = DiagramValidation.damage(document, source, result);
            damaged = damage.nodes();
            damagedMain = damage.main() || oversized;
            damagedPreamble = damage.preamble();
            if (hadDiagram) applyDamage(); else render();
        } else {
            fields.clear();
            diagrams.getChildren().clear();
            Label message = new Label(unavailableText());
            message.getStyleClass().addAll("diagram-unavailable", "diagram-damaged");
            message.setWrapText(true);
            message.setPadding(new Insets(10));
            message.setStyle(DAMAGED);
            diagrams.getChildren().add(message);
        }
        toolbox.setDisable(true);
        String message = oversized ? "Too many blocks to display (maximum " + MAX_RENDERED_NODES + ")."
                : result.message();
        showStatus(message + (document == null ? "" : " — diagram is read-only until the code is corrected."), true);
        setAccessibleText("Diagram has errors. " + message);
    }

    public String getSource() { return source == null ? "" : source; }
    public boolean isSynchronized() { return synchronizedSource; }
    public String getStatusText() { return status.getText(); }
    public DoubleProperty zoomFactorProperty() { return zoomPane.zoomFactorProperty(); }
    public double getZoomFactor() { return zoomPane.getZoomFactor(); }
    public void setZoomFactor(double value) { zoomPane.setZoomFactor(value); }

    /** Language changes update feedback without discarding pending edits or recreating controls. */
    public void setTaskLanguage(GameTaskLanguage language) {
        taskLanguage = Objects.requireNonNull(language, "language");
        status.setText(localize(statusMessage));
        Node unavailable = lookup(".diagram-unavailable");
        if (unavailable instanceof Label label) label.setText(unavailableText());
    }

    public void setFeatures(Set<GameFeature> available) {
        Set<GameFeature> next = Set.copyOf(available);
        if (features.equals(next)) return;
        features = next;
        toolbox.setFeatures(features);
        refreshSource();
    }

    public void setLibraries(Map<String, String> available) {
        Map<String, String> next = Map.copyOf(available);
        if (libraries.equals(next)) return;
        libraries = next;
        refreshSource();
    }

    public void setOnSourceChanged(Consumer<String> callback) {
        onSourceChanged = Objects.requireNonNull(callback, "callback");
    }

    /** Supplies workspace validation; null restores the learning game's validation policy. */
    public void setValidator(Function<String, DiagramValidation.Result> callback) {
        validator = callback; revalidateSource();
    }

    public void revalidateSource() {
        if (source == null) return;
        DiagramValidation.Result result = validate(source);
        if (synchronizedSource && document != null && result.valid() && result.diagram().nodeCount() <= MAX_RENDERED_NODES
                && document.source().equals(result.diagram().source())
                && document.languageVersion() == result.diagram().languageVersion()
                && document.allowsExplicitJumps() == result.diagram().allowsExplicitJumps()) return;
        refreshSource(result);
    }

    private DiagramValidation.Result validate(String candidate) {
        return validator == null ? DiagramValidation.validate(candidate, features, libraries)
                : Objects.requireNonNull(validator.apply(candidate), "validation result");
    }

    public void setOnSourceFocused(Consumer<IndexRange> callback) { onSourceFocused = Objects.requireNonNull(callback, "callback"); }

    /** Selects a source-owned box without moving focus or emitting a source-focus callback. */
    public void highlightSourceOffset(int offset) {
        if (!synchronizedSource || document == null || offset < 0 || offset > document.source().length()) { clearSourceHighlight(); return; }
        SourceLink link = sourceLinkAt(offset);
        if (link == null && offset > 0 && document.source().charAt(offset - 1) != '\n') link = sourceLinkAt(offset - 1);
        if (link == null) { clearSourceHighlight(); return; }
        highlight(link, offset); revealLinkedElement(link.element());
    }

    public IndexRange getHighlightedRange() { return highlightedRange; }

    public void clearSourceHighlight() {
        ++highlightRevision;
        Region previous = highlightedElement;
        highlightedElement = null; highlightedRange = null; highlightedOffset = -1;
        if (previous != null) paintSourceHighlight(previous, false);
    }

    private record SourceLink(Region element, int start, int end, IndexRange range) { }

    private SourceLink sourceLinkAt(int offset) {
        return sourceLinks.stream().filter(link -> offset >= link.start() && (offset < link.end() || link.start() == link.end() && offset == link.start()))
                .min(Comparator.comparingInt((SourceLink link) -> link.end() - link.start()).thenComparingInt(link -> link.range().getLength())).orElse(null);
    }

    private void highlight(SourceLink link, int offset) {
        ++highlightRevision;
        if (highlightedElement != link.element()) {
            if (highlightedElement != null) paintSourceHighlight(highlightedElement, false);
            highlightedElement = link.element(); paintSourceHighlight(highlightedElement, true);
        }
        highlightedRange = link.range(); highlightedOffset = offset;
    }

    private static void paintSourceHighlight(Region element, boolean selected) {
        element.getStyleClass().remove("diagram-source-highlight");
        if (selected) element.getStyleClass().add("diagram-source-highlight");
        element.setStyle((String) element.getProperties().getOrDefault("diagram-base-style", ""));
        if (selected) element.setStyle(element.getStyle() + "-fx-border-color: #176cb1; -fx-border-width: 2; -fx-background-color: #e5f3ff;");
        if (element.getStyleClass().contains("diagram-damaged")) element.setStyle(element.getStyle() + DAMAGED);
    }

    private void bindSourceFocus(StructuredDiagram snapshot, Node control, Region element, IndexRange range) {
        SourceLink link = new SourceLink(element, range.getStart(), range.getEnd(), range);
        focusLinks.put(control, link);
        control.focusedProperty().addListener((observable, before, focused) -> {
            if (focused) {
                sourceFocused(snapshot, link);
                if (control == element && editable(snapshot)) revealLinkedElement(element);
            }
        });
        control.addEventHandler(MouseEvent.MOUSE_PRESSED, event -> {
            Node nearest = event.getTarget() instanceof Node node ? node : null;
            while (nearest != null && !focusLinks.containsKey(nearest)) nearest = nearest.getParent();
            if (nearest == control) {
                if (control == element && control.isFocusTraversable() && !(control instanceof TextInputControl)) control.requestFocus();
                sourceFocused(snapshot, link);
            }
        });
    }

    private void sourceFocused(StructuredDiagram snapshot, SourceLink link) {
        if (!editable(snapshot)) return;
        highlight(link, link.range().getStart()); onSourceFocused.accept(link.range());
    }

    private void mapSource(Region element, int start, int end, IndexRange range, boolean indentation) {
        if (indentation) {
            int line = document.source().lastIndexOf('\n', Math.max(-1, start - 1)) + 1;
            if (document.source().substring(line, start).isBlank()) start = line;
        }
        sourceLinks.add(new SourceLink(element, start, end, range));
    }

    private void revealLinkedElement(Region element) {
        if (element.getParent() == null) return;
        if (element.getUserData() instanceof StructuredDiagram.Node node && node.start() < document.preamble().end()
                && lookup("#diagram-preamble") instanceof TitledPane pane) pane.setExpanded(true);
        StructuredDiagram snapshot = document; long revision = highlightRevision;
        Platform.runLater(() -> {
            if (document != snapshot || !synchronizedSource || highlightRevision != revision || highlightedElement != element) return;
            zoomPane.reveal(element);
        });
    }

    private static int statementStart(StructuredDiagram snapshot, StructuredDiagram.Node node) {
        int start = node.start() + node.labels().length();
        while (start < node.end() && Character.isWhitespace(snapshot.source().charAt(start))) ++start;
        return start;
    }

    private static IndexRange nodeRange(StructuredDiagram snapshot, StructuredDiagram.Node node) {
        return node.kind() == StructuredDiagram.Kind.BLOCK ? new IndexRange(statementStart(snapshot, node), node.body().start())
                : new IndexRange(node.editStart(), node.editEnd());
    }

    private void render() {
        int selectedOffset = highlightedOffset;
        clearSourceHighlight(); sourceLinks.clear(); focusLinks.clear();
        fields.clear();
        boxes.clear();
        diagrams.getChildren().clear();
        StructuredDiagram snapshot = document;
        VBox preamble = sequence(snapshot, snapshot.preamble(), true, false);
        VBox program = snapshot.hasMain() ? sequence(snapshot, snapshot.main(), false, false) : null;
        if (damagedPreamble) markDamaged(preamble);
        if (damagedMain && program != null) markDamaged(program);
        TitledPane setup = new TitledPane("Declarations and helpers (" + snapshot.preamble().nodes().size() + ")", preamble);
        setup.setId("diagram-preamble");
        setup.setAnimated(false);
        setup.setExpanded(preambleExpanded || preambleDamaged() || !snapshot.hasMain());
        setup.setStyle("-fx-font-size: 11px;");
        setup.expandedProperty().addListener((observable, before, expanded) -> preambleExpanded = expanded);
        diagrams.getChildren().add(setup);
        if (program != null) {
            Label begin = new Label("begin");
            begin.setStyle("-fx-font-size: 10px; -fx-font-weight: bold; -fx-text-fill: #365367;");
            begin.getProperties().put("diagram-base-style", begin.getStyle());
            begin.setFocusTraversable(true);
            IndexRange range = new IndexRange(snapshot.preamble().end(), snapshot.main().start());
            bindSourceFocus(snapshot, begin, begin, range); mapSource(begin, range.getStart(), range.getEnd(), range, true);
            program.setId("diagram-main"); diagrams.getChildren().add(new VBox(3, begin, program));
        }
        toolbox.setDisable(!synchronizedSource);
        setAccessibleText(synchronizedSource ? "Editable Nassi–Shneiderman diagram." : "Read-only diagram with errors marked in red.");
        if (synchronizedSource && selectedOffset >= 0) {
            SourceLink link = sourceLinkAt(selectedOffset);
            if (link != null) highlight(link, selectedOffset);
        }
    }

    private VBox sequence(StructuredDiagram snapshot, StructuredDiagram.Sequence sequence, boolean preamble, boolean falseBranch) {
        VBox stack = new VBox();
        stack.getStyleClass().add("diagram-sequence");
        stack.setFillWidth(true);
        stack.setMinWidth(130);
        if (sequence.nodes().isEmpty()) {
            DropTarget empty = new DropTarget(snapshot, sequence, 0, preamble, true, null);
            VBox.setVgrow(empty, Priority.ALWAYS);
            if (falseBranch) empty.getStyleClass().add("diagram-empty-else");
            stack.getChildren().add(empty);
        } else {
            for (int index = 0; index <= sequence.nodes().size(); index++) {
                stack.getChildren().add(new DropTarget(snapshot, sequence, index, preamble, false, null));
                if (index < sequence.nodes().size()) stack.getChildren().add(block(snapshot, sequence, index, preamble));
            }
        }
        return stack;
    }

    private Node block(StructuredDiagram snapshot, StructuredDiagram.Sequence sequence, int index, boolean preamble) {
        StructuredDiagram.Node node = sequence.nodes().get(index);
        VBox box = new VBox();
        box.setId("diagram-node-" + node.start());
        box.getStyleClass().addAll("diagram-node", "diagram-" + node.kind().name().toLowerCase().replace('_', '-'));
        box.setUserData(node);
        box.setStyle(OUTLINE + PAPER);
        box.getProperties().put("diagram-base-style", box.getStyle());
        boxes.put(node, box);
        box.setFocusTraversable(true);
        IndexRange range = nodeRange(snapshot, node);
        bindSourceFocus(snapshot, box, box, range); mapSource(box, node.start(), node.end(), range, true);
        box.setMinWidth(130);
        if (damaged.contains(node)) markDamaged(box);
        if (!node.labels().isBlank()) {
            Label labels = new Label(node.labels().strip());
            labels.getStyleClass().add("diagram-labels");
            labels.setWrapText(true);
            labels.setStyle("-fx-font-family: monospace; -fx-font-size: 10px; -fx-text-fill: #567080; -fx-padding: 3 7;");
            labels.setFocusTraversable(true);
            IndexRange prefix = new IndexRange(node.start(), statementStart(snapshot, node));
            bindSourceFocus(snapshot, labels, box, prefix); mapSource(box, prefix.getStart(), prefix.getEnd(), prefix, false);
            box.getChildren().add(labels);
        }
        Button trash = deleteButton(snapshot, node);
        bindSourceFocus(snapshot, trash, box, range);
        switch (node.kind()) {
            case IF -> {
                TextInputControl condition = editField(snapshot, node, preamble);
                if (condition instanceof TextField text) text.setAlignment(Pos.CENTER);
                box.getChildren().add(new DecisionHeader(condition, trash));
                GridPane branches = new GridPane();
                branches.getStyleClass().add("diagram-branches");
                ColumnConstraints left = new ColumnConstraints();
                left.setPercentWidth(50);
                left.setHgrow(Priority.ALWAYS);
                ColumnConstraints right = new ColumnConstraints();
                right.setPercentWidth(50);
                right.setHgrow(Priority.ALWAYS);
                branches.getColumnConstraints().addAll(left, right);
                VBox yes = sequence(snapshot, node.body(), false, false);
                yes.setStyle("-fx-border-color: #567080; -fx-border-width: 0 1 0 0;");
                branches.add(yes, 0, 0);
                VBox no;
                if (node.alternative() != null) no = sequence(snapshot, node.alternative(), false, true);
                else {
                    DropTarget empty = new DropTarget(snapshot, null, 0, false, true, node);
                    empty.getStyleClass().add("diagram-empty-else");
                    VBox.setVgrow(empty, Priority.ALWAYS);
                    no = new VBox(empty);
                }
                no.setMinWidth(130);
                branches.add(no, 1, 0);
                box.getChildren().add(branches);
            }
            case WHILE, DO_WHILE -> {
                HBox condition = editRow(snapshot, node, "while", trash, preamble);
                condition.setStyle("-fx-background-color: #e7f2f1;");
                VBox body = sequence(snapshot, node.body(), false, false);
                body.setStyle("-fx-border-color: #80aaa8; -fx-border-width: 0 0 0 12;");
                if (node.kind() == StructuredDiagram.Kind.WHILE) box.getChildren().addAll(condition, body);
                else {
                    Label start = new Label("do");
                    start.setStyle("-fx-padding: 3 7; -fx-font-family: monospace; -fx-font-size: 11px;");
                    start.setFocusTraversable(true);
                    IndexRange header = new IndexRange(statementStart(snapshot, node), node.body().start());
                    bindSourceFocus(snapshot, start, box, header); mapSource(box, header.getStart(), header.getEnd(), header, false);
                    box.getChildren().addAll(start, body, condition);
                }
            }
            case MACRO -> box.getChildren().addAll(editRow(snapshot, node, "", trash, true), sequence(snapshot, node.body(), false, false));
            case BLOCK -> {
                Label label = new Label("Block");
                Region spacer = new Region();
                HBox.setHgrow(spacer, Priority.ALWAYS);
                HBox header = new HBox(5, label, spacer, trash);
                header.setPadding(new Insets(3, 7, 3, 7));
                box.getChildren().addAll(header, sequence(snapshot, node.body(), false, false));
            }
            default -> box.getChildren().add(editRow(snapshot, node, "", trash, preamble));
        }
        return box;
    }

    private static void markDamaged(Region region) {
        if (!region.getStyleClass().contains("diagram-damaged")) region.getStyleClass().add("diagram-damaged");
        region.setStyle(region.getStyle() + DAMAGED);
    }

    private boolean preambleDamaged() {
        return damagedPreamble || damaged.stream().anyMatch(node -> node.start() < document.preamble().end());
    }

    /** Keep the previous scene nodes and scroll position while the corresponding source is incomplete. */
    private void applyDamage() {
        clearSourceHighlight(); sourceLinks.clear(); focusLinks.clear();
        boxes.forEach((node, box) -> {
            box.getStyleClass().remove("diagram-damaged");
            box.setStyle((String) box.getProperties().get("diagram-base-style"));
            if (damaged.contains(node)) markDamaged(box);
        });
        fields.forEach((node, field) -> {
            field.setEditable(false);
            // Source typing is authoritative. A cached tree must show its actual
            // last-valid labels, rather than uncommitted text from a prior box edit.
            if (!field.getText().equals(node.editText())) field.setText(node.editText());
            field.getStyleClass().remove("diagram-field-error");
            field.setStyle((String) field.getProperties().get("diagram-base-style"));
        });
        lookupAll(".diagram-delete").forEach(node -> node.setDisable(true));
        lookupAll(".diagram-drop-target").forEach(node -> node.setFocusTraversable(false));
        Node main = lookup("#diagram-main");
        if (main instanceof Region region) {
            region.getStyleClass().remove("diagram-damaged");
            region.setStyle("");
            if (damagedMain) markDamaged(region);
        }
        Node setup = lookup("#diagram-preamble");
        if (setup instanceof TitledPane pane) {
            Region preamble = (Region) pane.getContent();
            preamble.getStyleClass().remove("diagram-damaged");
            preamble.setStyle("");
            if (damagedPreamble) markDamaged(preamble);
            if (preambleDamaged()) pane.setExpanded(true);
        }
    }

    private HBox editRow(StructuredDiagram snapshot, StructuredDiagram.Node node, String prefix, Button trash, boolean preamble) {
        TextInputControl field = editField(snapshot, node, preamble);
        HBox.setHgrow(field, Priority.ALWAYS);
        HBox row = new HBox(3);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(3));
        if (!prefix.isEmpty()) {
            Label label = new Label(prefix);
            label.setStyle("-fx-font-size: 11px; -fx-font-weight: bold;");
            row.getChildren().add(label);
        }
        row.getChildren().addAll(field, trash);
        return row;
    }

    private TextInputControl editField(StructuredDiagram snapshot, StructuredDiagram.Node node, boolean preamble) {
        boolean multiline = node.editText().contains("\n") || node.editText().contains("\r");
        TextInputControl field;
        if (multiline) {
            TextArea area = new TextArea(node.editText());
            area.setPrefRowCount((int) Math.min(5, 1 + node.editText().chars().filter(c -> c == '\n').count()));
            area.setWrapText(false);
            area.setPrefColumnCount(12);
            area.getStyleClass().add("diagram-edit-multiline");
            field = area;
        } else {
            TextField single = new TextField(node.editText());
            single.setPrefColumnCount(12);
            field = single;
        }
        fields.put(node, field);
        IndexRange range = new IndexRange(node.editStart(), node.editEnd());
        bindSourceFocus(snapshot, field, boxes.get(node), range);
        mapSource(boxes.get(node), range.getStart(), range.getEnd(), range, false);
        field.setId("diagram-edit-" + node.start());
        field.getStyleClass().add("diagram-edit");
        field.setAccessibleText(node.kind().name().toLowerCase().replace('_', ' ') + " text");
        field.setEditable(synchronizedSource);
        field.setStyle("-fx-font-family: monospace; -fx-font-size: 12px; -fx-background-color: transparent;");
        field.getProperties().put("diagram-base-style", field.getStyle());
        field.setMinWidth(30);
        field.setTooltip(new Tooltip(preamble ? "Edit and press Enter to apply. Add declarations in the code editor."
                : "Enter at the start inserts before; at the end inserts after; inside applies edits. Shift+Enter adds a line break to multiline boxes."));
        field.textProperty().addListener((observable, before, after) -> {
            if (editable(snapshot) && !after.equals(node.editText())) showStatus("Edit pending — press Enter to apply.", false);
        });
        field.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ENTER && !event.isShiftDown()) {
                enter(snapshot, node, field, preamble);
                event.consume();
            }
        });
        // Also supports assistive technology and callers firing a TextField action.
        if (field instanceof TextField single) single.setOnAction(event -> enter(snapshot, node, field, preamble));
        return field;
    }

    private void enter(StructuredDiagram snapshot, StructuredDiagram.Node node, TextInputControl field, boolean preamble) {
        if (!editable(snapshot)) return;
        int caret = field.getCaretPosition();
        boolean before = !preamble && caret == 0;
        boolean after = !preamble && !before && caret == field.getLength();
        try {
            Pending pending = pending(snapshot);
            if (before || after) {
                if (!features.contains(GameFeature.EMPTY_STATEMENTS)) throw new IllegalArgumentException("Empty statements are still locked.");
                int offset = pending.map(before ? node.start() : node.end());
                Insertion insertion = insertAt(pending.source(), offset, ";", indentation(pending.source(), pending.map(node.start())));
                accept(snapshot, insertion.source(), insertion.start());
            } else accept(snapshot, pending.source(), -1);
        } catch (IllegalArgumentException error) { reject(error.getMessage()); }
    }

    private Button deleteButton(StructuredDiagram snapshot, StructuredDiagram.Node node) {
        Button button = new Button();
        button.setId("diagram-delete-" + node.start());
        button.getStyleClass().add("diagram-delete");
        button.setGraphic(trashIcon());
        button.setAccessibleText("Delete " + node.kind().name().toLowerCase().replace('_', ' ') + " block");
        button.setTooltip(new Tooltip("Delete this block"));
        button.setStyle("-fx-padding: 3 4; -fx-background-color: transparent;");
        button.setDisable(!synchronizedSource);
        button.setOnAction(event -> {
            if (!editable(snapshot)) return;
            Pending pending = pending(snapshot);
            int start = pending.map(node.start());
            int end = pending.map(node.end());
            accept(snapshot, pending.source().substring(0, start) + pending.source().substring(end), -1);
        });
        return button;
    }

    private static Node trashIcon() {
        Pane icon = new Pane();
        icon.getStyleClass().add("diagram-trash-icon");
        icon.setMinSize(15, 17);
        icon.setPrefSize(15, 17);
        icon.setMaxSize(15, 17);
        icon.setMouseTransparent(true);
        Rectangle bin = new Rectangle(3, 5, 9, 10);
        bin.setArcWidth(2);
        bin.setArcHeight(2);
        bin.setFill(Color.TRANSPARENT);
        bin.setStroke(Color.web("#647781"));
        icon.getChildren().addAll(bin, line(2, 3, 13, 3), line(5, 1, 10, 1), line(6, 7, 6, 13), line(9, 7, 9, 13));
        return icon;
    }

    private boolean editable(StructuredDiagram snapshot) {
        return synchronizedSource && document == snapshot && !isDisabled();
    }

    private record Edit(int start, int end, String value) { }
    private record Pending(String source, List<Edit> edits) {
        int map(int original) {
            int mapped = original;
            for (Edit edit : edits) if (edit.end() <= original) mapped += edit.value().length() - (edit.end() - edit.start());
            return mapped;
        }
    }

    /** Apply every pending field in one source transaction, before resolving an insertion boundary. */
    private Pending pending(StructuredDiagram snapshot) {
        List<Edit> edits = new ArrayList<>();
        fields.forEach((node, field) -> {
            if (!field.getText().equals(node.editText())) edits.add(new Edit(node.editStart(), node.editEnd(), field.getText()));
        });
        edits.sort(Comparator.comparingInt(Edit::start).reversed());
        String next = snapshot.source();
        for (Edit edit : edits) next = next.substring(0, edit.start()) + edit.value() + next.substring(edit.end());
        return new Pending(next, edits);
    }

    private record Insertion(String source, int start) { }

    private static Insertion insertAt(String source, int offset, String text, String indent) {
        String newline = source.contains("\r\n") ? "\r\n" : "\n";
        String prefix = newline + indent;
        String body = text.replace("\r\n", "\n").replace('\r', '\n').replace("\n", newline + indent);
        String inserted = prefix + body + newline + indentation(source, offset);
        return new Insertion(source.substring(0, offset) + inserted + source.substring(offset), offset + prefix.length());
    }

    private static String indentation(String source, int position) {
        int start = source.lastIndexOf('\n', Math.max(-1, position - 1)) + 1;
        int end = start;
        while (end < position && (source.charAt(end) == ' ' || source.charAt(end) == '\t')) ++end;
        return source.substring(start, end);
    }

    private boolean accept(StructuredDiagram snapshot, String next, int focusOffset) {
        if (!editable(snapshot)) return false;
        DiagramValidation.Result result = validate(next);
        if (!result.valid()) { reject(result.message()); return false; }
        if (result.diagram().nodeCount() > MAX_RENDERED_NODES) { reject("The diagram can display at most " + MAX_RENDERED_NODES + " blocks."); return false; }
        if (next.equals(source)) { showStatus("Code and diagram are synchronized.", false); return true; }
        source = next;
        document = result.diagram();
        damaged = Set.of();
        damagedMain = damagedPreamble = false;
        render();
        showStatus("Code and diagram are synchronized.", false);
        onSourceChanged.accept(source);
        if (focusOffset >= 0) focusInserted(document, focusOffset);
        return true;
    }

    private void focusInserted(StructuredDiagram snapshot, int offset) {
        TextInputControl target = fields.entrySet().stream().filter(entry -> entry.getKey().start() == offset)
                .map(Map.Entry::getValue).findFirst().orElse(null);
        if (target == null) return;
        Platform.runLater(() -> {
            if (!editable(snapshot) || target.getScene() == null) return;
            target.requestFocus();
            target.selectAll();
        });
    }

    private void reject(String message) {
        showStatus("Edit not applied: " + message, true);
        fields.forEach((node, field) -> {
            if (!field.getText().equals(node.editText())) {
                if (!field.getStyleClass().contains("diagram-field-error")) field.getStyleClass().add("diagram-field-error");
                field.setStyle(field.getStyle() + "-fx-border-color: #bd352d; -fx-border-width: 1;");
            }
        });
    }

    private String conditionVariable(StructuredDiagram snapshot, StructuredDiagram.Sequence target) {
        // A macro parameter is preferable to a global when inserting into its body.
        for (StructuredDiagram.Node node : snapshot.preamble().nodes()) {
            if (node.kind() == StructuredDiagram.Kind.MACRO && target != null
                    && target.start() >= node.start() && target.end() <= node.end()) {
                Matcher parameter = Pattern.compile("\\(\\s*([A-Za-z_][A-Za-z_0-9]*)").matcher(node.editText());
                if (parameter.find()) return parameter.group(1);
            }
        }
        for (StructuredDiagram.Node node : snapshot.preamble().nodes()) {
            if (node.kind() != StructuredDiagram.Kind.DECLARATION) continue;
            Matcher name = Pattern.compile("var\\s+([A-Za-z_][A-Za-z_0-9]*)(\\s*\\[)?").matcher(node.editText());
            if (name.find()) return name.group(1) + (name.group(2) == null ? "" : "[0]");
        }
        throw new IllegalArgumentException("Declare a condition variable in the code before inserting a control structure.");
    }

    private final class DropTarget extends Pane {
        private final StructuredDiagram snapshot;
        private final StructuredDiagram.Sequence sequence;
        private final int index;
        private final boolean preamble;
        private final boolean empty;
        private final StructuredDiagram.Node omittedElse;
        private final Line first = line(0, 0, 1, 1);
        private final Line second = line(0, 1, 1, 0);

        DropTarget(StructuredDiagram snapshot, StructuredDiagram.Sequence sequence, int index, boolean preamble,
                boolean empty, StructuredDiagram.Node omittedElse) {
            this.snapshot = snapshot;
            this.sequence = sequence;
            this.index = index;
            this.preamble = preamble;
            this.empty = empty;
            this.omittedElse = omittedElse;
            setId(omittedElse == null ? "diagram-drop-" + sequence.start() + "-" + index : "diagram-else-drop-" + omittedElse.start());
            getStyleClass().add("diagram-drop-target");
            if (empty) getStyleClass().add("diagram-empty");
            setMinHeight(empty ? 42 : 7);
            setPrefHeight(empty ? 42 : 7);
            setFocusTraversable(!preamble && synchronizedSource);
            setAccessibleRole(AccessibleRole.BUTTON);
            setAccessibleText(omittedElse != null ? "Empty else branch. Insert a selected structure or press Enter for an empty statement."
                    : empty ? "Empty sequence. Insert a selected structure or press Enter for an empty statement."
                    : "Insertion gap. Drop a control structure, or press Enter to insert a statement.");
            first.setMouseTransparent(true);
            second.setMouseTransparent(true);
            getChildren().add(first);
            if (empty) getChildren().add(second);
            Tooltip.install(this, new Tooltip(preamble ? "Add declarations in the code editor."
                    : "Drop a symbol here, or select a toolbox symbol then click. Enter inserts an empty statement."));
            setOnMouseEntered(event -> highlight(true));
            setOnMouseExited(event -> highlight(false));
            focusedProperty().addListener((observable, before, focused) -> highlight(focused));
            setOnMouseClicked(event -> { if (!preamble && toolbox.selectedKind() != null) insert(toolbox.selectedKind()); });
            setOnKeyPressed(event -> {
                if (event.getCode() == KeyCode.ENTER || event.getCode() == KeyCode.SPACE) {
                    insert(toolbox.selectedKind());
                    event.consume();
                }
            });
            setOnDragOver(event -> {
                DiagramToolbox.Kind kind = DiagramToolbox.read(event.getDragboard());
                if (canInsert(kind)) { event.acceptTransferModes(TransferMode.COPY); highlight(true); }
                event.consume();
            });
            setOnDragExited(event -> highlight(false));
            setOnDragDropped(event -> {
                DiagramToolbox.Kind kind = DiagramToolbox.read(event.getDragboard());
                event.setDropCompleted(kind != null && canInsert(kind) && insert(kind));
                highlight(false);
                event.consume();
            });
        }

        private boolean canInsert(DiagramToolbox.Kind kind) {
            return !preamble && editable(snapshot) && DiagramToolbox.available(kind, features)
                    && (omittedElse == null || features.contains(GameFeature.ELSE));
        }

        private boolean insert(DiagramToolbox.Kind kind) {
            if (preamble || !editable(snapshot)) return false;
            if (kind != null && !canInsert(kind)) return false;
            if (kind == null && !features.contains(GameFeature.EMPTY_STATEMENTS)) { reject("Empty statements are still locked."); return false; }
            if (omittedElse != null && !features.contains(GameFeature.ELSE)) { reject("Else branches are still locked."); return false; }
            try {
                String snippet = kind == null ? ";" : DiagramToolbox.template(kind, conditionVariable(snapshot,
                        omittedElse == null ? sequence : omittedElse.body()));
                Pending pending = pending(snapshot);
                Insertion insertion;
                if (omittedElse != null) {
                    int offset = pending.map(omittedElse.end());
                    String indent = indentation(pending.source(), pending.map(omittedElse.start()));
                    String start = " else {\n" + indent + "  ";
                    String inserted = start + snippet.replace("\n", "\n" + indent + "  ") + "\n" + indent + "}";
                    insertion = new Insertion(pending.source().substring(0, offset) + inserted + pending.source().substring(offset), offset + start.length());
                } else {
                    int original = index < sequence.nodes().size() ? sequence.nodes().get(index).start() : sequence.end();
                    int offset = pending.map(original);
                    String indent = indentation(pending.source(), offset);
                    if (sequence != snapshot.main() && sequence != snapshot.preamble()
                            && index == sequence.nodes().size()) indent += "  ";
                    insertion = insertAt(pending.source(), offset, snippet, indent);
                }
                boolean applied = accept(snapshot, insertion.source(), insertion.start());
                if (applied) toolbox.clearSelection();
                return applied;
            } catch (IllegalArgumentException error) { reject(error.getMessage()); return false; }
        }

        private void highlight(boolean active) {
            setStyle(active && editable(snapshot) && !preamble ? "-fx-background-color: #c9e9e6;" : "-fx-background-color: transparent;");
        }

        @Override public void executeAccessibleAction(AccessibleAction action, Object... parameters) {
            if (action == AccessibleAction.FIRE) insert(toolbox.selectedKind());
            else super.executeAccessibleAction(action, parameters);
        }

        @Override protected void layoutChildren() {
            double width = getWidth();
            double height = getHeight();
            first.setStartX(0);
            first.setStartY(empty ? 0 : height / 2);
            first.setEndX(width);
            first.setEndY(empty ? height : height / 2);
            first.setStroke(Color.web(empty ? "#91a4ad" : "#d5e1e6"));
            if (empty) {
                second.setStartX(0); second.setStartY(height);
                second.setEndX(width); second.setEndY(0);
                second.setStroke(Color.web("#91a4ad"));
            }
        }
    }

    private void showStatus(String text, boolean error) {
        statusMessage = text;
        status.setText(localize(text));
        status.setStyle("-fx-font-size: 10px; -fx-text-fill: " + (error ? "#b3332a" : "#34755a") + ";");
    }

    private String unavailableText() {
        return taskLanguage == GameTaskLanguage.GERMAN
                ? "Vervollständige die Programmstruktur im Code, um das Diagramm anzuzeigen. Dein Entwurf bleibt erhalten."
                : "Finish the program structure in code to show its diagram. Your draft is kept as typed.";
    }

    private String localize(String text) {
        if (taskLanguage != GameTaskLanguage.GERMAN) return text;
        if (text.equals("Code and diagram are synchronized.")) return "Code und Diagramm sind synchronisiert.";
        if (text.equals("The diagram follows the code.")) return "Das Diagramm folgt dem Code.";
        if (text.equals("Edit pending — press Enter to apply.")) return "Änderung noch offen — mit Enter übernehmen.";
        if (text.startsWith("Edit not applied: ")) return "Änderung nicht übernommen: "
                + GameMessages.diagnostic(taskLanguage, text.substring("Edit not applied: ".length()));
        String suffix = " — diagram is read-only until the code is corrected.";
        if (text.endsWith(suffix)) return GameMessages.diagnostic(taskLanguage, text.substring(0, text.length() - suffix.length()))
                + " — das Diagramm ist bis zur Korrektur des Codes schreibgeschützt.";
        return GameMessages.diagnostic(taskLanguage, text);
    }

    private static Line line(double x1, double y1, double x2, double y2) {
        Line line = new Line(x1, y1, x2, y2);
        line.setStroke(Color.web("#647781"));
        return line;
    }

    /** Condition bounds are symmetric around the complete header; the trash control is an independent overlay. */
    private static final class DecisionHeader extends Pane {
        private final TextInputControl condition;
        private final Button trash;
        private final Line left = line(0, 0, 0, 0);
        private final Line right = line(0, 0, 0, 0);
        private final Label yes = new Label("true");
        private final Label no = new Label("false");

        DecisionHeader(TextInputControl condition, Button trash) {
            this.condition = condition;
            this.trash = trash;
            getStyleClass().add("diagram-decision");
            setMinHeight(Region.USE_PREF_SIZE);
            setMinWidth(260);
            setStyle("-fx-background-color: #f4f0e2; -fx-border-color: #567080; -fx-border-width: 0 0 1 0;");
            left.setMouseTransparent(true);
            right.setMouseTransparent(true);
            yes.setStyle("-fx-font-size: 10px; -fx-text-fill: #34755a;");
            no.setStyle("-fx-font-size: 10px; -fx-text-fill: #9a431f;");
            getChildren().addAll(left, right, condition, yes, no, trash);
        }

        @Override protected double computePrefHeight(double width) {
            return Math.max(72, condition.prefHeight((width < 0 ? 300 : width) * 0.55) * 2.5 + 6);
        }

        @Override protected void layoutChildren() {
            double width = getWidth();
            double height = getHeight();
            left.setStartX(0); left.setStartY(0); left.setEndX(width / 2); left.setEndY(height);
            right.setStartX(width); right.setStartY(0); right.setEndX(width / 2); right.setEndY(height);
            condition.resizeRelocate(width * 0.225, 3, width * 0.55, condition.prefHeight(width * 0.55));
            trash.resizeRelocate(width - trash.prefWidth(-1) - 3, 3, trash.prefWidth(-1), trash.prefHeight(-1));
            yes.resizeRelocate(7, height - 22, yes.prefWidth(-1), 18);
            no.resizeRelocate(width - no.prefWidth(-1) - 7, height - 22, no.prefWidth(-1), 18);
        }
    }
}

package machine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
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
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.AccessibleAction;
import javafx.scene.AccessibleRole;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Dialog;
import javafx.scene.control.IndexRange;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Tooltip;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DataFormat;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Line;
import javafx.scene.shape.Circle;
import javafx.scene.shape.LineTo;
import javafx.scene.shape.MoveTo;
import javafx.scene.shape.Path;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.Shape;

/** Editable, source-owned control-flow graph for the machine foundations world. */
public final class FlowchartView extends javafx.scene.layout.BorderPane {
    private enum Tool { RECTANGLE, DIAMOND }
    private static final DataFormat FORMAT = new DataFormat("application/x-language-game-flowchart");
    private static final DataFormat HEAD_FORMAT = new DataFormat("application/x-language-game-jump-head");
    private static final Color INK = Color.web("#527180");
    private static final Color ERROR = Color.web("#bd352d");
    private static final Color EXECUTION = Color.web("#30843c");
    private static final Color TRANSITION = Color.web("#bd6c08");
    private final Label status = new Label();
    private final VBox content = new VBox(8);
    private final ScrollPane scroll = new ScrollPane(content);
    private final DiagramZoomPane zoomPane = new DiagramZoomPane(content);
    private final FlowPane palette = new FlowPane(5, 4);
    private final Map<Tool, VBox> tools = new LinkedHashMap<>();
    private final List<Field> fields = new ArrayList<>();
    private final Map<StructuredDiagram.Node, List<ShapeBox>> boxes = new IdentityHashMap<>();
    private final Map<StructuredDiagram.Node, List<Region>> setupRows = new IdentityHashMap<>();
    private final Map<StructuredDiagram.Node, Label> runtimeLabels = new IdentityHashMap<>();
    private Map<Integer, String> runtimeValues = Map.of();
    private Label runtimeSummary;
    private TitledPane setupPane;
    private GraphPane renderedGraph;
    private FlowchartExecution.Frame executionFrame;
    private boolean executionHalted;
    private StructuredDiagram document;
    private Flowchart graph;
    private String source;
    private Set<GameFeature> features = Set.of();
    private Map<String, String> libraries = Map.of();
    private LanguageVersion version = LanguageVersion.BEFORE_DO_WHILE;
    private Function<String, LanguageVersion> languageVersionResolver;
    private Function<String, DiagramValidation.Result> validator;
    private GameTaskLanguage taskLanguage = GameTaskLanguage.ENGLISH;
    private Consumer<String> onSourceChanged = ignored -> { };
    private Consumer<IndexRange> onSourceFocused = ignored -> { };
    private final List<SourceLink> sourceLinks = new ArrayList<>();
    private final Map<Node, SourceLink> focusLinks = new IdentityHashMap<>();
    private Region highlightedElement;
    private IndexRange highlightedRange;
    private int highlightedOffset = -1;
    private long highlightRevision;
    private boolean synchronizedSource;
    private boolean setupExpanded;
    private Tool selectedTool;
    private Set<StructuredDiagram.Node> damaged = Set.of();
    private boolean damagedMain;
    private boolean damagedPreamble;
    private String statusMessage = "";
    private Dialog<ButtonType> rawEditor;
    private long graphRevision;

    public FlowchartView() {
        setId("flowchart");
        getStyleClass().add("flowchart-view");
        setMinWidth(240);
        Label title = new Label("Flowchart");
        title.setMinWidth(Region.USE_PREF_SIZE);
        title.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #193747;");
        title.setTooltip(new Tooltip("Drag a symbol into a gap. Enter at the start/end inserts a statement; inside applies edits. Labels and JNZ targets are editable."));
        palette.setId("flowchart-toolbox");
        for (Tool tool : Tool.values()) { VBox tile = tile(tool); tools.put(tool, tile); palette.getChildren().add(tile); }
        HBox toolbar = new HBox(8, title, palette);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(palette, Priority.ALWAYS);
        status.setId("flowchart-status");
        status.setWrapText(true);
        VBox header = new VBox(4, toolbar, status);
        header.setPadding(new Insets(7));
        setTop(header);
        content.setPadding(new Insets(8));
        zoomPane.installIn(scroll);
        scroll.setStyle("-fx-background: #f3f6fa; -fx-background-color: #f3f6fa;");
        setCenter(scroll);
        refreshTools();
        resetContext();
    }

    public void resetContext() {
        clearExecutionFrame(); clearRuntimeValues();
        clearSourceHighlight(); sourceLinks.clear(); focusLinks.clear();
        if (rawEditor != null) { rawEditor.close(); rawEditor = null; }
        document = null; graph = null; source = null;
        synchronizedSource = false; damaged = Set.of(); damagedMain = damagedPreamble = false;
        fields.clear(); boxes.clear(); setupRows.clear(); content.getChildren().clear();
        runtimeLabels.clear(); runtimeSummary = null; setupPane = null; renderedGraph = null;
        select(null); palette.setDisable(true);
        showStatus("The flowchart follows the code.", false);
    }

    public void setSource(String value) {
        Objects.requireNonNull(value, "source");
        if (value.equals(source)) return;
        clearExecutionFrame(); clearRuntimeValues();
        clearSourceHighlight();
        source = value;
        refreshSource();
    }

    public String getSource() { return source == null ? "" : source; }
    public DoubleProperty zoomFactorProperty() { return zoomPane.zoomFactorProperty(); }
    public double getZoomFactor() { return zoomPane.getZoomFactor(); }
    public void setZoomFactor(double value) { zoomPane.setZoomFactor(value); }
    public boolean isSynchronized() { return synchronizedSource; }
    public String getStatusText() { return status.getText(); }

    /** The current graph only; cached invalid-source diagrams never expose stale execution mappings. */
    public Flowchart getGraph() { return synchronizedSource ? graph : null; }

    public FlowchartExecution.Frame getExecutionFrame() { return executionFrame; }
    public boolean isExecutionHalted() { return executionHalted; }

    public void setExecutionFrame(FlowchartExecution.Frame frame) { setExecutionFrame(frame, false); }

    /** Decorations follow observed execution; they never edit fields, move focus, or infer a branch from live values. */
    public void setExecutionFrame(FlowchartExecution.Frame frame, boolean halted) {
        if (frame == null || !synchronizedSource || graph == null || frame.nextVertex() < -1
                || frame.nextVertex() >= graph.vertices().size() || !graph.edges().containsAll(frame.transitions())) {
            clearExecutionFrame(); return;
        }
        executionFrame = frame; executionHalted = halted;
        if (renderedGraph != null) renderedGraph.paintExecution();
    }

    public void clearExecutionFrame() {
        executionFrame = null; executionHalted = false;
        if (renderedGraph != null) renderedGraph.paintExecution();
    }

    public Map<Integer, String> getRuntimeValues() { return runtimeValues; }

    /** Values are anchored to declarations in the unchanged source and rendered outside editable text. */
    public void setRuntimeValues(Map<Integer, String> values) {
        Objects.requireNonNull(values, "values");
        Map<Integer, String> next = synchronizedSource ? Map.copyOf(values) : Map.of();
        if (next.equals(runtimeValues)) return;
        runtimeValues = next; updateRuntimeLabels();
    }

    public void clearRuntimeValues() {
        if (runtimeValues.isEmpty()) return;
        runtimeValues = Map.of(); updateRuntimeLabels();
    }

    public void setFeatures(Set<GameFeature> available) {
        Set<GameFeature> next = Set.copyOf(available);
        if (next.equals(features)) return;
        features = next; refreshTools(); refreshSource();
    }

    public void setLibraries(Map<String, String> available) {
        Map<String, String> next = Map.copyOf(available);
        if (next.equals(libraries)) return;
        libraries = next; refreshSource();
    }

    public void setLanguageVersion(LanguageVersion languageVersion) {
        Objects.requireNonNull(languageVersion, "version");
        if (version == languageVersion) return;
        version = languageVersion; refreshTools(); refreshSource();
    }

    /** Allows the conversion lesson to validate the edited candidate in its own keyword profile. */
    public void setLanguageVersionResolver(Function<String, LanguageVersion> resolver) {
        languageVersionResolver = Objects.requireNonNull(resolver, "resolver");
        refreshTools();
        if (source != null && document != null && document.languageVersion() != languageFor(source)) refreshSource();
    }

    private LanguageVersion languageFor(String text) {
        return languageVersionResolver == null ? version : Objects.requireNonNull(languageVersionResolver.apply(text), "resolved language version");
    }

    public void setTaskLanguage(GameTaskLanguage language) {
        taskLanguage = Objects.requireNonNull(language, "language");
        status.setText(localize(statusMessage));
        Node unavailable = lookup(".flowchart-unavailable");
        if (unavailable instanceof Label label) label.setText(unavailableText());
    }

    public void setOnSourceChanged(Consumer<String> callback) { onSourceChanged = Objects.requireNonNull(callback, "callback"); }

    /** Supplies workspace validation; null restores the learning game's validation policy. */
    public void setValidator(Function<String, DiagramValidation.Result> callback) {
        validator = callback; revalidateSource();
    }

    /** Rechecks changed imports while retaining current fields and their pending edits when still valid. */
    public void revalidateSource() {
        if (source == null) return;
        DiagramValidation.Result result = validate(source);
        if (synchronizedSource && document != null && result.valid()
                && result.diagram().nodeCount() <= DiagramValidation.MAX_RENDERED_NODES
                && document.source().equals(result.diagram().source())
                && document.languageVersion() == result.diagram().languageVersion()
                && document.allowsExplicitJumps() == result.diagram().allowsExplicitJumps()) return;
        refreshSource(result);
    }

    private DiagramValidation.Result validate(String candidate) {
        return validator == null ? DiagramValidation.validate(candidate, features, libraries, languageFor(candidate), true)
                : Objects.requireNonNull(validator.apply(candidate), "validation result");
    }

    public void setOnSourceFocused(Consumer<IndexRange> callback) { onSourceFocused = Objects.requireNonNull(callback, "callback"); }

    /** Selects the source-owned shape without moving focus or emitting a source-focus callback. */
    public void highlightSourceOffset(int offset) {
        if (!synchronizedSource || document == null || offset < 0 || offset > document.source().length()) { clearSourceHighlight(); return; }
        SourceLink link = sourceLinkAt(offset);
        if (link == null && offset > 0 && document.source().charAt(offset - 1) != '\n') link = sourceLinkAt(offset - 1);
        if (link == null) { clearSourceHighlight(); return; }
        highlight(link, offset); revealLinkedElement(link.element());
    }

    /** Null when no current, valid source element is highlighted. */
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
                .min(Comparator.comparingInt((SourceLink link) -> link.end() - link.start())
                        .thenComparingInt(link -> link.range().getLength())).orElse(null);
    }

    private void highlight(SourceLink link, int offset) {
        ++highlightRevision;
        if (highlightedElement != link.element()) {
            if (highlightedElement != null) paintSourceHighlight(highlightedElement, false);
            highlightedElement = link.element(); paintSourceHighlight(highlightedElement, true);
        }
        highlightedRange = link.range(); highlightedOffset = offset;
    }

    private void paintSourceHighlight(Region element, boolean selected) {
        element.getStyleClass().remove("flowchart-source-highlight");
        if (selected) element.getStyleClass().add("flowchart-source-highlight");
        if (element instanceof ShapeBox shape) shape.damage(shape.vertex.owner() != null && damaged.contains(shape.vertex.owner()));
        else {
            element.setStyle((String) element.getProperties().getOrDefault("flowchart-base-style", ""));
            if (selected) element.setStyle(element.getStyle() + "-fx-border-color: #176cb1; -fx-border-width: 2; -fx-background-color: #e5f3ff;");
            if (element.getStyleClass().contains("flowchart-damaged")) element.setStyle(element.getStyle() + "-fx-border-color: #bd352d; -fx-background-color: #fff0ee;");
        }
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
        if (setupRows.values().stream().anyMatch(rows -> rows.contains(element)) && lookup("#flowchart-preamble") instanceof TitledPane pane) pane.setExpanded(true);
        for (Node parent = element; parent != null; parent = parent.getParent()) if (parent instanceof TitledPane pane) pane.setExpanded(true);
        StructuredDiagram snapshot = document;
        long revision = highlightRevision;
        Platform.runLater(() -> {
            if (document != snapshot || !synchronizedSource || highlightRevision != revision || highlightedElement != element) return;
            zoomPane.reveal(element);
        });
    }

    private void refreshSource() {
        if (source == null) return;
        refreshTools();
        refreshSource(validate(source));
    }

    private void refreshSource(DiagramValidation.Result result) {
        if (result.valid()) {
            try {
                Flowchart next = Flowchart.from(result.diagram());
                document = result.diagram(); graph = next; synchronizedSource = true;
                damaged = Set.of(); damagedMain = damagedPreamble = false;
                render(); showStatus("Code and flowchart are synchronized.", false); return;
            } catch (IllegalArgumentException error) {
                result = new DiagramValidation.Result(result.diagram(), List.of(new DiagramValidation.Diagnostic(error.getMessage(), -1, -1)));
            }
        }
        synchronizedSource = false;
        clearExecutionFrame(); clearRuntimeValues();
        clearSourceHighlight(); sourceLinks.clear(); focusLinks.clear();
        boolean existing = document != null;
        if (!existing && result.diagram() != null && result.diagram().nodeCount() <= DiagramValidation.MAX_RENDERED_NODES) {
            try { graph = Flowchart.from(result.diagram()); document = result.diagram(); }
            catch (IllegalArgumentException ignored) { /* Unresolved jumps have no trustworthy graph yet. */ }
        }
        if (document != null) {
            DiagramValidation.Damage damage = DiagramValidation.damage(document, source, result);
            damaged = damage.nodes(); damagedMain = damage.main(); damagedPreamble = damage.preamble();
            if (existing) applyDamage(); else render();
        } else {
            fields.clear(); boxes.clear(); setupRows.clear(); content.getChildren().clear();
            Label unavailable = new Label(unavailableText());
            unavailable.getStyleClass().addAll("flowchart-unavailable", "flowchart-damaged");
            unavailable.setStyle("-fx-border-color: #bd352d; -fx-background-color: #fff0ee; -fx-padding: 10;");
            unavailable.setWrapText(true); content.getChildren().add(unavailable);
        }
        palette.setDisable(true);
        showStatus(result.message() + (document == null ? "" : " — flowchart is read-only until the code is corrected."), true);
    }

    private void render() {
        int selectedOffset = highlightedOffset;
        clearSourceHighlight(); sourceLinks.clear(); focusLinks.clear();
        fields.clear(); boxes.clear(); setupRows.clear(); content.getChildren().clear();
        runtimeLabels.clear(); renderedGraph = null;
        VBox setup = new VBox(3);
        for (StructuredDiagram.Node node : document.preamble().nodes()) setup.getChildren().add(setupNode(document, node));
        if (setup.getChildren().isEmpty()) setup.getChildren().add(new Label("No declarations or helpers"));
        TitledPane preamble = new TitledPane("Declarations and helpers (" + document.preamble().nodes().size() + ")", setup);
        setupPane = preamble;
        runtimeSummary = runtimeLabel(); runtimeSummary.getStyleClass().add("flowchart-runtime-summary");
        runtimeSummary.setMaxWidth(260); runtimeSummary.setWrapText(true);
        Label setupTitle = new Label(preamble.getText());
        setupTitle.getStyleClass().add("flowchart-preamble-title");
        preamble.setGraphic(new VBox(2, setupTitle, runtimeSummary)); preamble.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        preamble.setId("flowchart-preamble"); preamble.setAnimated(false);
        preamble.setExpanded(setupExpanded || preambleDamaged() || !document.hasMain()); preamble.setStyle("-fx-font-size: 10px;");
        preamble.expandedProperty().addListener((observable, before, expanded) -> { setupExpanded = expanded; updateRuntimeLabels(); });
        content.getChildren().add(preamble);
        if (document.hasMain()) {
            GraphPane main = new GraphPane(document, graph);
            renderedGraph = main;
            main.setId("flowchart-main"); content.getChildren().add(main);
        }
        bindFieldSources(document);
        updateRuntimeLabels();
        if (renderedGraph != null) renderedGraph.paintExecution();
        palette.setDisable(!synchronizedSource || !document.hasMain());
        if (!synchronizedSource) applyDamage();
        else if (selectedOffset >= 0) {
            SourceLink link = sourceLinkAt(selectedOffset);
            if (link != null) highlight(link, selectedOffset);
        }
    }

    private Node setupNode(StructuredDiagram snapshot, StructuredDiagram.Node node) {
        VBox group = new VBox(3);
        boolean compound = node.body() != null;
        int start = compound ? node.editStart() : node.start();
        int end = compound ? node.editEnd() : node.end();
        if (node.kind() == StructuredDiagram.Kind.BLOCK) { start = node.start(); end = start + node.labels().length(); }
        TextInputControl field = editField(snapshot, node, start, end, true, "source");
        Label value = runtimeLabel(); runtimeLabels.put(node, value);
        Button trash = deleteButton(snapshot, node);
        HBox row = new HBox(3, field, value, trash);
        HBox.setHgrow(field, Priority.ALWAYS);
        row.setStyle("-fx-background-color: white; -fx-border-color: #bdcdd6; -fx-padding: 3;");
        row.getProperties().put("flowchart-base-style", row.getStyle());
        setupRows.computeIfAbsent(node, ignored -> new ArrayList<>()).add(row);
        row.setFocusTraversable(true);
        IndexRange range = new IndexRange(start, end);
        bindSourceFocus(snapshot, row, row, range);
        bindSourceFocus(snapshot, trash, row, range);
        mapSource(row, node.start(), node.end(), range, true);
        group.getChildren().add(row);
        if (node.body() != null) for (StructuredDiagram.Node child : node.body().nodes()) group.getChildren().add(setupNode(snapshot, child));
        if (node.alternative() != null) for (StructuredDiagram.Node child : node.alternative().nodes()) group.getChildren().add(setupNode(snapshot, child));
        return group;
    }

    private static Label runtimeLabel() {
        Label label = new Label(); label.setVisible(false); label.setManaged(false); label.setMouseTransparent(true);
        label.setStyle("-fx-font-family: monospace; -fx-font-size: 10px; -fx-text-fill: #30843c;");
        label.setMinWidth(0); label.setMaxWidth(220); label.setWrapText(true);
        return label;
    }

    private void updateRuntimeLabels() {
        Map<StructuredDiagram.Node, List<Map.Entry<Integer, String>>> grouped = new IdentityHashMap<>();
        runtimeValues.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            StructuredDiagram.Node owner = runtimeLabels.keySet().stream()
                    .filter(node -> entry.getKey() >= node.start() && entry.getKey() < node.end())
                    .min(Comparator.comparingInt(node -> node.end() - node.start())).orElse(null);
            if (owner != null) grouped.computeIfAbsent(owner, ignored -> new ArrayList<>()).add(entry);
        });
        List<String> summary = new ArrayList<>();
        runtimeLabels.forEach((owner, label) -> {
            List<Map.Entry<Integer, String>> values = grouped.getOrDefault(owner, List.of());
            String text = values.stream().map(Map.Entry::getValue).map(value -> value.replace('\n', ' ').replace('\r', ' '))
                    .collect(java.util.stream.Collectors.joining(", "));
            label.getStyleClass().remove("flowchart-runtime-value");
            label.setText(text.isEmpty() ? "" : "// now: " + text);
            label.setVisible(!text.isEmpty()); label.setManaged(!text.isEmpty());
            if (!text.isEmpty()) {
                label.getStyleClass().add("flowchart-runtime-value"); label.setUserData(values.get(0).getKey());
                label.setTooltip(new Tooltip(label.getText()));
            }
        });
        grouped.entrySet().stream().filter(entry -> setupRows.containsKey(entry.getKey()))
                .sorted(Comparator.comparingInt(entry -> entry.getKey().start())).forEach(entry -> {
                    String text = entry.getValue().stream().map(Map.Entry::getValue).collect(java.util.stream.Collectors.joining(", "));
                    Matcher name = Pattern.compile("\\bvar\\s+([A-Za-z_][A-Za-z_0-9]*)").matcher(entry.getKey().editText());
                    summary.add((name.find() ? name.group(1) + " = " : "") + text);
                });
        if (runtimeSummary != null) {
            runtimeSummary.setText(String.join("   ", summary));
            runtimeSummary.setTooltip(new Tooltip(String.join("\n", summary)));
            boolean shown = !summary.isEmpty() && setupPane != null && !setupPane.isExpanded();
            runtimeSummary.setVisible(shown); runtimeSummary.setManaged(shown);
        }
        if (renderedGraph != null) renderedGraph.requestLayout();
    }

    private record Field(StructuredDiagram.Node owner, int start, int end, String text, TextInputControl control, Function<String, String> replacement) { }

    private void bindFieldSources(StructuredDiagram snapshot) {
        for (Field field : fields) {
            Region target = null;
            List<Region> rows = setupRows.get(field.owner());
            if (rows != null && !rows.isEmpty()) target = rows.get(0);
            List<ShapeBox> shapes = boxes.get(field.owner());
            if (target == null && shapes != null) {
                int entry = graph.entryVertex(field.owner());
                boolean footer = field.owner().kind() == StructuredDiagram.Kind.DO_WHILE && field.start() >= field.owner().body().end();
                for (ShapeBox shape : shapes) if (footer ? shape.vertex.kind() == Flowchart.Kind.DECISION : shape.vertex.id() == entry) { target = shape; break; }
            }
            if (target == null) continue;
            IndexRange range = new IndexRange(field.start(), field.end());
            bindSourceFocus(snapshot, field.control(), target, range);
            if (range.getLength() > 0) mapSource(target, field.start(), field.end(), range, false);
        }
    }

    private static IndexRange vertexRange(StructuredDiagram snapshot, Flowchart flow, Flowchart.Vertex vertex) {
        StructuredDiagram.Node owner = vertex.owner();
        if (owner == null) return vertex.kind() == Flowchart.Kind.START
                ? new IndexRange(snapshot.preamble().end(), snapshot.main().start()) : new IndexRange(vertex.start(), vertex.end());
        if (vertex.kind() == Flowchart.Kind.DECISION && flow.jumpInfo(vertex) == null) return new IndexRange(owner.editStart(), owner.editEnd());
        if (owner.kind() == StructuredDiagram.Kind.BLOCK || isLoopEntry(vertex))
            return new IndexRange(flow.statementSpan(owner).start(), owner.body().start());
        if (vertex.kind() == Flowchart.Kind.JOIN) return new IndexRange(Math.max(owner.start(), owner.end() - 1), owner.end());
        Flowchart.SourceSpan span = flow.statementSpan(owner);
        return new IndexRange(span.start(), span.end());
    }

    private TextInputControl editField(StructuredDiagram snapshot, StructuredDiagram.Node owner, int start, int end, boolean applyOnly, String suffix) {
        String original = snapshot.source().substring(start, end);
        Function<String, String> replacement = Function.identity();
        if (suffix.equals("label")) {
            List<Flowchart.LabelInfo> declarations = graph.labelDeclarations().stream().filter(label -> label.owner() == owner).toList();
            String raw = original;
            original = declarations.stream().map(label -> label.name() + ": ").collect(java.util.stream.Collectors.joining());
            replacement = value -> replaceLabelPrefix(raw, start, declarations, value);
        }
        TextInputControl field;
        if (!suffix.equals("label") && (original.contains("\n") || original.contains("\r"))) {
            TextArea area = new TextArea(original); area.setPrefColumnCount(16);
            area.setPrefRowCount((int) Math.min(4, 1 + original.chars().filter(c -> c == '\n').count()));
            field = area;
        } else {
            TextField text = new TextField(original); text.setPrefColumnCount(16); text.setAlignment(Pos.CENTER); field = text;
        }
        fields.add(new Field(owner, start, end, original, field, replacement));
        field.setId("flowchart-edit-" + owner.start() + (suffix.equals("source") ? "" : "-" + suffix));
        field.getStyleClass().add("flowchart-edit");
        if (suffix.equals("label")) { field.getStyleClass().add("flowchart-label-edit"); field.setPromptText("label: (optional)"); }
        field.setEditable(synchronizedSource); field.setMinWidth(30);
        field.setStyle("-fx-font-family: monospace; -fx-font-size: 12px; -fx-background-color: transparent;");
        field.getProperties().put("flowchart-base-style", field.getStyle());
        field.setAccessibleText(suffix.equals("label") ? "Jump label" : "Flowchart source text");
        field.setTooltip(new Tooltip(applyOnly ? suffix.equals("label") ? "Edit and press Enter. Label prefixes end with a colon." : "Edit and press Enter to apply all pending changes."
                : "Edit commands and labels here. Enter at start/end inserts before/after; inside applies all edits. Shift+Enter keeps a line break."));
        field.textProperty().addListener((observable, before, after) -> {
            if (editable(snapshot)) showStatus(fields.stream().anyMatch(item -> !item.text().equals(item.control().getText()))
                    ? "Edit pending — press Enter to apply." : "Code and flowchart are synchronized.", false);
        });
        field.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ENTER && !event.isShiftDown()) { enter(snapshot, owner, field, applyOnly); event.consume(); }
        });
        if (field instanceof TextField single) single.setOnAction(event -> enter(snapshot, owner, field, applyOnly));
        return field;
    }

    private static String replaceLabelPrefix(String raw, int start, List<Flowchart.LabelInfo> declarations, String value) {
        List<String> names = new ArrayList<>();
        Matcher tokens = Pattern.compile("([A-Za-z_][A-Za-z_0-9]*)\\s*:").matcher(value);
        int end = 0;
        boolean labelsOnly = true;
        while (tokens.find()) {
            if (!value.substring(end, tokens.start()).isBlank()) labelsOnly = false;
            names.add(tokens.group(1)); end = tokens.end();
        }
        if (!value.substring(end).isBlank()) labelsOnly = false;
        StringBuilder result = new StringBuilder(raw);
        if (labelsOnly && names.size() == declarations.size()) {
            for (int i = declarations.size() - 1; i >= 0; --i) {
                Flowchart.SourceSpan name = declarations.get(i).nameSpan();
                result.replace(name.start() - start, name.end() - start, names.get(i));
            }
        } else {
            for (int i = declarations.size() - 1; i >= 0; --i) {
                Flowchart.SourceSpan declaration = declarations.get(i).declarationSpan();
                Flowchart.SourceSpan name = declarations.get(i).nameSpan();
                result.deleteCharAt(declaration.end() - start - 1);
                result.delete(name.start() - start, name.end() - start);
            }
            result.insert(0, value);
        }
        return result.toString();
    }

    private void enter(StructuredDiagram snapshot, StructuredDiagram.Node node, TextInputControl field, boolean applyOnly) {
        if (!editable(snapshot)) return;
        Pending pending = pending(snapshot);
        boolean before = !applyOnly && field.getCaretPosition() == 0;
        boolean after = !applyOnly && !before && field.getCaretPosition() == field.getLength();
        if (before || after) {
            int offset = before ? pending.mapStart(node.start()) : pending.map(node.end());
            Insert insertion = insert(pending.source(), offset, placeholder(), indent(pending.source(), pending.mapStart(node.start())));
            accept(snapshot, insertion.source(), insertion.start());
        } else accept(snapshot, pending.source(), -1);
    }

    private Button deleteButton(StructuredDiagram snapshot, StructuredDiagram.Node node) {
        Button button = new Button(); button.setId("flowchart-delete-" + node.start());
        button.getStyleClass().add("flowchart-delete"); button.setGraphic(trash());
        button.setStyle("-fx-padding: 3; -fx-background-color: transparent;");
        button.setTooltip(new Tooltip("Delete this block")); button.setAccessibleText("Delete flowchart block");
        button.setDisable(!synchronizedSource);
        button.setOnAction(event -> {
            if (!editable(snapshot)) return;
            Pending pending = pending(snapshot); int start = pending.mapStart(node.start()), end = pending.map(node.end());
            accept(snapshot, pending.source().substring(0, start) + pending.source().substring(end), -1);
        });
        return button;
    }

    private record Edit(int start, int end, String value) { }
    private record Pending(String source, List<Edit> edits) {
        int map(int offset) { return map(offset, false); }
        int mapStart(int offset) { return map(offset, true); }
        private int map(int offset, boolean leftAffinity) {
            int result = offset;
            for (Edit edit : edits) if (edit.end() <= offset
                    && !(leftAffinity && edit.start() == offset && edit.end() == offset)) {
                result += edit.value().length() - edit.end() + edit.start();
            }
            return result;
        }
    }

    private Pending pending(StructuredDiagram snapshot) { return pending(snapshot, null, null); }

    /** A full instruction edit replaces its operand drafts while preserving a pending label prefix. */
    private Pending pending(StructuredDiagram snapshot, Flowchart.SourceSpan override, String replacement) {
        List<Edit> edits = new ArrayList<>();
        for (Field field : fields) {
            boolean covered = override != null && field.start() >= override.start() && field.end() <= override.end()
                    && !(field.start() == field.end() && field.start() == override.start()
                        && field.control().getStyleClass().contains("flowchart-label-edit"));
            if (!covered && !field.text().equals(field.control().getText())) edits.add(new Edit(field.start(), field.end(), field.replacement().apply(field.control().getText())));
        }
        if (override != null && replacement != null) edits.add(new Edit(override.start(), override.end(), replacement));
        // Replace the original instruction before inserting a new label at the same offset.
        edits.sort(Comparator.comparingInt(Edit::start).reversed()
                .thenComparing(Comparator.comparingInt(Edit::end).reversed()));
        String next = snapshot.source();
        for (Edit edit : edits) next = next.substring(0, edit.start()) + edit.value() + next.substring(edit.end());
        return new Pending(next, edits);
    }

    private boolean editable(StructuredDiagram snapshot) { return synchronizedSource && document == snapshot && !isDisabled(); }
    private String placeholder() { return features.contains(GameFeature.EMPTY_STATEMENTS) ? ";" : "NOP;"; }
    private record Insert(String source, int start) { }

    private static Insert insert(String source, int offset, String code, String indent) {
        String nl = source.contains("\r\n") ? "\r\n" : "\n";
        String prefix = nl + indent;
        String text = prefix + code.replace("\r\n", "\n").replace('\r', '\n').replace("\n", nl + indent) + nl + indent(source, offset);
        return new Insert(source.substring(0, offset) + text + source.substring(offset), offset + prefix.length());
    }

    private static String indent(String source, int offset) {
        int start = source.lastIndexOf('\n', Math.max(-1, offset - 1)) + 1, end = start;
        while (end < offset && (source.charAt(end) == ' ' || source.charAt(end) == '\t')) ++end;
        return source.substring(start, end);
    }

    private boolean accept(StructuredDiagram snapshot, String next, int focusOffset) {
        if (!editable(snapshot)) return false;
        DiagramValidation.Result checked = validate(next);
        if (!checked.valid()) { reject(checked.message()); return false; }
        Flowchart candidate;
        try { candidate = Flowchart.from(checked.diagram()); } catch (IllegalArgumentException error) { reject(error.getMessage()); return false; }
        if (next.equals(source)) { showStatus("Code and flowchart are synchronized.", false); return true; }
        clearExecutionFrame(); clearRuntimeValues();
        source = next; document = checked.diagram(); graph = candidate;
        damaged = Set.of(); damagedMain = damagedPreamble = false;
        refreshTools();
        render(); showStatus("Code and flowchart are synchronized.", false); onSourceChanged.accept(source);
        if (focusOffset >= 0) {
            StructuredDiagram current = document;
            TextInputControl target = fields.stream().filter(field -> field.owner().start() == focusOffset && !field.control().getStyleClass().contains("flowchart-label-edit"))
                    .map(Field::control).findFirst().orElse(null);
            if (target != null) Platform.runLater(() -> { if (editable(current) && target.getScene() != null) { target.requestFocus(); target.selectAll(); } });
        }
        return true;
    }

    private void reject(String message) {
        showStatus("Edit not applied: " + message, true);
        for (Field field : fields) if (!field.text().equals(field.control().getText())) {
            if (!field.control().getStyleClass().contains("flowchart-field-error")) field.control().getStyleClass().add("flowchart-field-error");
            field.control().setStyle(field.control().getStyle() + "-fx-border-color: #bd352d;");
        }
    }

    private Button instructionButton(StructuredDiagram snapshot, Flowchart flow, StructuredDiagram.Node owner) {
        Button button = new Button("✎");
        button.setId("flowchart-jump-source-" + owner.start());
        button.getStyleClass().add("flowchart-edit-instruction");
        button.setStyle("-fx-padding: 1 4; -fx-background-color: transparent;");
        button.setAccessibleText("Edit source instruction");
        button.setTooltip(new Tooltip("Edit the complete source instruction, including conversions to structured syntax"));
        button.setDisable(!synchronizedSource);
        Region linkedShape = boxes.get(owner).stream().filter(box -> box.vertex.id() == flow.entryVertex(owner)).findFirst().orElseThrow();
        Flowchart.SourceSpan command = flow.statementSpan(owner);
        IndexRange commandRange = new IndexRange(command.start(), command.end());
        bindSourceFocus(snapshot, button, linkedShape, commandRange);
        button.setOnAction(event -> {
            if (!editable(snapshot)) return;
            Flowchart.SourceSpan span = flow.statementSpan(owner);
            Dialog<ButtonType> dialog = new Dialog<>(); rawEditor = dialog;
            if (getScene() != null) dialog.initOwner(getScene().getWindow());
            dialog.setTitle("Source instruction");
            Pending draft = pending(snapshot);
            TextArea input = new TextArea(draft.source().substring(draft.map(span.start()), draft.map(span.end())));
            input.setId("flowchart-jump-raw-" + owner.start()); input.getStyleClass().add("flowchart-jump-raw");
            input.setPrefRowCount(4); input.setPrefColumnCount(40);
            bindSourceFocus(snapshot, input, linkedShape, commandRange);
            Label error = new Label(); error.setWrapText(true);
            dialog.getDialogPane().setContent(new VBox(6, new Label("Enter applies; Shift+Enter inserts a line break."), input, error));
            dialog.getDialogPane().getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
            Button save = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
            save.addEventFilter(ActionEvent.ACTION, action -> {
                Pending pending = pending(snapshot, span, input.getText());
                if (!accept(snapshot, pending.source(), -1)) { error.setText(getStatusText()); action.consume(); }
            });
            input.addEventFilter(KeyEvent.KEY_PRESSED, key -> {
                if (key.getCode() == KeyCode.ENTER && !key.isShiftDown()) { save.fire(); key.consume(); }
            });
            dialog.setOnHidden(hidden -> { focusLinks.remove(input); if (rawEditor == dialog) rawEditor = null; });
            dialog.show(); input.requestFocus();
        });
        return button;
    }

    private boolean preambleDamaged() { return damagedPreamble || damaged.stream().anyMatch(node -> node.start() < document.preamble().end()); }

    private void applyDamage() {
        clearSourceHighlight(); sourceLinks.clear(); focusLinks.clear();
        boxes.forEach((owner, shapes) -> shapes.forEach(shape -> shape.damage(damaged.contains(owner))));
        setupRows.forEach((owner, rows) -> rows.forEach(row -> {
            row.getStyleClass().remove("flowchart-damaged"); row.setStyle((String) row.getProperties().get("flowchart-base-style"));
            if (damaged.contains(owner)) { row.getStyleClass().add("flowchart-damaged"); row.setStyle(row.getStyle() + "-fx-border-color: #bd352d; -fx-background-color: #fff0ee;"); }
        }));
        for (Field field : fields) {
            field.control().setEditable(false);
            if (!field.text().equals(field.control().getText())) field.control().setText(field.text());
            field.control().getStyleClass().remove("flowchart-field-error");
            field.control().setStyle((String) field.control().getProperties().get("flowchart-base-style"));
        }
        lookupAll(".flowchart-delete").forEach(node -> node.setDisable(true));
        lookupAll(".flowchart-edit-instruction").forEach(node -> node.setDisable(true));
        lookupAll(".flowchart-drop-target").forEach(node -> node.setFocusTraversable(false));
        Node main = lookup("#flowchart-main");
        if (main instanceof Region region) {
            region.getStyleClass().remove("flowchart-damaged"); region.setStyle("");
            if (damagedMain) { region.getStyleClass().add("flowchart-damaged"); region.setStyle("-fx-border-color: #bd352d;"); }
        }
        Node setup = lookup("#flowchart-preamble");
        if (setup instanceof TitledPane pane && preambleDamaged()) { pane.setExpanded(true); pane.getStyleClass().add("flowchart-damaged"); }
    }

    private HBox comparisonBody(StructuredDiagram snapshot, Flowchart flow, Flowchart.Vertex vertex) {
        Flowchart.JumpInfo jump = flow.jumpInfo(vertex);
        Flowchart.ComparisonInfo comparison = flow.comparisonInfo(vertex);
        Flowchart.SourceSpan operand = jump == null ? comparison.operand() : jump.operand();
        TextInputControl variable = editField(snapshot, vertex.owner(), operand.start(), operand.end(), true,
                jump == null ? "condition-variable" : "jump-variable");
        variable.setId((jump == null ? "flowchart-condition-variable-" : "flowchart-jump-variable-") + vertex.owner().start());
        variable.getStyleClass().add(jump == null ? "flowchart-condition-variable" : "flowchart-jump-variable");
        sizeComparisonField(variable);
        Label operator = new Label(jump == null ? comparison.canonicalNotEqual() ? "≠" : "=" : flow.isForwardJump(vertex) ? "=" : "≠");
        operator.setId("flowchart-operator-" + vertex.owner().start());
        operator.getStyleClass().add("flowchart-comparison-operator");
        operator.setStyle("-fx-font-family: monospace; -fx-font-size: 15px;");
        operator.setTooltip(new Tooltip(jump == null ? "The source comparison" : "Backward jumps test nonzero; forward jumps test zero. Select this decision to bring its arrowhead to the front, then drag the head to change its destination."));
        Region right;
        if (jump != null) {
            Label zero = new Label("0"); zero.setStyle("-fx-font-family: monospace; -fx-font-size: 12px; -fx-padding: 0 6;"); right = zero;
        } else {
            Flowchart.SourceSpan span = comparison.right();
            TextInputControl literal = editField(snapshot, vertex.owner(), span.start(), span.end(), true, "condition-literal");
            literal.setId("flowchart-condition-literal-" + vertex.owner().start()); literal.getStyleClass().add("flowchart-condition-literal");
            sizeComparisonField(literal); right = literal;
        }
        Label question = new Label("?"); question.getStyleClass().add("flowchart-condition-question");
        question.setStyle("-fx-font-family: monospace; -fx-font-size: 12px; -fx-padding: 0 6 0 0;");
        HBox row = new HBox(3, variable, operator, right, question); row.setAlignment(Pos.CENTER); return row;
    }

    private static void sizeComparisonField(TextInputControl field) {
        field.setMinWidth(18);
        field.setStyle(field.getStyle() + "-fx-padding: 3 6;");
        field.getProperties().put("flowchart-base-style", field.getStyle());
        javafx.beans.binding.DoubleBinding width = javafx.beans.binding.Bindings.createDoubleBinding(() -> {
            double longest = 0;
            for (String line : field.getText().split("\\R", -1)) {
                javafx.scene.text.Text measured = new javafx.scene.text.Text(line);
                measured.setFont(field.getFont()); longest = Math.max(longest, measured.getLayoutBounds().getWidth());
            }
            return Math.max(18, Math.min(110, longest + 12));
        }, field.textProperty(), field.fontProperty());
        field.prefWidthProperty().bind(width); field.maxWidthProperty().bind(width);
    }

    private static boolean isLoopEntry(Flowchart.Vertex vertex) {
        return vertex.kind() == Flowchart.Kind.JOIN && vertex.owner() != null && vertex.owner().kind() == StructuredDiagram.Kind.DO_WHILE;
    }

    private final class ShapeBox extends Pane {
        final Flowchart.Vertex vertex;
        final Shape shape;
        final Region body;
        final Button delete;
        final Label value = runtimeLabel();
        final double minimumHeight;
        ShapeBox(StructuredDiagram snapshot, Flowchart flow, Flowchart.Vertex vertex) {
            this.vertex = vertex;
            getStyleClass().addAll("flowchart-node", switch (vertex.kind()) {
                case DECISION -> "flowchart-decision";
                case START, HALT, END -> "flowchart-terminal";
                case JOIN -> "flowchart-join";
                default -> "flowchart-process";
            });
            setId("flowchart-node-" + vertex.id()); setUserData(vertex);
            setFocusTraversable(true);
            StructuredDiagram.Node owner = vertex.owner();
            boolean tinyJoin = vertex.kind() == Flowchart.Kind.JOIN && (owner == null || (owner.kind() != StructuredDiagram.Kind.BLOCK && !vertex.text().equals("do")));
            if (vertex.kind() == Flowchart.Kind.DECISION) shape = new Polygon();
            else { Rectangle rectangle = new Rectangle(); if (vertex.kind() == Flowchart.Kind.START || vertex.kind() == Flowchart.Kind.HALT || vertex.kind() == Flowchart.Kind.END || tinyJoin) { rectangle.setArcWidth(30); rectangle.setArcHeight(30); } shape = rectangle; }
            shape.setFill(Color.web(vertex.kind() == Flowchart.Kind.DECISION ? "#fbf4db" : "#ffffff"));
            shape.setStroke(INK); shape.setMouseTransparent(true); getChildren().add(shape);
            if (owner == null || tinyJoin || vertex.kind() == Flowchart.Kind.JOIN) {
                Label label = new Label(owner != null && owner.kind() == StructuredDiagram.Kind.BLOCK ? "Block" : vertex.text());
                label.setAlignment(Pos.CENTER); body = label;
                delete = owner != null && owner.kind() == StructuredDiagram.Kind.BLOCK ? deleteButton(snapshot, owner) : null;
            } else if (vertex.kind() == Flowchart.Kind.DECISION) {
                body = comparisonBody(snapshot, flow, vertex); delete = deleteButton(snapshot, owner);
            } else {
                Flowchart.SourceSpan statement = flow.statementSpan(owner);
                body = editField(snapshot, owner, statement.start(), statement.end(), false, "source");
                delete = deleteButton(snapshot, owner);
            }
            getChildren().add(body); if (delete != null) getChildren().add(delete);
            if (owner != null && !tinyJoin && !isLoopEntry(vertex)) { runtimeLabels.put(owner, value); getChildren().add(value); }
            if (isLoopEntry(vertex)) { shape.setVisible(false); body.setVisible(false); getStyleClass().add("flowchart-loop-entry"); setAccessibleText("Loop entry junction"); }
            if (owner != null) boxes.computeIfAbsent(owner, ignored -> new ArrayList<>()).add(this);
            minimumHeight = tinyJoin ? 12 : vertex.kind() == Flowchart.Kind.DECISION ? 68
                    : vertex.kind() == Flowchart.Kind.START || vertex.kind() == Flowchart.Kind.END || vertex.kind() == Flowchart.Kind.JOIN ? 26 : 34;
            setMinWidth(tinyJoin ? 16 : 200);
            if (owner != null && damaged.contains(owner)) damage(true);
            IndexRange range = vertexRange(snapshot, flow, vertex);
            bindSourceFocus(snapshot, this, this, range);
            if (delete != null) bindSourceFocus(snapshot, delete, this, range);
            int start = owner == null ? range.getStart() : owner.start(), end = owner == null ? range.getEnd() : owner.end();
            if (owner != null && owner.kind() == StructuredDiagram.Kind.DO_WHILE) {
                if (vertex.kind() == Flowchart.Kind.DECISION) start = owner.body().end();
                else end = owner.body().start();
            } else if (tinyJoin) { start = range.getStart(); end = range.getEnd(); }
            mapSource(this, start, end, range, true);
        }
        void damage(boolean error) {
            getStyleClass().remove("flowchart-damaged"); if (error) getStyleClass().add("flowchart-damaged");
            boolean highlighted = getStyleClass().contains("flowchart-source-highlight");
            boolean executing = getStyleClass().contains("flowchart-execution-next") || getStyleClass().contains("flowchart-execution-start");
            boolean halted = getStyleClass().contains("flowchart-execution-halted");
            shape.setStroke(error ? ERROR : highlighted ? Color.web("#176cb1") : executing ? EXECUTION : INK);
            shape.setStrokeWidth(error ? 2 : highlighted ? 2.5 : executing ? 2 : 1);
            shape.setFill(Color.web(error ? "#fff0ee" : halted ? "#ffe5b6" : executing ? "#def0d9" : highlighted ? "#e5f3ff" : vertex.kind() == Flowchart.Kind.DECISION ? "#fbf4db" : "#ffffff"));
            if (isLoopEntry(vertex) && FlowchartView.this.lookup("#flowchart-junction-" + vertex.id()) instanceof Circle circle) {
                circle.setFill(error ? ERROR : highlighted ? Color.web("#176cb1") : INK);
                circle.setRadius(highlighted ? 5 : 3.5);
            }
        }
        @Override protected double computePrefHeight(double width) {
            if (isLoopEntry(vertex)) return 0;
            double annotation = value.isManaged() ? value.prefHeight(width < 0 ? 220 : width - 18) + 4 : 0;
            return annotation + Math.max(minimumHeight, body.prefHeight(width < 0 ? 180 : width * .6) + (vertex.kind() == Flowchart.Kind.DECISION ? 32 : 6));
        }
        @Override protected void layoutChildren() {
            double width = getWidth(), height = getHeight();
            if (shape instanceof Polygon diamond) diamond.getPoints().setAll(width / 2, 0.0, width, height / 2, width / 2, height, 0.0, height / 2);
            else if (shape instanceof Rectangle rectangle) { rectangle.setWidth(width); rectangle.setHeight(height); }
            double inset = vertex.kind() == Flowchart.Kind.DECISION ? width * .2 : 9;
            double annotation = value.isManaged() ? value.prefHeight(width - 18) + 4 : 0;
            body.resizeRelocate(inset, (height - annotation - body.prefHeight(width - inset * 2)) / 2, width - inset * 2, body.prefHeight(width - inset * 2));
            if (value.isManaged()) value.resizeRelocate(9, height - annotation, width - 18, annotation - 4);
            if (delete != null) delete.resizeRelocate(width - 22, 3, 19, 20);
        }
    }

    private final class GraphPane extends Pane {
        private final StructuredDiagram snapshot;
        private final Flowchart flow;
        private final long revision = ++graphRevision;
        private final List<ShapeBox> vertices = new ArrayList<>();
        private final List<EdgeDrawing> edges = new ArrayList<>();
        private final Map<ShapeBox, List<Drop>> extra = new IdentityHashMap<>();
        private final Map<Integer, Junction> junctions = new LinkedHashMap<>();
        private final Map<Integer, Button> details = new LinkedHashMap<>();
        private final Map<Flowchart.Edge, Integer> jumpLanes;
        private final Map<Flowchart.Edge, Integer> leftLanes;
        private final Map<Flowchart.Edge, Integer> rightLanes;
        private final double jumpLaneStart;
        private int selectedJump = -1;
        GraphPane(StructuredDiagram snapshot, Flowchart flow) {
            this.snapshot = snapshot; this.flow = flow;
            jumpLanes = routeLanes(flow.edges().stream().filter(flow::isJumpRoute).toList());
            leftLanes = routeLanes(flow.edges().stream().filter(edge -> !flow.isJumpRoute(edge) && !direct(edge) && left(edge)).toList());
            rightLanes = routeLanes(flow.edges().stream().filter(edge -> !flow.isJumpRoute(edge) && !direct(edge) && !left(edge)).toList());
            jumpLaneStart = Math.max(180, laneExtent(rightLanes, 160)) + 24;
            double extent = Math.max(laneExtent(leftLanes, 160), Math.max(laneExtent(rightLanes, 160), laneExtent(jumpLanes, jumpLaneStart)));
            double width = Math.max(420, 2 * (extent + 24));
            setMinWidth(width); setPrefWidth(Math.max(460, width));
            for (Flowchart.Edge edge : flow.edges()) {
                EdgeDrawing drawing = new EdgeDrawing(edge); edges.add(drawing);
                getChildren().addAll(drawing.path, drawing.arrow, drawing.label);
                if (drawing.handle != null) getChildren().add(drawing.handle);
            }
            for (Flowchart.Vertex vertex : flow.vertices()) {
                ShapeBox box = new ShapeBox(snapshot, flow, vertex); vertices.add(box); getChildren().add(box);
                StructuredDiagram.Node owner = vertex.owner();
                Flowchart.JumpInfo jump = flow.jumpInfo(vertex);
                if (flow.canRetargetJump(vertex.id())) box.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> selectJump(vertex.id()));
                if (jump != null) {
                    Button edit = instructionButton(snapshot, flow, owner);
                    edit.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> selectJump(vertex.id()));
                    edit.getStyleClass().add("flowchart-jump-details"); details.put(vertex.id(), edit); getChildren().add(edit);
                }
            }
            for (Flowchart.Vertex vertex : flow.vertices()) if (flow.canTarget(vertex)) {
                Junction junction = new Junction(vertex); junctions.put(vertex.id(), junction);
                getChildren().addAll(junction.stem, junction.stemHead, junction.circle, junction.label, junction.target);
                if (junction.beginLabel != null) getChildren().add(junction.beginLabel);
            }
            sequenceDrops(snapshot.main(), null, false);
            extra.values().forEach(targets -> targets.sort(Comparator.comparingInt(target -> target.offset)));
            // Keep the movable wings above the target regions; the bullet itself never intercepts pointer gestures.
            restoreHeadOrder();
            for (Junction junction : junctions.values()) junction.circle.toFront();
            for (EdgeDrawing drawing : edges) if (flow.isJumpRoute(drawing.edge)) {
                ShapeBox box = vertices.get(drawing.edge.from());
                IndexRange range = vertexRange(snapshot, flow, box.vertex);
                drawing.arrow.setFocusTraversable(true);
                bindSourceFocus(snapshot, drawing.arrow, box, range);
                if (drawing.handle != null) bindSourceFocus(snapshot, drawing.handle, box, range);
                bindSourceFocus(snapshot, drawing.path, box, range);
            }
        }
        private boolean markedTarget(int id) {
            return flow.canTarget(id) && (!flow.vertex(id).owner().labels().isBlank()
                    || flow.edges().stream().anyMatch(edge -> flow.isJumpRoute(edge) && edge.to() == id));
        }
        private boolean direct(Flowchart.Edge edge) {
            return !flow.isJumpRoute(edge) && edge.to() == edge.from() + 1
                    && !(edge.label().equals("false") && flow.jumpInfo(edge.from()) == null
                    && flow.edges().stream().filter(other -> other.from() == edge.from() && other.to() == edge.to()).count() > 1);
        }
        private boolean left(Flowchart.Edge edge) {
            return !flow.isJumpRoute(edge) && !markedTarget(edge.to()) && (edge.to() <= edge.from() || edge.label().equals("true"));
        }
        private Map<Flowchart.Edge, Integer> routeLanes(List<Flowchart.Edge> routes) {
            List<Flowchart.Edge> ordered = new ArrayList<>(routes);
            ordered.sort(Comparator.comparingInt((Flowchart.Edge edge) -> Math.abs(edge.to() - edge.from()))
                    .thenComparingInt(edge -> Math.min(edge.from(), edge.to()))
                    .thenComparingInt(Flowchart.Edge::from).thenComparingInt(Flowchart.Edge::to).thenComparing(Flowchart.Edge::label));
            Map<Flowchart.Edge, Integer> allocated = new LinkedHashMap<>();
            for (Flowchart.Edge edge : ordered) {
                int first = Math.min(edge.from(), edge.to()), last = Math.max(edge.from(), edge.to());
                int lane = 0;
                Set<Integer> occupied = new HashSet<>();
                for (Map.Entry<Flowchart.Edge, Integer> previous : allocated.entrySet()) {
                    int from = Math.min(previous.getKey().from(), previous.getKey().to()), to = Math.max(previous.getKey().from(), previous.getKey().to());
                    if (from <= last && first <= to) occupied.add(previous.getValue());
                    if (first <= from && to <= last && (first < from || to < last)) lane = Math.max(lane, previous.getValue() + 1);
                }
                while (occupied.contains(lane)) ++lane;
                allocated.put(edge, lane);
            }
            return allocated;
        }
        private double laneExtent(Map<Flowchart.Edge, Integer> lanes, double first) {
            return lanes.isEmpty() ? 0 : first + lanes.values().stream().mapToInt(Integer::intValue).max().orElse(0) * 18;
        }
        /** Each source sequence has exactly n+1 boundaries, regardless of how many graph vertices its children use. */
        private void sequenceDrops(StructuredDiagram.Sequence sequence, StructuredDiagram.Node owner, boolean alternative) {
            List<StructuredDiagram.Node> children = sequence.nodes();
            for (int index = 0; index <= children.size(); ++index) {
                boolean beforeChild = index < children.size();
                int offset = beforeChild ? children.get(index).start() : sequence.end();
                Drop target = new Drop(snapshot, offset, sequence, false);
                target.leftAffinity = beforeChild;
                target.setUserData(sequence);
                target.getProperties().put("flowchart-boundary-index", index);
                target.getProperties().put("flowchart-boundary-size", children.size());
                String place = owner == null ? "program" : alternative ? "else branch" : owner.kind() == StructuredDiagram.Kind.IF ? "true branch" : "body";
                target.setAccessibleText("Insert " + (index == 0 ? "at the beginning of the " : index == children.size() ? "at the end of the " : "in the ") + place);
                Tooltip.install(target, new Tooltip(target.getAccessibleText() + ". Drop a symbol here, or select one and activate this gap."));
                if (owner == null && index == 0) target.setId("flowchart-main-gap");
                else if (owner != null && index == children.size()) target.setId("flowchart-" + (alternative ? "else-" : "body-") + owner.start());
                else if (beforeChild) target.setId("flowchart-before-" + children.get(index).start());
                else target.setId("flowchart-after-" + children.get(index - 1).start());
                int anchor = index > 0 ? lastVertex(children.get(index - 1)) : sequenceEntryAnchor(owner, alternative);
                addDrop(anchor, target);
            }
            for (StructuredDiagram.Node child : children) {
                if (child.body() != null) sequenceDrops(child.body(), child, false);
                if (child.alternative() != null) sequenceDrops(child.alternative(), child, true);
                else if (child.kind() == StructuredDiagram.Kind.IF) {
                    Drop target = new Drop(snapshot, child.end(), null, true);
                    target.elseOwner = child; target.setId("flowchart-else-" + child.start());
                    target.getProperties().put("flowchart-omitted-else", true);
                    addDrop(lastVertex(child), target);
                }
            }
        }
        private int sequenceEntryAnchor(StructuredDiagram.Node owner, boolean alternative) {
            if (owner == null) return 0;
            if (alternative && !owner.body().nodes().isEmpty()) return lastVertex(owner.body().nodes().get(owner.body().nodes().size() - 1));
            return flow.entryVertex(owner);
        }
        private int lastVertex(StructuredDiagram.Node owner) {
            int last = flow.entryVertex(owner);
            for (Flowchart.Vertex vertex : flow.vertices()) if (vertex.owner() != null
                    && vertex.owner().start() >= owner.start() && vertex.owner().end() <= owner.end()) last = vertex.id();
            return last;
        }
        private void addDrop(int anchor, Drop target) {
            extra.computeIfAbsent(vertices.get(anchor), ignored -> new ArrayList<>()).add(target);
            getChildren().add(target);
        }
        private void selectJump(int id) {
            if (!editable(snapshot)) return;
            selectedJump = id;
            for (EdgeDrawing drawing : edges) if (flow.isJumpRoute(drawing.edge)) {
                boolean selected = drawing.edge.from() == id;
                drawing.path.getStyleClass().remove("flowchart-jump-selected");
                drawing.arrow.getStyleClass().remove("flowchart-jump-selected");
                if (drawing.handle != null) drawing.handle.getStyleClass().remove("flowchart-jump-selected");
                if (selected) {
                    drawing.path.getStyleClass().add("flowchart-jump-selected");
                    drawing.arrow.getStyleClass().add("flowchart-jump-selected");
                    if (drawing.handle != null) drawing.handle.getStyleClass().add("flowchart-jump-selected");
                    drawing.toFront();
                }
                drawing.paint();
            }
        }
        private void restoreHeadOrder() {
            for (EdgeDrawing drawing : edges) if (flow.isJumpRoute(drawing.edge)) drawing.toFront();
            if (selectedJump >= 0) for (EdgeDrawing drawing : edges) if (flow.isJumpRoute(drawing.edge) && drawing.edge.from() == selectedJump) drawing.toFront();
        }
        private void draggingHead(boolean active) {
            for (Junction junction : junctions.values()) {
                junction.target.setMouseTransparent(!active);
                junction.target.setStyle("");
                if (active) junction.target.toFront();
                junction.layout(getWidth() / 2);
            }
            if (!active) restoreHeadOrder();
        }
        private void paintExecution() {
            for (ShapeBox box : vertices) {
                box.getStyleClass().removeAll("flowchart-execution-next", "flowchart-execution-start", "flowchart-execution-halted");
                if (executionFrame != null) {
                    if (box.vertex.id() == executionFrame.nextVertex()) {
                        box.getStyleClass().add("flowchart-execution-next");
                        if (executionHalted) box.getStyleClass().add("flowchart-execution-halted");
                    }
                    if (executionFrame.initial() && box.vertex.kind() == Flowchart.Kind.START) box.getStyleClass().add("flowchart-execution-start");
                }
                box.damage(box.vertex.owner() != null && damaged.contains(box.vertex.owner()));
            }
            for (EdgeDrawing drawing : edges) drawing.paint();
            for (Junction junction : junctions.values()) {
                boolean crossed = executionFrame != null && executionFrame.transitions().stream().anyMatch(edge -> edge.to() == junction.vertex.id());
                boolean focused = vertices.get(junction.vertex.id()).getStyleClass().contains("flowchart-source-highlight");
                junction.stem.setStroke(crossed ? TRANSITION : INK); junction.stemHead.setFill(crossed ? TRANSITION : INK);
                junction.circle.setFill(focused && isLoopEntry(junction.vertex) ? Color.web("#176cb1") : crossed ? TRANSITION : INK);
            }
        }
        @Override protected double computePrefHeight(double width) { double height = 14; for (ShapeBox box : vertices) height += box.prefHeight(240) + gap(box); return height; }
        private double gap(ShapeBox box) {
            return Math.max(28, extra.getOrDefault(box, List.of()).size() * 28 + 8);
        }
        @Override protected void layoutChildren() {
            double center = getWidth() / 2, y = 7;
            for (ShapeBox box : vertices) {
                boolean tiny = box.vertex.kind() == Flowchart.Kind.JOIN && (box.vertex.owner() == null || (box.vertex.owner().kind() != StructuredDiagram.Kind.BLOCK && !box.vertex.text().equals("do")));
                double width = tiny ? 16 : box.vertex.kind() == Flowchart.Kind.DECISION ? 270 : 240;
                double height = isLoopEntry(box.vertex) ? 0 : tiny ? 16 : box.prefHeight(width);
                box.resizeRelocate(center - width / 2, y, width, height); box.layout();
                List<Drop> targets = extra.get(box);
                if (targets != null) for (int index = 0; index < targets.size(); ++index)
                    targets.get(index).resizeRelocate(center + 36, y + height + 3 + index * 28, 90, 24);
                Button detail = details.get(box.vertex.id());
                if (detail != null) detail.resizeRelocate(box.getLayoutX() + box.delete.getLayoutX(),
                        y + Math.max(height - 23, box.delete.getLayoutY() + box.delete.getHeight() + 3),
                        box.delete.getWidth(), 20);
                y += height + gap(box);
            }
            positionLoopEntries();
            for (Junction junction : junctions.values()) junction.layout(center);
            for (EdgeDrawing edge : edges) edge.layout(center);
        }
        private void positionLoopEntries() {
            for (int first = 1; first < vertices.size() - 1; ++first) {
                if (!isLoopEntry(vertices.get(first).vertex)) continue;
                int last = first;
                while (last + 1 < vertices.size() && isLoopEntry(vertices.get(last + 1).vertex)) ++last;
                ShapeBox before = vertices.get(first - 1), after = vertices.get(last + 1);
                double top = before.getLayoutY() + before.getHeight(), bottom = after.getLayoutY();
                // Hidden loop entries live on the visible connector, not above a fictitious do box.
                // Nested entries retain separate, evenly spaced junctions and label editors.
                for (int index = first; index <= last; ++index)
                    vertices.get(index).setLayoutY(top + (bottom - top) * (index - first + 1) / (last - first + 2));
                first = last;
            }
        }

        private Integer draggedJump(javafx.scene.input.Dragboard board) {
            Object value = board.getContent(HEAD_FORMAT);
            if (!(value instanceof String text)) return null;
            String[] parts = text.split(":");
            try {
                if (parts.length != 2 || Long.parseLong(parts[0]) != revision) return null;
                int id = Integer.parseInt(parts[1]);
                return id >= 0 && id < flow.vertices().size() && flow.canRetargetJump(id) ? id : null;
            } catch (NumberFormatException error) { return null; }
        }

        private boolean moveHead(int jumpId, int targetId) {
            if (!editable(snapshot) || !flow.canTarget(targetId)) return false;
            try {
                if (!flow.canRetargetJump(jumpId)) return false;
                Pending pending = pending(snapshot);
                StructuredDiagram candidate = StructuredDiagram.parse(pending.source(), languageFor(pending.source()), true);
                String moved = Flowchart.retargetJump(candidate, pending.mapStart(flow.vertex(jumpId).owner().start()),
                        pending.mapStart(flow.vertex(targetId).owner().start()));
                return accept(snapshot, moved, -1);
            } catch (StructuredDiagram.ParseException | IllegalArgumentException error) { reject(error.getMessage()); return false; }
        }

        private final class Junction {
            final Flowchart.Vertex vertex;
            final boolean marked;
            final Circle circle = new Circle(3.5, INK);
            final Path stem = new Path();
            final Polygon stemHead = new Polygon();
            final TextInputControl label;
            final Label beginLabel;
            final Region target = new Region();
            double x, y;
            Junction(Flowchart.Vertex vertex) {
                this.vertex = vertex;
                Flowchart.SourceSpan prefix = flow.labelPrefix(vertex.owner());
                marked = markedTarget(vertex.id());
                circle.setId("flowchart-junction-" + vertex.id()); circle.setUserData(vertex.id()); circle.setMouseTransparent(true); circle.setVisible(marked);
                if (marked) circle.getStyleClass().add("flowchart-junction");
                stem.getStyleClass().add("flowchart-junction-stem"); stem.setUserData(vertex.id()); stem.setStroke(INK); stem.setFill(null); stem.setMouseTransparent(true); stem.setVisible(marked);
                stemHead.getStyleClass().add("flowchart-junction-arrowhead"); stemHead.setFill(INK); stemHead.setMouseTransparent(true); stemHead.setVisible(marked && !isLoopEntry(vertex));
                label = editField(snapshot, vertex.owner(), prefix.start(), prefix.end(), true, "label");
                label.getStyleClass().add("flowchart-junction-label"); label.setUserData(vertex.owner().start());
                label.setStyle("-fx-font-family: monospace; -fx-font-size: 10px; -fx-background-color: transparent;");
                if (label instanceof TextField text) text.setAlignment(Pos.CENTER_RIGHT);
                label.promptTextProperty().bind(javafx.beans.binding.Bindings.when(label.hoverProperty().or(label.focusedProperty())).then("label:").otherwise(""));
                label.setTooltip(new Tooltip("Jump labels belong to this incoming arrow. Update matching jump names in the source or instruction editor when renaming a label."));
                boolean implicitBegin = flow.isImplicitBeginTarget(vertex.id()) && flow.edges().stream().anyMatch(edge ->
                        edge.to() == vertex.id() && flow.isJumpEdge(edge) && flow.jumpInfo(edge.from()).targetLabel().equals("begin"));
                if (implicitBegin) {
                    beginLabel = new Label("begin:");
                    beginLabel.setId("flowchart-begin-label");
                    beginLabel.getStyleClass().add("flowchart-begin-label");
                    beginLabel.setUserData(vertex.id());
                    beginLabel.setAlignment(Pos.CENTER_RIGHT);
                    beginLabel.setStyle("-fx-font-family: monospace; -fx-font-size: 10px;");
                    beginLabel.setFocusTraversable(true);
                    beginLabel.setTooltip(new Tooltip("The program's begin: marker defines this jump destination."));
                    Flowchart.SourceSpan marker = flow.beginSpan();
                    bindSourceFocus(snapshot, beginLabel, vertices.get(0), new IndexRange(marker.start(), marker.end()));
                } else beginLabel = null;
                target.setId("flowchart-head-target-" + vertex.id()); target.getStyleClass().add("flowchart-head-target"); target.setUserData(vertex.id());
                target.setMouseTransparent(true);
                target.setAccessibleText("Move a jump arrowhead here to target this block");
                Tooltip.install(target, new Tooltip("Drop an arrowhead here to move its destination label. Jumps sharing that label follow it."));
                target.setOnDragOver(event -> {
                    if (editable(snapshot) && draggedJump(event.getDragboard()) != null) {
                        event.acceptTransferModes(TransferMode.MOVE); target.setStyle("-fx-background-color: #bfe8e4; -fx-background-radius: 8; -fx-border-color: #137f83; -fx-border-radius: 8;");
                        event.consume();
                    }
                });
                target.setOnDragExited(event -> target.setStyle(""));
                target.setOnDragDropped(event -> {
                    Integer jump = draggedJump(event.getDragboard());
                    event.setDropCompleted(jump != null && moveHead(jump, vertex.id())); target.setStyle(""); event.consume();
                });
            }
            void layout(double center) {
                ShapeBox box = vertices.get(vertex.id());
                ShapeBox previous = vertices.get(vertex.id() - 1);
                // Routed branches merge into the local connector; invisible do entries already sit on it.
                x = center; y = isLoopEntry(vertex) ? box.getLayoutY()
                        : (previous.getLayoutY() + previous.getHeight() + box.getLayoutY()) / 2;
                circle.setCenterX(x); circle.setCenterY(y);
                stem.getElements().setAll(new MoveTo(x, y), new LineTo(x, box.getLayoutY()));
                arrow(stemHead, x, box.getLayoutY(), x, y);
                label.resizeRelocate(x - 157, y - 11, beginLabel == null ? 149 : 98, 22);
                if (beginLabel != null) beginLabel.resizeRelocate(x - 55, y - 11, 47, 22);
                target.resizeRelocate(x - 16, y - 16, 32, 32);
            }
        }

        private final class EdgeDrawing {
            final Flowchart.Edge edge;
            final Path path = new Path(); final Polygon arrow = new Polygon(); final Label label;
            final Region handle;
            EdgeDrawing(Flowchart.Edge edge) {
                this.edge = edge; path.getStyleClass().add("flowchart-edge"); arrow.getStyleClass().add("flowchart-arrowhead");
                handle = flow.isJumpRoute(edge) && flow.canRetargetJump(edge.from()) ? new Region() : null;
                path.setUserData(edge); arrow.setUserData(edge); path.setStroke(INK); path.setStrokeWidth(1.5); path.setFill(null);
                arrow.setFill(INK); path.setMouseTransparent(true);
                String text = "";
                if (flow.vertex(edge.from()).kind() == Flowchart.Kind.DECISION && !edge.label().isBlank()) {
                    boolean yes = edge.label().equals("true");
                    if (flow.jumpInfo(edge.from()) != null && flow.isForwardJump(edge.from())) yes = !yes;
                    text = yes ? "YES" : "NO";
                }
                label = new Label(text); label.getStyleClass().add("flowchart-edge-label"); label.setUserData(edge);
                label.setStyle("-fx-font-size: 10px; -fx-text-fill: #365367; -fx-background-color: #f3f6fa;"); label.setMouseTransparent(true);
                if (flow.isJumpRoute(edge)) {
                    boolean movable = flow.canRetargetJump(edge.from());
                    path.setMouseTransparent(false);
                    path.setOnMousePressed(event -> selectJump(edge.from()));
                    String help = movable ? flow.jumpInfo(edge.from()) != null
                            ? "Select this jump, then drag its arrowhead to move the destination label. Jumps sharing that label follow it."
                            : "Drag this loop's arrowhead to another incoming arrow to create an equivalent labeled jump."
                            : "This comparison needs more than one JNZ, so its return arrow cannot be moved here. Edit the loop condition in the source.";
                    Tooltip.install(path, new Tooltip(help)); Tooltip.install(arrow, new Tooltip(help));
                    if (flow.loopJumpInfo(edge.from()) != null) arrow.getStyleClass().add("flowchart-loop-return");
                    if (movable) {
                        arrow.getStyleClass().add("flowchart-jump-head"); arrow.setId("flowchart-jump-head-" + edge.from());
                        arrow.setAccessibleText("Move jump arrowhead to another block's incoming arrow");
                        handle.getStyleClass().add("flowchart-jump-handle"); handle.setId("flowchart-jump-handle-" + edge.from());
                        handle.setUserData(edge); handle.setAccessibleText(arrow.getAccessibleText());
                        handle.setCursor(javafx.scene.Cursor.HAND);
                        Tooltip.install(handle, new Tooltip(help));
                        handle.hoverProperty().addListener((observable, before, after) -> paintHandle());
                        for (Node pickup : List.of(arrow, handle)) {
                            pickup.setOnMousePressed(event -> selectJump(edge.from()));
                            pickup.setOnDragDetected(event -> {
                                if (!editable(snapshot)) return;
                                selectJump(edge.from());
                                ClipboardContent payload = new ClipboardContent(); payload.put(HEAD_FORMAT, revision + ":" + edge.from());
                                pickup.startDragAndDrop(TransferMode.MOVE).setContent(payload);
                                draggingHead(true); event.consume();
                            });
                            pickup.setOnDragDone(event -> draggingHead(false));
                        }
                        paintHandle();
                    } else arrow.setAccessibleText("Loop return arrow follows the source comparison");
                } else arrow.setMouseTransparent(true);
            }
            void toFront() { arrow.toFront(); if (handle != null) handle.toFront(); }
            void paintHandle() {
                if (handle == null) return;
                boolean visible = handle.isHover() || path.getStyleClass().contains("flowchart-jump-selected");
                handle.setStyle(visible
                        ? "-fx-background-color: rgba(19,127,131,0.12); -fx-background-radius: 7; -fx-border-color: #137f83; -fx-border-radius: 7;"
                        : "-fx-background-color: transparent;");
            }
            void paint() {
                boolean active = executionFrame != null && executionFrame.transitions().contains(edge);
                boolean selected = path.getStyleClass().contains("flowchart-jump-selected");
                path.getStyleClass().remove("flowchart-execution-edge"); arrow.getStyleClass().remove("flowchart-execution-arrowhead");
                if (active) { path.getStyleClass().add("flowchart-execution-edge"); arrow.getStyleClass().add("flowchart-execution-arrowhead"); }
                path.setStroke(active ? TRANSITION : selected ? Color.web("#137f83") : INK);
                path.setStrokeWidth(active ? 3 : selected ? 2.5 : 1.5);
                arrow.setFill(active ? TRANSITION : selected ? Color.web("#137f83") : INK);
                paintHandle();
            }
            void layout(double center) {
                ShapeBox from = vertices.get(edge.from()), to = vertices.get(edge.to());
                Junction junction = junctions.get(edge.to());
                boolean joins = junction != null && junction.marked;
                boolean jump = flow.isJumpRoute(edge);
                double fx = center, fy = from.getLayoutY() + from.getHeight();
                double tx = center, ty = joins ? junction.y : to.getLayoutY();
                List<double[]> points = new ArrayList<>();
                if (direct(edge)) {
                    points.add(new double[] {fx, fy}); points.add(new double[] {tx, ty});
                    label.relocate(fx + 6, fy + 1);
                } else {
                    boolean left = left(edge);
                    double side = left ? -1 : 1;
                    double lane = center + (jump ? jumpLaneStart + jumpLanes.get(edge) * 18
                            : side * (160 + (left ? leftLanes : rightLanes).get(edge) * 18));
                    fx = center + side * from.getWidth() / 2; fy = from.getLayoutY() + from.getHeight() / 2;
                    if (!joins) { tx = center + side * to.getWidth() / 2; ty = to.getLayoutY() + to.getHeight() / 2; }
                    points.add(new double[] {fx, fy}); points.add(new double[] {lane, fy});
                    if (!joins && edge.to() == edge.from()) { ty = from.getLayoutY(); tx = center; points.add(new double[] {lane, ty - 16}); points.add(new double[] {tx, ty - 16}); }
                    else points.add(new double[] {lane, ty});
                    points.add(new double[] {tx, ty});
                    label.relocate(left ? fx - label.prefWidth(-1) - 6 : fx + 6, fy - 15);
                }
                path.getElements().clear(); path.getElements().add(new MoveTo(points.get(0)[0], points.get(0)[1]));
                for (int i = 1; i < points.size(); i++) path.getElements().add(new LineTo(points.get(i)[0], points.get(i)[1]));
                double[] end = points.get(points.size() - 1), previous = points.get(points.size() - 2);
                arrow(arrow, end[0], end[1], previous[0], previous[1]);
                if (handle != null) handle.resizeRelocate(end[0] - 6, end[1] - 14, 32, 28);
                arrow.setVisible(jump || !joins);
                label.resize(label.prefWidth(-1), label.prefHeight(-1));
            }
        }
    }

    private static void arrow(Polygon arrow, double x, double y, double previousX, double previousY) {
        double angle = Math.atan2(y - previousY, x - previousX), dx = Math.cos(angle), dy = Math.sin(angle);
        arrow.getPoints().setAll(x, y, x - 8 * dx + 4 * dy, y - 8 * dy - 4 * dx, x - 8 * dx - 4 * dy, y - 8 * dy + 4 * dx);
    }

    private final class Drop extends Region {
        final StructuredDiagram snapshot; final int offset; final StructuredDiagram.Sequence sequence; final boolean omittedElse;
        StructuredDiagram.Node elseOwner;
        boolean leftAffinity;
        Drop(StructuredDiagram snapshot, int offset, StructuredDiagram.Sequence sequence, boolean omittedElse) {
            this.snapshot = snapshot; this.offset = offset; this.sequence = sequence; this.omittedElse = omittedElse;
            getStyleClass().add("flowchart-drop-target"); setFocusTraversable(synchronizedSource); setAccessibleRole(AccessibleRole.BUTTON);
            setAccessibleText(omittedElse ? "Insert into else branch" : "Insert a flowchart statement here");
            decorate(false);
            Tooltip.install(this, new Tooltip("Drop a flowchart symbol here, or select one and activate this gap. Enter inserts a statement."));
            setOnMouseClicked(event -> { if (selectedTool != null) insertTool(selectedTool); });
            setOnKeyPressed(event -> { if (event.getCode() == KeyCode.ENTER || event.getCode() == KeyCode.SPACE) { insertTool(selectedTool); event.consume(); } });
            setOnDragOver(event -> {
                Tool tool = decode(event.getDragboard().getContent(FORMAT));
                if (editable(snapshot) && available(tool)) { event.acceptTransferModes(TransferMode.COPY); decorate(true); event.consume(); }
            });
            setOnDragExited(event -> decorate(false));
            setOnDragDropped(event -> {
                Tool tool = decode(event.getDragboard().getContent(FORMAT));
                if (tool != null) { event.setDropCompleted(insertTool(tool)); decorate(false); event.consume(); }
            });
            setOnMouseEntered(event -> { if (editable(snapshot)) decorate(true); });
            setOnMouseExited(event -> decorate(false));
            focusedProperty().addListener((observable, before, after) -> decorate(after));
        }
        private void decorate(boolean active) {
            setStyle(active ? "-fx-background-color: #c9e9e6; -fx-background-radius: 3; -fx-border-color: #568eaa; -fx-border-radius: 3;"
                    : "-fx-background-color: rgba(82,113,128,0.035); -fx-background-radius: 3; -fx-border-color: #d5e1e7; -fx-border-radius: 3;");
        }
        boolean insertTool(Tool tool) {
            if (!editable(snapshot) || (tool != null && !available(tool))) return false;
            if (omittedElse && !features.contains(GameFeature.ELSE)) { reject("Else branches are still locked."); return false; }
            try {
                Pending pending = pending(snapshot);
                String code = tool == null ? placeholder() : template(tool, snapshot);
                Insert insertion;
                int at = leftAffinity ? pending.mapStart(offset) : pending.map(offset);
                if (omittedElse) {
                    String indentation = indent(pending.source(), pending.map(elseOwner.start()));
                    String prefix = " else {\n" + indentation + "  ";
                    String inserted = prefix + code.replace("\n", "\n" + indentation + "  ") + "\n" + indentation + "}";
                    insertion = new Insert(pending.source().substring(0, at) + inserted + pending.source().substring(at), at + prefix.length());
                } else {
                    String indentation = indent(pending.source(), at);
                    if (sequence != null && sequence != snapshot.main() && !leftAffinity) indentation += "  ";
                    insertion = insert(pending.source(), at, code, indentation);
                }
                boolean done = accept(snapshot, insertion.source(), insertion.start()); if (done) select(null); return done;
            } catch (IllegalArgumentException error) { reject(error.getMessage()); return false; }
        }
        @Override public void executeAccessibleAction(AccessibleAction action, Object... parameters) { if (action == AccessibleAction.FIRE) insertTool(selectedTool); else super.executeAccessibleAction(action, parameters); }
    }

    private boolean available(Tool tool) { return tool != null; }
    private void refreshTools() {
        if (selectedTool != null && !available(selectedTool)) select(null);
        tools.forEach((tool, tile) -> { tile.setVisible(available(tool)); tile.setManaged(available(tool)); tile.setDisable(!available(tool)); });
    }
    private void select(Tool tool) {
        selectedTool = tool;
        tools.forEach((kind, tile) -> tile.setStyle("-fx-padding: 3 4; -fx-background-radius: 3; -fx-border-radius: 3; -fx-border-color: "
                + (kind == tool ? "#157f83" : "#b9cbd3") + "; -fx-background-color: " + (kind == tool ? "#dceef1" : "white") + ";"));
    }
    private VBox tile(Tool tool) {
        VBox tile = new VBox(1) {
            @Override public void executeAccessibleAction(AccessibleAction action, Object... parameters) { if (action == AccessibleAction.FIRE && !isDisabled()) select(tool); else super.executeAccessibleAction(action, parameters); }
        };
        tile.setId("flowchart-tool-" + tool.name().toLowerCase().replace('_', '-')); tile.getStyleClass().add("flowchart-tool");
        tile.setAlignment(Pos.CENTER); tile.setFocusTraversable(true); tile.setAccessibleRole(AccessibleRole.BUTTON);
        boolean decision = tool == Tool.DIAMOND;
        tile.setAccessibleText(decision ? "Insert a decision diamond" : "Insert a statement rectangle");
        Pane symbol = new Pane(); symbol.getStyleClass().add("flowchart-tool-symbol"); symbol.setMinSize(45, 28); symbol.setPrefSize(45, 28); symbol.setMaxSize(45, 28); symbol.setMouseTransparent(true);
        Shape outline = decision ? new Polygon(22.0, 2.0, 42.0, 14.0, 22.0, 26.0, 2.0, 14.0) : new Rectangle(3, 5, 38, 18);
        outline.setFill(Color.web(decision ? "#fbf4db" : "#ffffff")); outline.setStroke(INK); symbol.getChildren().add(outline);
        tile.getChildren().add(symbol);
        Tooltip.install(tile, new Tooltip(decision ? "Decision: drag into a gap, then move its arrowhead to choose where it jumps."
                : "Statement: drag into a gap, then edit the instruction."));
        tile.setOnMouseClicked(event -> { if (!tile.isDisabled()) { select(tool); tile.requestFocus(); } });
        tile.setOnKeyPressed(event -> { if (!tile.isDisabled() && (event.getCode() == KeyCode.ENTER || event.getCode() == KeyCode.SPACE)) { select(tool); event.consume(); } else if (event.getCode() == KeyCode.ESCAPE) select(null); });
        tile.setOnDragDetected(event -> { if (!tile.isDisabled() && available(tool)) { select(tool); ClipboardContent payload = new ClipboardContent(); payload.put(FORMAT, tool.name()); tile.startDragAndDrop(TransferMode.COPY).setContent(payload); event.consume(); } });
        return tile;
    }
    private static Tool decode(Object value) { try { return value instanceof String text ? Tool.valueOf(text) : null; } catch (IllegalArgumentException invalid) { return null; } }
    private String template(Tool tool, StructuredDiagram snapshot) {
        if (tool == Tool.RECTANGLE) return placeholder();
        String variable = null;
        for (StructuredDiagram.Node node : snapshot.preamble().nodes()) if (node.kind() == StructuredDiagram.Kind.DECLARATION) {
            Matcher matcher = Pattern.compile("var\\s+([A-Za-z_][A-Za-z_0-9]*)(\\s*\\[)?").matcher(node.text());
            if (matcher.find()) { variable = matcher.group(1) + (matcher.group(2) == null ? "" : "[0]"); break; }
        }
        if (variable == null) throw new IllegalArgumentException("Declare a condition variable in the code before inserting a control structure.");
        if (tool == Tool.DIAMOND) {
            String label = graph.labels().keySet().stream().filter(name -> graph.labelInfo(name) != null).findFirst().orElse(null);
            if (label != null) return "JNZ " + variable + " " + label + ";";
            label = "again"; int suffix = 1;
            while (Pattern.compile("\\b" + label + "\\b").matcher(snapshot.source()).find()) label = "again" + suffix++;
            return label + ": JNZ " + variable + " " + label + ";";
        }
        throw new IllegalArgumentException("Unknown flowchart tool");
    }

    private static Node trash() {
        Pane pane = new Pane(); pane.setMinSize(13, 15); pane.setPrefSize(13, 15); pane.setMaxSize(13, 15); pane.setMouseTransparent(true);
        Rectangle bin = new Rectangle(3, 5, 8, 9); bin.setFill(Color.TRANSPARENT); bin.setStroke(INK);
        pane.getChildren().addAll(bin, line(2, 3, 12, 3), line(5, 1, 9, 1), line(6, 7, 6, 12), line(8, 7, 8, 12)); return pane;
    }
    private static Line line(double x1, double y1, double x2, double y2) { Line line = new Line(x1, y1, x2, y2); line.setStroke(INK); return line; }
    private void showStatus(String message, boolean error) { statusMessage = message; status.setText(localize(message)); status.setStyle("-fx-font-size: 10px; -fx-text-fill: " + (error ? "#b3332a" : "#34755a") + ";"); }
    private String unavailableText() { return taskLanguage == GameTaskLanguage.GERMAN ? "Korrigiere den Code, um den Programmablaufplan anzuzeigen. Dein Entwurf bleibt erhalten." : "Correct the code to show its flowchart. Your draft is kept as typed."; }
    private String localize(String message) {
        if (taskLanguage != GameTaskLanguage.GERMAN) return message;
        if (message.equals("The flowchart follows the code.")) return "Der Programmablaufplan folgt dem Code.";
        if (message.equals("Code and flowchart are synchronized.")) return "Code und Programmablaufplan sind synchronisiert.";
        if (message.equals("Edit pending — press Enter to apply.")) return "Änderung noch offen — mit Enter übernehmen.";
        if (message.startsWith("Edit not applied: ")) return "Änderung nicht übernommen: " + GameMessages.diagnostic(taskLanguage, message.substring("Edit not applied: ".length()));
        String suffix = " — flowchart is read-only until the code is corrected.";
        if (message.endsWith(suffix)) return GameMessages.diagnostic(taskLanguage, message.substring(0, message.length() - suffix.length())) + " — der Programmablaufplan ist bis zur Korrektur des Codes schreibgeschützt.";
        return GameMessages.diagnostic(taskLanguage, message);
    }
}

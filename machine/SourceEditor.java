package machine;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javafx.beans.InvalidationListener;
import javafx.geometry.Point2D;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.IndexRange;
import javafx.scene.control.Skin;
import javafx.scene.control.TextArea;
import javafx.scene.control.skin.TextAreaSkin;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.scene.shape.Path;
import javafx.scene.shape.PathElement;
import javafx.scene.text.Text;

/** A source editor that retains TextArea's editing behavior and adds token colors. */
public final class SourceEditor extends TextArea {
    private IndexRange executionRange;
    private List<IndexRange> callerRanges = List.of();
    private boolean executionHalted;
    private int pendingReveal = -1;

    public SourceEditor(String text) {
        super(text);
        getStyleClass().add("source-editor");
        setWrapText(false);
        setStyle("-fx-font-family: monospace; -fx-font-size: 13px;");
        textProperty().addListener((observable, previous, current) -> clearExecutionHighlights());
    }

    public IndexRange getExecutionRange() {
        return executionRange;
    }

    public boolean isExecutionHalted() {
        return executionHalted;
    }

    public List<IndexRange> getCallerRanges() {
        return callerRanges;
    }

    /** Decorates the next statement without editing the document or selection. */
    public void setExecutionRange(int start, int end, boolean halted) {
        checkRange(start, end);
        executionRange = new IndexRange(start, end);
        executionHalted = halted;
        invalidateExecutionHighlights();
    }

    public void setCallerRanges(List<IndexRange> ranges) {
        for (IndexRange range : ranges) {
            checkRange(range.getStart(), range.getEnd());
        }
        callerRanges = List.copyOf(ranges);
        invalidateExecutionHighlights();
    }

    public void clearExecutionHighlights() {
        executionRange = null;
        callerRanges = List.of();
        executionHalted = false;
        pendingReveal = -1;
        invalidateExecutionHighlights();
    }

    /** Scrolls to a source offset, leaving the caret, selection and focus alone. */
    public void revealSourceOffset(int offset) {
        if (offset < 0 || offset > getLength()) {
            throw new IllegalArgumentException("Source offset outside the document: " + offset);
        }
        pendingReveal = offset;
        requestLayout();
    }

    private void checkRange(int start, int end) {
        if (start < 0 || end < start || end > getLength()) {
            throw new IllegalArgumentException("Source range outside the document: " + start + ".." + end);
        }
    }

    private void invalidateExecutionHighlights() {
        if (getSkin() instanceof SyntaxSkin skin) {
            skin.markersDirty = true;
        }
        requestLayout();
    }

    @Override
    protected Skin<?> createDefaultSkin() {
        return new SyntaxSkin(this);
    }

    private static final class SyntaxSkin extends TextAreaSkin {
        private final SourceEditor editor;
        private final Group highlights = new Group();
        private final Group executionHighlights = new Group();
        private final Map<SourceSyntax.Kind, List<SourceSyntax.Span>> spansByKind =
                new EnumMap<>(SourceSyntax.Kind.class);
        private Text nativeText;
        private boolean dirty = true;
        private boolean metricsDirty = true;
        private boolean markersDirty = true;
        private String renderedSource = "";
        private boolean updatingGeometry;
        private final InvalidationListener renderListener = observable -> invalidateHighlighting();
        private final InvalidationListener positionListener = observable -> positionHighlights();
        private final InvalidationListener geometryListener = observable -> {
            metricsDirty = true;
            markersDirty = true;
            positionHighlights();
        };

        SyntaxSkin(SourceEditor editor) {
            super(editor);
            this.editor = editor;
            highlights.getStyleClass().add("source-highlights");
            highlights.setManaged(false);
            highlights.setMouseTransparent(true);
            highlights.setAutoSizeChildren(false);
            executionHighlights.getStyleClass().add("execution-highlights");
            executionHighlights.setManaged(false);
            executionHighlights.setMouseTransparent(true);
            executionHighlights.setAutoSizeChildren(false);
            editor.textProperty().addListener(renderListener);
            editor.fontProperty().addListener(renderListener);
            textFillProperty().addListener(renderListener);
        }

        private void invalidateHighlighting() {
            dirty = true;
            metricsDirty = true;
            markersDirty = true;
            editor.requestLayout();
        }

        @Override
        protected void layoutChildren(double x, double y, double width, double height) {
            super.layoutChildren(x, y, width, height);
            if (nativeText == null) {
                attachHighlights();
            }
            if (nativeText != null) {
                if (dirty) {
                    renderHighlights();
                    dirty = false;
                }
                positionHighlights();
                if (editor.pendingReveal >= 0) {
                    int offset = editor.pendingReveal;
                    editor.pendingReveal = -1;
                    scrollCharacterToVisible(offset);
                }
            }
        }

        private void attachHighlights() {
            // Use the standard skin's public scene graph and highlight hooks. If a
            // different skin structure is encountered, ordinary TextArea stays usable.
            for (Node child : getChildren()) {
                if (child instanceof ScrollPane scrollPane
                        && scrollPane.getContent() instanceof Parent content) {
                    for (Node node : content.getChildrenUnmodifiable()) {
                        if (node instanceof Group group && group.getChildren().size() == 1
                                && group.getChildren().get(0) instanceof Text text
                                && text.getText().equals(editor.getText())) {
                            nativeText = text;
                            break;
                        }
                    }
                }
                if (nativeText != null) {
                    break;
                }
            }
            if (nativeText == null) {
                return;
            }

            addHighlight(List.of(highlights), 0);
            highlights.toBack();
            addHighlight(List.of(executionHighlights), 0);
            executionHighlights.toBack();
            // Hide only unselected native glyphs. Its selection text, selection
            // background, caret and input-method decorations still render on top.
            nativeText.fillProperty().unbind();
            nativeText.setFill(Color.TRANSPARENT);
            nativeText.layoutBoundsProperty().addListener(geometryListener);
            nativeText.localToSceneTransformProperty().addListener(positionListener);
            nativeText.wrappingWidthProperty().addListener(geometryListener);
            nativeText.lineSpacingProperty().addListener(geometryListener);
            nativeText.tabSizeProperty().addListener(geometryListener);
            nativeText.boundsTypeProperty().addListener(geometryListener);
            nativeText.textOriginProperty().addListener(geometryListener);
            nativeText.textAlignmentProperty().addListener(geometryListener);
        }

        private void renderHighlights() {
            String source = editor.getText() == null ? "" : editor.getText();
            renderedSource = source;
            spansByKind.clear();
            for (SourceSyntax.Span span : SourceSyntax.highlight(source)) {
                spansByKind.computeIfAbsent(span.kind(), kind -> new ArrayList<>()).add(span);
            }
            List<Node> layers = new ArrayList<>();
            layers.add(createLayer(SourceSyntax.Kind.PLAIN));
            for (SourceSyntax.Kind kind : spansByKind.keySet()) {
                if (kind != SourceSyntax.Kind.PLAIN) {
                    layers.add(createLayer(kind));
                }
            }
            highlights.getChildren().setAll(layers);
        }

        private Text createLayer(SourceSyntax.Kind kind) {
            Text layer = new Text();
            layer.setManaged(false);
            layer.setFill(color(kind));
            layer.getStyleClass().add("syntax-" + kind.name().toLowerCase(Locale.ROOT));
            layer.setUserData(kind);
            // Every glyph is painted once; an unclipped plain layer underneath
            // colored glyphs would darken their antialiased edges.
            Path clip = new Path();
            clip.setFill(Color.BLACK);
            clip.setStroke(null);
            layer.setClip(clip);
            return layer;
        }

        private Paint color(SourceSyntax.Kind kind) {
            return switch (kind) {
                case KEYWORD -> Color.web("#7b2cbf");
                case INSTRUCTION -> Color.web("#005c99");
                case NUMBER -> Color.web("#a34b00");
                case COMMENT -> Color.web("#63735b");
                case STRING -> Color.web("#1e6f45");
                case LABEL -> Color.web("#8a3560");
                case MACRO -> Color.web("#006e78");
                case OPERATOR -> Color.web("#374151");
                case PLAIN -> getTextFill();
            };
        }

        private void positionHighlights() {
            if (nativeText == null || highlights.getParent() == null || updatingGeometry) {
                return;
            }
            if (!nativeText.getText().equals(renderedSource)) {
                // The native text can update before our text listener runs. Its
                // old token offsets must not be applied to the new source.
                invalidateHighlighting();
                return;
            }
            updatingGeometry = true;
            try {
                // Full-text clones use the native Text's layout engine and bounds
                // type. TextFlow has different line metrics and would drift from
                // TextArea's caret, especially with multiple lines or fallback fonts.
                // Read the observed transform property itself so later parent
                // layout changes can invalidate it and notify us again.
                Point2D origin = highlights.getParent().sceneToLocal(
                        nativeText.localToSceneTransformProperty().get().transform(0, 0));
                highlights.setLayoutX(origin.getX());
                highlights.setLayoutY(origin.getY());
                executionHighlights.setLayoutX(origin.getX());
                executionHighlights.setLayoutY(origin.getY());
                if (markersDirty) {
                    List<Node> markers = new ArrayList<>();
                    for (IndexRange range : editor.callerRanges) {
                        markers.add(marker(range, "execution-caller", Color.web("#dcecff")));
                    }
                    if (editor.executionRange != null) {
                        markers.add(marker(editor.executionRange, "execution-current",
                                Color.web(editor.executionHalted ? "#ffe1a3" : "#c8efc0")));
                    }
                    executionHighlights.getChildren().setAll(markers);
                    markersDirty = false;
                }
                for (Node node : highlights.getChildren()) {
                    Text layer = (Text) node;
                    layer.setText(nativeText.getText());
                    layer.setFont(nativeText.getFont());
                    layer.setFontSmoothingType(nativeText.getFontSmoothingType());
                    layer.setBoundsType(nativeText.getBoundsType());
                    layer.setTextOrigin(nativeText.getTextOrigin());
                    layer.setTextAlignment(nativeText.getTextAlignment());
                    layer.setWrappingWidth(nativeText.getWrappingWidth());
                    layer.setLineSpacing(nativeText.getLineSpacing());
                    layer.setTabSize(nativeText.getTabSize());
                    layer.setNodeOrientation(nativeText.getEffectiveNodeOrientation());
                    layer.setX(nativeText.getX());
                    layer.setY(nativeText.getY());
                    if (metricsDirty && layer.getClip() instanceof Path clip) {
                        List<PathElement> elements = new ArrayList<>();
                        for (SourceSyntax.Span span : spansByKind.getOrDefault(layer.getUserData(), List.of())) {
                            elements.addAll(List.of(nativeText.rangeShape(span.start(), span.end())));
                        }
                        clip.getElements().setAll(elements);
                    }
                }
                metricsDirty = false;
            } finally {
                updatingGeometry = false;
            }
        }

        private Path marker(IndexRange range, String styleClass, Color color) {
            Path marker = new Path(nativeText.rangeShape(range.getStart(), range.getEnd()));
            marker.getStyleClass().add(styleClass);
            marker.setFill(color);
            marker.setStroke(null);
            return marker;
        }

        @Override
        public void dispose() {
            editor.textProperty().removeListener(renderListener);
            editor.fontProperty().removeListener(renderListener);
            textFillProperty().removeListener(renderListener);
            if (nativeText != null) {
                nativeText.layoutBoundsProperty().removeListener(geometryListener);
                nativeText.localToSceneTransformProperty().removeListener(positionListener);
                nativeText.wrappingWidthProperty().removeListener(geometryListener);
                nativeText.lineSpacingProperty().removeListener(geometryListener);
                nativeText.tabSizeProperty().removeListener(geometryListener);
                nativeText.boundsTypeProperty().removeListener(geometryListener);
                nativeText.textOriginProperty().removeListener(geometryListener);
                nativeText.textAlignmentProperty().removeListener(geometryListener);
                nativeText.fillProperty().bind(textFillProperty());
                removeHighlight(List.of(highlights));
                removeHighlight(List.of(executionHighlights));
            }
            super.dispose();
        }
    }
}

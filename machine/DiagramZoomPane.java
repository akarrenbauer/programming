package machine;

import java.util.Objects;

import javafx.beans.property.DoubleProperty;
import javafx.beans.property.DoublePropertyBase;
import javafx.geometry.Bounds;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Region;
import javafx.scene.transform.Scale;

/** Scales an existing diagram, including its controls and hit areas, with matching scroll extents. */
public final class DiagramZoomPane extends Region {
    public static final double MIN_ZOOM = .5;
    public static final double MAX_ZOOM = 3;
    private final Region content;
    private final Group group;
    private final Scale scale = new Scale(1, 1, 0, 0);
    private ScrollPane scroll;
    private double viewportWidth;
    private double appliedZoom = 1;
    private boolean restorePosition;
    private double anchorX, anchorY;
    private final DoubleProperty zoomFactor = new DoublePropertyBase(1) {
        @Override public Object getBean() { return DiagramZoomPane.this; }
        @Override public String getName() { return "zoomFactor"; }
        @Override public void set(double value) { super.set(clamp(value)); }
        @Override protected void invalidated() { zoomChanged(); }
    };

    public DiagramZoomPane(Region content) {
        this.content = Objects.requireNonNull(content, "content");
        getStyleClass().add("diagram-zoom-pane");
        group = new Group(content);
        group.setAutoSizeChildren(false);
        group.getTransforms().add(scale);
        getChildren().add(group);
    }

    /** Installs this wrapper once; diagram nodes keep their identity, focus and pending text. */
    public void installIn(ScrollPane scroll) {
        if (this.scroll != null) throw new IllegalStateException("Zoom pane is already installed.");
        this.scroll = Objects.requireNonNull(scroll, "scroll");
        scroll.setFitToWidth(false);
        scroll.setFitToHeight(false);
        scroll.setContent(this);
        viewportWidth = scroll.getViewportBounds().getWidth();
        scroll.viewportBoundsProperty().addListener((observable, before, after) -> {
            if (Math.abs(viewportWidth - after.getWidth()) > .01) {
                viewportWidth = after.getWidth();
                requestLayout();
            }
        });
    }

    public DoubleProperty zoomFactorProperty() { return zoomFactor; }
    public double getZoomFactor() { return clamp(zoomFactor.get()); }
    public void setZoomFactor(double value) { zoomFactor.set(clamp(value)); }
    public static double clamp(double value) { return Double.isFinite(value) ? Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, value)) : 1; }

    private void zoomChanged() {
        double next = getZoomFactor();
        if (next == appliedZoom) return;
        if (scroll != null && !restorePosition) {
            anchorX = position(scroll.getHvalue(), scroll.getHmin(), scroll.getHmax(), getWidth() - scroll.getViewportBounds().getWidth()) / appliedZoom;
            anchorY = position(scroll.getVvalue(), scroll.getVmin(), scroll.getVmax(), getHeight() - scroll.getViewportBounds().getHeight()) / appliedZoom;
            restorePosition = true;
        }
        appliedZoom = next;
        scale.setX(next); scale.setY(next);
        requestLayout();
    }

    private double baseWidth() {
        return Math.max(viewportWidth, Math.max(content.minWidth(-1), content.prefWidth(-1)));
    }

    @Override protected double computeMinWidth(double height) { return computePrefWidth(height); }
    @Override protected double computePrefWidth(double height) { return baseWidth() * getZoomFactor(); }
    @Override protected double computeMaxWidth(double height) { return computePrefWidth(height); }
    @Override protected double computeMinHeight(double width) { return computePrefHeight(width); }
    @Override protected double computePrefHeight(double width) {
        double base = baseWidth();
        return Math.max(content.minHeight(base), content.prefHeight(base)) * getZoomFactor();
    }
    @Override protected double computeMaxHeight(double width) { return computePrefHeight(width); }

    @Override protected void layoutChildren() {
        double width = baseWidth(), height = Math.max(content.minHeight(width), content.prefHeight(width));
        content.resizeRelocate(0, 0, width, height);
        content.layout();
        if (restorePosition && scroll != null) {
            restorePosition = false;
            scroll.setHvalue(fraction(anchorX * appliedZoom, getWidth() - scroll.getViewportBounds().getWidth(), scroll.getHmin(), scroll.getHmax()));
            scroll.setVvalue(fraction(anchorY * appliedZoom, getHeight() - scroll.getViewportBounds().getHeight(), scroll.getVmin(), scroll.getVmax()));
        }
    }

    /** Reveals an existing linked element using its transformed bounds, without changing focus or selection. */
    public void reveal(Node element) {
        if (scroll == null || element.getScene() == null || element.getScene() != getScene()) return;
        applyCss(); autosize(); layout();
        Bounds target = sceneToLocal(element.localToScene(element.getBoundsInLocal()));
        Bounds viewport = scroll.getViewportBounds();
        double x = position(scroll.getHvalue(), scroll.getHmin(), scroll.getHmax(), getWidth() - viewport.getWidth());
        double y = position(scroll.getVvalue(), scroll.getVmin(), scroll.getVmax(), getHeight() - viewport.getHeight());
        scroll.setHvalue(fraction(revealPosition(x, viewport.getWidth(), target.getMinX(), target.getMaxX()),
                getWidth() - viewport.getWidth(), scroll.getHmin(), scroll.getHmax()));
        scroll.setVvalue(fraction(revealPosition(y, viewport.getHeight(), target.getMinY(), target.getMaxY()),
                getHeight() - viewport.getHeight(), scroll.getVmin(), scroll.getVmax()));
    }

    private static double revealPosition(double current, double viewport, double first, double last) {
        if (first < current || last - first > viewport) return first - 8;
        if (last > current + viewport) return last - viewport + 8;
        return current;
    }

    private static double position(double value, double min, double max, double extent) {
        return extent <= 0 || max <= min ? 0 : (value - min) / (max - min) * extent;
    }

    private static double fraction(double position, double extent, double min, double max) {
        return extent <= 0 ? min : min + Math.max(0, Math.min(1, position / extent)) * (max - min);
    }
}

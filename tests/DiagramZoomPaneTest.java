package machine;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import javafx.application.Platform;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.geometry.Bounds;
import javafx.scene.Scene;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

/** Geometric diagram scaling, true scroll extents, retained input, and transformed reveal coordinates. */
public final class DiagramZoomPaneTest {
    private static Stage stage;
    private static ScrollPane scroll;
    private static Pane content;
    private static DiagramZoomPane zoom;
    private static TextField field;
    private static int passed;
    @FunctionalInterface private interface Check { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        Platform.startup(() -> { Platform.setImplicitExit(false); started.countDown(); });
        require(started.await(10, TimeUnit.SECONDS), "JavaFX starts");
        try {
            onFx(() -> {
                content = new Pane(); content.setMinSize(600, 1800); content.setPrefSize(600, 1800);
                field = new TextField("INC x;"); field.resizeRelocate(380, 1700, 200, 30); content.getChildren().add(field);
                zoom = new DiagramZoomPane(content); scroll = new ScrollPane(); zoom.installIn(scroll);
                stage = new Stage(); stage.setScene(new Scene(new StackPane(scroll), 500, 350)); stage.show(); layout();
            });
            check("zoom scales controls and scroll extents by the same factor", () -> {
                double initialHeight = field.localToScene(field.getBoundsInLocal()).getHeight();
                zoom.setZoomFactor(2); layout();
                require(Math.abs(zoom.getWidth() - 1200) < 1 && Math.abs(zoom.getHeight() - 3600) < 1,
                        "wrapper exposes scaled content dimensions to ScrollPane");
                require(Math.abs(field.localToScene(field.getBoundsInLocal()).getHeight() - 2 * initialHeight) < .1,
                        "text field and its pointer target scale geometrically");
                require(scroll.getContent() == zoom && content.getChildren().get(0) == field, "zoom retains content and control identities");
            });
            check("zoom preserves pending input focus selection and its undo history", () -> {
                field.requestFocus(); field.replaceText(0, 3, "DEC"); field.selectRange(4, 1);
                zoom.setZoomFactor(.75); layout();
                require(stage.getScene().getFocusOwner() == field && field.getAnchor() == 4 && field.getCaretPosition() == 1,
                        "scaling never steals focus or selection");
                require(field.getText().equals("DEC x;") && field.isUndoable(), "pending edit survives zoom");
                field.undo(); require(field.getText().equals("INC x;"), "zoom introduces no edit history");
            });
            check("multiple zoom changes before layout retain the original logical scroll anchor", () -> {
                zoom.setZoomFactor(1); layout(); scroll.setVvalue(.5); scroll.setHvalue(.4); layout();
                double oldY = scroll.getVvalue() * (zoom.getHeight() - scroll.getViewportBounds().getHeight());
                double oldX = scroll.getHvalue() * (zoom.getWidth() - scroll.getViewportBounds().getWidth());
                zoom.setZoomFactor(1.2); zoom.setZoomFactor(1.5); zoom.setZoomFactor(2); layout();
                double newY = scroll.getVvalue() * (zoom.getHeight() - scroll.getViewportBounds().getHeight()) / 2;
                double newX = scroll.getHvalue() * (zoom.getWidth() - scroll.getViewportBounds().getWidth()) / 2;
                require(Math.abs(newY - oldY) < 1 && Math.abs(newX - oldX) < 1, "rapid zoom does not drift the visible content origin");
            });
            check("revealing a zoomed offscreen field scrolls both axes without changing focus", () -> {
                zoom.setZoomFactor(2.5); layout(); scroll.setVvalue(0); scroll.setHvalue(0); layout();
                zoom.reveal(field); layout();
                Bounds viewport = scroll.lookup(".viewport").localToScene(scroll.lookup(".viewport").getLayoutBounds());
                Bounds target = field.localToScene(field.getBoundsInLocal());
                require(scroll.getVvalue() > 0 && scroll.getHvalue() > 0 && viewport.intersects(target),
                        "transformed source navigation reaches the scaled field");
                require(stage.getScene().getFocusOwner() == field && field.getText().equals("INC x;"), "navigation leaves keyboard ownership and input intact");
            });
            check("zoom limits normalize setters and binding updates retain the same controls", () -> {
                zoom.setZoomFactor(-4); require(zoom.getZoomFactor() == .5, "minimum zoom");
                zoom.zoomFactorProperty().set(9); require(zoom.getZoomFactor() == 3, "property maximum zoom");
                zoom.setZoomFactor(Double.NaN); require(zoom.getZoomFactor() == 1, "nonfinite zoom resets to default");
                SimpleDoubleProperty shared = new SimpleDoubleProperty(1.5); zoom.zoomFactorProperty().bind(shared); layout();
                require(zoom.getZoomFactor() == 1.5 && content.getChildren().get(0) == field, "session binding uses existing content");
                shared.set(2); layout(); require(zoom.getZoomFactor() == 2, "session changes propagate"); zoom.zoomFactorProperty().unbind();
            });
            System.out.println("DiagramZoomPaneTest: " + passed + " passed, 0 failed");
        } finally { onFx(() -> { if (stage != null) stage.close(); }); Platform.exit(); }
    }

    private static void layout() { stage.getScene().getRoot().applyCss(); stage.getScene().getRoot().layout(); scroll.layout(); zoom.layout(); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void check(String name, Check action) throws Exception { onFx(action); passed++; System.out.println("PASS " + name); }
    private static void onFx(Check action) throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> { action.run(); return null; }); Platform.runLater(task); task.get(20, TimeUnit.SECONDS);
    }
}

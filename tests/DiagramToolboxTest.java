import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.scene.AccessibleAction;
import javafx.scene.AccessibleRole;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.scene.robot.Robot;
import javafx.stage.Stage;

import machine.DiagramToolbox;
import machine.GameFeature;
import machine.StructuredDiagram;
import machine.StructuredDiagramView;

/** Native symbol, accessibility, safe-payload and real pointer drag tests. Run on a private/headless display. */
public final class DiagramToolboxTest {
    private static DiagramToolbox toolbox;
    private static Stage stage;
    private static Robot robot;
    private static StackPane target;
    private static final AtomicReference<DiagramToolbox.Kind> dropped = new AtomicReference<>();
    private static final AtomicReference<Set<TransferMode>> offered = new AtomicReference<>();
    private static int passed;
    private static int failed;
    @FunctionalInterface private interface Check { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        Platform.startup(() -> { Platform.setImplicitExit(false); started.countDown(); });
        require(started.await(10, TimeUnit.SECONDS), "JavaFX did not start");
        try {
            onFx(() -> {
                toolbox = new DiagramToolbox();
                toolbox.setPadding(new Insets(15));
                target = new StackPane(new Label("Drop test target"));
                target.setStyle("-fx-background-color: #e7f2f1; -fx-border-color: #456777;");
                target.setOnDragOver(event -> {
                    DiagramToolbox.Kind kind = DiagramToolbox.read(event.getDragboard());
                    if (DiagramToolbox.available(kind, EnumSet.allOf(GameFeature.class))) {
                        event.acceptTransferModes(TransferMode.COPY);
                        offered.set(Set.copyOf(event.getDragboard().getTransferModes()));
                    }
                    event.consume();
                });
                target.setOnDragDropped(event -> {
                    DiagramToolbox.Kind kind = DiagramToolbox.read(event.getDragboard());
                    dropped.set(kind);
                    event.setDropCompleted(kind != null);
                    toolbox.clearSelection();
                    event.consume();
                });
                BorderPane root = new BorderPane(target, toolbox, null, null, null);
                root.setPadding(new Insets(15));
                stage = new Stage();
                stage.setScene(new Scene(root, 720, 720));
                stage.show();
                robot = new Robot();
            });

            checkFx("only earned structures are visible and selection is cleared on relock", () -> {
                require(toolbox.getChildren().stream().noneMatch(Node::isVisible), "initial toolbox has no unlocked symbols");
                toolbox.setFeatures(Set.of(GameFeature.IF));
                require(tile(DiagramToolbox.Kind.IF).isVisible(), "if is earned");
                require(!tile(DiagramToolbox.Kind.WHILE).isVisible() && !tile(DiagramToolbox.Kind.DO_WHILE).isVisible(), "both loop forms remain locked");
                tile(DiagramToolbox.Kind.IF).executeAccessibleAction(AccessibleAction.FIRE);
                require(toolbox.selectedKind() == DiagramToolbox.Kind.IF, "accessible action selects an available symbol");
                toolbox.setFeatures(Set.of(GameFeature.WHILE));
                require(toolbox.selectedKind() == null, "removing selected feature clears selection");
                tile(DiagramToolbox.Kind.IF).executeAccessibleAction(AccessibleAction.FIRE);
                require(toolbox.selectedKind() == null, "locked symbols cannot be selected accessibly");
            });

            checkFx("native shapes distinguish decision top-test and bottom-test loops without extra controls", () -> {
                toolbox.setFeatures(EnumSet.allOf(GameFeature.class));
                toolbox.applyCss();
                toolbox.layout();
                for (DiagramToolbox.Kind kind : DiagramToolbox.Kind.values()) {
                    Node tile = tile(kind);
                    require(tile.isVisible() && tile.isFocusTraversable(), "earned symbol is visible and keyboard reachable");
                    require(tile.getAccessibleRole() == AccessibleRole.BUTTON && tile.getAccessibleText().contains("Insert"), "symbol has an accessible insertion action");
                    String css = ".diagram-symbol-" + kind.name().toLowerCase().replace('_', '-');
                    require(tile.lookup(css) != null, "native symbolic diagram for " + kind);
                }
                require(toolbox.lookupAll(".button").isEmpty() && toolbox.lookupAll(".menu-button").isEmpty(), "toolbox has only symbol tiles");
            });

            checkFx("keyboard selection and Escape provide a drag-free insertion path", () -> {
                Node whileTile = tile(DiagramToolbox.Kind.WHILE);
                whileTile.fireEvent(key(KeyCode.ENTER));
                require(toolbox.selectedKind() == DiagramToolbox.Kind.WHILE, "Enter selects loop");
                whileTile.fireEvent(key(KeyCode.ESCAPE));
                require(toolbox.selectedKind() == null, "Escape cancels selected insertion");
                tile(DiagramToolbox.Kind.DO_WHILE).fireEvent(key(KeyCode.SPACE));
                require(toolbox.selectedKind() == DiagramToolbox.Kind.DO_WHILE, "Space selects post-test loop");
                toolbox.clearSelection();
            });

            checkFx("controlled payloads reject arbitrary source and templates parse as the intended structures", () -> {
                for (Object value : new Object[] {null, 4, "", "while", "IF; HALT;", "UNKNOWN"}) {
                    require(DiagramToolbox.decode(value) == null, "invalid payload rejected: " + value);
                }
                require(DiagramToolbox.read(null) == null, "missing dragboard rejected");
                for (DiagramToolbox.Kind kind : DiagramToolbox.Kind.values()) {
                    require(DiagramToolbox.decode(kind.name()) == kind, "kind payload round trip");
                    String code = "var cells[2]=0; begin:\n" + DiagramToolbox.template(kind, "cells[1]") + "\nHALT;";
                    StructuredDiagram diagram = StructuredDiagram.parse(code);
                    require(diagram.main().nodes().get(0).kind().name().equals(kind.name()), "template creates requested structure");
                    require(diagram.main().nodes().get(0).body().nodes().get(0).text().equals(";"), "editable empty body placeholder");
                }
                try {
                    DiagramToolbox.template(DiagramToolbox.Kind.IF, "x); HALT; if(x");
                    throw new AssertionError("template accepted source injection as variable");
                } catch (IllegalArgumentException expected) { }
            });

            check("real pointer gesture transfers only the selected kind using COPY", () -> {
                for (DiagramToolbox.Kind kind : DiagramToolbox.Kind.values()) {
                    dropped.set(null);
                    offered.set(null);
                    drag(tile(kind), target);
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (dropped.get() == null && System.nanoTime() < deadline) Thread.sleep(20);
                    require(dropped.get() == kind, "real drag reaches target with " + kind);
                    require(offered.get().equals(Set.of(TransferMode.COPY)), "source offers COPY exclusively");
                    onFx(() -> require(toolbox.selectedKind() == null, "successful drop can clear keyboard selection"));
                }
            });

            check("real toolbox drops insert structures into the source and nested sequence gaps", () -> {
                AtomicReference<StructuredDiagramView> actual = new AtomicReference<>();
                AtomicReference<String> saved = new AtomicReference<>();
                String starter = "// keep this note\nvar n=0;\nbegin:\nHALT;\n";
                onFx(() -> {
                    StructuredDiagramView view = new StructuredDiagramView();
                    view.setFeatures(EnumSet.allOf(GameFeature.class));
                    view.setSource(starter);
                    view.setOnSourceChanged(text -> { saved.set(text); view.setSource(text); });
                    stage.setScene(new Scene(new StackPane(view), 720, 720));
                    actual.set(view);
                });
                for (DiagramToolbox.Kind kind : DiagramToolbox.Kind.values()) {
                    onFx(() -> actual.get().setSource(starter));
                    saved.set(null);
                    AtomicReference<Node> symbol = new AtomicReference<>();
                    AtomicReference<Node> gap = new AtomicReference<>();
                    onFx(() -> {
                        actual.get().applyCss();
                        actual.get().layout();
                        int start = StructuredDiagram.parse(starter).main().start();
                        symbol.set(actual.get().lookup("#diagram-tool-" + kind.name().toLowerCase().replace('_', '-')));
                        gap.set(actual.get().lookup("#diagram-drop-" + start + "-0"));
                        require(symbol.get() != null && gap.get() != null, "real view exposes symbol and main gap");
                    });
                    drag(symbol.get(), gap.get());
                    awaitSource(saved);
                    StructuredDiagram diagram = StructuredDiagram.parse(saved.get());
                    require(diagram.main().nodes().get(0).kind().name().equals(kind.name()), "real view inserted " + kind);
                    require(diagram.main().nodes().get(1).text().equals("HALT;"), "neighbor retained after drop");
                    require(saved.get().startsWith("// keep this note\nvar n=0;"), "declaration and comment retained");
                }

                AtomicReference<Node> nestedSymbol = new AtomicReference<>();
                AtomicReference<Node> nestedGap = new AtomicReference<>();
                saved.set(null);
                onFx(() -> {
                    StructuredDiagram diagram = StructuredDiagram.parse(actual.get().getSource());
                    int nestedStart = diagram.main().nodes().get(0).body().start();
                    nestedSymbol.set(actual.get().lookup("#diagram-tool-if"));
                    nestedGap.set(actual.get().lookup("#diagram-drop-" + nestedStart + "-0"));
                    require(nestedSymbol.get() != null && nestedGap.get() != null, "nested sequence exposes a drop gap");
                });
                drag(nestedSymbol.get(), nestedGap.get());
                awaitSource(saved);
                StructuredDiagram nested = StructuredDiagram.parse(saved.get());
                require(nested.main().nodes().get(0).body().nodes().get(0).kind() == StructuredDiagram.Kind.IF,
                        "real gesture inserts into an enclosing loop's body");
                onFx(() -> {
                    StructuredDiagram diagram = StructuredDiagram.parse(actual.get().getSource());
                    int branchStart = diagram.main().nodes().get(0).body().nodes().get(0).body().start();
                    Node symbol = actual.get().lookup("#diagram-tool-while");
                    Node gap = actual.get().lookup("#diagram-drop-" + branchStart + "-0");
                    require(symbol != null && gap != null, "true branch has a keyboard insertion gap");
                    symbol.fireEvent(key(KeyCode.ENTER));
                    gap.fireEvent(key(KeyCode.SPACE));
                    StructuredDiagram changed = StructuredDiagram.parse(actual.get().getSource());
                    require(changed.main().nodes().get(0).body().nodes().get(0).body().nodes().get(0).kind() == StructuredDiagram.Kind.WHILE,
                            "keyboard symbol selection inserts into nested decision branch");
                });
            });
        } finally {
            onFx(() -> stage.close());
            Platform.exit();
        }
        System.out.println("DiagramToolboxTest: " + passed + " passed, " + failed + " failed");
        if (failed != 0) throw new AssertionError(failed + " toolbox checks failed");
    }

    private static void drag(Node from, Node to) throws Exception {
        AtomicReference<Point2D> start = new AtomicReference<>();
        AtomicReference<Point2D> end = new AtomicReference<>();
        onFx(() -> {
            stage.getScene().getRoot().applyCss();
            stage.getScene().getRoot().layout();
            start.set(center(from));
            end.set(center(to));
            robot.mouseMove(start.get());
        });
        Thread.sleep(80);
        onFx(() -> robot.mousePress(MouseButton.PRIMARY));
        Thread.sleep(80);
        for (int index = 1; index <= 12; index++) {
            double fraction = index / 12.0;
            Point2D point = start.get().interpolate(end.get(), fraction);
            onFx(() -> robot.mouseMove(point));
            Thread.sleep(30);
        }
        onFx(() -> robot.mouseRelease(MouseButton.PRIMARY));
        Thread.sleep(80);
    }

    private static void awaitSource(AtomicReference<String> source) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (source.get() == null && System.nanoTime() < deadline) Thread.sleep(20);
        require(source.get() != null, "successful drop emits changed source");
    }

    private static Point2D center(Node node) {
        Bounds bounds = node.localToScreen(node.getBoundsInLocal());
        require(bounds != null, "drag node must be visible on screen");
        return new Point2D((bounds.getMinX() + bounds.getMaxX()) / 2, (bounds.getMinY() + bounds.getMaxY()) / 2);
    }

    private static Node tile(DiagramToolbox.Kind kind) {
        return toolbox.getChildren().stream().filter(node -> node.getUserData() == kind).findFirst().orElseThrow();
    }

    private static KeyEvent key(KeyCode code) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false);
    }

    private static void checkFx(String name, Check action) { check(name, () -> onFx(action)); }

    private static void check(String name, Check action) {
        try { action.run(); passed++; System.out.println("PASS " + name); }
        catch (Exception | AssertionError error) { failed++; System.err.println("FAIL " + name); error.printStackTrace(); }
    }

    private static void onFx(Check action) throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> { action.run(); return null; });
        Platform.runLater(task);
        task.get(15, TimeUnit.SECONDS);
    }

    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}

package machine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.robot.Robot;
import javafx.stage.Stage;
import javafx.stage.Window;

/** Actual pointer coverage of the tutorial action row after layout and hint changes. */
public final class LanguageGameButtonHitTest {
    private static LanguageGame game;
    private static Stage stage;
    private static Robot robot;
    private static final AtomicInteger actions = new AtomicInteger();
    private static final AtomicInteger checks = new AtomicInteger();
    private static final AtomicReference<String> picked = new AtomicReference<>();
    private static final AtomicReference<Throwable> fxFailure = new AtomicReference<>();
    private static int passed;
    @FunctionalInterface private interface Action { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.Category.DISPLAY, Locale.ENGLISH);
        Path profile = Files.createTempDirectory("game-button-hits-").resolve("progress.properties");
        GameProgress progress = new GameProgress(profile, GameCatalog.levels());
        for (int index = 0; index < 8; ++index) {
            GameLevel level = GameCatalog.levels().get(index);
            progress.complete(level, level.starter());
        }
        progress.setSelectedIndex(0); progress.save();
        CountDownLatch ready = new CountDownLatch(1);
        Platform.startup(() -> {
            Thread.currentThread().setUncaughtExceptionHandler((thread, error) -> fxFailure.compareAndSet(null, error));
            Platform.setImplicitExit(false); ready.countDown();
        });
        expect(ready.await(10, TimeUnit.SECONDS), "JavaFX starts");
        try {
            onFx(() -> {
                game = new LanguageGame() { @Override Path progressPath() { return profile; } };
                stage = new Stage(); game.start(stage); stage.setX(0); stage.setY(0);
                robot = new Robot();
                button().addEventFilter(ActionEvent.ACTION, event -> actions.incrementAndGet());
                button().disabledProperty().addListener((observable, before, disabled) -> { if (disabled) checks.incrementAndGet(); });
                stage.getScene().addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
                    Node hit = event.getPickResult().getIntersectedNode();
                    picked.set(hit == null ? "none" : hit.getClass().getSimpleName() + "#" + hit.getId() + " " + hit.getStyleClass());
                });
                layout();
            });
            pulse();
            check("all parts of Check solution respond to a real pointer", LanguageGameButtonHitTest::probeButton);

            for (int index : new int[] {1, 8, 0, 7, 2, 0}) {
                check("clearing a visible hint while changing to level " + (index + 1) + " keeps the entire button clickable", () -> {
                    onFx(() -> node("show-hint", Button.class).fire());
                    pulse();
                    onFx(() -> {
                        expect(!node("game-hint", Label.class).getText().isEmpty(), "a visible hint precedes navigation");
                        node("game-levels", ListView.class).getSelectionModel().select(index);
                        expect(node("game-hint", Label.class).getText().isEmpty(), "navigation clears the hint");
                    });
                    pulse(); probeButton();
                });
            }

            check("compact German task layout and enlarged editors leave the whole action button clickable", () -> {
                onFx(() -> {
                    stage.hide(); stage.setWidth(1000); stage.setHeight(720); stage.show();
                    @SuppressWarnings("unchecked") ComboBox<GameTaskLanguage> language = node("task-language", ComboBox.class);
                    language.setValue(GameTaskLanguage.GERMAN);
                    SourceEditor editor = node("game-editor", SourceEditor.class);
                    for (int i = 0; i < 10; ++i) editor.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ADD, false, true, false, false));
                    node("show-hint", Button.class).fire();
                });
                pulse();
                onFx(() -> node("game-levels", ListView.class).getSelectionModel().select(8));
                pulse(); probeButton();
            });

            check("clicking from a pending diagram field reaches the action button without applying its draft", () -> {
                AtomicReference<String> source = new AtomicReference<>();
                AtomicReference<TextInputControl> draft = new AtomicReference<>();
                onFx(() -> {
                    StructuredDiagramView view = node("game-diagram", StructuredDiagramView.class);
                    TextInputControl field = view.lookupAll(".diagram-edit").stream().filter(TextInputControl.class::isInstance)
                            .map(TextInputControl.class::cast).filter(input -> input.getText().equals("DEC n;")).findFirst().orElseThrow();
                    source.set(node("game-editor", SourceEditor.class).getText()); draft.set(field);
                    field.setText("INC n;"); field.requestFocus();
                });
                pulse(); click("label");
                onFx(() -> {
                    expect(node("game-editor", SourceEditor.class).getText().equals(source.get()), "checking does not implicitly apply pending diagram text");
                    expect(draft.get().getText().equals("INC n;"), "pending diagram text remains available");
                });
            });
            check("resetting a private game with a visible hint leaves no stale button overlay", () -> {
                onFx(() -> {
                    node("show-hint", Button.class).fire();
                    Platform.runLater(() -> node("reset-game", Button.class).fire());
                });
                AtomicReference<DialogPane> dialog = new AtomicReference<>();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (dialog.get() == null && System.nanoTime() < deadline) {
                    onFx(() -> Window.getWindows().stream().filter(window -> window != stage && window.isShowing())
                            .map(window -> window.getScene().lookup(".dialog-pane")).filter(DialogPane.class::isInstance)
                            .map(DialogPane.class::cast).findFirst().ifPresent(dialog::set));
                    if (dialog.get() == null) Thread.sleep(20);
                }
                expect(dialog.get() != null, "reset confirmation opens");
                onFx(() -> {
                    DialogPane pane = dialog.get();
                    ((Button) pane.lookupButton(pane.getButtonTypes().stream().filter(type -> type.getText().equals("Reset game"))
                            .findFirst().orElseThrow())).fire();
                });
                pulse(); probeButton();
            });
            System.out.println("LanguageGameButtonHitTest: " + passed + " passed, 0 failed (" + actions.get() + " real checks)");
        } finally {
            try { onFx(() -> { if (game != null) game.stop(); for (Window window : new ArrayList<>(Window.getWindows())) window.hide(); }); }
            finally { Platform.exit(); }
        }
    }

    private static void probeButton() throws Exception {
        for (String location : new String[] {"center", "label", "left", "right", "top", "bottom"}) click(location);
    }

    private static void click(String location) throws Exception {
        AtomicReference<Point2D> point = new AtomicReference<>();
        int before = actions.get(), beforeChecks = checks.get();
        onFx(() -> {
            layout(); Button button = button(); expect(!button.isDisabled(), "button is enabled before pointer press");
            Bounds bounds = button.localToScreen(button.getLayoutBounds());
            double x = (bounds.getMinX() + bounds.getMaxX()) / 2, y = (bounds.getMinY() + bounds.getMaxY()) / 2;
            switch (location) {
                case "label" -> {
                    Node text = button.lookup(".text"); expect(text != null, "button text exists");
                    Bounds label = text.localToScreen(text.getBoundsInLocal());
                    x = label.getMinX() + label.getWidth() * .35; y = (label.getMinY() + label.getMaxY()) / 2;
                }
                case "left" -> x = bounds.getMinX() + 2;
                case "right" -> x = bounds.getMaxX() - 2;
                case "top" -> y = bounds.getMinY() + 2;
                case "bottom" -> y = bounds.getMaxY() - 2;
                default -> { }
            }
            point.set(new Point2D(x, y)); picked.set(null); robot.mouseMove(point.get());
        });
        Thread.sleep(60);
        onFx(() -> robot.mousePress(MouseButton.PRIMARY));
        Thread.sleep(60);
        onFx(() -> robot.mouseRelease(MouseButton.PRIMARY));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (actions.get() == before && System.nanoTime() < deadline) Thread.sleep(20);
        onFx(() -> {
            Label hint = node("game-hint", Label.class);
            expect(actions.get() == before + 1, "pointer " + location + " at " + point.get() + " must fire once; picked=" + picked.get()
                    + "; hint managed=" + hint.isManaged() + " visible=" + hint.isVisible()
                    + " bounds=" + hint.localToScreen(hint.getBoundsInLocal()));
            expect(checks.get() == beforeChecks + 1, "pointer " + location + " must start exactly one actual asynchronous check");
        });
        AtomicReference<Boolean> done = new AtomicReference<>(false);
        while (!done.get() && System.nanoTime() < deadline) {
            onFx(() -> done.set(!button().isDisabled()));
            if (!done.get()) Thread.sleep(20);
        }
        expect(done.get(), "solution check finishes"); pulse();
    }

    private static Button button() { return node("check-solution", Button.class); }
    private static <T extends Node> T node(String id, Class<T> type) {
        Node found = stage.getScene().lookup("#" + id); expect(type.isInstance(found), "missing " + id); return type.cast(found);
    }
    private static void layout() { stage.getScene().getRoot().applyCss(); stage.getScene().getRoot().layout(); }
    private static void pulse() throws Exception {
        CountDownLatch rendered = new CountDownLatch(1);
        onFx(() -> new AnimationTimer() {
            private int frames;
            @Override public void handle(long now) { if (++frames == 2) { stop(); rendered.countDown(); } }
        }.start());
        expect(rendered.await(10, TimeUnit.SECONDS), "JavaFX renders layout pulses"); onFx(LanguageGameButtonHitTest::layout);
    }
    private static void check(String name, Action action) throws Exception { action.run(); ++passed; System.out.println("PASS " + name); }
    private static void onFx(Action action) throws Exception {
        FutureTask<Void> future = new FutureTask<>(() -> { action.run(); return null; }); Platform.runLater(future); future.get(15, TimeUnit.SECONDS);
        Throwable failure = fxFailure.getAndSet(null); if (failure != null) throw new AssertionError("uncaught JavaFX exception", failure);
    }
    private static void expect(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}

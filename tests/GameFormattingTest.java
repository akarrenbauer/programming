package machine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import javafx.stage.Stage;

/** Earned helper presentation, shared zoom, and undoable formatting through the real game UI. */
public final class GameFormattingTest {
    private static final String RAW = "// Keep this note.\nvar n=2;var result=0;\n"
            + "begin:while(n!=0){DEC n;if(n==0){INC result;}else{NOP;}}HALT;\n";
    private static final String BASE = "var n = 0;\nvar result = 0;\nbegin:\nHALT;\n";
    private static LanguageGame game;
    private static Stage stage;
    private static SourceEditor source;
    private static SourceEditor toolbox;
    private static int selectedIndex;
    private static int passed;
    private static final AtomicReference<Throwable> fxFailure = new AtomicReference<>();
    @FunctionalInterface private interface Action { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        Locale previousLocale = Locale.getDefault(Locale.Category.DISPLAY);
        Locale.setDefault(Locale.Category.DISPLAY, Locale.ENGLISH);
        Path profile = Files.createTempDirectory("game-formatting-").resolve("progress.properties");
        GameProgress progress = new GameProgress(profile, GameCatalog.levels());
        for (int index = 0; index < GameCatalog.levels().size(); ++index) {
            GameLevel level = GameCatalog.levels().get(index);
            if (level.id().equals("macro-practice")) { selectedIndex = index; break; }
            progress.complete(level, level.starter());
        }
        expect(progress.features().contains(GameFeature.MACROS), "fixture has earned helper insertion");
        progress.setSelectedIndex(selectedIndex);
        progress.setDraft(GameCatalog.levels().get(selectedIndex).id(), RAW);
        progress.save();
        String formattedHelpers = SourceFormatter.format(GameCatalog.library(progress.completedIds()).get("toolbox"));
        String formatted = SourceFormatter.format(RAW);
        CountDownLatch started = new CountDownLatch(1);
        Platform.startup(() -> {
            Thread.currentThread().setUncaughtExceptionHandler((thread, error) -> fxFailure.compareAndSet(null, error));
            Platform.setImplicitExit(false); started.countDown();
        });
        expect(started.await(10, TimeUnit.SECONDS), "JavaFX starts");
        try {
            onFx(() -> {
                game = new LanguageGame() { @Override Path progressPath() { return profile; } };
                stage = new Stage(); game.start(stage);
                source = node("game-editor", SourceEditor.class);
                selectTab("toolbox-tab");
                toolbox = node("game-toolbox", SourceEditor.class);
            });
            pulse();

            check("earned helpers are formatted, colored, and read-only", () -> onFx(() -> {
                expect(!toolbox.isEditable() && toolbox.getText().equals(formattedHelpers), "toolbox uses a read-only formatted source editor");
                expect(toolbox.getText().contains("void bump(out) {\n  INC out;\n}"), "earned macro body has two-space indentation and Java braces");
                expect(toolbox.getText().contains("\n    DEC out;"), "nested helper loop body has four-space indentation");
                Text keyword = token(toolbox, "keyword"), instruction = token(toolbox, "instruction"), comment = token(toolbox, "comment");
                for (Text layer : List.of(keyword, instruction, comment)) {
                    expect(layer.getFill() instanceof Color && ((Color) layer.getFill()).getOpacity() > 0, "syntax layer has a visible color");
                    expect(layer.getText().equals(toolbox.getText()), "highlighting retains the exact formatted source");
                }
                expect(!keyword.getFill().equals(instruction.getFill()) && !comment.getFill().equals(instruction.getFill()),
                        "keywords, instructions, and helper comments use distinct colors");
                toolbox.requestFocus();
                toolbox.fireEvent(new KeyEvent(KeyEvent.KEY_TYPED, "x", "", KeyCode.UNDEFINED, false, false, false, false));
                expect(toolbox.getText().equals(formattedHelpers), "typing cannot change earned helper definitions");
            }));

            check("toolbox zoom shares the source and diagram session", () -> onFx(() -> {
                toolbox.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ADD, false, true, false, false));
                close(toolbox.getZoomFactor(), 1.1, "toolbox shortcut changes its zoom");
                close(source.getZoomFactor(), 1.1, "source follows the same zoom");
                selectTab(null);
                close(node("game-diagram", StructuredDiagramView.class).getZoomFactor(), 1.1, "diagram follows toolbox zoom");
                expect(source.getText().equals(RAW) && !source.isUndoable(), "zoom does not edit the source or its history");
                source.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.SUBTRACT, false, true, false, false));
                close(toolbox.getZoomFactor(), 1, "source shortcut also updates the cached toolbox");
            }));

            check("inserting earned helpers uses the same indentation and one undo transaction", () -> onFx(() -> {
                source.setText(BASE);
                selectTab("toolbox-tab");
                Button insert = node("game-insert-helpers", Button.class);
                expect(!insert.isDisabled(), "earned macros enable insertion");
                insert.fire();
                String expected = formattedHelpers + "\n" + BASE;
                expect(source.getText().equals(expected), "inserted definitions exactly match the displayed helper source");
                expect(toolbox.getText().equals(formattedHelpers), "insertion leaves the reference toolbox unchanged");
                selectTab(null);
                expect(node("game-diagram", StructuredDiagramView.class).isSynchronized(), "inserted helper definitions remain valid in the diagram");
                node("game-undo", Button.class).fire();
                expect(source.getText().equals(BASE) && !source.isUndoable(), "one Undo removes the whole helper insertion");
            }));

            check("Format updates both diagrams and is exactly one native undoable edit", () -> onFx(() -> {
                source.setText(RAW);
                mode("Flowchart");
                FlowchartView flowchart = node("game-flowchart", FlowchartView.class);
                mode("Nassi–Shneiderman");
                StructuredDiagramView structured = node("game-diagram", StructuredDiagramView.class);
                AtomicInteger writes = new AtomicInteger();
                ChangeListener<String> listener = (observable, before, after) -> writes.incrementAndGet();
                source.textProperty().addListener(listener);
                try {
                    node("game-format", Button.class).fire();
                    expect(!formatted.equals(RAW) && source.getText().equals(formatted), "Format changes the entire source through the formatter");
                    expect(formatted.contains("\n  DEC n;") && formatted.contains("\n    INC result;")
                                    && formatted.contains("} else {"), "nested source receives two-space Java-style formatting");
                    expect(flowchart.isSynchronized() && flowchart.getSource().equals(formatted)
                                    && structured.isSynchronized() && structured.getSource().equals(formatted),
                            "visible and cached diagram snapshots both follow the formatted draft");
                    node("game-format", Button.class).fire();
                    expect(writes.get() == 1, "repeating Format on formatted text emits no extra source edit");
                    node("game-undo", Button.class).fire();
                    expect(source.getText().equals(RAW) && !source.isUndoable(), "one Undo restores all original whitespace");
                    expect(flowchart.getSource().equals(RAW) && structured.getSource().equals(RAW), "Undo restores both diagrams");
                    node("game-redo", Button.class).fire();
                    expect(source.getText().equals(formatted) && flowchart.getSource().equals(formatted)
                                    && structured.getSource().equals(formatted), "Redo restores the formatted source and diagrams");
                } finally { source.textProperty().removeListener(listener); }
            }));

            check("no-op formatting retains an existing redo branch", () -> onFx(() -> {
                source.setText(""); source.setText(formatted);
                source.insertText(0, "// Later note.\n"); source.undo();
                expect(source.isRedoable(), "fixture has an undone source edit");
                node("game-format", Button.class).fire();
                expect(source.getText().equals(formatted) && source.isRedoable() && !source.isUndoable(),
                        "no-op Format does not reset or extend native history");
                node("game-redo", Button.class).fire();
                expect(source.getText().equals("// Later note.\n" + formatted), "the earlier undone edit remains redoable");
            }));

            check("Format is disabled for empty, read-only, and currently checked sources", () -> {
                onFx(() -> {
                    source.setText("");
                    expect(node("game-format", Button.class).isDisabled(), "empty source cannot be formatted");
                    source.setText(RAW); source.setEditable(false);
                    Button format = node("game-format", Button.class);
                    expect(format.isDisabled(), "read-only source disables Format");
                    format.fire();
                    expect(source.getText().equals(RAW) && !source.isUndoable(), "disabled Format cannot mutate source");
                    source.setEditable(true);
                    expect(!format.isDisabled(), "editing availability restores Format");
                    node("check-solution", Button.class).fire();
                    expect(!source.isEditable() && format.isDisabled(), "a real solution check locks formatting");
                    format.fire();
                    expect(source.getText().equals(RAW), "checking cannot format the submitted snapshot");
                    node("cancel-check", Button.class).fire();
                });
                awaitEditable();
                onFx(() -> expect(!node("game-format", Button.class).isDisabled(), "finishing or cancelling a check restores Format"));
            });

            check("source actions fit the minimum window without overlapping the editor", () -> {
                onFx(() -> { stage.setWidth(1000); stage.setHeight(720); });
                pulse();
                onFx(() -> {
                    layout();
                    Bounds pane = source.getParent().localToScene(source.getParent().getBoundsInLocal());
                    // Focus halos extend outside controls without changing their layout or hit areas.
                    Bounds editorBounds = source.localToScene(source.getLayoutBounds());
                    Bounds previous = null;
                    for (String id : List.of("game-format", "game-undo", "game-redo")) {
                        Button button = node(id, Button.class);
                        Bounds bounds = button.localToScene(button.getLayoutBounds());
                        expect(bounds.getMinX() >= pane.getMinX() && bounds.getMaxX() <= pane.getMaxX() + .5,
                                id + " remains inside the source pane");
                        expect(bounds.getMaxY() <= editorBounds.getMinY() + .5, id + " stays above the editable text");
                        if (previous != null) expect(previous.getMaxX() <= bounds.getMinX(), "source action buttons do not overlap");
                        previous = bounds;
                    }
                });
            });

            check("the formatted shared draft persists without changing earned helpers", () -> onFx(() -> {
                source.setText(RAW); node("game-format", Button.class).fire();
                game.stop();
                GameProgress saved = new GameProgress(profile, GameCatalog.levels());
                expect(saved.draft(GameCatalog.levels().get(selectedIndex)).equals(formatted), "saved progress contains the formatted source");
                expect(toolbox.getText().equals(formattedHelpers), "formatting the source never edits earned definitions");
            }));
            System.out.println("GameFormattingTest: " + passed + " passed, 0 failed");
        } finally {
            onFx(() -> { if (game != null) game.stop(); if (stage != null) stage.close(); });
            Platform.exit();
            Locale.setDefault(Locale.Category.DISPLAY, previousLocale);
        }
    }

    private static void selectTab(String id) {
        TabPane tabs = node("game-work", TabPane.class);
        Tab selected = id == null ? tabs.getTabs().get(0)
                : tabs.getTabs().stream().filter(tab -> id.equals(tab.getId())).findFirst().orElseThrow();
        tabs.getSelectionModel().select(selected); layout();
    }
    @SuppressWarnings("unchecked") private static void mode(String value) {
        node("game-diagram-mode", ComboBox.class).setValue(value); layout();
    }
    private static Text token(SourceEditor editor, String kind) {
        Node layer = editor.lookup(".syntax-" + kind);
        expect(layer instanceof Text, "missing toolbox syntax layer " + kind); return (Text) layer;
    }
    private static <T extends Node> T node(String id, Class<T> type) {
        Node found = stage.getScene().lookup("#" + id);
        expect(type.isInstance(found), "missing " + id); return type.cast(found);
    }
    private static void layout() { stage.getScene().getRoot().applyCss(); stage.getScene().getRoot().layout(); }
    private static void pulse() throws Exception {
        CountDownLatch rendered = new CountDownLatch(1);
        onFx(() -> new AnimationTimer() {
            private int frames;
            @Override public void handle(long now) { if (++frames == 2) { stop(); rendered.countDown(); } }
        }.start());
        expect(rendered.await(10, TimeUnit.SECONDS), "JavaFX renders layout pulses");
    }
    private static void awaitEditable() throws Exception {
        AtomicReference<Boolean> editable = new AtomicReference<>(false);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!editable.get() && System.nanoTime() < deadline) {
            onFx(() -> editable.set(source.isEditable()));
            if (!editable.get()) Thread.sleep(20);
        }
        expect(editable.get(), "solution-check editing lock is released");
    }
    private static void check(String name, Action action) throws Exception {
        action.run(); ++passed; System.out.println("PASS " + name);
    }
    private static void onFx(Action action) throws Exception {
        FutureTask<Void> future = new FutureTask<>(() -> { action.run(); return null; });
        Platform.runLater(future); future.get(20, TimeUnit.SECONDS);
        Throwable failure = fxFailure.getAndSet(null);
        if (failure != null) throw new AssertionError("uncaught JavaFX exception", failure);
    }
    private static void close(double actual, double expected, String message) {
        expect(Math.abs(actual - expected) < .001, message + ": " + actual);
    }
    private static void expect(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}

package machine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;

/** Shared drafts, undo, structured-only flowcharts, and retained fields across diagram modes. */
public final class GameDiagramModesTest {
    private static final String NASSI = "Nassi–Shneiderman";
    private static final String FLOWCHART = "Flowchart";
    private static final String SOURCE = "// One source for both diagrams.\nvar n = 2;\nvar result = 0;\nbegin:\n"
            + "while (n != 0) {\n  DEC n;\n}\n"
            + "if (n == 0) {\n  INC result;\n} else {\n  DEC result;\n}\n"
            + "do {\n  NOP;\n} while (n != 0);\nHALT;\n";
    private static final String CHANGED = SOURCE.replace("DEC n;", "INC n;");
    private static LanguageGame game;
    private static Stage stage;
    private static int passed;
    private static final AtomicReference<Throwable> fxFailure = new AtomicReference<>();
    @FunctionalInterface private interface Action { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        Locale previousLocale = Locale.getDefault(Locale.Category.DISPLAY);
        Locale.setDefault(Locale.Category.DISPLAY, Locale.ENGLISH);
        Path profile = Files.createTempDirectory("game-diagram-modes-").resolve("progress.properties");
        GameProgress progress = new GameProgress(profile, GameCatalog.levels());
        for (int index = 0; index < 8; ++index) {
            GameLevel level = GameCatalog.levels().get(index);
            progress.complete(level, level.starter());
        }
        progress.setSelectedIndex(7);
        progress.save();
        CountDownLatch started = new CountDownLatch(1);
        Platform.startup(() -> {
            Thread.currentThread().setUncaughtExceptionHandler((thread, error) -> fxFailure.compareAndSet(null, error));
            Platform.setImplicitExit(false);
            started.countDown();
        });
        expect(started.await(10, TimeUnit.SECONDS), "JavaFX starts");
        try {
            onFx(() -> {
                game = new LanguageGame() { @Override Path progressPath() { return profile; } };
                stage = new Stage();
                game.start(stage);
                layout();
            });

            check("entering World 2 defaults to Nassi with both diagram choices", () -> {
                expect(flowchart().isSynchronized(), "World 1 still has its flowchart");
                expect(mode().getItems().equals(List.of(FLOWCHART)) && mode().isDisabled(),
                        "World 1 offers only its original flowchart");
                node("next-level", Button.class).fire();
                expect(mode().getItems().equals(List.of(NASSI, FLOWCHART)), "both diagram choices are offered");
                expect(mode().getValue().equals(NASSI), "Nassi is the initial structured-world default");
                expect(structured().isSynchronized(), "default diagram follows the working starter");
                expect(node("game-program-editors", SplitPane.class).getItems().size() == 2,
                        "source and one diagram share the editor area");
                editor().setText(SOURCE);
                expect(node("game-undo", Button.class).isDisabled() && node("game-redo", Button.class).isDisabled(),
                        "loading a fresh source has no undo or redo history");
                choose(FLOWCHART);
                expect(flowchart().isSynchronized() && flowchart().getSource().equals(SOURCE), "flowchart follows structured source");
                expect(flowchart().getGraph().vertices().stream().filter(v -> v.kind() == Flowchart.Kind.DECISION).count() == 3,
                        "if, while, and do/while each render their condition");
                expect(stage.getScene().lookup("#game-diagram") == null
                                && node("game-program-editors", SplitPane.class).getItems().size() == 2,
                        "switching replaces the diagram pane without adding a third editor");
                expect(!editor().isUndoable(), "changing representation does not create a source edit");
            });

            check("flowchart changes share the Undo and Redo buttons across diagram modes", () -> {
                commit(field(flowchart(), "DEC n;"), "INC n;");
                expect(editor().getText().equals(CHANGED), "flowchart commits one source change");
                expect(!node("game-undo", Button.class).isDisabled(), "committed diagram change enables Undo");
                choose(NASSI);
                expect(structured().getSource().equals(CHANGED) && field(structured(), "INC n;") != null,
                        "alternate diagram reflects the committed change");
                node("game-undo", Button.class).fire();
                expect(editor().getText().equals(SOURCE) && structured().getSource().equals(SOURCE),
                        "Undo restores the source and visible diagram");
                expect(!node("game-redo", Button.class).isDisabled(), "Undo enables Redo");
                choose(FLOWCHART);
                expect(flowchart().getSource().equals(SOURCE) && field(flowchart(), "DEC n;") != null,
                        "the other diagram also reflects Undo");
                node("game-redo", Button.class).fire();
                expect(editor().getText().equals(CHANGED) && flowchart().getSource().equals(CHANGED),
                        "Redo restores the diagram transaction");
                node("game-undo", Button.class).fire();
            });

            check("Nassi edits use the same history and source keyboard undo updates the flowchart", () -> {
                choose(NASSI);
                commit(field(structured(), "DEC n;"), "INC n;");
                expect(editor().getText().equals(CHANGED), "structured edit reaches the same source");
                choose(FLOWCHART);
                editor().requestFocus();
                editor().fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.Z, false, true, false, false));
                expect(editor().getText().equals(SOURCE) && flowchart().getSource().equals(SOURCE),
                        "Ctrl+Z in source reverses a committed Nassi edit and updates flowchart");
                choose(NASSI);
                expect(structured().getSource().equals(SOURCE), "Nassi also reflects keyboard undo");
                node("game-redo", Button.class).fire();
                expect(editor().getText().equals(CHANGED), "Redo restores the structured edit");
                node("game-undo", Button.class).fire();
            });

            check("mode switches retain uncommitted fields while source stays unchanged", () -> {
                TextInputControl nassiPending = field(structured(), "DEC n;");
                nassiPending.setText("INC n;");
                nassiPending.selectRange(5, 2);
                choose(FLOWCHART);
                TextInputControl flowPending = field(flowchart(), "INC result;");
                flowPending.setText("DEC n;");
                flowPending.selectRange(4, 1);
                choose(NASSI);
                expect(field(structured(), "INC n;") == nassiPending && nassiPending.getAnchor() == 5
                                && nassiPending.getCaretPosition() == 2,
                        "returning retains the original Nassi field and selection");
                choose(FLOWCHART);
                expect(flowchart().lookupAll(".flowchart-edit").contains(flowPending),
                        "returning retains the original pending flowchart control");
                expect(flowPending.getText().equals("DEC n;") && flowPending.getAnchor() == 4 && flowPending.getCaretPosition() == 1,
                        "pending flowchart text and selection survive switching");
                expect(editor().getText().equals(SOURCE), "switching neither applies nor discards pending edits");
                choose(NASSI);
                nassiPending.positionCaret(3);
                nassiPending.fireEvent(new ActionEvent());
                expect(editor().getText().equals(CHANGED), "retained Nassi edit still commits successfully");
                choose(FLOWCHART);
                expect(flowchart().getSource().equals(CHANGED) && field(flowchart(), "INC result;") != flowPending,
                        "a changed source replaces the other view's stale uncommitted fields");
                node("game-undo", Button.class).fire();
                expect(editor().getText().equals(SOURCE), "undo still reverses just the committed source change");
            });

            check("structured-world flowcharts reject explicit jumps in both editing directions", () -> {
                TextInputControl statement = field(flowchart(), "DEC n;");
                commit(statement, "JNZ n begin;");
                expect(editor().getText().equals(SOURCE), "a prohibited diagram edit cannot alter the source");
                expect(flowchart().getStatusText().contains("JNZ is suspended"), "diagram explains the world's jump restriction");
                // Discard the rejected field before testing an independent source edit.
                statement.setText("DEC n;");
                int offset = SOURCE.indexOf("DEC n;");
                editor().replaceText(offset, offset + "DEC n;".length(), "JNZ n begin;");
                expect(!flowchart().isSynchronized() && flowchart().getStatusText().contains("JNZ is suspended"),
                        "source-side jumps invalidate the later-world flowchart");
                choose(NASSI);
                expect(!structured().isSynchronized(), "both representations enforce the same world policy");
                node("game-undo", Button.class).fire();
                expect(editor().getText().equals(SOURCE) && structured().isSynchronized(), "Undo recovers the valid structured program");
                choose(FLOWCHART);
                expect(flowchart().isSynchronized(), "flowchart recovers from the same undo");
            });

            check("replay retains the later-world choice while resetting each level's diagram state", () -> {
                FlowchartView later = flowchart();
                TextInputControl pending = field(later, "DEC n;");
                pending.setText("INC n;");
                node("game-levels", ListView.class).getSelectionModel().select(7);
                expect(stage.getScene().lookup("#game-diagram") == null && flowchart().isSynchronized(),
                        "World 1 replay always displays its flowchart");
                expect(mode().getItems().equals(List.of(FLOWCHART)) && mode().isDisabled(),
                        "replay restores the single flowchart choice");
                String foundation = "var n = 1;\nbegin:\nagain: DEC n;\nJNZ n again;\nHALT;\n";
                editor().setText(foundation);
                expect(flowchart().isSynchronized(), "World 1 replay restores explicit jumps");
                node("game-levels", ListView.class).getSelectionModel().select(8);
                expect(mode().getValue().equals(FLOWCHART), "returning remembers the later-world diagram choice");
                expect(editor().getText().equals(SOURCE) && flowchart().getSource().equals(SOURCE) && flowchart().isSynchronized(),
                        "later-world flowchart reloads its own saved source");
                expect(field(flowchart(), "DEC n;") != pending && pending.getScene() == null,
                        "level changes discard the previous level's pending control cache");
                expect(!editor().isUndoable() && node("game-undo", Button.class).isDisabled(),
                        "loading another level clears the previous draft's undo history");
                int offset = SOURCE.indexOf("DEC n;");
                editor().replaceText(offset, offset + "DEC n;".length(), "JNZ n begin;");
                expect(!flowchart().isSynchronized(), "returning reapplies the structured-only validation rule");
                node("game-undo", Button.class).fire();
                game.stop();
                GameProgress saved = new GameProgress(profile, GameCatalog.levels());
                expect(saved.draft(GameCatalog.levels().get(8)).equals(SOURCE)
                                && saved.draft(GameCatalog.levels().get(7)).equals(foundation),
                        "both levels persist their separate source drafts without diagram files");
            });
            System.out.println("GameDiagramModesTest: " + passed + " passed, 0 failed");
        } finally {
            onFx(() -> { if (game != null) game.stop(); if (stage != null) stage.close(); });
            Platform.exit();
            Locale.setDefault(Locale.Category.DISPLAY, previousLocale);
        }
    }

    @SuppressWarnings("unchecked") private static ComboBox<String> mode() { return node("game-diagram-mode", ComboBox.class); }
    private static void choose(String value) { mode().setValue(value); layout(); }
    private static SourceEditor editor() { return node("game-editor", SourceEditor.class); }
    private static FlowchartView flowchart() { return node("game-flowchart", FlowchartView.class); }
    private static StructuredDiagramView structured() { return node("game-diagram", StructuredDiagramView.class); }
    private static TextInputControl field(Parent view, String text) {
        return view.lookupAll(view instanceof FlowchartView ? ".flowchart-edit" : ".diagram-edit").stream()
                .filter(TextInputControl.class::isInstance).map(TextInputControl.class::cast)
                .filter(control -> control.getText().equals(text)).findFirst()
                .orElseThrow(() -> new AssertionError("missing diagram field " + text));
    }
    private static void commit(TextInputControl field, String value) {
        field.setText(value); field.positionCaret(3); field.fireEvent(new ActionEvent());
    }
    private static <T extends Node> T node(String id, Class<T> type) {
        Node found = stage.getScene().lookup("#" + id);
        expect(type.isInstance(found), "missing " + id); return type.cast(found);
    }
    private static void layout() { stage.getScene().getRoot().applyCss(); stage.getScene().getRoot().layout(); }
    private static void check(String name, Action action) throws Exception {
        onFx(action); ++passed; System.out.println("PASS " + name);
    }
    private static void onFx(Action action) throws Exception {
        FutureTask<Void> future = new FutureTask<>(() -> { action.run(); return null; });
        Platform.runLater(future); future.get(20, TimeUnit.SECONDS);
        Throwable failure = fxFailure.getAndSet(null);
        if (failure != null) throw new AssertionError("uncaught JavaFX exception", failure);
    }
    private static void expect(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}

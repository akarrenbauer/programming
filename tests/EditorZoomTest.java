package machine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.IndexRange;
import javafx.scene.control.ListView;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import javafx.stage.Window;

/** App-level keyboard zoom, preserving source editing and synchronized diagram state. */
public final class EditorZoomTest {
    private static final String SOURCE = "// Keep the source and its undo history.\nvar n = 2;\nbegin:\n"
            + "while (n != 0) {\n  DEC n;\n}\nHALT;\n";
    private static Stage stage;
    private static MachineGUI machine;
    private static LanguageGame game;
    private static TabPane sourceTabs;
    private static Tab mainTab;
    private static SourceEditor mainEditor;
    private static int passed;
    private static final AtomicReference<Throwable> fxFailure = new AtomicReference<>();
    @FunctionalInterface private interface Action { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.Category.DISPLAY, Locale.ENGLISH);
        Path directory = Files.createTempDirectory("editor-zoom-");
        Path main = directory.resolve("main.a26");
        Files.writeString(main, SOURCE);
        Path profile = directory.resolve("progress.properties");
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
                machine = new MachineGUI(); stage = new Stage(); machine.start(stage);
                stage.setWidth(1500); stage.setHeight(1000); stage.setX(0); stage.setY(0);
                machine.openSourceFile(main);
                sourceTabs = node("source-tabs", TabPane.class);
                mainTab = sourceTabs.getTabs().stream().filter(tab -> tab.getUserData() instanceof SourceWorkspace.Document document
                        && main.equals(document.getPath())).findFirst().orElseThrow();
                sourceTabs.getSelectionModel().select(mainTab);
                mainEditor = (SourceEditor) mainTab.getContent();
                mainEditor.selectRange(18, 5); mainEditor.requestFocus(); layout();
            });
            pulse();

            check("simulator shortcuts cover main and numeric keys without changing source selection or focus", () -> {
                AtomicReference<Double> font = new AtomicReference<>();
                onFx(() -> {
                    expect(node("source-diagram-mode", ComboBox.class).getValue().equals("Source only"), "starts source only");
                    close(mainEditor.getZoomFactor(), 1, "new simulator session starts at 100%");
                    font.set(mainEditor.getFont().getSize());
                    expect(mainEditor.isFocused(), "source owns focus before zoom");
                    key(mainEditor, KeyCode.PLUS, "+");
                    key(mainEditor, KeyCode.EQUALS, "=");
                    key(mainEditor, KeyCode.ADD, "+");
                    close(mainEditor.getZoomFactor(), 1.3, "three plus variants each zoom once");
                    key(mainEditor, KeyCode.MINUS, "-");
                    key(mainEditor, KeyCode.SUBTRACT, "-");
                    close(mainEditor.getZoomFactor(), 1.1, "both minus variants zoom out");
                    repeat(mainEditor, KeyCode.PLUS, 4);
                    expect(mainEditor.getText().equals(SOURCE), "shortcut glyphs never enter source");
                    expect(mainEditor.getAnchor() == 18 && mainEditor.getCaretPosition() == 5, "reversed source selection survives");
                });
                pulse();
                onFx(() -> {
                    close(mainEditor.getFont().getSize(), font.get() * 1.5, "source font scales visibly");
                    expect(stage.getScene().getFocusOwner() == mainEditor, "zoom keeps source focus");
                    expect(mainEditor.getAnchor() == 18 && mainEditor.getCaretPosition() == 5, "layout retains source selection");
                    mainEditor.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.PLUS, false, true, true, false));
                    close(mainEditor.getZoomFactor(), 1.5, "AltGr-style Ctrl+Alt is not a zoom shortcut");
                });
            });

            check("zoom leaves one native source edit as one undo operation", () -> onFx(() -> {
                int offset = SOURCE.indexOf("DEC n;");
                mainEditor.replaceText(offset, offset + 6, "INC n;");
                key(mainEditor, KeyCode.ADD, "+");
                key(mainEditor, KeyCode.SUBTRACT, "-");
                mainEditor.undo();
                expect(mainEditor.getText().equals(SOURCE), "undo reverses only the source edit");
                expect(!((SourceWorkspace.Document) mainTab.getUserData()).isModified(), "zoom never dirties the source buffer");
                close(mainEditor.getZoomFactor(), 1.5, "undo does not undo presentation zoom");
            }));

            check("flowchart zoom preserves pending controls and source linkage through commit and undo", () -> {
                AtomicReference<TextInputControl> pending = new AtomicReference<>();
                onFx(() -> {
                    mode("Flowchart");
                    close(flowchart().getZoomFactor(), 1.5, "newly cached flowchart inherits session zoom");
                    TextInputControl field = field(flowchart(), "DEC n;"); pending.set(field);
                    mainEditor.selectRange(18, 5); field.setText("INC n;"); field.selectRange(5, 2); field.requestFocus();
                });
                pulse();
                onFx(() -> {
                    TextInputControl field = pending.get();
                    field.selectRange(5, 2);
                    expect(field.isFocused(), "flowchart field owns focus before zoom");
                    IndexRange linked = mainEditor.getLinkedRange();
                    expect(linked != null && linked.getStart() <= SOURCE.indexOf("DEC n;")
                            && linked.getEnd() >= SOURCE.indexOf("DEC n;") + 6, "zoomed diagram focus maps to source offsets");
                    key(field, KeyCode.EQUALS, "+");
                    close(flowchart().getZoomFactor(), 1.6, "shortcut in a diagram field zooms the flowchart");
                    close(mainEditor.getZoomFactor(), 1.6, "same shortcut scales the source");
                    expect(field.getScene() == stage.getScene() && field.getText().equals("INC n;"), "pending field survives without extra glyphs");
                    expect(field.getAnchor() == 5 && field.getCaretPosition() == 2, "diagram selection survives");
                    expect(mainEditor.getText().equals(SOURCE), "zoom does not apply pending diagram edits");
                    expect(mainEditor.getAnchor() == 18 && mainEditor.getCaretPosition() == 5, "source selection remains untouched");
                    field.positionCaret(2); field.fireEvent(new ActionEvent());
                    expect(mainEditor.getText().equals(SOURCE.replace("DEC n;", "INC n;")), "diagram commit remains one source-span edit");
                    mainEditor.undo();
                    expect(mainEditor.getText().equals(SOURCE) && flowchart().getSource().equals(SOURCE), "undo updates the zoomed diagram");
                    close(flowchart().getZoomFactor(), 1.6, "diagram rebuild retains zoom");
                });
            });

            check("structured diagrams inherit zoom and keep focused drafts when the shortcut changes it", () -> {
                AtomicReference<TextInputControl> pending = new AtomicReference<>();
                onFx(() -> {
                    mode("Nassi");
                    close(structured().getZoomFactor(), 1.6, "new structured diagram inherits zoom");
                    TextInputControl field = field(structured(), "DEC n;"); pending.set(field);
                    mainEditor.selectRange(18, 5); field.setText("INC n;"); field.selectRange(4, 1); field.requestFocus();
                });
                pulse();
                onFx(() -> {
                    TextInputControl field = pending.get();
                    field.selectRange(4, 1);
                    expect(field.isFocused(), "structured field owns focus before zoom");
                    expect(mainEditor.getLinkedRange() != null, "structured field links to source at enlarged scale");
                    key(field, KeyCode.SUBTRACT, "-");
                    close(structured().getZoomFactor(), 1.5, "minus in structured field updates session scale");
                    expect(field.getScene() == stage.getScene() && field.getText().equals("INC n;"), "structured draft and control identity remain");
                    expect(field.getAnchor() == 4 && field.getCaretPosition() == 1, "structured selection remains");
                    expect(mainEditor.getText().equals(SOURCE), "shortcut does not commit structured draft");
                    field.positionCaret(2); field.fireEvent(new ActionEvent()); mainEditor.undo();
                    expect(mainEditor.getText().equals(SOURCE) && structured().getSource().equals(SOURCE), "structured commit remains undoable");
                    mainEditor.requestFocus(); mainEditor.positionCaret(SOURCE.indexOf("DEC n;") + 2);
                    expect(structured().getHighlightedRange() != null, "source caret still highlights the structured element");
                });
            });

            check("zoom clamps consistently across cached views and new simulator source tabs", () -> onFx(() -> {
                repeat(mainEditor, KeyCode.ADD, 30);
                close(mainEditor.getZoomFactor(), 3, "zoom stops at 300%");
                close(structured().getZoomFactor(), 3, "structured view shares maximum");
                mode("Flowchart"); close(flowchart().getZoomFactor(), 3, "hidden cached flowchart shares maximum");
                repeat(mainEditor, KeyCode.SUBTRACT, 40);
                close(mainEditor.getZoomFactor(), .5, "zoom stops at 50%");
                close(flowchart().getZoomFactor(), .5, "flowchart shares minimum");
                repeat(mainEditor, KeyCode.ADD, 10);
                node("new-source", Button.class).fire();
                SourceEditor fresh = (SourceEditor) sourceTabs.getSelectionModel().getSelectedItem().getContent();
                close(fresh.getZoomFactor(), 1.5, "new source editor inherits current zoom");
                fresh.setText("var x = 0;\nbegin:\nINC x;\nHALT;\n");
                expect(flowchart().isSynchronized(), flowchart().getStatusText());
                close(flowchart().getZoomFactor(), 1.5, "new document's flowchart inherits current zoom");
                mode("Nassi"); close(structured().getZoomFactor(), 1.5, "new document's structured view inherits current zoom");
                sourceTabs.getSelectionModel().select(mainTab);
                expect(structured().getSource().equals(SOURCE), "returning restores the original cached document");
                close(structured().getZoomFactor(), 1.5, "tab selection preserves session zoom");
                mode("Source only");
                key(mainEditor, KeyCode.MINUS, "-");
                close(mainEditor.getZoomFactor(), 1.4, "source-only mode still handles shortcuts");
                expect(mainEditor.getText().equals(SOURCE), "clamping and mode changes never modify source");
            }));

            onFx(() -> {
                machine.stop(); machine = null; stage.close();
                game = new LanguageGame() { @Override Path progressPath() { return profile; } };
                stage = new Stage(); game.start(stage);
                stage.setWidth(1500); stage.setHeight(1000); stage.setX(0); stage.setY(0);
                gameEditor().setText(SOURCE); gameEditor().selectRange(18, 5); gameEditor().requestFocus(); layout();
            });
            pulse();

            check("LanguageGame has an independent zoom session shared by source and flowchart", () -> onFx(() -> {
                close(gameEditor().getZoomFactor(), 1, "new game session is independent of simulator scale");
                repeat(gameEditor(), KeyCode.EQUALS, 5);
                close(gameEditor().getZoomFactor(), 1.5, "game handles Ctrl+=");
                close(gameFlowchart().getZoomFactor(), 1.5, "game flowchart shares source zoom");
                expect(gameEditor().getText().equals(SOURCE), "game shortcuts do not enter glyphs or alter the saved draft");
                expect(gameEditor().getAnchor() == 18 && gameEditor().getCaretPosition() == 5, "game source selection survives zoom");
                TextInputControl statement = field(gameFlowchart(), "DEC n;");
                statement.setText("INC n;"); statement.positionCaret(2);
                key(statement, KeyCode.ADD, "+");
                expect(statement.getText().equals("INC n;") && gameEditor().getText().equals(SOURCE), "game field keeps pending source edit");
                statement.fireEvent(new ActionEvent()); gameEditor().undo();
                expect(gameEditor().getText().equals(SOURCE) && gameFlowchart().getSource().equals(SOURCE), "game zoomed commit has native undo");
                close(gameFlowchart().getZoomFactor(), 1.6, "game flowchart rerender keeps zoom");
            }));

            check("world transitions and replay reset diagram context while retaining shared zoom", () -> onFx(() -> {
                node("next-level", Button.class).fire();
                StructuredDiagramView view = node("game-diagram", StructuredDiagramView.class);
                close(view.getZoomFactor(), 1.6, "entering the structured world retains shared zoom");
                expect(view.isSynchronized(), view.getStatusText());
                gameEditor().setText(SOURCE);
                expect(view.isSynchronized(), view.getStatusText());
                layout();
                TextInputControl field = field(view, "DEC n;"); field.setText("INC n;"); field.positionCaret(2);
                key(field, KeyCode.SUBTRACT, "-");
                close(view.getZoomFactor(), 1.5, "game structured fields handle zoom shortcuts");
                expect(field.getText().equals("INC n;") && gameEditor().getText().equals(SOURCE), "structured shortcut preserves pending draft");
                field.fireEvent(new ActionEvent()); gameEditor().undo();
                expect(gameEditor().getText().equals(SOURCE), "structured world keeps native undo at enlarged scale");
                node("game-levels", ListView.class).getSelectionModel().select(0);
                close(gameFlowchart().getZoomFactor(), 1.5, "replaying foundations retains scale after context reset");
                expect(gameFlowchart().isSynchronized(), gameFlowchart().getStatusText());
                ComboBox<?> worlds = node("game-worlds", ComboBox.class);
                worlds.getSelectionModel().select(1);
                close(node("game-diagram", StructuredDiagramView.class).getZoomFactor(), 1.5, "returning to structured diagrams retains scale");
                expect(gameEditor().getText().equals(SOURCE), "returning restores the unmodified structured draft");
            }));
            System.out.println("EditorZoomTest: " + passed + " passed, 0 failed");
        } finally {
            try {
                onFx(() -> {
                    if (machine != null) machine.stop();
                    if (game != null) game.stop();
                    for (Window window : new ArrayList<>(Window.getWindows())) window.hide();
                });
            } finally { Platform.exit(); }
        }
    }

    private static void key(Node target, KeyCode code, String glyph) {
        target.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, code == KeyCode.PLUS, true, false, false));
        target.fireEvent(new KeyEvent(KeyEvent.KEY_TYPED, glyph, "", KeyCode.UNDEFINED, code == KeyCode.PLUS, true, false, false));
        target.fireEvent(new KeyEvent(KeyEvent.KEY_RELEASED, "", "", code, code == KeyCode.PLUS, true, false, false));
    }
    private static void repeat(Node target, KeyCode code, int count) {
        for (int index = 0; index < count; ++index) key(target, code, code == KeyCode.MINUS || code == KeyCode.SUBTRACT ? "-" : "+");
    }
    private static FlowchartView flowchart() { return node("machine-flowchart", FlowchartView.class); }
    private static StructuredDiagramView structured() { return node("machine-structured-diagram", StructuredDiagramView.class); }
    private static SourceEditor gameEditor() { return node("game-editor", SourceEditor.class); }
    private static FlowchartView gameFlowchart() { return node("game-flowchart", FlowchartView.class); }
    private static TextInputControl field(Parent view, String text) {
        return view.lookupAll(view instanceof FlowchartView ? ".flowchart-edit" : ".diagram-edit").stream()
                .filter(TextInputControl.class::isInstance).map(TextInputControl.class::cast)
                .filter(field -> field.getText().equals(text)).findFirst()
                .orElseThrow(() -> new AssertionError("missing diagram field " + text));
    }
    private static void mode(String prefix) {
        ComboBox<?> choices = node("source-diagram-mode", ComboBox.class);
        for (int index = 0; index < choices.getItems().size(); ++index) {
            if (choices.getItems().get(index).toString().startsWith(prefix)) {
                choices.getSelectionModel().select(index); layout(); return;
            }
        }
        throw new AssertionError("missing diagram mode " + prefix);
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
        expect(rendered.await(10, TimeUnit.SECONDS), "JavaFX renders layout pulses"); onFx(EditorZoomTest::layout);
    }
    private static void check(String name, Action action) throws Exception { action.run(); ++passed; System.out.println("PASS " + name); }
    private static void onFx(Action action) throws Exception {
        FutureTask<Void> future = new FutureTask<>(() -> { action.run(); return null; });
        Platform.runLater(future); future.get(20, TimeUnit.SECONDS);
        Throwable failure = fxFailure.getAndSet(null);
        if (failure != null) throw new AssertionError("uncaught JavaFX exception", failure);
    }
    private static void close(double actual, double expected, String message) {
        expect(Math.abs(actual - expected) < .001, message + ": expected " + expected + ", got " + actual);
    }
    private static void expect(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}

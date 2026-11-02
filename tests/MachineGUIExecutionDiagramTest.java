package machine;

import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;

import javafx.animation.AnimationTimer;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.CheckBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TablePosition;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.IndexRange;
import javafx.scene.control.Label;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

/** Real simulator controls and decorative execution/value overlays; no native file chooser. */
public final class MachineGUIExecutionDiagramTest {
    private static final String SOURCE = "var x = 5;\n\nbegin:\n\tDEC x;\n\tJNZ x begin;\nHALT;\n";
    private static MachineGUI application;
    private static Stage stage;
    private static Machine machine;
    private static SourceEditor editor;
    private static Path sourcePath;
    private static int passed;
    private static final AtomicReference<Throwable> fxFailure = new AtomicReference<>();
    @FunctionalInterface private interface Action { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        sourcePath = Files.createTempDirectory("machine-execution-diagram-").resolve("loop.a26");
        Files.writeString(sourcePath, SOURCE);
        CountDownLatch started = new CountDownLatch(1);
        Platform.startup(() -> {
            Thread.currentThread().setUncaughtExceptionHandler((thread, error) -> fxFailure.compareAndSet(null, error));
            Platform.setImplicitExit(false); started.countDown();
        });
        require(started.await(10, TimeUnit.SECONDS), "JavaFX starts");
        try {
            onFx(() -> {
                application = new MachineGUI(); stage = new Stage(); application.start(stage);
                stage.setX(0); stage.setY(0); stage.setWidth(1500); stage.setHeight(980);
                application.openSourceFile(sourcePath);
                machine = (Machine) field("machine");
                editor = (SourceEditor) sourceTab().getContent();
                mode("Flowchart"); layout();
            });
            pulse();

            check("fresh compilation marks begin, its outgoing edge, the first command and current values", () -> onFx(() -> {
                require(view().isSynchronized(), view().getStatusText());
                expectInitial(); expectValues(5);
                require(editor.getText().equals(SOURCE) && !document().isModified() && !editor.isUndoable(),
                        "initial execution decorations neither edit source nor create undo history");
                require(Files.readString(sourcePath).equals(SOURCE), "runtime annotations leave the source file intact");
                if (args.length > 0) snapshot(Path.of(args[0] + ".initial.png"));
            }));

            check("Step follows the real DEC and taken JNZ transitions with refreshed values", () -> onFx(() -> {
                button("Step").fire(); layout();
                expectNext("JNZ x begin;"); expectTransition("DEC x;", "JNZ x begin;", ""); expectValues(4);
                require(view().lookupAll(".flowchart-execution-start").isEmpty(), "begin is no longer marked after execution starts");
                button("Step").fire(); layout();
                expectNext("DEC x;"); expectTransition("JNZ x begin;", "DEC x;", "true"); expectValues(4);
                require(machine.getStepCounter() == 2 && editor.getText().equals(SOURCE) && !editor.isUndoable(),
                        "two instruction steps preserve the exact source and undo history");
            }));

            check("runtime updates preserve pending diagram fields and source focus decorations", () -> {
                AtomicReference<TextInputControl> pending = new AtomicReference<>();
                onFx(() -> {
                    editor.selectRange(7, 2);
                    pending.set(diagramField("DEC x;")); pending.get().setText("INC x;"); pending.get().requestFocus(); layout();
                });
                pulse();
                onFx(() -> {
                    require(editor.getLinkedRange() != null && editor.getExecutionRange() != null,
                            "linked-source and next-instruction markers coexist");
                    IndexRange linked = editor.getLinkedRange();
                    button("Step").fire(); layout();
                    expectNext("JNZ x begin;"); expectValues(3);
                    require(diagramField("INC x;") == pending.get() && editor.getText().equals(SOURCE),
                            "refreshing runtime values preserves the pending control without committing it");
                    require(editor.getAnchor() == 7 && editor.getCaretPosition() == 2,
                            "execution and value refresh preserve source selection direction");
                    require(linked.equals(editor.getLinkedRange()), "runtime refresh retains independent source-focus decoration");
                    require(stage.getScene().getFocusOwner() == pending.get(), "runtime refresh does not steal diagram focus");
                    pending.get().setText("DEC x;");
                });
            });

            check("Step n retains its actual final transition and Run finishes on the false edge to HALT", () -> {
                onFx(() -> {
                    recompile(); expectInitial();
                    stepMany(2);
                    require(machine.getStepCounter() == 2, "Step n executes the requested instruction count");
                    expectNext("DEC x;"); expectTransition("JNZ x begin;", "DEC x;", "true"); expectValues(4);
                    button("Run").fire();
                });
                awaitRunFinished();
                onFx(() -> {
                    require(machine.isHalted() && machine.get(1) == 0, "Run reaches HALT with x equal to zero");
                    expectNext("HALT;"); expectTransition("JNZ x begin;", "HALT;", "false"); expectValues(0);
                    require(!view().lookupAll(".flowchart-execution-halted").isEmpty(), "HALT has a distinct execution decoration");
                    Set<Flowchart.Edge> before = markedEdges();
                    button("Step").fire(); layout();
                    require(before.equals(markedEdges()), "Step at HALT retains the last real transition");
                    require(editor.getText().equals(SOURCE) && !document().isModified() && !editor.isUndoable(),
                            "Run and HALT decorations never alter source or undo history");
                    if (args.length > 0) snapshot(Path.of(args[0] + ".halted.png"));
                });
            });

            check("manual data edits refresh values without changing history and manual IP clears the old edge", () -> onFx(() -> {
                recompile(); button("Step").fire(); button("Step").fire(); layout();
                expectTransition("JNZ x begin;", "DEC x;", "true");
                editMemory(1, 0); expectValues(0);
                expectTransition("JNZ x begin;", "DEC x;", "true");
                expectNext("DEC x;");
                CompilationResult compiled = (CompilationResult) field("compiledProgram");
                int halt = compiled.sourceMap().entrySet().stream()
                        .filter(entry -> entry.getValue().location().startOffset() == SOURCE.indexOf("HALT;"))
                        .mapToInt(java.util.Map.Entry::getKey).findFirst().orElseThrow();
                editMemory(0, halt);
                expectNext("HALT;"); require(markedEdges().isEmpty(), "manual IP movement does not invent a last transition");
                require(view().lookupAll(".flowchart-execution-start").isEmpty(), "manual IP movement does not falsely mark begin");
                expectValues(0);
            }));

            check("source edits and undo clear stale runtime details until successful compilation", () -> onFx(() -> {
                recompile(); button("Step").fire(); layout(); expectValues(4);
                editor.appendText("// retained source edit\n"); layout();
                expectCleared();
                editor.undo(); layout();
                require(editor.getText().equals(SOURCE), "native undo restores source independently of runtime overlays");
                expectCleared();
                recompile(); expectInitial(); expectValues(5);
            }));

            check("Reset Reload and raw memory replacement clear execution and values", () -> onFx(() -> {
                button("Reset").fire(); layout(); expectCleared();
                recompile(); expectInitial(); expectValues(5);
                button("Reload").fire(); layout(); expectCleared();
                recompile();
                machine.load(List.of(1, 0)); refresh(); expectCleared();
                recompile(); expectInitial(); expectValues(5);
            }));

            check("changing compiled instruction bytes clears runtime decorations", () -> onFx(() -> {
                int instruction = machine.getInstructionPointer();
                editMemory(instruction, 0); expectCleared();
                recompile(); expectInitial(); expectValues(5);
            }));

            check("current-value captions appear only in the flowchart and refresh after browsing other modes", () -> onFx(() -> {
                mode("Source only"); button("Step").fire(); layout();
                require(editor.getText().equals(SOURCE) && editor.lookupAll(".source-runtime-value").isEmpty(),
                        "source-only mode contains the unchanged program without runtime captions");
                mode("Flowchart"); expectValues(4); expectNext("JNZ x begin;");
                String structured = "var x=5;\nbegin:\nwhile (x != 0) { DEC x; }\nHALT;\n";
                editor.setText(structured); recompile(); mode("Nassi");
                StructuredDiagramView diagram = node("machine-structured-diagram", StructuredDiagramView.class);
                require(diagram.lookupAll(".diagram-runtime-value").isEmpty(), "current-value captions are limited to the flowchart");
                require(editor.getText().equals(structured), "browsing structured diagrams never inserts runtime source comments");
                editor.setText(SOURCE); mode("Flowchart"); recompile();
            }));

            check("an unnamed compiled main has runtime overlays without leaking to an identical unnamed draft", () -> onFx(() -> {
                TabPane tabs = node("source-tabs", TabPane.class);
                node("new-source", Button.class).fire();
                Tab main = tabs.getSelectionModel().getSelectedItem();
                SourceEditor mainEditor = (SourceEditor) main.getContent(); mainEditor.setText(SOURCE);
                node("new-source", Button.class).fire();
                Tab other = tabs.getSelectionModel().getSelectedItem();
                SourceEditor otherEditor = (SourceEditor) other.getContent(); otherEditor.setText(SOURCE);
                tabs.getSelectionModel().select(main); layout();
                Node header = tabs.lookupAll(".tab").stream()
                        .filter(candidate -> candidate.lookup(".tab-label") instanceof Label label && label.getText().equals(main.getText()))
                        .findFirst().orElseThrow();
                Event.fireEvent(header, new MouseEvent(MouseEvent.MOUSE_CLICKED, 1, 1, 1, 1, MouseButton.PRIMARY, 2,
                        false, false, false, false, true, false, false, false, false, true, null));
                mode("Flowchart"); expectInitial(); expectValues(5);
                require(((SourceWorkspace.Document) main.getUserData()).getPath() == null && mainEditor.getExecutionRange() != null,
                        "an unnamed source compiles and tracks execution without an invented file path");
                button("Step").fire(); layout(); expectNext("JNZ x begin;"); expectValues(4);
                node("follow-execution", CheckBox.class).setSelected(false);
                tabs.getSelectionModel().select(other); layout(); expectCleared();
                require(otherEditor.getExecutionRange() == null, "another unnamed document never receives the main's source marker");
                tabs.getSelectionModel().select(main); layout(); expectNext("JNZ x begin;"); expectValues(4);
                require(mainEditor.getText().equals(SOURCE) && otherEditor.getText().equals(SOURCE), "runtime overlays preserve both unnamed buffers");
            }));

            check("JavaFX reports no asynchronous execution overlay failures", () -> onFx(() -> { }));
            System.out.println("MachineGUIExecutionDiagramTest: " + passed + " passed, 0 failed");
        } finally {
            try {
                onFx(() -> {
                    if (application != null) application.stop();
                    for (Window window : new ArrayList<>(Window.getWindows())) window.hide();
                });
            } finally { Platform.exit(); }
        }
    }

    private static void recompile() { node("compile", Button.class).fire(); layout(); }
    private static void expectInitial() {
        require(machine.getStepCounter() == 0, "fresh compilation resets executed steps");
        require(!view().lookupAll(".flowchart-execution-start").isEmpty(), "fresh state decorates begin");
        expectNext("DEC x;"); expectTransition("begin", "DEC x;", "");
    }
    private static void expectNext(String text) {
        require(view().lookupAll(".flowchart-execution-next").stream().map(Node::getUserData)
                        .filter(Flowchart.Vertex.class::isInstance).map(Flowchart.Vertex.class::cast)
                        .anyMatch(vertex -> vertexText(vertex).equals(text)), "next-command decoration points to " + text);
    }
    private static Set<Flowchart.Edge> markedEdges() {
        return view().lookupAll(".flowchart-execution-edge").stream().map(Node::getUserData)
                .filter(Flowchart.Edge.class::isInstance).map(Flowchart.Edge.class::cast).collect(java.util.stream.Collectors.toSet());
    }
    private static void expectTransition(String from, String to, String label) {
        try {
            Flowchart graph = Flowchart.from(StructuredDiagram.parse(editor.getText(), LanguageVersion.STRUCTURED, true));
            Flowchart.Edge expected = graph.edges().stream().filter(edge -> vertexText(graph.vertex(edge.from())).equals(from)
                    && vertexText(graph.vertex(edge.to())).equals(to) && edge.label().equals(label)).findFirst().orElseThrow();
            require(markedEdges().equals(Set.of(expected)), "last visible transition is " + from + " -> " + to + " (" + label + ")");
        } catch (StructuredDiagram.ParseException error) { throw new AssertionError(error); }
    }
    private static String vertexText(Flowchart.Vertex vertex) {
        return vertex.owner() == null ? vertex.text() : vertex.owner().text().strip();
    }
    private static void expectValues(int value) {
        String expected = Integer.toString(value);
        require(view().getRuntimeValues().containsValue(expected), "flowchart runtime values contain " + expected + ": " + view().getRuntimeValues());
        layout();
        require(view().lookupAll(".flowchart-runtime-value").stream().filter(Label.class::isInstance).map(Label.class::cast)
                        .anyMatch(label -> label.getText().strip().equals("// now: " + expected)), "flowchart renders the current-value caption");
    }
    private static void expectCleared() {
        require(view().lookupAll(".flowchart-execution-start").isEmpty()
                        && view().lookupAll(".flowchart-execution-next").isEmpty() && markedEdges().isEmpty(),
                "invalidated tracking clears every diagram execution decoration");
        require(view().getRuntimeValues().isEmpty(), "invalidated tracking clears current-value annotations");
    }
    private static void mode(String value) {
        ComboBox<?> choices = node("source-diagram-mode", ComboBox.class);
        for (int i = 0; i < choices.getItems().size(); ++i) {
            if (choices.getItems().get(i).toString().startsWith(value)) { choices.getSelectionModel().select(i); layout(); return; }
        }
        throw new AssertionError("missing diagram mode " + value);
    }
    private static FlowchartView view() { return node("machine-flowchart", FlowchartView.class); }
    private static Tab sourceTab() {
        return node("source-tabs", TabPane.class).getTabs().stream()
                .filter(tab -> tab.getUserData() instanceof SourceWorkspace.Document document && sourcePath.equals(document.getPath()))
                .findFirst().orElseThrow();
    }
    private static SourceWorkspace.Document document() { return (SourceWorkspace.Document) sourceTab().getUserData(); }
    private static TextInputControl diagramField(String text) {
        return view().lookupAll(".flowchart-edit").stream().filter(TextInputControl.class::isInstance)
                .map(TextInputControl.class::cast).filter(field -> field.getText().equals(text)).findFirst().orElseThrow();
    }
    private static Button button(String label) {
        return stage.getScene().getRoot().lookupAll(".button").stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(button -> button.getText().equals(label)).findFirst().orElseThrow();
    }
    private static <T extends Node> T node(String id, Class<T> type) {
        Node node = stage.getScene().lookup("#" + id);
        require(type.isInstance(node), "missing " + id); return type.cast(node);
    }
    private static Object field(String name) throws Exception {
        Field field = MachineGUI.class.getDeclaredField(name); field.setAccessible(true); return field.get(application);
    }
    @SuppressWarnings("unchecked") private static void editMemory(int address, int value) throws Exception {
        TableView<MachineGUI.MemoryEntry> table = (TableView<MachineGUI.MemoryEntry>) field("memoryTable");
        TableColumn<MachineGUI.MemoryEntry, Integer> column = (TableColumn<MachineGUI.MemoryEntry, Integer>) table.getColumns().get(1);
        column.getOnEditCommit().handle(new TableColumn.CellEditEvent<>(table,
                new TablePosition<>(table, address, column), TableColumn.editCommitEvent(), value));
        layout();
    }
    private static void refresh() throws Exception {
        Method refresh = MachineGUI.class.getDeclaredMethod("updateMemoryDisplay"); refresh.setAccessible(true); refresh.invoke(application); layout();
    }
    private static void stepMany(int count) {
        try (StepAnswer answer = new StepAnswer(count)) {
            button("Step n").fire(); require(answer.responses == 1, "Step n accepts one count through its dialog");
        }
        layout();
    }
    private static void awaitRunFinished() throws Exception {
        CountDownLatch finished = new CountDownLatch(1);
        onFx(() -> new AnimationTimer() {
            @Override public void handle(long now) {
                if (!button("Run").isDisabled()) { stop(); finished.countDown(); }
            }
        }.start());
        require(finished.await(10, TimeUnit.SECONDS), "finite Run finishes"); pulse();
    }
    private static void layout() { stage.getScene().getRoot().applyCss(); stage.getScene().getRoot().layout(); }
    private static void snapshot(Path path) throws Exception {
        layout(); WritableImage shot = stage.getScene().snapshot(null);
        BufferedImage image = new BufferedImage((int) shot.getWidth(), (int) shot.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.getHeight(); ++y) for (int x = 0; x < image.getWidth(); ++x) image.setRGB(x, y, shot.getPixelReader().getArgb(x, y));
        require(ImageIO.write(image, "png", path.toFile()), "PNG writer is available");
    }
    private static void pulse() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        onFx(() -> new AnimationTimer() {
            private int frames;
            @Override public void handle(long now) { if (++frames == 2) { stop(); ready.countDown(); } }
        }.start());
        require(ready.await(10, TimeUnit.SECONDS), "JavaFX layout pulses arrive"); onFx(MachineGUIExecutionDiagramTest::layout);
    }
    private static void onFx(Action action) throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> { action.run(); return null; }); Platform.runLater(task); task.get(15, TimeUnit.SECONDS);
        Throwable failure = fxFailure.getAndSet(null); if (failure != null) throw new AssertionError("uncaught JavaFX failure", failure);
    }
    private static void check(String name, Action action) throws Exception { action.run(); ++passed; System.out.println("PASS " + name); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

    private static final class StepAnswer implements AutoCloseable {
        private final Timeline timer;
        private int responses;
        StepAnswer(int count) {
            timer = new Timeline(new KeyFrame(Duration.millis(20), event -> {
                for (Window window : new ArrayList<>(Window.getWindows())) {
                    if (window.getScene() == null || !(window.getScene().getRoot() instanceof DialogPane dialog)) continue;
                    TextField input = (TextField) dialog.lookup(".text-field");
                    if (input == null) continue;
                    Button ok = dialog.getButtonTypes().stream().map(dialog::lookupButton).filter(Button.class::isInstance)
                            .map(Button.class::cast).filter(button -> button.getText().equals("OK")).findFirst().orElse(null);
                    if (ok != null) { input.setText(Integer.toString(count)); ++responses; ok.fire(); return; }
                }
            }));
            timer.setCycleCount(Timeline.INDEFINITE); timer.play();
        }
        @Override public void close() { timer.stop(); }
    }
}

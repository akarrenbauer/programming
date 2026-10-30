package machine;

import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;

import javafx.animation.AnimationTimer;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.IndexRange;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TablePosition;
import javafx.scene.control.TableView;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.FlowPane;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

/**
 * Execution-to-source GUI checks. Run separately from the core-only suites on a
 * headless JavaFX platform or private virtual display; no native chooser opens.
 *
 * fx_modules=/usr/share/java/openjfx/javafx.base.jar:/usr/share/java/openjfx/javafx.graphics.jar:/usr/share/java/openjfx/javafx.controls.jar
 * gui_build=$(mktemp -d)
 * javac --release 17 --module-path "$fx_modules" --add-modules javafx.controls \
 *     -d "$gui_build" -classpath "$gui_build" -sourcepath . tests/MachineGUISourceMappingTest.java
 * timeout 60s java --module-path "$fx_modules" --add-modules javafx.controls \
 *     -Dglass.platform=Monocle -Dmonocle.platform=Headless -Dprism.order=sw \
 *     -Djava.library.path=/usr/lib64/openjfx -cp "$gui_build" machine.MachineGUISourceMappingTest
 *
 * The local distribution's native libraries require Java 17 and process-local
 * LD_PRELOAD=libfreetype.so.6:libpango-1.0.so.0:libpangoft2-1.0.so.0:libm.so.6.
 * An optional first argument saves a PNG of execution inside an imported macro.
 */
public final class MachineGUISourceMappingTest {
    private static final String BASIC = "var result = 0;\nbegin:\nINC result; DEC result; HALT;\n";
    private static final String LIBRARY = "void leaf(value) {\n    INC value; DEC value;\n}\n"
            + "void outer(value) {\n    leaf(value);\n}\n";
    private static int passed;
    private static int failed;
    private static MachineGUI application;
    private static Stage stage;
    private static Machine machine;
    private static TabPane tabs;
    private static Path mainPath;
    private static Path libraryPath;
    private static Path invalidPath;

    @FunctionalInterface
    private interface Check {
        void run() throws Exception;
    }

    public static void main(String[] args) throws Exception {
        Path screenshot = args.length == 0 ? null : Path.of(args[0]);
        Path directory = Files.createTempDirectory("machine-source-mapping-test-");
        mainPath = directory.resolve("main.ad04");
        libraryPath = directory.resolve("library.ad04");
        invalidPath = directory.resolve("invalid.ad04");
        Files.writeString(libraryPath, LIBRARY);
        Files.writeString(invalidPath, "var result = 0;\nbegin:\nINC missing;\nHALT;\n");

        AtomicReference<Throwable> asynchronousError = new AtomicReference<>();
        Thread.UncaughtExceptionHandler previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            asynchronousError.compareAndSet(null, error);
            error.printStackTrace(System.err);
        });
        CountDownLatch started = new CountDownLatch(1);
        Platform.startup(() -> {
            Platform.setImplicitExit(false);
            started.countDown();
        });
        expect(started.await(10, TimeUnit.SECONDS), "JavaFX toolkit did not start");
        try {
            onFx(() -> {
                application = new MachineGUI();
                stage = new Stage();
                application.start(stage);
                tabs = node("source-tabs", TabPane.class);
                machine = (Machine) field("machine");
                layout();
            });

            checkFx("compile and Step distinguish statements sharing one source line", () -> {
                open(BASIC);
                SourceEditor main = editor(mainPath);
                expect(node("follow-execution", CheckBox.class).isSelected(), "Follow execution starts enabled");
                expectRange(main, "INC result;");
                expect(!main.isExecutionHalted(), "first instruction is not halted");
                expect(status().toLowerCase().contains("next"), "status identifies the next instruction");
                button("Step").fire();
                expect(machine.get(1) == 1 && machine.getStepCounter() == 1, "Step executes the first instruction");
                expectRange(main, "DEC result;");
                button("Step").fire();
                expect(machine.isHalted() && machine.get(1) == 0, "second Step reaches HALT");
                expectRange(main, "HALT;");
                expect(main.isExecutionHalted() && status().toLowerCase().contains("halt"), "HALT has a visible halted state");
                button("Step").fire();
                expectRange(main, "HALT;");
                expect(machine.getStepCounter() == 2, "stepping HALT does not advance the counter");
            });

            check("nested imported macros show body and ordered caller locations", () -> {
                onFx(() -> {
                    open(nestedProgram());
                    SourceEditor main = editor(mainPath);
                    SourceEditor library = editor(libraryPath);
                    expectRange(main, "NOP;");
                    button("Step").fire();
                    expect(tabs.getSelectionModel().getSelectedItem() == tab(libraryPath), "Follow selects the executing import");
                    expectRange(library, "INC value;");
                    expect(main.getExecutionRange() == null, "old main execution range is cleared");
                    expectCaller(main, "outer(result);");
                    expectCaller(library, "leaf(value);");
                    List<Hyperlink> callers = callerLinks();
                    expect(callers.size() == 2, "nested execution exposes two caller links");
                    callers.get(0).fire();
                    expect(tabs.getSelectionModel().getSelectedItem() == tab(mainPath), "outer caller link opens main");
                    callers.get(1).fire();
                    expect(tabs.getSelectionModel().getSelectedItem() == tab(libraryPath), "inner caller link opens its definition file");
                    layout();
                });
                if (screenshot != null) {
                    // Newly opened TabPane headers animate from zero width.
                    awaitFxDelay(750);
                    onFx(() -> {
                        layout();
                        writeSnapshot(screenshot);
                    });
                }
                onFx(() -> {
                    SourceEditor main = editor(mainPath);
                    SourceEditor library = editor(libraryPath);
                    button("Step").fire();
                    expectRange(library, "DEC value;");
                    button("Step").fire();
                    expectRange(main, "HALT;");
                    expect(library.getExecutionRange() == null && library.getCallerRanges().isEmpty(),
                            "return from expansion clears the old body and caller highlights");
                    expect(callerLinks().isEmpty(), "ordinary main instruction has no macro caller links");
                });
            });

            checkFx("execution backgrounds align with native text without covering syntax or selection", () -> {
                open("// padding\n".repeat(45) + BASIC);
                SourceEditor main = editor(mainPath);
                main.selectRange(4, 10);
                expectMarkerAligned(main);
                main.setStyle("-fx-font-family: monospace; -fx-font-size: 19px;");
                main.setScrollTop(120);
                layout();
                expectMarkerAligned(main);
                expect(main.getAnchor() == 4 && main.getCaretPosition() == 10,
                        "execution background layout must preserve native selection");
            });

            checkFx("execution following preserves editor selection caret and undo", () -> {
                open(nestedProgram());
                SourceEditor main = editor(mainPath);
                SourceEditor library = editor(libraryPath);
                main.appendText("// unsaved history\n");
                node("recompile", Button.class).fire();
                main.selectRange(9, 2);
                library.selectRange(7, 12);
                String mainText = main.getText();
                button("Step").fire();
                layout();
                expect(main.getAnchor() == 9 && main.getCaretPosition() == 2,
                        "following must preserve the main selection direction");
                expect(library.getAnchor() == 7 && library.getCaretPosition() == 12,
                        "following must preserve imported editor selection and caret");
                expect(mainText.equals(main.getText()) && main.isUndoable(), "following preserves text and undo history");
                main.undo();
                expect(nestedProgram().equals(main.getText()), "undo removes the user's edit rather than an execution marker");
            });

            checkFx("Follow off updates execution without changing the chosen source tab", () -> {
                open(nestedProgram());
                node("follow-execution", CheckBox.class).fire();
                tabs.getSelectionModel().select(tab(mainPath));
                button("Step").fire();
                expect(tabs.getSelectionModel().getSelectedItem() == tab(mainPath), "Follow off leaves selected tab unchanged");
                expectRange(editor(libraryPath), "INC value;");
                expect(callerLinks().size() == 2 && !status().isBlank(), "execution details continue updating with Follow off");
                node("follow-execution", CheckBox.class).fire();
                expect(tabs.getSelectionModel().getSelectedItem() == tab(libraryPath), "reenabling Follow reveals current execution");
            });

            check("repeated display refreshes preserve a queued offscreen source reveal", () -> {
                onFx(() -> {
                    open("var result = 0;\nbegin:\nNOP;\n" + "// padding\n".repeat(100)
                            + " ".repeat(120) + "INC result;\nHALT;\n");
                    SourceEditor main = editor(mainPath);
                    main.requestFocus();
                    main.selectRange(7, 3);
                    main.setScrollTop(0);
                    main.setScrollLeft(0);
                    layout();
                    expectRange(main, "NOP;");
                    expect(main.getScrollTop() == 0 && main.getScrollLeft() == 0,
                            "fixture begins with the destination outside the viewport");
                    button("Step").fire();
                    // These calls deliberately precede the next JavaFX layout pulse.
                    refreshDisplay();
                    refreshDisplay();
                    refreshDisplay();
                    expectRange(main, "INC result;");
                });
                awaitFxDelay(200);
                onFx(() -> {
                    SourceEditor main = editor(mainPath);
                    layout();
                    expect(main.getScrollTop() > 0 && main.getScrollLeft() > 0,
                            "Follow must still reveal the offscreen line and column after repeated refreshes");
                    Node viewport = main.lookup(".viewport");
                    Node marker = main.lookup(".execution-current");
                    expect(viewport != null && marker != null, "revealed statement has a rendered marker and viewport");
                    expect(viewport.localToScene(viewport.getLayoutBounds()).intersects(
                            marker.localToScene(marker.getBoundsInLocal())), "executing source must enter the viewport");
                    expect(main.getAnchor() == 7 && main.getCaretPosition() == 3,
                            "following preserves the editor caret and selection direction");
                    expect(stage.getScene().getFocusOwner() == main, "following must preserve keyboard focus");
                });
            });

            check("Run finishes with HALT highlighted in the main source", () -> {
                onFx(() -> {
                    open(nestedProgram());
                    button("Run").fire();
                });
                awaitRunFinished();
                onFx(() -> {
                    expect(machine.isHalted(), "finite Run reaches HALT");
                    expectRange(editor(mainPath), "HALT;");
                    expect(editor(mainPath).isExecutionHalted(), "Run completion retains halted highlight");
                    expect(tabs.getSelectionModel().getSelectedItem() == tab(mainPath), "Follow returns to main at HALT");
                });
            });

            checkFx("source changes invalidate mapping and saving does not revive it", () -> {
                open(nestedProgram());
                button("Step").fire();
                SourceEditor library = editor(libraryPath);
                library.appendText("// changed import\n");
                expectNoHighlights();
                expect(status().toLowerCase().contains("recompile"), "edited source explains how to restore mapping");
                tabs.getSelectionModel().select(tab(libraryPath));
                node("save-source", Button.class).fire();
                expect(!((SourceWorkspace.Document) tab(libraryPath).getUserData()).isModified(), "save writes the edited import");
                expectNoHighlights();
                node("recompile", Button.class).fire();
                expectRange(editor(mainPath), "NOP;");
            });

            checkFx("failed recompile preserves VM state and keeps mapping invalid", () -> {
                open(BASIC);
                button("Step").fire();
                List<Integer> before = memory();
                int steps = machine.getStepCounter();
                SourceEditor main = editor(mainPath);
                main.setText(main.getText().replace("HALT;", "INC missing; HALT;"));
                try (DialogResponse response = new DialogResponse("OK")) {
                    node("recompile", Button.class).fire();
                    expect(response.count == 1, "failed recompile reports its error");
                }
                expect(before.equals(memory()) && steps == machine.getStepCounter(), "failed recompile leaves VM unchanged");
                expectNoHighlights();
                button("Step").fire();
                expectNoHighlights();
            });

            checkFx("opening invalid new source clears mapping for the previous program", () -> {
                open(BASIC);
                List<Integer> before = memory();
                try (DialogResponse response = new DialogResponse("OK")) {
                    application.openSourceFile(invalidPath);
                    expect(response.count == 1, "invalid new source reports compilation error");
                }
                expect(before.equals(memory()), "invalid new source leaves previous VM image intact");
                expect(tab(invalidPath).getContent() instanceof SourceEditor, "new source stays editable after its error");
                expectNoHighlights();
            });

            checkFx("data edits preserve mapping and instruction-pointer edits move it", () -> {
                open(BASIC);
                int firstAddress = machine.getInstructionPointer();
                editMemory(1, 7);
                expect(machine.get(1) == 7, "table edit changes data");
                expectRange(editor(mainPath), "INC result;");
                editMemory(0, firstAddress + 2);
                expectRange(editor(mainPath), "DEC result;");
                expect(machine.getStepCounter() == 0, "moving IP does not execute an instruction");
            });

            checkFx("unmapped IP clears markers without inventing a nearby location", () -> {
                open(BASIC);
                int firstAddress = machine.getInstructionPointer();
                editMemory(0, firstAddress + 1);
                expectNoHighlights();
                editMemory(0, firstAddress);
                expectRange(editor(mainPath), "INC result;");
            });

            checkFx("opcode and operand edits invalidate source mapping", () -> {
                open(BASIC);
                editMemory(machine.getInstructionPointer(), 1);
                expectNoHighlights();
                node("recompile", Button.class).fire();
                expectRange(editor(mainPath), "INC result;");
                editMemory(machine.getInstructionPointer() + 1, 0);
                expectNoHighlights();
            });

            checkFx("self-modifying code invalidates mapping during Step", () -> {
                open("var result = 0;\nbegin:\nINC result[1];\nHALT;\n");
                int firstAddress = machine.getInstructionPointer();
                expectRange(editor(mainPath), "INC result[1];");
                button("Step").fire();
                expect(machine.get(firstAddress) == 3, "fixture modifies its own opcode through shared memory");
                expectNoHighlights();
            });

            checkFx("Reset Reload and a replaced raw image clear source mapping", () -> {
                open(BASIC);
                button("Reset").fire();
                expectNoHighlights();
                node("recompile", Button.class).fire();
                expectRange(editor(mainPath), "INC result;");
                button("Reload").fire();
                expectNoHighlights();
                node("recompile", Button.class).fire();
                machine.load(List.of(1, 0));
                refreshDisplay();
                expectNoHighlights();
            });

            checkFx("CRLF lines and tabs map to normalized editor character offsets", () -> {
                open("var result = 0;\r\nbegin:\r\n\tINC result; DEC result; HALT;\r\n");
                SourceEditor main = editor(mainPath);
                expect(!main.getText().contains("\r"), "visible source uses normalized LF line endings");
                expectRange(main, "INC result;");
                button("Step").fire();
                expectRange(main, "DEC result;");
            });

            check("JavaFX reports no asynchronous execution-highlight exceptions", () -> {
                if (asynchronousError.get() != null) {
                    throw new AssertionError("JavaFX event or layout failure", asynchronousError.get());
                }
            });
        } finally {
            try {
                onFx(() -> {
                    application.stop();
                    for (Window window : new ArrayList<>(Window.getWindows())) {
                        window.hide();
                    }
                });
            } finally {
                Platform.exit();
                Thread.setDefaultUncaughtExceptionHandler(previousHandler);
            }
        }
        System.out.println("MachineGUISourceMappingTest: " + passed + " passed, " + failed + " failed");
        if (failed != 0) {
            throw new AssertionError(failed + " execution-to-source GUI check(s) failed");
        }
    }

    private static String nestedProgram() {
        return "import \"" + libraryPath + "\";\nvar result = 0;\nbegin:\nNOP;\nouter(result);\nHALT;\n";
    }

    private static void open(String source) throws Exception {
        Files.writeString(mainPath, source);
        Files.writeString(libraryPath, LIBRARY);
        try (DialogResponse response = new DialogResponse("Discard", "OK")) {
            application.openSourceFile(mainPath);
            expect(response.errors == 0, "valid fixture must compile without an error dialog");
        }
        if (!node("follow-execution", CheckBox.class).isSelected()) {
            node("follow-execution", CheckBox.class).fire();
        }
        layout();
        expect(field("compiledProgram") != null, "opening valid source must retain its compilation mapping");
    }

    private static SourceEditor editor(Path path) {
        return (SourceEditor) tab(path).getContent();
    }

    private static Tab tab(Path path) {
        return tabs.getTabs().stream()
                .filter(tab -> tab.getUserData() instanceof SourceWorkspace.Document document
                        && document.getPath().equals(path.toAbsolutePath().normalize()))
                .findFirst().orElseThrow(() -> new AssertionError("No source tab for " + path));
    }

    private static void expectRange(SourceEditor editor, String statement) {
        IndexRange range = editor.getExecutionRange();
        expect(range != null, "expected execution range for " + statement);
        String text = editor.getText().substring(range.getStart(), range.getEnd());
        expect(statement.equals(text), "expected execution text '" + statement + "', got '" + text + "'");
    }

    private static void expectCaller(SourceEditor editor, String call) {
        expect(editor.getCallerRanges().stream().anyMatch(range -> call.equals(
                editor.getText().substring(range.getStart(), range.getEnd()))), "missing caller highlight " + call);
    }

    private static void expectMarkerAligned(SourceEditor editor) {
        layout();
        editor.layout();
        expect(editor.lookup(".execution-highlights") instanceof Group, "execution marker group is rendered");
        Group markers = (Group) editor.lookup(".execution-highlights");
        Node syntax = editor.lookup(".source-highlights");
        expect(markers.isMouseTransparent() && !markers.isManaged(), "markers must not intercept editing or affect layout");
        expect(markers.lookup(".execution-current") instanceof javafx.scene.shape.Path, "current range has a visible background");
        javafx.scene.shape.Path marker = (javafx.scene.shape.Path) markers.lookup(".execution-current");
        expect(marker.getFill() instanceof Color color && color.getOpacity() > 0,
                "execution background must have a visible fill");
        Parent content = markers.getParent();
        expect(content.getChildrenUnmodifiable().indexOf(markers)
                < content.getChildrenUnmodifiable().indexOf(syntax), "execution backgrounds render behind syntax colors");
        Text nativeText = content.getChildrenUnmodifiable().stream()
                .filter(node -> node instanceof Group group && node != markers && node != syntax
                        && group.getChildren().size() == 1 && group.getChildren().get(0) instanceof Text)
                .map(node -> (Text) ((Group) node).getChildren().get(0)).findFirst()
                .orElseThrow(() -> new AssertionError("native TextArea text remains present"));
        expect(content.getChildrenUnmodifiable().indexOf(markers)
                < content.getChildrenUnmodifiable().indexOf(nativeText.getParent()), "native selection text stays above execution backgrounds");
        IndexRange range = editor.getExecutionRange();
        javafx.scene.shape.Path expectedPath = new javafx.scene.shape.Path(nativeText.rangeShape(range.getStart(), range.getEnd()));
        expectedPath.setFill(Color.BLACK);
        expectedPath.setStroke(null);
        Bounds expected = nativeText.localToScene(expectedPath.getBoundsInLocal());
        Bounds actual = marker.localToScene(marker.getBoundsInLocal());
        expect(Math.abs(expected.getMinX() - actual.getMinX()) < 0.02
                        && Math.abs(expected.getMinY() - actual.getMinY()) < 0.02
                        && Math.abs(expected.getWidth() - actual.getWidth()) < 0.02
                        && Math.abs(expected.getHeight() - actual.getHeight()) < 0.02,
                "execution background must match native text range after layout, font changes and scrolling");
    }

    private static void expectNoHighlights() {
        for (Tab tab : tabs.getTabs()) {
            if (tab.getContent() instanceof SourceEditor editor) {
                expect(editor.getExecutionRange() == null && editor.getCallerRanges().isEmpty(),
                        "stale execution/caller highlights remain in " + tab.getText());
            }
        }
        expect(callerLinks().isEmpty(), "stale macro caller links remain");
    }

    private static List<Hyperlink> callerLinks() {
        return node("execution-callers", FlowPane.class).getChildren().stream()
                .filter(Hyperlink.class::isInstance).map(Hyperlink.class::cast).toList();
    }

    private static String status() {
        return node("execution-status", Label.class).getText();
    }

    private static <T extends Node> T node(String id, Class<T> type) {
        Node result = stage.getScene().lookup("#" + id);
        expect(type.isInstance(result), "missing " + type.getSimpleName() + " #" + id);
        return type.cast(result);
    }

    private static Button button(String label) {
        return stage.getScene().getRoot().lookupAll(".button").stream()
                .filter(Button.class::isInstance).map(Button.class::cast)
                .filter(button -> label.equals(button.getText())).findFirst()
                .orElseThrow(() -> new AssertionError("Missing button " + label));
    }

    private static Object field(String name) throws Exception {
        Field field = MachineGUI.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(application);
    }

    @SuppressWarnings("unchecked")
    private static void editMemory(int address, int value) throws Exception {
        TableView<MachineGUI.MemoryEntry> table = (TableView<MachineGUI.MemoryEntry>) field("memoryTable");
        TableColumn<MachineGUI.MemoryEntry, Integer> column =
                (TableColumn<MachineGUI.MemoryEntry, Integer>) table.getColumns().get(1);
        expect(table.getItems().get(address).getAddress() == address, "memory fixture uses address-indexed rows");
        TableColumn.CellEditEvent<MachineGUI.MemoryEntry, Integer> event = new TableColumn.CellEditEvent<>(
                table, new TablePosition<>(table, address, column), TableColumn.editCommitEvent(), value);
        column.getOnEditCommit().handle(event);
    }

    private static List<Integer> memory() {
        List<Integer> values = new ArrayList<>();
        for (int address = 0; address < machine.getMemorySize(); address++) {
            values.add(machine.get(address));
        }
        return values;
    }

    private static void refreshDisplay() throws Exception {
        Method method = MachineGUI.class.getDeclaredMethod("updateMemoryDisplay");
        method.setAccessible(true);
        try {
            method.invoke(application);
        } catch (InvocationTargetException error) {
            throw new AssertionError("Display refresh failed", error.getCause());
        }
    }

    private static void layout() {
        stage.getScene().getRoot().applyCss();
        stage.getScene().getRoot().layout();
    }

    private static void awaitRunFinished() throws Exception {
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        onFx(() -> new AnimationTimer() {
            private int frames;

            @Override
            public void handle(long now) {
                try {
                    if (field("runTimer") == null || ++frames > 300) {
                        expect(field("runTimer") == null, "finite Run did not finish");
                        stop();
                        finished.countDown();
                    }
                } catch (Throwable error) {
                    failure.set(error);
                    stop();
                    finished.countDown();
                }
            }
        }.start());
        expect(finished.await(10, TimeUnit.SECONDS), "Run did not complete within ten seconds");
        if (failure.get() != null) {
            throw new AssertionError("Run failed", failure.get());
        }
    }

    private static void awaitFxDelay(int milliseconds) throws Exception {
        CountDownLatch settled = new CountDownLatch(1);
        onFx(() -> {
            PauseTransition pause = new PauseTransition(Duration.millis(milliseconds));
            pause.setOnFinished(event -> settled.countDown());
            pause.play();
        });
        expect(settled.await(5, TimeUnit.SECONDS), "JavaFX animation/layout delay did not finish");
    }

    private static void writeSnapshot(Path path) throws Exception {
        WritableImage shot = stage.getScene().snapshot(null);
        BufferedImage image = new BufferedImage((int) shot.getWidth(), (int) shot.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, shot.getPixelReader().getArgb(x, y));
            }
        }
        expect(ImageIO.write(image, "png", path.toFile()), "PNG writer is available");
    }

    private static void onFx(Check action) throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> {
            action.run();
            return null;
        });
        Platform.runLater(task);
        try {
            task.get(10, TimeUnit.SECONDS);
        } catch (ExecutionException error) {
            if (error.getCause() instanceof Exception exception) {
                throw exception;
            }
            if (error.getCause() instanceof Error cause) {
                throw cause;
            }
            throw new AssertionError(error.getCause());
        }
    }

    private static void checkFx(String name, Check action) {
        check(name, () -> onFx(action));
    }

    private static void check(String name, Check action) {
        try {
            action.run();
            passed++;
            System.out.println("PASS " + name);
        } catch (Exception | AssertionError error) {
            failed++;
            System.err.println("FAIL " + name + ": " + error);
            error.printStackTrace(System.err);
        }
    }

    private static void expect(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class DialogResponse implements AutoCloseable {
        private final Timeline timer;
        private int count;
        private int errors;

        DialogResponse(String... answers) {
            List<String> labels = List.of(answers);
            timer = new Timeline(new KeyFrame(Duration.millis(25), event -> {
                for (Window window : new ArrayList<>(Window.getWindows())) {
                    if (window != stage && window.isShowing() && window.getScene() != null
                            && window.getScene().getRoot() instanceof DialogPane pane) {
                        pane.getButtonTypes().stream().filter(type -> labels.contains(type.getText()))
                                .findFirst().ifPresent(type -> {
                                    count++;
                                    if ("OK".equals(type.getText())) {
                                        errors++;
                                    }
                                    ((Button) pane.lookupButton(type)).fire();
                                });
                    }
                }
            }));
            timer.setCycleCount(Timeline.INDEFINITE);
            timer.play();
        }

        @Override
        public void close() {
            timer.stop();
        }
    }
}

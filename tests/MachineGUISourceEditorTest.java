package machine;

import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
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
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.stage.WindowEvent;
import javafx.util.Duration;

/**
 * JavaFX integration checks; use a headless platform or a private virtual display.
 * This test edits files only in its own temporary directory and never opens a
 * native file chooser. It is separate from the core-only regression suites.
 *
 * Example from the repository root with the installed JavaFX jars:
 *
 * fx_modules=/usr/share/java/openjfx/javafx.base.jar:/usr/share/java/openjfx/javafx.graphics.jar:/usr/share/java/openjfx/javafx.controls.jar
 * gui_build=$(mktemp -d)
 * javac --release 17 --module-path "$fx_modules" --add-modules javafx.controls \
 *     -d "$gui_build" -classpath "$gui_build" -sourcepath . tests/MachineGUISourceEditorTest.java
 * timeout 45s java --module-path "$fx_modules" --add-modules javafx.controls \
 *     -Dglass.platform=Monocle -Dmonocle.platform=Headless -Dprism.order=sw \
 *     -Djava.library.path=/usr/lib64/openjfx -cp "$gui_build" machine.MachineGUISourceEditorTest
 *
 * Some distributions omit native-library dependencies from their JavaFX binaries.
 * For that installation only, this test was run with Java 17 and process-local
 * LD_PRELOAD=libfreetype.so.6:libpango-1.0.so.0:libpangoft2-1.0.so.0:libm.so.6.
 * An optional first argument saves a PNG snapshot of the loaded source editor.
 */
public final class MachineGUISourceEditorTest {
    private static int passed;
    private static int failed;
    private static MachineGUI application;
    private static Stage stage;
    private static Machine machine;
    private static TabPane tabs;
    private static Button compile;
    private static Button save;
    private static Tab mainTab;
    private static Tab libraryTab;
    private static Tab carriageReturnTab;
    private static TextArea mainEditor;
    private static TextArea libraryEditor;
    private static TextArea carriageReturnEditor;

    @FunctionalInterface
    private interface Check {
        void run() throws Exception;
    }

    public static void main(String[] args) throws Exception {
        Path screenshotPath = args.length == 0 ? null : Path.of(args[0]);
        Path directory = Files.createTempDirectory("machine-source-editor-test-");
        Path mainPath = directory.resolve("main.ad04");
        Path libraryPath = directory.resolve("library.ad04");
        Path carriageReturnPath = directory.resolve("carriage-return.ad04");
        String carriageReturnSource = "// heading\rvar result = 1;\rbegin:\rINC result;\rHALT;\r";
        String carriageReturnDraft = carriageReturnSource.replace('\r', '\n') + "// retained draft\n";
        String libraryOriginal = "void bump(value) {\n    INC value;\n}\n";
        String libraryChanged = "void bump(value) {\n    INC value;\n    INC value;\n}\n";
        String mainOriginal = "import \"" + libraryPath + "\";\n"
                + "var result = 1;\nbegin:\nbump(result);\nHALT;\n";
        String mainChanged = mainOriginal.replace("var result = 1;", "var result = 2;");
        Files.writeString(mainPath, mainOriginal);
        Files.writeString(libraryPath, libraryOriginal);
        Files.writeString(carriageReturnPath, carriageReturnSource);

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
        if (!started.await(10, TimeUnit.SECONDS)) {
            throw new AssertionError("JavaFX toolkit did not start");
        }

        try {
            onFx(() -> {
                application = new MachineGUI();
                stage = new Stage();
                application.start(stage);
                stage.getScene().getRoot().applyCss();
                stage.getScene().getRoot().layout();
                tabs = node("source-tabs", TabPane.class);
                compile = node("compile", Button.class);
                save = node("save-source", Button.class);
                Field machineField = MachineGUI.class.getDeclaredField("machine");
                machineField.setAccessible(true);
                machine = (Machine) machineField.get(application);
            });

            checkFx("startup leaves source actions disabled", () -> {
                expect(compile.isDisabled(), "Compile must be disabled without a source workspace");
                expect("Compile".equals(compile.getText()), "source compilation uses the Compile action");
                expect("Open".equals(node("open-source", Button.class).getText()), "source browsing uses the Open action");
                expect(save.isDisabled(), "Save source must be disabled without a document");
                expect(!node("source-status", Label.class).getText().isBlank(), "source status is visible");
            });

            checkFx("opening a library only browses source without compiling", () -> {
                List<Integer> memoryBefore = memory();
                application.openSourceFile(libraryPath);
                libraryTab = tabFor(libraryPath);
                libraryEditor = editor(libraryTab);
                expect(tabs.getTabs().size() == 1 && tabs.getSelectionModel().getSelectedItem() == libraryTab,
                        "a library must open as the selected source tab");
                expect(libraryOriginal.equals(libraryEditor.getText()), "library source remains editable");
                expect(compile.isDisabled() && !libraryTab.getText().contains("(main)"),
                        "a file without begin must not become the main program");
                expect(memoryBefore.equals(memory()) && machine.getStepCounter() == 0,
                        "opening a library must not compile or change machine state");
            });

            checkFx("opening CR-delimited source preserves editor line boundaries", () -> {
                application.openSourceFile(carriageReturnPath);
                carriageReturnTab = tabFor(carriageReturnPath);
                carriageReturnEditor = editor(carriageReturnTab);
                expect(carriageReturnSource.lines().toList().equals(carriageReturnEditor.getText().lines().toList()),
                        "visible source lines must match the compiled document even with CR delimiters");
                carriageReturnEditor.setText(carriageReturnDraft);
            });

            checkFx("opening a program retains old buffers and reuses its imported library tab", () -> {
                try (DialogResponse response = new DialogResponse("Discard")) {
                    application.openSourceFile(mainPath);
                    expect(response.count == 0, "Open must not ask to discard existing source edits");
                }
                expect(tabs.getTabs().size() == 3, "library, previous program, and new main must each keep one tab");
                mainTab = tabFor(mainPath);
                expect(libraryTab == tabFor(libraryPath), "compiling an import must reuse its already open tab");
                mainEditor = editor(mainTab);
                expect(tabs.getTabs().get(0) != mainTab && mainTab.getText().contains("(main)"),
                        "opening a program activates it even when it is not the first tab");
                expect(tabFor(carriageReturnPath) == carriageReturnTab
                                && carriageReturnDraft.equals(carriageReturnEditor.getText())
                                && document(carriageReturnTab).isModified(),
                        "opening another program preserves the previous tab and its unsaved buffer");
                expect(carriageReturnSource.equals(Files.readString(carriageReturnPath)),
                        "opening another program must not save previous edits");
                expect(mainEditor instanceof SourceEditor && libraryEditor instanceof SourceEditor,
                        "main and import tabs must both use syntax-highlighting editors");
                expect(mainOriginal.equals(mainEditor.getText()), "main editor must show file contents");
                expect(libraryOriginal.equals(libraryEditor.getText()), "import editor must show file contents");
                expect(!document(mainTab).isModified() && !document(libraryTab).isModified(), "opened files start clean");
                expect(!compile.isDisabled(), "opening source enables Compile");
                expect(machine.get(1) == 1 && machine.getStepCounter() == 0, "opening compiles the main program");
            });

            checkFx("highlighting and font styling do not dirty source tabs", () -> {
                for (Tab tab : List.of(mainTab, libraryTab)) {
                    tabs.getSelectionModel().select(tab);
                    TextArea editor = editor(tab);
                    String textBefore = editor.getText();
                    String styleBefore = editor.getStyle();
                    editor.selectRange(1, 4);
                    editor.setStyle("-fx-font-family: monospace; -fx-font-size: 16px;");
                    stage.getScene().getRoot().applyCss();
                    stage.getScene().getRoot().layout();
                    expect(editor.lookup(".source-highlights") != null, "selected source tab must render highlights");
                    expect(textBefore.equals(editor.getText()), "highlight styling must preserve source text");
                    expect(editor.getAnchor() == 1 && editor.getCaretPosition() == 4,
                            "highlight styling must preserve selection and caret");
                    expect(!document(tab).isModified() && !tab.getText().contains("*"),
                            "highlight styling must not create unsaved source changes");
                    expect(save.isDisabled(), "style-only updates must not enable Save source");
                    editor.setStyle(styleBefore);
                }
                tabs.getSelectionModel().select(mainTab);
            });

            checkFx("Compile uses unsaved buffers while an import tab is selected", () -> {
                mainEditor.setText(mainChanged);
                libraryEditor.setText(libraryChanged);
                tabs.getSelectionModel().select(libraryTab);
                expect(mainTab.getText().contains("*") && libraryTab.getText().contains("*"), "both edits need dirty markers");
                compile.fire();
                machine.step(1000);
                expect(machine.isHalted() && machine.get(1) == 4, "main value 2 plus two unsaved import increments must equal 4");
                expect(mainOriginal.equals(Files.readString(mainPath)), "compilation must not save main source");
                expect(libraryOriginal.equals(Files.readString(libraryPath)), "compilation must not save imported source");
                expect(document(mainTab).isModified() && document(libraryTab).isModified(), "compilation must keep unsaved state");
            });

            checkFx("reopening a dirty main compiles its cached buffer without replacing tabs", () -> {
                List<Tab> tabsBefore = new ArrayList<>(tabs.getTabs());
                try (DialogResponse response = new DialogResponse("Discard")) {
                    application.openSourceFile(mainPath);
                    expect(response.count == 0, "reopening a cached file must not ask to discard edits");
                }
                expect(tabsBefore.equals(tabs.getTabs()) && mainEditor == editor(tabFor(mainPath)),
                        "reopening must preserve all tabs and the cached editor instance");
                expect(mainChanged.equals(mainEditor.getText()) && document(mainTab).isModified(),
                        "cached unsaved source must take precedence over disk contents");
                expect(machine.get(1) == 2 && machine.getStepCounter() == 0,
                        "reopening main automatically compiles the unsaved initial value");
                machine.step(1000);
                expect(machine.isHalted() && machine.get(1) == 4,
                        "reopening also compiles the unsaved imported buffer");
                expect(mainOriginal.equals(Files.readString(mainPath))
                                && libraryOriginal.equals(Files.readString(libraryPath)),
                        "reopening source must not save either edited file");
            });

            checkFx("opening a cached library preserves the active program and execution state", () -> {
                List<Integer> memoryBefore = memory();
                int stepsBefore = machine.getStepCounter();
                List<Tab> tabsBefore = new ArrayList<>(tabs.getTabs());
                try (DialogResponse response = new DialogResponse("Discard")) {
                    application.openSourceFile(libraryPath);
                    expect(response.count == 0, "browsing an import must not prompt about unsaved source");
                }
                expect(tabsBefore.equals(tabs.getTabs()) && tabs.getSelectionModel().getSelectedItem() == libraryTab,
                        "reopening a library selects its existing tab");
                expect(libraryChanged.equals(libraryEditor.getText()) && document(libraryTab).isModified(),
                        "reopening a library must retain its unsaved source");
                expect(mainTab.getText().contains("(main)") && !compile.isDisabled(),
                        "browsing a library keeps the existing main active");
                expect(memoryBefore.equals(memory()) && stepsBefore == machine.getStepCounter(),
                        "browsing a library must not recompile or reset execution");
            });

            checkFx("Save source writes only the selected document", () -> {
                tabs.getSelectionModel().select(libraryTab);
                expect(!save.isDisabled(), "selected modified import must be saveable");
                save.fire();
                expect(libraryChanged.equals(Files.readString(libraryPath)), "selected library must be written");
                expect(mainOriginal.equals(Files.readString(mainPath)), "saving the library must leave main file unchanged");
                expect(!document(libraryTab).isModified() && !libraryTab.getText().contains("*"), "saved tab must become clean");
                expect(save.isDisabled(), "saved selected document no longer needs saving");
                expect(document(mainTab).isModified() && mainTab.getText().contains("*"), "main edit must remain dirty");
            });

            check("Home retains text-editor line navigation", () -> {
                onFx(() -> {
                    tabs.getSelectionModel().select(mainTab);
                    stage.getScene().getRoot().applyCss();
                    stage.getScene().getRoot().layout();
                    mainEditor.requestFocus();
                    int lineStart = mainEditor.getText().indexOf("var result");
                    mainEditor.positionCaret(lineStart + "var result = 2;".length());
                });
                awaitLayoutPulse();
                onFx(() -> {
                    expect(stage.getScene().getFocusOwner() == mainEditor, "main editor must own keyboard focus");
                    int lineStart = mainEditor.getText().indexOf("var result");
                    if (screenshotPath != null) {
                        writeSnapshot(screenshotPath);
                    }
                    Event.fireEvent(mainEditor, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.HOME,
                            false, false, false, false));
                    Event.fireEvent(mainEditor, new KeyEvent(KeyEvent.KEY_RELEASED, "", "", KeyCode.HOME,
                            false, false, false, false));
                    expect(mainEditor.getCaretPosition() == lineStart,
                            "Home must move to the beginning of the editor line: expected " + lineStart
                                    + ", actual " + mainEditor.getCaretPosition());
                    expect(mainChanged.equals(mainEditor.getText()), "navigation must preserve source text");
                });
            });

            checkFx("failed compilation preserves machine state and open edits", () -> {
                List<Integer> memoryBefore = memory();
                int stepsBefore = machine.getStepCounter();
                List<Tab> tabsBefore = new ArrayList<>(tabs.getTabs());
                String invalidMain = mainChanged.replace("HALT;", "INC missing;\nHALT;");
                String importDraft = libraryChanged + "// unsaved import draft\n";
                mainEditor.setText(invalidMain);
                libraryEditor.setText(importDraft);
                try (DialogResponse response = new DialogResponse("OK")) {
                    compile.fire();
                    expect(response.count == 1, "invalid source must produce an error dialog");
                }
                expect(memoryBefore.equals(memory()) && stepsBefore == machine.getStepCounter(), "failed compilation must preserve VM memory and steps");
                expect(tabsBefore.equals(tabs.getTabs()), "failed compilation must retain existing tabs");
                expect(invalidMain.equals(mainEditor.getText()) && importDraft.equals(libraryEditor.getText()), "failed compilation must retain both editor buffers");
                expect(document(mainTab).isModified() && document(libraryTab).isModified(), "failed compilation must retain dirty state");
                expect(mainOriginal.equals(Files.readString(mainPath)) && libraryChanged.equals(Files.readString(libraryPath)), "failed compilation must not write source files");
                expect(node("source-status", Label.class).getText().toLowerCase().contains("failed"), "status must show compilation failure");
            });

            checkFx("canceling a dirty-window close preserves edits and the window", () -> {
                String mainDraft = mainEditor.getText();
                try (DialogResponse response = new DialogResponse("Cancel")) {
                    Event.fireEvent(stage, new WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST));
                    expect(response.count == 1, "closing dirty source must prompt once");
                }
                expect(stage.isShowing(), "Cancel must keep the window open");
                expect(mainDraft.equals(mainEditor.getText()), "Cancel must retain the edited source");
                expect(mainOriginal.equals(Files.readString(mainPath)) && libraryChanged.equals(Files.readString(libraryPath)), "Cancel must not save source files");
            });

            checkFx("discarding a dirty-window close leaves source files untouched", () -> {
                try (DialogResponse response = new DialogResponse("Discard")) {
                    Event.fireEvent(stage, new WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST));
                    expect(response.count == 1, "discard close must ask about dirty source");
                }
                expect(!stage.isShowing(), "Discard must allow the window to close");
                expect(mainOriginal.equals(Files.readString(mainPath)) && libraryChanged.equals(Files.readString(libraryPath)), "Discard must not write either source file");
            });

            check("JavaFX reports no asynchronous exceptions", () -> {
                if (asynchronousError.get() != null) {
                    throw new AssertionError("JavaFX event or layout failure", asynchronousError.get());
                }
            });
        } finally {
            try {
                onFx(() -> {
                    for (Window window : new ArrayList<>(Window.getWindows())) {
                        window.hide();
                    }
                    if (application != null) {
                        application.stop();
                    }
                });
            } finally {
                Platform.exit();
                Thread.setDefaultUncaughtExceptionHandler(previousHandler);
            }
        }

        System.out.println("MachineGUISourceEditorTest: " + passed + " passed, " + failed + " failed");
        if (failed != 0) {
            throw new AssertionError(failed + " source-editor integration check(s) failed");
        }
    }

    private static <T extends Node> T node(String id, Class<T> type) {
        Node found = stage.getScene().lookup("#" + id);
        expect(type.isInstance(found), "missing " + type.getSimpleName() + " #" + id);
        return type.cast(found);
    }

    private static Tab tabFor(Path path) {
        return tabs.getTabs().stream()
                .filter(tab -> tab.getUserData() instanceof SourceWorkspace.Document)
                .filter(tab -> path.toAbsolutePath().normalize().equals(document(tab).getPath()))
                .findFirst().orElseThrow(() -> new AssertionError("No source tab for " + path));
    }

    private static SourceWorkspace.Document document(Tab tab) {
        return (SourceWorkspace.Document) tab.getUserData();
    }

    private static TextArea editor(Tab tab) {
        expect(tab.getContent() instanceof TextArea, "source tab must contain an editable TextArea");
        return (TextArea) tab.getContent();
    }

    private static List<Integer> memory() {
        List<Integer> image = new ArrayList<>();
        for (int address = 0; address < machine.getMemorySize(); ++address) {
            image.add(machine.get(address));
        }
        return image;
    }

    private static void writeSnapshot(Path path) throws Exception {
        WritableImage snapshot = stage.getScene().snapshot(null);
        int width = (int) snapshot.getWidth();
        int height = (int) snapshot.getHeight();
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; ++y) {
            for (int x = 0; x < width; ++x) {
                image.setRGB(x, y, snapshot.getPixelReader().getArgb(x, y));
            }
        }
        if (!ImageIO.write(image, "png", path.toFile())) {
            throw new AssertionError("No PNG writer is available");
        }
    }

    private static void onFx(Check action) throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> {
            action.run();
            return null;
        });
        Platform.runLater(task);
        try {
            task.get(10, TimeUnit.SECONDS);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new AssertionError(cause);
        }
    }

    private static void awaitLayoutPulse() throws Exception {
        CountDownLatch completed = new CountDownLatch(1);
        onFx(() -> new AnimationTimer() {
            private int frames;

            @Override
            public void handle(long now) {
                if (++frames == 2) {
                    stop();
                    completed.countDown();
                }
            }
        }.start());
        expect(completed.await(5, TimeUnit.SECONDS), "JavaFX did not complete a layout pulse");
    }

    private static void checkFx(String name, Check action) {
        check(name, () -> onFx(action));
    }

    private static void check(String name, Check action) {
        try {
            action.run();
            ++passed;
            System.out.println("PASS " + name);
        } catch (Exception | AssertionError error) {
            ++failed;
            System.err.println("FAIL " + name + ": " + error);
            error.printStackTrace(System.err);
        }
    }

    private static void expect(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    /** Responds while showAndWait runs its nested JavaFX event loop. */
    private static final class DialogResponse implements AutoCloseable {
        private final Timeline timer;
        private int count;

        private DialogResponse(String buttonText) {
            timer = new Timeline(new KeyFrame(Duration.millis(25), event -> {
                for (Window window : new ArrayList<>(Window.getWindows())) {
                    if (window != stage && window.isShowing() && window.getScene() != null
                            && window.getScene().getRoot() instanceof DialogPane) {
                        DialogPane pane = (DialogPane) window.getScene().getRoot();
                        pane.getButtonTypes().stream()
                                .filter(type -> type.getText().equalsIgnoreCase(buttonText))
                                .findFirst().ifPresent(type -> {
                                    ++count;
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

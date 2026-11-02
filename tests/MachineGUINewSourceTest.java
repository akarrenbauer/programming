package machine;

import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;

import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.IndexRange;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.FlowPane;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.stage.WindowEvent;
import javafx.util.Duration;

/**
 * New-source GUI checks. Native save choosers are replaced by queued test paths.
 * Compile separately from core-only tests and run on a private/headless display:
 *
 * fx_modules=/usr/share/java/openjfx/javafx.base.jar:/usr/share/java/openjfx/javafx.graphics.jar:/usr/share/java/openjfx/javafx.controls.jar
 * gui_build=$(mktemp -d)
 * javac --release 17 --module-path "$fx_modules" --add-modules javafx.controls \
 *     -d "$gui_build" -classpath "$gui_build" -sourcepath . tests/MachineGUINewSourceTest.java
 * timeout 60s java --module-path "$fx_modules" --add-modules javafx.controls \
 *     -Dglass.platform=Monocle -Dmonocle.platform=Headless -Dprism.order=sw \
 *     -Djava.library.path=/usr/lib64/openjfx -cp "$gui_build" machine.MachineGUINewSourceTest
 *
 * This distribution's JavaFX natives require Java 17 and process-local
 * LD_PRELOAD=libfreetype.so.6:libpango-1.0.so.0:libpangoft2-1.0.so.0:libm.so.6.
 * Optional first argument: PNG of a selected empty Untitled tab after animations.
 */
public final class MachineGUINewSourceTest {
    private static final String PROGRAM = "var result = 1;\nbegin:\nINC result;\nHALT;\n";
    private static int passed;
    private static int failed;
    private static TestGUI application;
    private static Stage stage;
    private static TabPane tabs;
    private static Machine machine;
    private static Path directory;
    private static Path existing;
    private static Path library;

    @FunctionalInterface
    private interface Check { void run() throws Exception; }

    private static final class TestGUI extends MachineGUI {
        private final List<Path> savePaths = new ArrayList<>();
        private final List<SourceWorkspace.Document> saveRequests = new ArrayList<>();

        void choose(Path... paths) { savePaths.addAll(Arrays.asList(paths)); }

        @Override
        Path chooseSourceSavePath(SourceWorkspace.Document document) {
            saveRequests.add(document);
            expect(!savePaths.isEmpty(), "unexpected save-path request for " + document.getDisplayName());
            return savePaths.remove(0);
        }
    }

    public static void main(String[] args) throws Exception {
        Path screenshot = args.length == 0 ? null : Path.of(args[0]);
        directory = Files.createTempDirectory("machine-new-source-test-");
        existing = directory.resolve("existing.ad04");
        library = directory.resolve("library.ad04");
        Files.writeString(existing, PROGRAM);
        Files.writeString(library, "void bump(value) {\n    INC value;\n}\n");
        AtomicReference<Throwable> asynchronousError = new AtomicReference<>();
        Thread.UncaughtExceptionHandler previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            asynchronousError.compareAndSet(null, error);
            error.printStackTrace(System.err);
        });
        CountDownLatch started = new CountDownLatch(1);
        Platform.startup(() -> { Platform.setImplicitExit(false); started.countDown(); });
        expect(started.await(10, TimeUnit.SECONDS), "JavaFX toolkit did not start");
        try {
            check("New creates distinct empty selected and focused unnamed tabs", () -> {
                onFx(() -> {
                    fresh();
                    expect("Open".equals(node("open-source", Button.class).getText()), "Open is distinct from Compile");
                    expect("Compile".equals(node("compile", Button.class).getText()), "main toolbar exposes Compile");
                    List<Integer> memoryBefore = memory();
                    Tab main = createTab();
                    expect("Untitled".equals(main.getText()), "blank unnamed tab is not an active main");
                    expect(editor(main).getText().isEmpty() && document(main).isUntitled(), "draft starts empty and unnamed");
                    expect(editor(main).getLanguageVersion() == LanguageVersion.STRUCTURED,
                            "an unnamed source editor uses the final grammar");
                    expect(activeMain() == null && node("compile", Button.class).isDisabled(), "Compile stays disabled until a main is eligible");
                    expect(!node("save-source", Button.class).isDisabled(), "even an empty unnamed draft can be saved");
                    Tab draft = createTab();
                    expect("Untitled 2".equals(draft.getText()), "additional draft has a distinct name");
                    expect(tabs.getTabs().size() == 2 && editor(draft).getText().isEmpty(), "New appends an empty editor");
                    expect(editor(draft).getLanguageVersion() == LanguageVersion.STRUCTURED,
                            "every additional source editor uses the final grammar");
                    expect(memoryBefore.equals(memory()), "creating source tabs does not replace memory");
                });
                awaitFxDelay(screenshot == null ? 150 : 750);
                onFx(() -> {
                    expect(stage.getScene().getFocusOwner() == editor(selected()), "newly selected editor receives keyboard focus");
                    if (screenshot != null) writeSnapshot(screenshot);
                });
            });

            checkFx("unnamed main compiles before saving and naming keeps its identity", () -> {
                fresh();
                Tab main = createTab();
                SourceWorkspace.Document document = document(main);
                List<Integer> initialMemory = memory();
                editor(main).setText(PROGRAM);
                expect(activeMain() == document && main.getText().contains("(main)"), "first eligible edit activates an unnamed main");
                expect(!node("compile", Button.class).isDisabled() && field("compiledProgram") == null
                        && initialMemory.equals(memory()), "editing activates without compiling or replacing memory");
                node("compile", Button.class).fire();
                expectRange(main, "INC result;");
                CompilationResult compiled = (CompilationResult) field("compiledProgram");
                expect(compiled.sourceMap().get(machine.getInstructionPointer()).location().file() == null,
                        "unnamed compiled locations have no invented file path");
                List<Integer> before = memory();
                Path saved = directory.resolve("named-main.a26");
                application.choose(saved);
                node("save-source", Button.class).fire();
                expect(selected() == main && document(main) == document, "first save keeps the same tab and document");
                expect(saved.toRealPath().equals(document.getPath()) && PROGRAM.equals(Files.readString(saved)), "first save names and writes the main");
                expect(!document.isModified() && "named-main.a26 (main)".equals(main.getText()), "saved main is clean and retitled");
                expect(before.equals(memory()) && editor(main).getExecutionRange() == null, "naming preserves VM but invalidates old source locations");
                node("compile", Button.class).fire();
                expectRange(main, "INC result;");
                button("Step").fire();
                expect(machine.get(1) == 2 && machine.isHalted(), "named main still executes correctly");
            });

            checkFx("unnamed import callers return to main rather than another unnamed draft", () -> {
                fresh();
                Tab main = createTab();
                editor(main).setText("import \"" + library + "\";\nvar result = 1;\nbegin:\nbump(result);\nHALT;\n");
                Tab draft = createTab();
                node("compile", Button.class).fire();
                Tab imported = tabs.getTabs().stream().filter(tab -> library.equals(document(tab).getPath())).findFirst().orElseThrow();
                expect(selected() == imported, "execution follows the imported body");
                expectRange(imported, "INC value;");
                expect(editor(main).getCallerRanges().size() == 1 && editor(draft).getCallerRanges().isEmpty(), "null-file caller belongs only to unnamed main");
                List<Hyperlink> links = node("execution-callers", FlowPane.class).getChildren().stream()
                        .filter(Hyperlink.class::isInstance).map(Hyperlink.class::cast).toList();
                expect(links.size() == 1 && links.get(0).getText().contains("Untitled"), "breadcrumb names the unnamed caller");
                links.get(0).fire();
                expect(selected() == main, "caller hyperlink selects the unnamed main");
                button("Step").fire();
                expectRange(main, "HALT;");
                expect(editor(draft).getExecutionRange() == null, "separate unnamed draft never receives main execution highlights");
            });

            checkFx("New preserves an existing program and Compile still uses its main", () -> {
                fresh();
                application.openSourceFile(existing);
                Tab main = selected();
                Object compiled = field("compiledProgram");
                List<Integer> before = memory();
                IndexRange marker = editor(main).getExecutionRange();
                Tab draft = createTab();
                expect(tabs.getTabs().contains(main) && compiled == field("compiledProgram") && before.equals(memory()), "New retains tabs bytecode and mapping");
                expect(marker.equals(editor(main).getExecutionRange()), "New keeps current execution marker");
                String otherProgram = PROGRAM.replace("= 1", "= 9");
                editor(draft).setText(otherProgram);
                expect(activeMain() == document(main), "editing another eligible tab does not replace an active main");
                node("compile", Button.class).fire();
                expectRange(main, "INC result;");
                expect(before.equals(memory()) && otherProgram.equals(editor(draft).getText()), "Compile uses active main while retaining selected eligible draft text");
            });

            checkFx("empty unnamed files can be saved by button and source shortcut", () -> {
                fresh();
                Tab main = createTab();
                Path first = directory.resolve("empty-main.a26");
                application.choose(first);
                node("save-source", Button.class).fire();
                expect(Files.size(first) == 0 && !document(main).isUntitled(), "Save writes an empty unnamed draft");
                expect(activeMain() == null && node("compile", Button.class).isDisabled(), "naming a blank draft does not make it executable");
                Tab draft = createTab();
                Path second = directory.resolve("empty-draft.a26");
                application.choose(second);
                Runnable shortcut = stage.getScene().getAccelerators().get(new KeyCodeCombination(KeyCode.S, KeyCombination.SHORTCUT_DOWN));
                expect(shortcut != null, "source save shortcut is installed");
                shortcut.run();
                expect(Files.size(second) == 0 && second.toRealPath().equals(document(draft).getPath()), "shortcut saves selected unnamed draft");
                expect(node("save-source", Button.class).isDisabled(), "clean named file no longer needs saving");
            });

            checkFx("save cancellation and write failure preserve unnamed draft contents", () -> {
                fresh();
                Tab draft = createTab();
                editor(draft).setText(PROGRAM);
                String title = draft.getText();
                application.choose((Path) null);
                node("save-source", Button.class).fire();
                expect(document(draft).isUntitled() && title.equals(draft.getText()), "cancel does not name or retitle a draft");
                application.choose(directory);
                try (DialogResponse response = new DialogResponse("OK")) {
                    node("save-source", Button.class).fire();
                    expect(response.count == 1, "failed write reports an error");
                }
                expect(PROGRAM.equals(editor(draft).getText()) && document(draft).isUntitled()
                        && document(draft).isModified() && title.equals(draft.getText()), "failed save retains name text and dirty state");
            });

            checkFx("saving over another open document rejects the collision", () -> {
                fresh();
                application.openSourceFile(existing);
                Tab main = selected();
                Tab draft = createTab();
                editor(draft).setText("// separate draft\n");
                application.choose(existing);
                try (DialogResponse response = new DialogResponse("OK")) {
                    node("save-source", Button.class).fire();
                    expect(response.count == 1, "open-path collision reports an error");
                }
                expect(PROGRAM.equals(Files.readString(existing)) && PROGRAM.equals(editor(main).getText()), "collision leaves original disk and buffer intact");
                expect(document(draft).isUntitled() && "// separate draft\n".equals(editor(draft).getText()), "collision leaves new draft intact");
            });

            checkFx("Open appends and reuses source tabs without prompting or discarding edits", () -> {
                fresh();
                Tab main = createTab();
                editor(main).setText(PROGRAM);
                Tab draft = createTab();
                editor(draft).setText("// keep this draft\n");
                try (DialogResponse response = new DialogResponse("Cancel")) {
                    application.openSourceFile(existing);
                    expect(response.count == 0, "Open must not prompt to save existing dirty tabs");
                }
                Tab opened = selected();
                expect(tabs.getTabs().size() == 3 && tabs.getTabs().containsAll(List.of(main, draft)), "Open appends to existing tabs");
                expect(PROGRAM.equals(editor(main).getText()) && "// keep this draft\n".equals(editor(draft).getText()), "Open preserves all unsaved buffers");
                expect(activeMain() == document(opened) && machine.get(1) == 1, "eligible Open activates and compiles its document");
                editor(opened).setText(PROGRAM.replace("= 1", "= 7"));
                try (DialogResponse response = new DialogResponse("Cancel")) {
                    application.openSourceFile(existing);
                    expect(response.count == 0, "reopening an edited file also avoids save prompts");
                }
                expect(selected() == opened && tabs.getTabs().size() == 3, "reopening reuses the same document and tab");
                expect(machine.get(1) == 7 && editor(opened).getText().contains("= 7"), "reopening compiles the unsaved buffer rather than disk");
                expect(PROGRAM.equals(Files.readString(existing)), "Open never writes edited source to disk");
            });

            checkFx("library Open selects its tab without activating or compiling it", () -> {
                fresh();
                try (DialogResponse response = new DialogResponse("OK")) {
                    application.openSourceFile(library);
                    expect(response.count == 0, "opening a library produces no compilation error");
                }
                Tab imported = selected();
                expect(activeMain() == null && node("compile", Button.class).isDisabled(), "library alone cannot enable Compile");
                application.openSourceFile(existing);
                Tab main = selected();
                Object compiled = field("compiledProgram");
                List<Integer> before = memory();
                application.openSourceFile(library);
                expect(selected() == imported && activeMain() == document(main), "library Open only changes the browsed tab");
                clickHeader(imported, MouseButton.PRIMARY, 2);
                expect(activeMain() == document(main) && before.equals(memory()) && compiled == field("compiledProgram"),
                        "library double-click preserves active main memory and mapping");
            });

            checkFx("only a primary tab-header double-click activates an eligible program", () -> {
                fresh();
                application.openSourceFile(existing);
                Tab first = selected();
                Tab second = createTab();
                editor(second).setText(PROGRAM.replace("= 1", "= 9"));
                List<Integer> before = memory();
                clickHeader(second, MouseButton.PRIMARY, 1);
                expect(activeMain() == document(first) && before.equals(memory()), "single header click only selects a tab");
                fireClick(editor(second), MouseButton.PRIMARY, 2);
                expect(activeMain() == document(first) && before.equals(memory()), "editor double-click only edits or selects source text");
                clickHeader(second, MouseButton.MIDDLE, 2);
                expect(activeMain() == document(first) && before.equals(memory()), "non-primary header double-click cannot activate");
                clickHeader(second, MouseButton.PRIMARY, 2);
                expect(activeMain() == document(second) && machine.get(1) == 9, "primary header double-click activates and compiles selected program");
                expect(second.getText().contains("(main)") && !first.getText().contains("(main)"), "main title follows activation");
                clickHeader(first, MouseButton.PRIMARY, 2);
                expect(activeMain() == document(first) && machine.get(1) == 1, "another eligible tab can be reactivated");
            });

            checkFx("removing the main marker disables Compile until an eligible edit", () -> {
                fresh();
                application.openSourceFile(existing);
                Tab main = selected();
                List<Integer> before = memory();
                editor(main).setText("// begin: is only a comment\n");
                expect(activeMain() == null && node("compile", Button.class).isDisabled(), "removing the top-level marker clears active main");
                expect(!main.getText().contains("(main)") && field("compiledProgram") == null, "removed main loses title and stale mapping");
                Tab draft = createTab();
                editor(draft).setText("// begin:\n");
                expect(activeMain() == null, "a marker inside a comment cannot activate a draft");
                editor(draft).setText(PROGRAM.replace("= 1", "= 8"));
                expect(activeMain() == document(draft) && !node("compile", Button.class).isDisabled(), "eligible edit activates when no main exists");
                expect(before.equals(memory()) && field("compiledProgram") == null, "automatic activation does not compile");
            });

            checkFx("Save all filename cancellation keeps a dirty window open", () -> {
                fresh();
                Tab main = createTab();
                editor(main).setText(PROGRAM);
                Tab draft = createTab();
                editor(draft).setText("// keep this draft\n");
                Path saved = directory.resolve("close-main.a26");
                application.choose(saved, null);
                try (DialogResponse response = new DialogResponse("Save all")) {
                    closeRequest();
                    expect(response.count == 1, "closing dirty unnamed source prompts");
                }
                expect(stage.isShowing() && document(draft).isUntitled(), "canceling a later filename prevents close");
                expect(PROGRAM.equals(Files.readString(saved)), "successful earlier Save all writes remain saved");
                Path draftSave = directory.resolve("close-draft.a26");
                application.choose(draftSave);
                try (DialogResponse response = new DialogResponse("Save all")) {
                    closeRequest();
                    expect(response.count == 1, "retry asks to save remaining changes");
                }
                expect(!stage.isShowing() && "// keep this draft\n".equals(Files.readString(draftSave)), "saving the remaining draft permits close");
            });

            checkFx("untouched empty drafts need no prompt when opening or closing", () -> {
                fresh();
                createTab();
                createTab();
                try (DialogResponse response = new DialogResponse("Cancel")) {
                    application.openSourceFile(existing);
                    expect(response.count == 0, "blank untouched drafts do not prompt on open");
                }
                expect(existing.equals(document(selected()).getPath()) && tabs.getTabs().size() == 3,
                        "Open appends an active program beside blank drafts");
                createTab();
                try (DialogResponse response = new DialogResponse("Cancel")) {
                    closeRequest();
                    expect(response.count == 0, "blank untouched draft does not prompt on close");
                }
                expect(!stage.isShowing(), "clean workspace closes normally");
            });

            checkFx("New remains available during Run without enabling Compile", () -> {
                fresh();
                Tab main = createTab();
                editor(main).setText("var keepGoing = 1;\nbegin:\nloop: JNZ keepGoing loop;\n");
                Tab otherProgram = createTab();
                editor(otherProgram).setText(PROGRAM);
                node("compile", Button.class).fire();
                Object compiled = field("compiledProgram");
                button("Run").fire();
                try {
                    expect(field("runTimer") != null && node("compile", Button.class).isDisabled(), "fixture is running");
                    expect(!node("new-source", Button.class).isDisabled(), "New remains available while running");
                    clickHeader(otherProgram, MouseButton.PRIMARY, 2);
                    expect(activeMain() == document(main) && field("compiledProgram") == compiled,
                            "tab-header activation is guarded while running");
                    Tab draft = createTab();
                    expect(selected() == draft && editor(draft).getText().isEmpty(), "New appends and selects an empty draft during Run");
                    expect(node("compile", Button.class).isDisabled() && field("compiledProgram") == compiled,
                            "new draft neither enables Compile nor replaces running mapping");
                } finally {
                    button("Stop").fire();
                }
                expect(!node("compile", Button.class).isDisabled(), "stopping restores Compile");
            });

            check("JavaFX reports no asynchronous unnamed-source exceptions", () -> {
                if (asynchronousError.get() != null) throw new AssertionError("JavaFX event or layout failure", asynchronousError.get());
            });
        } finally {
            try {
                onFx(() -> {
                    if (application != null) application.stop();
                    for (Window window : new ArrayList<>(Window.getWindows())) window.hide();
                });
            } finally {
                Platform.exit();
                Thread.setDefaultUncaughtExceptionHandler(previousHandler);
            }
        }
        System.out.println("MachineGUINewSourceTest: " + passed + " passed, " + failed + " failed");
        if (failed != 0) throw new AssertionError(failed + " unnamed-source GUI check(s) failed");
    }

    private static void fresh() throws Exception {
        if (application != null) application.stop();
        if (stage != null) stage.hide();
        application = new TestGUI();
        stage = new Stage();
        application.start(stage);
        tabs = node("source-tabs", TabPane.class);
        machine = (Machine) field("machine");
        layout();
    }

    private static Tab createTab() {
        node("new-source", Button.class).fire();
        return selected();
    }

    private static Tab selected() { return tabs.getSelectionModel().getSelectedItem(); }
    private static SourceEditor editor(Tab tab) { return (SourceEditor) tab.getContent(); }
    private static SourceWorkspace.Document document(Tab tab) { return (SourceWorkspace.Document) tab.getUserData(); }

    private static SourceWorkspace.Document activeMain() throws Exception {
        return ((SourceWorkspace) field("sourceWorkspace")).getMainDocument();
    }

    private static void clickHeader(Tab tab, MouseButton button, int count) {
        // A real press selects the tab before its click handler receives the event.
        tabs.getSelectionModel().select(tab);
        layout();
        Node header = tabs.lookupAll(".tab").stream()
                .filter(node -> node.lookup(".tab-label") instanceof Label label && tab.getText().equals(label.getText()))
                .findFirst().orElseThrow(() -> new AssertionError("No header for " + tab.getText()));
        fireClick(header, button, count);
    }

    private static void fireClick(Node target, MouseButton button, int count) {
        Event.fireEvent(target, new MouseEvent(MouseEvent.MOUSE_CLICKED, 1, 1, 1, 1, button, count,
                false, false, false, false, false, false, false, false, false, true, null));
    }

    private static void expectRange(Tab tab, String source) {
        IndexRange range = editor(tab).getExecutionRange();
        expect(range != null && source.equals(editor(tab).getText().substring(range.getStart(), range.getEnd())), "expected execution highlight for " + source);
    }

    private static <T extends Node> T node(String id, Class<T> type) {
        Node found = stage.getScene().lookup("#" + id);
        expect(type.isInstance(found), "missing " + type.getSimpleName() + " #" + id);
        return type.cast(found);
    }

    private static Button button(String text) {
        return stage.getScene().getRoot().lookupAll(".button").stream().filter(Button.class::isInstance)
                .map(Button.class::cast).filter(button -> text.equals(button.getText())).findFirst().orElseThrow();
    }

    private static Object field(String name) throws Exception {
        Field field = MachineGUI.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(application);
    }

    private static List<Integer> memory() {
        List<Integer> values = new ArrayList<>();
        for (int i = 0; i < machine.getMemorySize(); i++) values.add(machine.get(i));
        return values;
    }

    private static void closeRequest() { Event.fireEvent(stage, new WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST)); }

    private static void layout() {
        stage.getScene().getRoot().applyCss();
        stage.getScene().getRoot().layout();
    }

    private static void awaitFxDelay(int millis) throws Exception {
        CountDownLatch settled = new CountDownLatch(1);
        onFx(() -> {
            PauseTransition delay = new PauseTransition(Duration.millis(millis));
            delay.setOnFinished(event -> settled.countDown());
            delay.play();
        });
        expect(settled.await(5, TimeUnit.SECONDS), "JavaFX animation did not settle");
    }

    private static void writeSnapshot(Path path) throws Exception {
        layout();
        WritableImage shot = stage.getScene().snapshot(null);
        BufferedImage image = new BufferedImage((int) shot.getWidth(), (int) shot.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++)
            image.setRGB(x, y, shot.getPixelReader().getArgb(x, y));
        expect(ImageIO.write(image, "png", path.toFile()), "PNG writer is available");
    }

    private static void onFx(Check action) throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> { action.run(); return null; });
        Platform.runLater(task);
        try {
            task.get(10, TimeUnit.SECONDS);
        } catch (ExecutionException error) {
            if (error.getCause() instanceof Exception exception) throw exception;
            if (error.getCause() instanceof Error cause) throw cause;
            throw new AssertionError(error.getCause());
        }
    }

    private static void checkFx(String name, Check action) { check(name, () -> onFx(action)); }

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
        if (!condition) throw new AssertionError(message);
    }

    private static final class DialogResponse implements AutoCloseable {
        private final Timeline timer;
        private int count;

        DialogResponse(String answer) {
            timer = new Timeline(new KeyFrame(Duration.millis(25), event -> {
                for (Window window : new ArrayList<>(Window.getWindows())) {
                    if (window != stage && window.isShowing() && window.getScene() != null
                            && window.getScene().getRoot() instanceof DialogPane pane) {
                        pane.getButtonTypes().stream().filter(type -> answer.equals(type.getText())).findFirst().ifPresent(type -> {
                            count++;
                            ((Button) pane.lookupButton(type)).fire();
                        });
                    }
                }
            }));
            timer.setCycleCount(Timeline.INDEFINITE);
            timer.play();
        }

        @Override public void close() { timer.stop(); }
    }
}

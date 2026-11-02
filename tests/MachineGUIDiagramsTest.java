package machine;

import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.IndexRange;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextInputControl;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javafx.stage.Window;

/** Simulator diagram integration; private files, headless JavaFX, no native chooser. */
public final class MachineGUIDiagramsTest {
    private static final String LIBRARY = "void bump(value) {\n  INC value;\n}\n";
    private static MachineGUI application;
    private static Stage stage;
    private static TabPane tabs;
    private static Machine machine;
    private static Path mainPath;
    private static Path libraryPath;
    private static String program;
    private static int passed;
    private static final AtomicReference<Throwable> fxFailure = new AtomicReference<>();
    @FunctionalInterface private interface Action { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("machine-diagrams-");
        mainPath = directory.resolve("main.a26");
        libraryPath = directory.resolve("helper.a26");
        Path extraLibraryPath = directory.resolve("extra-helper.a26");
        String extraLibrary = "void extra(value) { DEC value; }\n";
        program = "// Keep this explanation.\nimport \"" + libraryPath + "\";\n"
                + "var n = 2;\nvar spare = 0;\nbegin:\nwhile (n != 0) {\n"
                + "  DEC n;\n  bump(spare);\n}\nHALT;\n";
        Files.writeString(mainPath, program);
        Files.writeString(libraryPath, LIBRARY);
        Files.writeString(extraLibraryPath, extraLibrary);
        CountDownLatch started = new CountDownLatch(1);
        Platform.startup(() -> {
            Thread.currentThread().setUncaughtExceptionHandler((thread, error) -> fxFailure.compareAndSet(null, error));
            Platform.setImplicitExit(false);
            started.countDown();
        });
        expect(started.await(10, TimeUnit.SECONDS), "JavaFX starts");
        try {
            onFx(() -> {
                application = new MachineGUI();
                stage = new Stage();
                application.start(stage);
                stage.setX(0); stage.setY(0);
                stage.setWidth(1500); stage.setHeight(980);
                tabs = node("source-tabs", TabPane.class);
                Field field = MachineGUI.class.getDeclaredField("machine");
                field.setAccessible(true);
                machine = (Machine) field.get(application);
                application.openSourceFile(mainPath);
                tabs.getSelectionModel().select(tab(mainPath));
                layout();
            });
            pulse();

            check("diagram selection starts with source only and preserves source tab editors", () -> onFx(() -> {
                expect(node("source-diagram-mode", ComboBox.class).getValue().equals("Source only"), "diagram mode starts with source only");
                expect(node("source-diagram-editors", SplitPane.class).getItems().size() == 1, "source-only mode has one editor pane");
                expect(tab(mainPath).getContent() instanceof SourceEditor && tab(libraryPath).getContent() instanceof SourceEditor,
                        "source tabs retain their editable buffers");
            }));

            check("flowchart renders the selected final-grammar source and resolves imported helpers", () -> onFx(() -> {
                mode("Flowchart");
                expect(flowchart().isSynchronized(), flowchart().getStatusText());
                expect(flowchart().getSource().equals(program), "diagram shows the selected main buffer");
                expect(node("source-diagram-editors", SplitPane.class).getItems().size() == 2,
                        "diagram mode displays source and diagram side by side");
                expect(field(flowchart(), "DEC n;") != null && field(flowchart(), "bump(spare);") != null,
                        "flowchart contains source statements and imported macro calls");
                expect(editor(mainPath).getLanguageVersion() == LanguageVersion.STRUCTURED,
                        "simulator source highlighting uses the final grammar");
                expect(stage.getScene().lookup("#language-version") == null, "there is no grammar selector");
            }));

            check("flowchart changes preserve comments, update buffers and undo without executing or saving", () -> onFx(() -> {
                List<Integer> before = memory();
                int steps = machine.getStepCounter();
                apply(field(flowchart(), "DEC n;"), "INC n;");
                String changed = program.replace("DEC n;", "INC n;");
                expect(editor(mainPath).getText().equals(changed) && document(mainPath).getText().equals(changed),
                        "diagram edit changes the exact source statement in the editor and workspace");
                expect(document(mainPath).isModified() && tab(mainPath).getText().contains("*"), "diagram edit marks the file dirty");
                expect(flowchart().isSynchronized() && flowchart().getSource().equals(changed), "flowchart remains synchronized");
                expect(memory().equals(before) && machine.getStepCounter() == steps, "diagram editing never executes or recompiles");
                expect(editor(mainPath).getExecutionRange() == null, "diagram editing invalidates compiled execution mapping");
                expect(Files.readString(mainPath).equals(program), "diagram editing never saves implicitly");
                editor(mainPath).undo();
                expect(editor(mainPath).getText().equals(program) && flowchart().getSource().equals(program), "one native undo restores both views");
                expect(!document(mainPath).isModified(), "undo restores the saved baseline");
                editor(mainPath).replaceText(program.indexOf("DEC n;"), program.indexOf("DEC n;") + 6, "INC n;");
                expect(field(flowchart(), "INC n;") != null, "source editing rebuilds the matching flowchart statement");
                editor(mainPath).undo();
            }));

            check("Nassi–Shneiderman supports the same bidirectional source edits and native undo", () -> onFx(() -> {
                mode("Nassi");
                expect(diagram().isSynchronized(), diagram().getStatusText());
                expect(diagram().getSource().equals(program), "structured diagram follows the same source document");
                apply(field(diagram(), "DEC n;"), "INC n;");
                expect(editor(mainPath).getText().equals(program.replace("DEC n;", "INC n;")), "structured diagram updates the source statement");
                editor(mainPath).undo();
                expect(editor(mainPath).getText().equals(program) && diagram().getSource().equals(program), "undo restores source and structured diagram");
            }));

            check("both diagrams link focus without moving the source selection or changing execution", () -> {
                onFx(() -> node("compile", Button.class).fire());
                for (String choice : List.of("Flowchart", "Nassi")) {
                    onFx(() -> {
                        tabs.getSelectionModel().select(tab(mainPath));
                        mode(choice);
                        editor(mainPath).selectRange(18, 5);
                        field(activeDiagram(choice), "DEC n;").requestFocus();
                        layout();
                    });
                    pulse();
                    onFx(() -> {
                        int start = program.indexOf("DEC n;");
                        expect(new IndexRange(start, start + 6).equals(editor(mainPath).getLinkedRange()), "diagram focus links the exact source range");
                        expect(editor(mainPath).getAnchor() == 18 && editor(mainPath).getCaretPosition() == 5,
                                "diagram focus preserves the source caret and selection direction");
                        expect(stage.getScene().getFocusOwner() == field(activeDiagram(choice), "DEC n;"), "source reveal keeps diagram focus");
                        expect(editor(mainPath).getExecutionRange() != null, "focus linking preserves execution markers");
                        editor(mainPath).requestFocus();
                        editor(mainPath).positionCaret(start + 2);
                    });
                    pulse();
                    onFx(() -> {
                        IndexRange highlighted = choice.equals("Flowchart") ? flowchart().getHighlightedRange() : diagram().getHighlightedRange();
                        int offset = editor(mainPath).getCaretPosition();
                        expect(highlighted != null && highlighted.getStart() <= offset && highlighted.getEnd() >= offset,
                                "source caret highlights its matching diagram element");
                        expect(stage.getScene().getFocusOwner() == editor(mainPath), "diagram reveal keeps source focus");
                    });
                }
            });

            check("pending diagram fields survive source-only mode and switching source tabs", () -> onFx(() -> {
                for (String choice : List.of("Flowchart", "Nassi")) {
                    mode(choice);
                    TextInputControl pending = field(activeDiagram(choice), "DEC n;");
                    pending.setText("INC spare;");
                    mode("Source only");
                    expect(node("source-diagram-editors", SplitPane.class).getItems().size() == 1,
                            "source-only mode removes the diagram pane");
                    tabs.getSelectionModel().select(tab(libraryPath));
                    mode(choice);
                    expect(source(choice).equals(LIBRARY), "selected helper does not inherit the main's pending field");
                    tabs.getSelectionModel().select(tab(mainPath));
                    expect(field(activeDiagram(choice), "INC spare;") == pending,
                            "returning to the main retains the exact pending control");
                    expect(editor(mainPath).getText().equals(program), "navigation neither commits nor discards the pending source edit");
                    apply(pending, "INC spare;");
                    expect(editor(mainPath).getText().equals(program.replace("DEC n;", "INC spare;")),
                            "the retained pending field still commits to its own source");
                    editor(mainPath).undo();
                }
            }));

            check("invalid source retains a read-only last valid diagram and cannot overwrite the draft", () -> onFx(() -> {
                for (String choice : List.of("Flowchart", "Nassi")) {
                    mode(choice);
                    Parent view = activeDiagram(choice);
                    TextInputControl retained = field(view, "DEC n;");
                    String invalid = program.replace("DEC n;", "DEC missing;");
                    editor(mainPath).setText(invalid);
                    expect(!synchronizedView(choice), "unknown variable makes the diagram invalid");
                    expect(!view.lookupAll(choice.equals("Flowchart") ? ".flowchart-damaged" : ".diagram-damaged").isEmpty(),
                            "the retained diagram shows its damaged state");
                    expect(editor(mainPath).getLinkedRange() == null, "invalid source clears stale linked ranges");
                    apply(retained, "INC n;");
                    expect(editor(mainPath).getText().equals(invalid), "cached diagram fields cannot replace an invalid draft");
                    editor(mainPath).setText(program);
                    expect(synchronizedView(choice), "correcting the source restores editing");
                }
            }));

            check("selected imports have their own diagrams while the active main and VM remain unchanged", () -> onFx(() -> {
                node("compile", Button.class).fire();
                List<Integer> before = memory();
                tabs.getSelectionModel().select(tab(libraryPath));
                for (String choice : List.of("Flowchart", "Nassi")) {
                    mode(choice);
                    expect(synchronizedView(choice), "helper diagram validates in the active main context: " + status(choice));
                    expect(source(choice).equals(LIBRARY), "helper diagram contains its exact source without a fake main");
                    expect(field(activeDiagram(choice), "INC value;") != null, "helper body is editable");
                    expect(tab(mainPath).getText().contains("(main)") && !tab(libraryPath).getText().contains("(main)"),
                            "selecting an imported diagram leaves the active main unchanged");
                }
                expect(memory().equals(before), "diagram selection preserves machine memory");
                apply(field(diagram(), "INC value;"), "DEC value;");
                expect(editor(libraryPath).getText().equals(LIBRARY.replace("INC value;", "DEC value;")), "helper diagram edits only its own buffer");
                expect(editor(mainPath).getText().equals(program) && document(libraryPath).isModified(), "main source remains intact while helper becomes dirty");
                expect(Files.readString(libraryPath).equals(LIBRARY), "helper diagram editing leaves disk untouched");
                tabs.getSelectionModel().select(tab(mainPath));
                expect(diagram().isSynchronized(), "main diagram resolves the unsaved helper buffer");
                editor(libraryPath).undo();
                expect(editor(libraryPath).getText().equals(LIBRARY), "native helper undo restores its own source");
            }));

            check("the diagrams reject legacy do labels while accepting structured post-tested loops", () -> onFx(() -> {
                tabs.getSelectionModel().select(tab(mainPath));
                String structured = "var n=2; begin: do { DEC n; } while(n!=0); HALT;";
                String legacy = "var n=2; begin: do: DEC n; JNZ n do; HALT;";
                for (String choice : List.of("Flowchart", "Nassi")) {
                    mode(choice);
                    editor(mainPath).setText(structured);
                    expect(synchronizedView(choice), "final structured loop is valid: " + status(choice));
                    editor(mainPath).setText(legacy);
                    expect(!synchronizedView(choice) && editor(mainPath).getText().equals(legacy), "legacy identifier profile is unavailable");
                    expect(editor(mainPath).getLanguageVersion() == LanguageVersion.STRUCTURED, "diagram validation never changes the source profile");
                    editor(mainPath).setText(program);
                }
            }));

            check("previewing a new import opens its source buffer without compiling or changing the main", () -> onFx(() -> {
                mode("Flowchart");
                tabs.getSelectionModel().select(tab(mainPath));
                List<Integer> before = memory();
                String withImport = "import \"" + extraLibraryPath + "\";\n" + program;
                editor(mainPath).setText(withImport);
                expect(flowchart().isSynchronized(), flowchart().getStatusText());
                expect(editor(extraLibraryPath).getText().equals(extraLibrary), "newly resolved import has a source tab");
                expect(tabs.getSelectionModel().getSelectedItem() == tab(mainPath) && tab(mainPath).getText().contains("(main)"),
                        "import discovery neither switches the selected document nor changes the main");
                expect(memory().equals(before), "import discovery never compiles or replaces the machine image");
                editor(mainPath).setText(program);
            }));

            check("unsaved imported errors invalidate the main diagram and recover without recompiling", () -> onFx(() -> {
                tabs.getSelectionModel().select(tab(mainPath));
                List<Integer> before = memory();
                for (String choice : List.of("Flowchart", "Nassi")) {
                    mode(choice);
                    expect(synchronizedView(choice), status(choice));
                    editor(libraryPath).setText(LIBRARY.replace("INC value;", "INC missing;"));
                    expect(!synchronizedView(choice), "main diagram validation sees errors in the unsaved imported buffer");
                    expect(source(choice).equals(program) && editor(mainPath).getText().equals(program),
                            "an import error never replaces the selected main source");
                    editor(libraryPath).setText(LIBRARY);
                    expect(synchronizedView(choice), "correcting the imported buffer refreshes the main diagram");
                }
                expect(memory().equals(before), "diagram validation never loads a new machine image");
                expect(Files.readString(libraryPath).equals(LIBRARY), "validation never saves imports");
            }));

            check("explicit jumps stay editable as flowcharts and never become structured diagram blocks", () -> onFx(() -> {
                String jumps = "var n=2; begin: again: DEC n; JNZ n again; HALT;";
                mode("Flowchart");
                editor(mainPath).setText(jumps);
                expect(flowchart().isSynchronized(), flowchart().getStatusText());
                expect(flowchart().lookup(".flowchart-jump-variable") != null, "explicit jump is an editable flowchart decision");
                mode("Nassi");
                expect(!diagram().isSynchronized(), "structured diagram reports an unrepresentable explicit jump");
                expect(editor(mainPath).getText().equals(jumps), "switching diagram type never rewrites program control flow");
                editor(mainPath).setText(program);
            }));

            check("JNZ to the implicit begin entry renders, links, edits and executes without rewriting source", () -> {
                String exact = "var x = 5;\n\nbegin:\n\tDEC x;\n\tJNZ x begin;\nHALT;\n";
                onFx(() -> {
                    mode("Flowchart");
                    editor(mainPath).setText(exact);
                    editor(mainPath).selectRange(8, 4);
                    layout();
                    expect(flowchart().isSynchronized(), flowchart().getStatusText());
                    Flowchart graph = Flowchart.from(StructuredDiagram.parse(exact, LanguageVersion.STRUCTURED, true));
                    Integer entry = graph.labels().get("begin");
                    expect(entry != null && flowchart().lookup("#flowchart-junction-" + entry) != null,
                            "implicit begin is a visible incoming junction for the first instruction");
                    expect(editor(mainPath).getText().equals(exact) && flowchart().getSource().equals(exact),
                            "rendering the built-in entry preserves tabs, line breaks and all source text");
                    expect(editor(mainPath).getAnchor() == 8 && editor(mainPath).getCaretPosition() == 4,
                            "diagram rendering preserves source selection and caret");
                    editor(mainPath).requestFocus();
                    editor(mainPath).positionCaret(exact.indexOf("begin:") + 2);
                });
                pulse();
                onFx(() -> {
                    IndexRange highlighted = flowchart().getHighlightedRange();
                    int caret = editor(mainPath).getCaretPosition();
                    expect(highlighted != null && highlighted.getStart() <= caret && highlighted.getEnd() > caret,
                            "caret on begin links to its flowchart entry");
                    TextInputControl operand = (TextInputControl) flowchart().lookup(".flowchart-jump-variable");
                    apply(operand, "x[0]");
                    expect(editor(mainPath).getText().equals(exact.replace("JNZ x begin;", "JNZ x[0] begin;")),
                            "editing the jump operand leaves its built-in target and unrelated source unchanged");
                    expect(flowchart().isSynchronized(), flowchart().getStatusText());
                    editor(mainPath).undo();
                    expect(editor(mainPath).getText().equals(exact) && flowchart().getSource().equals(exact),
                            "one undo restores the exact original source and graph");
                    node("compile", Button.class).fire();
                    machine.step(100);
                    expect(machine.isHalted() && machine.get(1) == 0,
                            "the compiled backward jump reaches HALT after decrementing x to zero");
                    expect(editor(mainPath).getText().equals(exact), "execution does not rewrite the source");
                    if (args.length > 0) snapshot(Path.of(args[0] + ".begin.png"));
                    editor(mainPath).setText(program);
                });
            });

            if (args.length > 0) {
                for (String choice : List.of("Flowchart", "Nassi")) {
                    for (boolean wide : List.of(false, true)) {
                        onFx(() -> {
                            mode("Source only");
                            // A fresh native peer avoids Monocle's stale pixel buffer on live resize.
                            stage.hide();
                            stage.setWidth(wide ? 1500 : 1100);
                            stage.setHeight(wide ? 980 : 650);
                            stage.show();
                        });
                        pulse();
                        onFx(() -> mode(choice));
                        pulse();
                        onFx(() -> snapshot(Path.of(args[0] + "." + choice.toLowerCase()
                                + (wide ? "-wide.png" : "-default.png"))));
                    }
                }
            }
            check("JavaFX reports no asynchronous diagram exceptions", () -> onFx(() -> { }));
            System.out.println("MachineGUIDiagramsTest: " + passed + " passed, 0 failed");
        } finally {
            try {
                onFx(() -> {
                    if (application != null) application.stop();
                    for (Window window : new ArrayList<>(Window.getWindows())) window.hide();
                });
            } finally { Platform.exit(); }
        }
    }

    private static Parent activeDiagram(String choice) { return choice.equals("Flowchart") ? flowchart() : diagram(); }
    private static boolean synchronizedView(String choice) { return choice.equals("Flowchart") ? flowchart().isSynchronized() : diagram().isSynchronized(); }
    private static String source(String choice) { return choice.equals("Flowchart") ? flowchart().getSource() : diagram().getSource(); }
    private static String status(String choice) { return choice.equals("Flowchart") ? flowchart().getStatusText() : diagram().getStatusText(); }
    private static FlowchartView flowchart() { return node("machine-flowchart", FlowchartView.class); }
    private static StructuredDiagramView diagram() { return node("machine-structured-diagram", StructuredDiagramView.class); }
    private static SourceEditor editor(Path path) { return (SourceEditor) tab(path).getContent(); }
    private static SourceWorkspace.Document document(Path path) { return (SourceWorkspace.Document) tab(path).getUserData(); }
    private static Tab tab(Path path) {
        return tabs.getTabs().stream().filter(tab -> tab.getUserData() instanceof SourceWorkspace.Document document && path.equals(document.getPath()))
                .findFirst().orElseThrow(() -> new AssertionError("missing source tab " + path));
    }
    private static void mode(String prefix) {
        ComboBox<?> choices = node("source-diagram-mode", ComboBox.class);
        for (int index = 0; index < choices.getItems().size(); ++index) {
            if (choices.getItems().get(index).toString().startsWith(prefix)) {
                choices.getSelectionModel().select(index); layout(); return;
            }
        }
        throw new AssertionError("missing diagram choice " + prefix);
    }
    private static TextInputControl field(Parent view, String text) {
        return view.lookupAll(view instanceof FlowchartView ? ".flowchart-edit" : ".diagram-edit").stream()
                .filter(TextInputControl.class::isInstance).map(TextInputControl.class::cast)
                .filter(field -> field.getText().equals(text)).findFirst()
                .orElseThrow(() -> new AssertionError("missing diagram field " + text));
    }
    private static void apply(TextInputControl field, String text) {
        field.setText(text); field.positionCaret(Math.min(2, text.length())); field.fireEvent(new ActionEvent());
    }
    private static <T extends Node> T node(String id, Class<T> type) {
        Node found = stage.getScene().lookup("#" + id);
        expect(type.isInstance(found), "missing " + id); return type.cast(found);
    }
    private static List<Integer> memory() {
        List<Integer> result = new ArrayList<>();
        for (int address = 0; address < machine.getMemorySize(); ++address) result.add(machine.get(address));
        return result;
    }
    private static void layout() { stage.getScene().getRoot().applyCss(); stage.getScene().getRoot().layout(); }
    private static void pulse() throws Exception {
        CountDownLatch rendered = new CountDownLatch(1);
        onFx(() -> new AnimationTimer() {
            private int frames;
            @Override public void handle(long now) { if (++frames == 2) { stop(); rendered.countDown(); } }
        }.start());
        expect(rendered.await(10, TimeUnit.SECONDS), "JavaFX renders layout pulses");
        onFx(MachineGUIDiagramsTest::layout);
    }
    private static void snapshot(Path path) throws Exception {
        layout(); WritableImage shot = stage.getScene().snapshot(null);
        BufferedImage image = new BufferedImage((int) shot.getWidth(), (int) shot.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.getHeight(); ++y) for (int x = 0; x < image.getWidth(); ++x) image.setRGB(x, y, shot.getPixelReader().getArgb(x, y));
        expect(ImageIO.write(image, "png", path.toFile()), "PNG writer is available");
    }
    private static void onFx(Action action) throws Exception {
        FutureTask<Void> future = new FutureTask<>(() -> { action.run(); return null; });
        Platform.runLater(future); future.get(15, TimeUnit.SECONDS);
        Throwable failure = fxFailure.getAndSet(null);
        if (failure != null) throw new AssertionError("uncaught JavaFX exception", failure);
    }
    private static void check(String name, Action action) throws Exception { action.run(); ++passed; System.out.println("PASS " + name); }
    private static void expect(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}

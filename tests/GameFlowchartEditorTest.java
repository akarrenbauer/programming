package machine;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;

import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ListView;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;
import javafx.stage.Window;

/** First-world flowchart editing, validation, profile changes and cross-world draft isolation. */
public final class GameFlowchartEditorTest {
    private static final String LOOP = "// Keep the label and jump explanation.\nvar x = 2;\nvar y = 1;\nbegin:\n"
            + "again: DEC x;\nJNZ x again;\ndone: HALT;\n";
    private static final String REWRITTEN = "var dummy = 0;\nvar x = 0;\nbegin:\nINC dummy;\n;\n"
            + "do {\n  DEC x;\n} while (x != 0);\nHALT;\n";
    private static LanguageGame game;
    private static Stage stage;
    private static int passed;
    private static final AtomicReference<Throwable> fxFailure = new AtomicReference<>();
    @FunctionalInterface private interface Check { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        Locale original = Locale.getDefault(Locale.Category.DISPLAY);
        Locale.setDefault(Locale.Category.DISPLAY, Locale.ENGLISH);
        Path directory = Files.createTempDirectory("game-flowchart-editor-");
        Path fresh = directory.resolve("fresh.properties");
        Path rewrite = directory.resolve("rewrite.properties");
        Path boundary = directory.resolve("boundary.properties");
        profile(rewrite, 2, 2);
        profile(boundary, 8, 7);
        CountDownLatch started = new CountDownLatch(1);
        Platform.startup(() -> {
            Thread.currentThread().setUncaughtExceptionHandler((thread, error) -> fxFailure.compareAndSet(null, error));
            Platform.setImplicitExit(false);
            started.countDown();
        });
        expect(started.await(10, TimeUnit.SECONDS), "JavaFX starts");
        try {
            onFx(() -> open(fresh));
            check("first world opens source and editable flowchart with explicit jumps still available", () -> {
                expect(node("game-program-editors", SplitPane.class).getItems().size() == 2, "two synchronized views are present from level one");
                expect(stage.getScene().lookup("#game-diagram") == null, "structured diagram is not the first world's editor");
                expect(flowchart().isSynchronized() && flowchart().getSource().equals(editor().getText()), "starter has a synchronized flowchart");
                expect(editor().getLanguageVersion() == LanguageVersion.BEFORE_DO_WHILE, "first lesson retains the earlier identifier profile");
                expect(node("game-grammar", GameGrammarView.class).getText().contains("JNZ Variable Label"), "jump syntax remains available");
                editor().setText(LOOP);
                expect(flowchart().isSynchronized() && flowchart().getSource().equals(LOOP), "labeled jump program is accepted");
                expect(!flowchart().lookupAll(".flowchart-decision").isEmpty(), "JNZ has a decision shape");
                expect(!flowchart().lookupAll(".flowchart-edge").isEmpty()
                                && !flowchart().lookupAll(".flowchart-arrowhead").isEmpty(), "execution paths have arrows");
                expect(field(".flowchart-jump-variable", "x") != null
                                && field(".flowchart-label-edit", "again:") != null && field("DEC x;") != null
                                && instruction("JNZ x again;") != null,
                        "jump operand, destination junction label and instruction pencil remain editable");
                expect(flowchart().lookupAll(".flowchart-jump-target").isEmpty(), "no duplicate target label field remains below the diamond");
                expect(((Label) flowchart().lookup(".flowchart-comparison-operator")).getText().equals("≠"),
                        "backward jump displays the nonzero comparison");
                expect(flowchart().lookup(".flowchart-decision").lookupAll(".flowchart-edit").stream()
                                .filter(TextInputControl.class::isInstance).map(TextInputControl.class::cast)
                                .noneMatch(control -> control.getText().contains("JNZ")),
                        "decision displays a comparison instead of a raw jump instruction");
                if (args.length > 0) {
                    screenshot(Path.of(args[0]));
                    writeImage(flowchart().lookup("#flowchart-main").snapshot(null, null), Path.of(args[0] + ".graph.png"));
                }
            });

            check("flowchart statement edits change one source range and native undo updates both views", () -> {
                TextInputControl decrement = field("DEC x;");
                apply(decrement, "INC x;");
                expect(editor().getText().equals(LOOP.replace("DEC x;", "INC x;")), "diagram edit preserves comments and labels");
                expect(flowchart().isSynchronized() && flowchart().getSource().equals(editor().getText()), "source callback remains synchronized");
                editor().undo();
                expect(editor().getText().equals(LOOP) && flowchart().getSource().equals(LOOP), "source undo also restores flowchart");
                String commented = "var x=1;\nbegin:\nagain: // preserve this label comment\n  DEC x;\nJNZ x again;\nHALT;\n";
                editor().setText(commented);
                TextInputControl label = flowchart().lookupAll(".flowchart-label-edit").stream()
                        .filter(TextInputControl.class::isInstance).map(TextInputControl.class::cast)
                        .filter(control -> control.getText().strip().equals("again:")).findFirst().orElseThrow();
                expect(label instanceof TextField && !label.getText().contains("\n"), "entry label remains a single-line field even with source trivia");
                label.setText(label.getText().replace("again:", "loop:"));
                editInstruction("JNZ x again;", "JNZ x loop;");
                expect(editor().getText().equals(commented.replace("again:", "loop:").replace("JNZ x again;", "JNZ x loop;")),
                        "renaming a label and target preserves source comments, line breaks and indentation");
                editor().undo();
                expect(editor().getText().equals(commented), "native undo restores the complete label edit transaction");
                editor().setText(LOOP);
            });

            check("editing a jump operand and target updates the shared source and remains undoable", () -> {
                apply(field(".flowchart-jump-variable", "x"), "y");
                expect(editor().getText().equals(LOOP.replace("JNZ x again;", "JNZ y again;")), "operand edit changes only the jump variable");
                expect(flowchart().isSynchronized() && field(".flowchart-jump-variable", "y") != null,
                        "changed condition operand remains editable");
                editor().undo();
                expect(editor().getText().equals(LOOP) && flowchart().isSynchronized(), "undo restores original jump variable");
                editInstruction("JNZ x again;", "JNZ x done;");
                expect(editor().getText().equals(LOOP.replace("JNZ x again;", "JNZ x done;")), "jump destination is edited in source");
                expect(flowchart().isSynchronized() && instruction("JNZ x done;") != null, "updated branch remains editable through the instruction pencil");
                expect(((Label) flowchart().lookup(".flowchart-comparison-operator")).getText().equals("="),
                        "retargeting forward changes the displayed comparison to equality");
                editor().undo();
                expect(editor().getText().equals(LOOP) && flowchart().isSynchronized(), "undo restores backward jump");
                expect(((Label) flowchart().lookup(".flowchart-comparison-operator")).getText().equals("≠"),
                        "undo also restores the direction-derived comparison");
            });

            check("invalid code retains the last valid flowchart in red without allowing stale edits", () -> {
                TextInputControl retainedJump = field(".flowchart-jump-variable", "x");
                Button retainedInstruction = instruction("JNZ x again;");
                Node retainedDecision = flowchart().lookup(".flowchart-decision");
                String invalid = LOOP.replace("JNZ x again;", "JNZ x missing;");
                editor().setText(invalid);
                expect(!flowchart().isSynchronized() && flowchart().lookup(".flowchart-decision") == retainedDecision,
                        "unresolved jump target keeps the last valid graph");
                expect(!flowchart().lookupAll(".flowchart-damaged").isEmpty(), "damaged graph nodes are marked");
                expect(flowchart().getStatusText().contains("missing"), "failure identifies the unresolved target");
                expect(!retainedJump.isEditable() && retainedInstruction.isDisabled(), "cached operand and complete-instruction editor are read-only");
                apply(retainedJump, "y");
                retainedInstruction.fire();
                expect(editor().getText().equals(invalid), "cached controls cannot overwrite invalid current code");
                expect(rawInstruction() == null, "a cached pencil cannot open an instruction dialog");
                editor().setText(LOOP);
                expect(flowchart().isSynchronized() && flowchart().lookupAll(".flowchart-damaged").isEmpty(), "correction restores editing and removes damage");
            });

            check("a flowchart cannot unlock structured syntax before the lesson awards it", () -> {
                String locked = "var x=2; begin: while (x != 0) { DEC x; } HALT;";
                editor().setText(locked);
                expect(!flowchart().isSynchronized(), "unearned while remains invalid despite diagram support");
                expect(flowchart().getStatusText().toLowerCase().contains("locked"), "locked feature is explained");
                expect(editor().getText().equals(locked) && !flowchart().lookupAll(".flowchart-damaged").isEmpty(), "draft is retained beside marked prior graph");
                editor().setText(LOOP);
            });

            check("the do-while rewrite changes flowchart language profile and undo restores legacy labels", () -> {
                open(rewrite);
                String legacy = GameCatalog.levels().get(2).starter();
                expect(editor().getText().equals(legacy) && editor().getLanguageVersion() == LanguageVersion.BEFORE_DO_WHILE,
                        "working labeled-loop starter retains its legacy profile");
                expect(flowchart().isSynchronized() && instruction("JNZ x do;") != null
                                && flowchart().getGraph().labels().containsKey("do"), "do remains a usable jump label before the rewrite");
                editor().replaceText(0, editor().getLength(), REWRITTEN);
                expect(editor().getLanguageVersion() == LanguageVersion.STRUCTURED && flowchart().isSynchronized(),
                        "rewritten loop updates the compiler and flowchart profile together");
                expect(field(".flowchart-condition-variable", "x") != null
                                && field(".flowchart-condition-literal", "0") != null && flowchart().getSource().equals(REWRITTEN),
                        "structured loop condition operands are editable");
                expect(flowchart().lookupAll(".flowchart-node").stream().map(Node::getUserData)
                                .filter(Flowchart.Vertex.class::isInstance).map(Flowchart.Vertex.class::cast)
                                .noneMatch(vertex -> vertex.kind() == Flowchart.Kind.JOIN && vertex.text().equals("do")),
                        "rewritten do-while uses its body and condition without a synthetic do action box");
                editor().undo();
                expect(editor().getText().equals(legacy) && editor().getLanguageVersion() == LanguageVersion.BEFORE_DO_WHILE
                                && flowchart().isSynchronized() && instruction("JNZ x do;") != null,
                        "undo restores both the earlier spelling and its valid flowchart");
                TextInputControl loopLabel = flowchart().lookupAll(".flowchart-label-edit").stream()
                        .filter(TextInputControl.class::isInstance).map(TextInputControl.class::cast)
                        .filter(control -> control.getText().strip().equals("do:")).findFirst().orElseThrow();
                loopLabel.setText("do");
                editInstruction("JNZ x do;", "while (x != 0);");
                String graphRewrite = legacy.replace("do:", "do").replace("JNZ x do;", "while (x != 0);");
                expect(editor().getText().equals(graphRewrite) && editor().getLanguageVersion() == LanguageVersion.STRUCTURED
                                && flowchart().isSynchronized() && field(".flowchart-condition-variable", "x") != null,
                        "applying both flowchart edits switches profile atomically and updates source: "
                                + editor().getText().replace("\n", "\\n") + " / " + flowchart().getStatusText());
                editor().undo();
                expect(editor().getText().equals(legacy) && editor().getLanguageVersion() == LanguageVersion.BEFORE_DO_WHILE
                                && flowchart().isSynchronized() && instruction("JNZ x do;") != null,
                        "one source undo restores the whole diagram-side rewrite and legacy profile");
                node("game-levels", ListView.class).getSelectionModel().select(1);
                expect(editor().getLanguageVersion() == LanguageVersion.BEFORE_DO_WHILE && flowchart().isSynchronized(),
                        "revisiting the earlier block lesson still allows do labels after earning do/while");
            });

            check("switching worlds clears stale graph controls and preserves each source draft", () -> {
                open(boundary);
                node("game-levels", ListView.class).getSelectionModel().select(0);
                editor().setText(LOOP);
                FlowchartView old = flowchart();
                TextInputControl stale = field("DEC x;");
                @SuppressWarnings("unchecked") ComboBox<String> worlds = node("game-worlds", ComboBox.class);
                worlds.setValue(GameWorld.STRUCTURED_PROGRAMMING.title());
                String structuredStarter = editor().getText();
                StructuredDiagramView structured = node("game-diagram", StructuredDiagramView.class);
                expect(structured.isSynchronized() && node("game-program-editors", SplitPane.class).getItems().size() == 2,
                        "world two shows source and Nassi–Shneiderman diagram");
                expect(old.getSource().isEmpty() && old.lookupAll(".flowchart-node").isEmpty(), "hidden flowchart cache is cleared");
                apply(stale, "INC x;");
                expect(editor().getText().equals(structuredStarter), "detached flowchart controls cannot change another world's program");
                node("game-levels", ListView.class).getSelectionModel().select(0);
                expect(flowchart().isSynchronized() && editor().getText().equals(LOOP), "returning restores foundation source and graph");
                expect(structured.getSource().isEmpty() && structured.lookupAll(".diagram-node").isEmpty(), "returning clears hidden structured cache");
                game.stop();
                expect(new GameProgress(boundary, GameCatalog.levels()).draft(GameCatalog.levels().get(0)).equals(LOOP),
                        "only the shared source is needed to persist the flowchart draft");
            });
            System.out.println("GameFlowchartEditorTest: " + passed + " passed, 0 failed");
        } finally {
            onFx(() -> { if (game != null) game.stop(); if (stage != null) stage.close(); });
            Platform.exit();
            Locale.setDefault(Locale.Category.DISPLAY, original);
        }
    }

    private static void profile(Path path, int completed, int selected) throws Exception {
        GameProgress progress = new GameProgress(path, GameCatalog.levels());
        for (int index = 0; index < completed; index++) {
            GameLevel level = GameCatalog.levels().get(index);
            progress.complete(level, level.starter());
        }
        progress.setSelectedIndex(selected);
        progress.save();
    }

    private static void open(Path profile) throws Exception {
        if (game != null) game.stop();
        if (stage != null) stage.close();
        game = new LanguageGame() { @Override Path progressPath() { return profile; } };
        stage = new Stage();
        game.start(stage);
        stage.getScene().getRoot().applyCss();
        stage.getScene().getRoot().layout();
    }

    private static SourceEditor editor() { return node("game-editor", SourceEditor.class); }
    private static FlowchartView flowchart() { return node("game-flowchart", FlowchartView.class); }
    private static TextInputControl field(String text) {
        return field(".flowchart-edit", text);
    }
    private static TextInputControl field(String selector, String text) {
        return flowchart().lookupAll(selector).stream().filter(TextInputControl.class::isInstance)
                .map(TextInputControl.class::cast).filter(field -> field.getText().equals(text)
                        || selector.equals(".flowchart-label-edit") && field.getText().strip().equals(text)).findFirst().orElseThrow();
    }

    private static Button instruction(String command) {
        Flowchart graph = flowchart().getGraph();
        Flowchart.Vertex jump = graph.vertices().stream().filter(vertex -> graph.jumpInfo(vertex) != null
                && vertex.owner().editText().equals(command)).findFirst().orElseThrow();
        Node button = flowchart().lookup("#flowchart-jump-source-" + jump.owner().start());
        expect(button instanceof Button, "complete jump instruction is editable through its pencil");
        return (Button) button;
    }

    private static TextInputControl rawInstruction() {
        return Window.getWindows().stream().map(Window::getScene).filter(java.util.Objects::nonNull)
                .map(scene -> scene.lookup(".flowchart-jump-raw")).filter(TextInputControl.class::isInstance)
                .map(TextInputControl.class::cast).findFirst().orElse(null);
    }

    private static void editInstruction(String command, String replacement) {
        instruction(command).fire();
        TextInputControl raw = rawInstruction();
        expect(raw != null && raw.getText().equals(command), "instruction dialog contains the current command");
        raw.setText(replacement);
        raw.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
        expect(rawInstruction() == null, "accepted instruction closes its dialog: " + flowchart().getStatusText());
    }

    private static void apply(TextInputControl field, String text) {
        field.setText(text);
        field.positionCaret(Math.min(2, text.length()));
        expect(field instanceof TextField, "single-line fixture uses a text field");
        field.fireEvent(new ActionEvent());
    }

    private static <T extends Node> T node(String id, Class<T> type) {
        Node found = stage.getScene().lookup("#" + id);
        expect(type.isInstance(found), "missing " + id);
        return type.cast(found);
    }
    private static void screenshot(Path path) throws Exception {
        stage.getScene().getRoot().applyCss();
        stage.getScene().getRoot().layout();
        writeImage(stage.getScene().snapshot(null), path);
    }
    private static void writeImage(WritableImage shot, Path path) throws Exception {
        BufferedImage image = new BufferedImage((int) shot.getWidth(), (int) shot.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
            image.setRGB(x, y, shot.getPixelReader().getArgb(x, y));
        }
        expect(ImageIO.write(image, "png", path.toFile()), "PNG writer");
    }
    private static void check(String name, Check action) throws Exception {
        onFx(action);
        passed++;
        System.out.println("PASS " + name);
    }
    private static void onFx(Check action) throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> { action.run(); return null; });
        Platform.runLater(task);
        task.get(20, TimeUnit.SECONDS);
        Throwable failure = fxFailure.getAndSet(null);
        if (failure != null) throw new AssertionError("Uncaught JavaFX error", failure);
    }
    private static void expect(boolean value, String message) { if (!value) throw new AssertionError(message); }
}

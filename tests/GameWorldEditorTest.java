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
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;

/** Integration of world navigation, both editors, undo and the persisted shared draft. */
public final class GameWorldEditorTest {
    private static final String SOURCE = "// Keep this explanation.\nvar n = 2;\nvar result = 0;\nbegin:\n"
            + "while (n != 0) {\n    DEC n;\n}\n"
            + "if (n == 0) {\n    INC result;\n} else {\n    DEC result;\n}\n"
            + "do {\n    NOP;\n} while (n != 0);\nHALT;\n";
    private static LanguageGame game;
    private static Stage stage;
    private static int passed;
    private static final AtomicReference<Throwable> fxFailure = new AtomicReference<>();
    private interface Action { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.Category.DISPLAY, Locale.ENGLISH);
        Path profile = Files.createTempDirectory("game-world-editor-").resolve("progress.properties");
        GameProgress progress = new GameProgress(profile, GameCatalog.levels());
        for (int index = 0; index < 8; index++) {
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
            onFx(() -> open(profile));
            check("world boundary exposes the newly unlocked editor", () -> {
                expect(node("game-program-editors", SplitPane.class).getItems().size() == 2, "foundations show source and flowchart");
                FlowchartView foundation = node("game-flowchart", FlowchartView.class);
                expect(foundation.isSynchronized() && foundation.getSource().equals(editor().getText()), "foundation flowchart shares its current draft");
                expect(node("game-worlds", ComboBox.class).getItems().size() == 6, "six navigable worlds");
                expect(node("next-level", Button.class).getText().equals("Enter world 2 →"), "transition button names the next world");
                expect(node("game-unlocks", Label.class).getText().contains("Nassi"), "reward announces diagram world");
                node("next-level", Button.class).fire();
                expect(node("game-program-editors", SplitPane.class).getItems().size() == 2, "both editors visible");
                expect(!node("game-program-editors", SplitPane.class).getItems().contains(foundation)
                                && stage.getScene().lookup("#game-flowchart") == null,
                        "structured world replaces the flowchart rather than adding a third editor");
                expect(foundation.getSource().isEmpty() && foundation.lookupAll(".flowchart-node").isEmpty(),
                        "entering the structured world clears the hidden flowchart cache");
                expect(node("game-world", Label.class).getText().contains("World 2"), "world heading updates");
                expect(!node("game-grammar", GameGrammarView.class).getText().contains("JNZ Variable Label"), "grammar suspends explicit jumps");
                expect(diagram().isSynchronized(), "new world's starter has an editable diagram");
                expect(GameCatalog.levels().get(8).id().equals("while-practice"), "while rewrite immediately follows the unlock");
                expect(editor().getText().equals(GameCatalog.levels().get(8).starter()), "the first structured task starts from the working guarded loop");
                stage.getScene().getRoot().applyCss();
                stage.getScene().getRoot().layout();
                expect(diagram().lookupAll(".diagram-if").size() == 1
                                && diagram().lookupAll(".diagram-do-while").size() == 1
                                && diagram().lookupAll(".diagram-while").isEmpty(),
                        "the starter diagram shows an if surrounding a post-test loop");
            });
            check("rewriting the new while exercise replaces its diagram and undo restores it", () -> {
                String starter = editor().getText();
                String solution = "var n = 0;\nvar count = 0;\nbegin:\nwhile (n != 0) {\n  DEC n;\n  INC count;\n}\nHALT;\n";
                Node oldDecision = diagram().lookup(".diagram-if");
                editor().replaceText(0, editor().getLength(), solution);
                expect(diagram().isSynchronized() && diagram().getSource().equals(solution), "rewrite synchronizes both editors");
                expect(diagram().lookupAll(".diagram-while").size() == 1
                                && diagram().lookupAll(".diagram-if").isEmpty()
                                && diagram().lookupAll(".diagram-do-while").isEmpty(),
                        "one while box replaces the if and do/while boxes");
                expect(oldDecision.getScene() == null, "old decision controls are detached");
                editor().undo();
                expect(editor().getText().equals(starter) && diagram().getSource().equals(starter), "undo restores the working starter exactly");
                expect(diagram().lookupAll(".diagram-if").size() == 1
                                && diagram().lookupAll(".diagram-do-while").size() == 1
                                && diagram().lookupAll(".diagram-while").isEmpty(),
                        "undo restores both original diagram structures");
            });
            check("code edits update nested sequence selection and loop diagrams", () -> {
                editor().setText(SOURCE);
                expect(diagram().getSource().equals(SOURCE) && diagram().isSynchronized(), "diagram shares the source");
                expect(diagram().lookupAll(".diagram-if").size() == 1, "decision present");
                expect(diagram().lookupAll(".diagram-while").size() == 1, "pre-test loop present");
                expect(diagram().lookupAll(".diagram-do-while").size() == 1, "post-test loop present");
                stage.getScene().getRoot().applyCss();
                stage.getScene().getRoot().layout();
                expect(editor().localToScene(editor().getBoundsInLocal()).getMaxX()
                        <= diagram().localToScene(diagram().getBoundsInLocal()).getMinX(), "editors laid out side by side");
            });
            check("diagram edits change source and native undo updates both views", () -> {
                TextField field = diagram().lookupAll(".diagram-edit").stream().filter(TextField.class::isInstance)
                        .map(TextField.class::cast).filter(control -> control.getText().equals("DEC n;")).findFirst().orElseThrow();
                field.setText("INC n;");
                field.positionCaret(3);
                field.fireEvent(new ActionEvent());
                expect(editor().getText().equals(SOURCE.replace("DEC n;", "INC n;")), "diagram edits exactly one source span");
                expect(diagram().getSource().equals(editor().getText()), "callback echo stays synchronized");
                editor().undo();
                expect(editor().getText().equals(SOURCE) && diagram().getSource().equals(SOURCE), "undo restores source and diagram");
            });
            check("unfinished code keeps its last valid diagram with damaged structures marked", () -> {
                Node decision = diagram().lookup(".diagram-if");
                TextField retained = diagram().lookupAll(".diagram-edit").stream().filter(TextField.class::isInstance)
                        .map(TextField.class::cast).filter(control -> control.getText().equals("DEC n;")).findFirst().orElseThrow();
                String incomplete = SOURCE.replace("while (n != 0)", "while (n !=");
                editor().setText(incomplete);
                expect(!diagram().isSynchronized() && !diagram().lookupAll(".diagram-edit").isEmpty(), "last diagram remains visible");
                expect(diagram().lookup(".diagram-if") == decision, "the unaffected decision retains its diagram node");
                expect(!diagram().lookupAll(".diagram-damaged").isEmpty(), "damaged loop structures are marked");
                expect(!decision.getStyleClass().contains("diagram-damaged"), "unaffected sibling remains unmarked");
                expect(editor().getText().equals(incomplete), "incomplete draft remains exactly as typed");
                retained.setText("INC n;");
                retained.positionCaret(3);
                retained.fireEvent(new ActionEvent());
                expect(editor().getText().equals(incomplete), "retained controls cannot overwrite invalid current source");
                editor().setText(SOURCE);
                expect(diagram().isSynchronized() && diagram().lookupAll(".diagram-damaged").isEmpty(),
                        "correcting source restores diagram editing and clears damage");
            });
            check("compiler errors also preserve the valid diagram and recover", () -> {
                Node loop = diagram().lookup(".diagram-while");
                String invalid = SOURCE.replace("DEC n;", "DEC missing;");
                editor().setText(invalid);
                expect(!diagram().isSynchronized() && diagram().lookup(".diagram-while") == loop,
                        "structurally parseable unknown variable retains last compiled diagram");
                expect(!diagram().lookupAll(".diagram-damaged").isEmpty(), "compiler failure marks the broken structure");
                expect(diagram().getStatusText().contains("missing"), "diagnostic explains the invalid identifier");
                expect(editor().getText().equals(invalid), "compiler feedback never rewrites the draft");
                editor().setText(SOURCE);
                expect(diagram().isSynchronized() && diagram().lookupAll(".diagram-damaged").isEmpty(), "compiler recovery removes red marks");
            });
            check("replaying foundations restores its grammar and returning keeps the draft", () -> {
                StructuredDiagramView structured = diagram();
                node("game-levels", ListView.class).getSelectionModel().select(0);
                expect(node("game-program-editors", SplitPane.class).getItems().size() == 2, "replay shows source and flowchart");
                FlowchartView foundation = node("game-flowchart", FlowchartView.class);
                expect(foundation.isSynchronized() && foundation.getSource().equals(editor().getText()), "replay restores foundation flowchart");
                expect(stage.getScene().lookup("#game-diagram") == null
                                && structured.getSource().isEmpty() && structured.lookupAll(".diagram-node").isEmpty(),
                        "replay removes the structured editor and clears its cached program");
                expect(node("game-grammar", GameGrammarView.class).getText().contains("JNZ Variable Label"), "foundations retain jumps");
                String incompleteFoundation = "var x=0; begin: INC missing; HALT;";
                editor().setText(incompleteFoundation);
                expect(!foundation.isSynchronized() && !foundation.lookupAll(".flowchart-damaged").isEmpty(),
                        "an invalid replay draft is marked in its own flowchart");
                @SuppressWarnings("unchecked") ComboBox<String> picker = node("game-worlds", ComboBox.class);
                picker.setValue(GameWorld.STRUCTURED_PROGRAMMING.title());
                expect(editor().getText().equals(SOURCE) && diagram().getSource().equals(SOURCE), "returning restores shared draft");
                expect(diagram().isSynchronized() && diagram().lookupAll(".diagram-damaged").isEmpty(),
                        "foundation failure state cannot contaminate the structured diagram");
                expect(foundation.getSource().isEmpty() && foundation.lookupAll(".flowchart-node").isEmpty(),
                        "invalid foundation cache is cleared when leaving its world");
                picker.setValue(GameWorld.SIGNED_WORDS.title());
                expect(picker.getValue().equals(GameWorld.STRUCTURED_PROGRAMMING.title()), "locked world cannot replace selection");
                expect(editor().getText().equals(SOURCE), "locked navigation leaves draft intact");
            });
            check("reopening restores selected world and both editors from the same saved source", () -> {
                game.stop();
                stage.close();
                GameProgress saved = new GameProgress(profile, GameCatalog.levels());
                expect(saved.selectedIndex() == 8 && saved.draft(GameCatalog.levels().get(8)).equals(SOURCE), "one source draft is persisted");
                open(profile);
                expect(node("game-program-editors", SplitPane.class).getItems().size() == 2, "structured editor restored");
                expect(editor().getText().equals(SOURCE) && diagram().getSource().equals(SOURCE), "both views reconstructed from saved source");
                if (args.length > 0) screenshot(Path.of(args[0]));
            });
            System.out.println("GameWorldEditorTest: " + passed + " passed, 0 failed");
        } finally {
            onFx(() -> { if (game != null) game.stop(); if (stage != null) stage.close(); });
            Platform.exit();
        }
    }

    private static void open(Path profile) throws Exception {
        game = new LanguageGame() { @Override Path progressPath() { return profile; } };
        stage = new Stage();
        game.start(stage);
    }

    private static SourceEditor editor() { return node("game-editor", SourceEditor.class); }
    private static StructuredDiagramView diagram() { return node("game-diagram", StructuredDiagramView.class); }
    private static <T extends Node> T node(String id, Class<T> type) {
        Node node = stage.getScene().lookup("#" + id);
        expect(type.isInstance(node), "missing " + id);
        return type.cast(node);
    }

    private static void screenshot(Path path) throws Exception {
        stage.getScene().getRoot().applyCss();
        stage.getScene().getRoot().layout();
        WritableImage shot = stage.getScene().snapshot(null);
        BufferedImage image = new BufferedImage((int) shot.getWidth(), (int) shot.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
            image.setRGB(x, y, shot.getPixelReader().getArgb(x, y));
        }
        expect(ImageIO.write(image, "png", path.toFile()), "PNG writer");
    }

    private static void check(String name, Action action) throws Exception {
        onFx(action);
        passed++;
        System.out.println("PASS " + name);
    }
    private static void onFx(Action action) throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> { action.run(); return null; });
        Platform.runLater(task);
        task.get(15, TimeUnit.SECONDS);
        Throwable failure = fxFailure.getAndSet(null);
        if (failure != null) throw new AssertionError("Uncaught JavaFX error", failure);
    }
    private static void expect(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

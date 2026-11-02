package machine;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.IndexRange;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextInputControl;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/** Real-focus integration of linked editors and the compact task/details layout, using a private profile. */
public final class GameLinkedEditorsTest {
    private static final String SOURCE = "// Linked editors keep this explanation.\n"
            + "var n = 2;\nvar spare = 0;\n\nbegin:\n"
            + "again: DEC n;\nJNZ n again;\nHALT;\n";
    private static LanguageGame game;
    private static Stage stage;
    private static int passed;
    private static final AtomicReference<Throwable> fxFailure = new AtomicReference<>();
    @FunctionalInterface private interface Action { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        Locale previousLocale = Locale.getDefault(Locale.Category.DISPLAY);
        Locale.setDefault(Locale.Category.DISPLAY, Locale.ENGLISH);
        Path profile = Files.createTempDirectory("game-linked-editors-").resolve("progress.properties");
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
                stage.setWidth(1280); stage.setHeight(860);
                editor().setText(SOURCE);
                layout();
                expect(flowchart().isSynchronized(), flowchart().getStatusText());
            });
            pulse();

            check("compact introduction and fourth task tab preserve all English and German task details", () -> {
                onFx(() -> {
                    TabPane work = node("game-work", TabPane.class);
                    expect(work.getTabs().size() == 4, "four work tabs");
                    expect(work.getTabs().get(2).getId().equals("toolbox-tab")
                                    && work.getTabs().get(3).getId().equals("task-details-tab"),
                            "Task details immediately follows Earned toolbox");
                    Tab details = work.getTabs().get(3);
                    expect(details.getText().equals("Task details") && details.getContent() instanceof ScrollPane,
                            "long teaching text has its own scrollable task tab");
                    work.getSelectionModel().select(details);
                    for (GameTaskLanguage language : List.of(GameTaskLanguage.ENGLISH, GameTaskLanguage.GERMAN)) {
                        taskLanguage().setValue(language);
                        layout();
                        GameLevel level = GameCatalog.levels().get(7);
                        GameTranslations.TaskText text = GameTranslations.text(level, language);
                        VBox introduction = node("game-introduction", VBox.class);
                        expect(introduction.getChildren().size() == 3 && introduction.lookupAll(".scroll-bar").isEmpty(),
                                "the introduction is a plain world/title/summary box without a scrollbar");
                        expect(node("game-title", Label.class).getText().equals(text.title()), "top heading contains only the task title");
                        expect(node("game-summary", Label.class).getText().equals(GameTaskSummaries.text(level, language)),
                                "top summary uses the selected task language");
                        expect(node("game-task-title", Label.class).getText().equals(text.title())
                                        && node("game-goal", Label.class).getText().equals(text.goal())
                                        && node("game-lesson", Label.class).getText().equals(text.lesson()),
                                "the complete title goal and teaching lesson remain available in " + language);
                        expect(node("game-contract", Label.class).getText().equals(GameMessages.text(language, GameMessages.Key.CONTRACT,
                                        level.cases().size(), level.maxSteps())), "the full input/output contract is translated");
                        expect(introduction.getHeight() < 160, "the introduction stays compact at 1280 by 860");
                        expect(editor().getText().equals(SOURCE), "task navigation never changes source");
                    }
                    taskLanguage().setValue(GameTaskLanguage.ENGLISH);
                    work.getSelectionModel().select(0);
                    layout();
                    expect(editor().getHeight() > 170, "the compact introduction leaves usable editor height");
                });
            });

            check("focusing process operand and label fields links source without stealing focus or selection", () -> {
                onFx(() -> editor().selectRange(18, 5));
                for (String text : List.of("DEC n;", "n", "again: ")) {
                    TextInputControl field = focusField(text);
                    onFx(() -> {
                        int start = text.equals("n") ? SOURCE.indexOf("JNZ n") + 4 : SOURCE.indexOf(text);
                        IndexRange expected = new IndexRange(start, start + text.length());
                        expect(expected.equals(editor().getLinkedRange()), "field focus links its exact source span for " + text);
                        expect(editor().lookup(".source-linked") != null, "the corresponding source line has a visible marker");
                        expect(stage.getScene().getFocusOwner() == field, "source reveal preserves diagram field focus");
                        expect(editor().getAnchor() == 18 && editor().getCaretPosition() == 5,
                                "source selection direction and caret remain unchanged");
                        expect(SOURCE.equals(editor().getText()) && !editor().isUndoable(), "focus linking introduces no source edit or undo entry");
                    });
                }
            });

            check("moving the real source caret highlights the matching flowchart element and clears blank lines", () -> {
                for (int offset : List.of(SOURCE.indexOf("DEC") + 1, SOURCE.indexOf("JNZ n") + 4,
                        SOURCE.indexOf("again:"), SOURCE.indexOf("HALT") + 2)) {
                    focusSource(offset);
                    onFx(() -> {
                        expectHighlighted(offset);
                        expect(stage.getScene().getFocusOwner() == editor(), "diagram reveal cannot steal source focus");
                        expect(editor().getCaretPosition() == offset && editor().getLinkedRange() == null,
                                "source-driven navigation leaves the caret in place and clears reverse decoration");
                    });
                }
                focusSource(SOURCE.indexOf("\n\n") + 1);
                onFx(() -> expectNoFlowHighlight("an unowned blank line has no flowchart element"));
            });

            check("caret navigation and task-language changes preserve pending flowchart edits", () -> {
                TextInputControl pending = focusField("DEC n;");
                onFx(() -> pending.setText("INC spare;"));
                focusSource(SOURCE.indexOf("JNZ n") + 4);
                onFx(() -> {
                    taskLanguage().setValue(GameTaskLanguage.GERMAN);
                    layout();
                    expect(field("INC spare;") == pending && pending.getScene() == stage.getScene(),
                            "navigation and translation retain the same pending control");
                    expect(editor().getText().equals(SOURCE) && flowchart().getSource().equals(SOURCE),
                            "pending diagram text is not silently committed or discarded");
                    expectHighlighted(SOURCE.indexOf("JNZ n") + 4);
                    pending.positionCaret(3);
                    pending.fireEvent(new ActionEvent());
                    expect(editor().getText().equals(SOURCE.replace("DEC n;", "INC spare;")),
                            "the retained edit still commits to the shared draft");
                    expect(flowchart().getSource().equals(editor().getText()) && flowchart().isSynchronized(),
                            "source and flowchart remain synchronized after commit");
                });
                pulse();
            });

            check("source undo restores both editors and caret links use the restored offsets", () -> {
                onFx(() -> {
                    editor().requestFocus();
                    editor().undo();
                    layout();
                    expect(editor().getText().equals(SOURCE) && flowchart().getSource().equals(SOURCE),
                            "native undo restores the complete original source transaction");
                    expect(field("DEC n;") != null, "undo restores the process field");
                });
                focusSource(SOURCE.indexOf("JNZ n") + 4);
                onFx(() -> expectHighlighted(SOURCE.indexOf("JNZ n") + 4));
                if (args.length > 0) onFx(() -> snapshot(Path.of(args[0])));
            });

            check("invalid cached diagrams ignore caret and stale field focus until source recovers", () -> {
                TextInputControl retained = focusField("DEC n;");
                onFx(() -> {
                    editor().setText(SOURCE.replace("DEC n;", "DEC missing;"));
                    layout();
                    expect(!flowchart().isSynchronized() && retained.getScene() == stage.getScene(),
                            "compiler failure retains the previous diagram");
                    expect(editor().getLinkedRange() == null, "editing clears the old linked source range");
                    expectNoFlowHighlight("invalid source clears stale shape selection");
                });
                focusSource(SOURCE.indexOf("DEC") + 1);
                onFx(() -> {
                    expectNoFlowHighlight("invalid source offsets cannot select cached shapes");
                    retained.requestFocus();
                });
                pulse();
                onFx(() -> {
                    expect(stage.getScene().getFocusOwner() == retained, "fixture focuses the retained read-only field");
                    expect(editor().getLinkedRange() == null, "stale focus cannot highlight an obsolete source offset");
                    expectNoFlowHighlight("stale focus cannot select the old shape");
                    editor().setText(SOURCE);
                });
                focusSource(SOURCE.indexOf("DEC") + 1);
                onFx(() -> expectHighlighted(SOURCE.indexOf("DEC") + 1));
            });

            check("cross-world navigation clears both link directions and restores the foundations draft", () -> {
                focusField("n");
                AtomicReference<FlowchartView> previous = new AtomicReference<>();
                onFx(() -> {
                    previous.set(flowchart());
                    expect(editor().getLinkedRange() != null, "fixture starts linked from the diagram");
                    node("game-levels", ListView.class).getSelectionModel().select(8);
                    layout();
                    expect(stage.getScene().lookup("#game-flowchart") == null && stage.getScene().lookup("#game-diagram") != null,
                            "structured world replaces the flowchart");
                    expect(editor().getLinkedRange() == null && previous.get().getHighlightedRange() == null,
                            "leaving foundations clears source and diagram links");
                    expect(previous.get().lookupAll(".flowchart-source-highlight").isEmpty() && previous.get().getSource().isEmpty(),
                            "hidden diagram retains no selected shape or source snapshot");
                    node("game-levels", ListView.class).getSelectionModel().select(7);
                    layout();
                    expect(editor().getText().equals(SOURCE) && flowchart().isSynchronized(), "returning restores the same shared draft");
                    expect(editor().getLinkedRange() == null, "returning cannot restore an obsolete reverse link");
                });
                focusSource(SOURCE.indexOf("HALT") + 1);
                onFx(() -> expectHighlighted(SOURCE.indexOf("HALT") + 1));
            });

            System.out.println("GameLinkedEditorsTest: " + passed + " passed, 0 failed");
        } finally {
            onFx(() -> { if (game != null) game.stop(); if (stage != null) stage.close(); });
            Platform.exit();
            Locale.setDefault(Locale.Category.DISPLAY, previousLocale);
        }
    }

    private static SourceEditor editor() { return node("game-editor", SourceEditor.class); }
    private static FlowchartView flowchart() { return node("game-flowchart", FlowchartView.class); }
    @SuppressWarnings("unchecked") private static ComboBox<GameTaskLanguage> taskLanguage() { return node("task-language", ComboBox.class); }
    private static TextInputControl field(String text) {
        return flowchart().lookupAll(".flowchart-edit").stream().filter(TextInputControl.class::isInstance)
                .map(TextInputControl.class::cast).filter(control -> control.getText().equals(text)).findFirst()
                .orElseThrow(() -> new AssertionError("missing flowchart field " + text));
    }
    private static TextInputControl focusField(String text) throws Exception {
        AtomicReference<TextInputControl> target = new AtomicReference<>();
        onFx(() -> { target.set(field(text)); target.get().requestFocus(); layout(); });
        pulse();
        onFx(() -> expect(stage.getScene().getFocusOwner() == target.get(), "real keyboard focus reaches field " + text));
        return target.get();
    }
    private static void focusSource(int offset) throws Exception {
        onFx(() -> { editor().requestFocus(); editor().positionCaret(offset); layout(); });
        pulse();
    }
    private static void expectHighlighted(int offset) {
        IndexRange range = flowchart().getHighlightedRange();
        expect(range != null && range.getStart() <= offset && range.getEnd() >= offset,
                "highlighted flowchart source range contains caret " + offset + ": " + range);
        var selected = flowchart().lookupAll(".flowchart-source-highlight");
        expect(selected.size() == 1, "one matching diagram element is highlighted");
        Node shape = selected.iterator().next();
        expect(shape.getUserData() instanceof Flowchart.Vertex, "source caret selects a source-owned graph shape");
        Flowchart.Vertex vertex = (Flowchart.Vertex) shape.getUserData();
        expect(vertex.owner() != null && vertex.owner().start() <= offset && vertex.owner().end() >= offset,
                "selected shape owns the current source offset");
    }
    private static void expectNoFlowHighlight(String message) {
        expect(flowchart().getHighlightedRange() == null && flowchart().lookupAll(".flowchart-source-highlight").isEmpty(), message);
    }
    private static <T extends Node> T node(String id, Class<T> type) {
        Node found = stage.getScene().lookup("#" + id);
        expect(type.isInstance(found), "missing " + id);
        return type.cast(found);
    }
    private static void layout() { stage.getScene().getRoot().applyCss(); stage.getScene().getRoot().layout(); }
    private static void pulse() throws Exception {
        CountDownLatch rendered = new CountDownLatch(1);
        onFx(() -> new AnimationTimer() {
            private int ticks;
            @Override public void handle(long now) { if (++ticks >= 2) { stop(); rendered.countDown(); } }
        }.start());
        expect(rendered.await(10, TimeUnit.SECONDS), "JavaFX layout pulses arrive");
        onFx(GameLinkedEditorsTest::layout);
    }
    private static void snapshot(Path path) throws Exception {
        layout();
        WritableImage shot = stage.getScene().snapshot(null);
        BufferedImage image = new BufferedImage((int) shot.getWidth(), (int) shot.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.getHeight(); ++y) for (int x = 0; x < image.getWidth(); ++x) image.setRGB(x, y, shot.getPixelReader().getArgb(x, y));
        expect(ImageIO.write(image, "png", path.toFile()), "PNG writer");
    }
    private static void check(String name, Action action) throws Exception {
        action.run(); ++passed; System.out.println("PASS " + name);
    }
    private static void onFx(Action action) throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> { action.run(); return null; });
        Platform.runLater(task); task.get(15, TimeUnit.SECONDS);
        Throwable failure = fxFailure.getAndSet(null);
        if (failure != null) throw new AssertionError("Uncaught JavaFX error", failure);
    }
    private static void expect(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}

package machine;

import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;

import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.TabPane;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

import static machine.GameMessages.Key.*;

/**
 * Standalone JavaFX game checks; all progress lives in a temporary directory.
 * Run only on a private/headless display. The optional argument exports a PNG.
 *
 * fx_modules=/usr/share/java/openjfx/javafx.base.jar:/usr/share/java/openjfx/javafx.graphics.jar:/usr/share/java/openjfx/javafx.controls.jar
 * gui_build=$(mktemp -d)
 * javac --release 17 --module-path "$fx_modules" --add-modules javafx.controls \
 *     -d "$gui_build" -classpath "$gui_build" -sourcepath . \
 *     tests/LanguageGameTest.java tests/GameCurriculumTest.java tests/GameBookLevelsTest.java
 * timeout 90s java --module-path "$fx_modules" --add-modules javafx.controls \
 *     -Dglass.platform=Monocle -Dmonocle.platform=Headless -Dprism.order=sw \
 *     -Djava.library.path=/usr/lib64/openjfx -cp "$gui_build" machine.LanguageGameTest
 *
 * This distribution's JavaFX natives require Java 17 and process-local
 * LD_PRELOAD=libfreetype.so.6:libpango-1.0.so.0:libpangoft2-1.0.so.0:libm.so.6.
 */
public final class LanguageGameTest {
    private static final String ANSWER = "var x = 0;\nbegin:\nINC x;\nHALT;\n";
    private static final String INFINITE = "var x = 0;\nvar one = 1;\nbegin:\nloop: JNZ one loop;\nHALT;\n";
    private static int passed;
    private static int failed;
    private static TestGame application;
    private static Stage stage;
    private static ListView<GameLevel> levels;
    private static SourceEditor editor;
    private static TableView<GameResult.CaseResult> cases;

    @FunctionalInterface
    private interface Check { void run() throws Exception; }

    private static final class TestGame extends LanguageGame {
        private final Path path;
        TestGame(Path path) { this.path = path; }
        @Override Path progressPath() { return path; }
    }

    public static void main(String[] args) throws Exception {
        Locale originalDisplayLocale = Locale.getDefault(Locale.Category.DISPLAY);
        Locale.setDefault(Locale.Category.DISPLAY, Locale.ENGLISH);
        Path screenshot = args.length == 0 ? null : Path.of(args[0]);
        Path directory = Files.createTempDirectory("language-game-gui-test-");
        Path progressFile = directory.resolve("progress.properties");
        Path journeyProgress = directory.resolve("complete-journey.properties");
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
            checkFx("task language follows the system until an explicit preference is saved", () -> {
                Path languageProfile = directory.resolve("task-language.properties");
                Locale.setDefault(Locale.Category.DISPLAY, Locale.GERMANY);
                open(languageProfile);
                expect(taskLanguage().getValue() == GameTaskLanguage.GERMAN, "German system locale selects German tasks");
                GameLevel first = levels.getItems().get(0);
                assertTaskText(first, GameTaskLanguage.GERMAN);
                expect(editor.getText().equals(first.starter()), "system locale never translates source");
                expect(node("check-solution", Button.class).getText().equals("Check solution"), "controls remain English");
                if (screenshot != null) writeSnapshot(screenshot.resolveSibling("LanguageGame-german.png"));
                taskLanguage().setValue(GameTaskLanguage.ENGLISH);
                open(languageProfile);
                expect(taskLanguage().getValue() == GameTaskLanguage.ENGLISH, "explicit English overrides German system locale after restart");
                taskLanguage().setValue(GameTaskLanguage.GERMAN);
                Locale.setDefault(Locale.Category.DISPLAY, Locale.ENGLISH);
                open(languageProfile);
                expect(taskLanguage().getValue() == GameTaskLanguage.GERMAN, "explicit German overrides English system locale after restart");
            });
            onFx(() -> open(progressFile));

            checkFx("initial level locks and hints expose only the starting language", () -> {
                expect(levels.getItems().size() >= 3 && levels.getSelectionModel().getSelectedIndex() == 0, "game starts on first catalog level");
                expect(editor.getText().equals(levels.getItems().get(0).starter()), "starter appears unchanged in the editor");
                expect(node("next-level", Button.class).isDisabled(), "next level starts locked");
                GameGrammarView grammar = node("game-grammar", GameGrammarView.class);
                expect(grammar.getText().contains("INC Variable")
                        && !grammar.getText().contains("while ( Condition )"), "initial grammar has base instructions without locked loops");
                expect(!node("game-toolbox", TextArea.class).isEditable(), "earned helper listing is read-only");
                expect(!node("reset-game", Button.class).isDisabled(), "reset is available while idle");
                assertRewardPreview(levels.getItems().get(0), 0);
                expect(!node("game-unlocks", Label.class).isVisible(), "new-game preview is not a success announcement");
                expect(cases.getItems().size() == Math.min(3, levels.getItems().get(0).cases().size()), "input/output examples are visible before checking");
                levels.getSelectionModel().select(2);
                expect(levels.getSelectionModel().getSelectedIndex() == 0, "locked navigation returns to the current level");
                node("show-hint", Button.class).fire();
                expect(node("game-hint", Label.class).getText().contains(levels.getItems().get(0).hints().get(0)), "hint button reveals the first teaching hint");
                expect(progress().completedCount() == 0, "viewing hints does not complete a level");
            });

            check("wrong output shows execution evidence without unlocking progress", () -> {
                submit("var x = 0;\nbegin:\nHALT;\n");
                onFx(() -> {
                    expect(!cases.getItems().isEmpty() && !cases.getItems().get(0).passed(), "failed output has a failing case row");
                    GameResult.CaseResult row = cases.getItems().get(0);
                    expect(!row.actual().isEmpty() && !row.actual().equals(row.expected()), "failed case exposes actual and expected values");
                    expect(progress().completedCount() == 0 && node("next-level", Button.class).isDisabled(), "wrong output cannot unlock next level");
                    expect(!feedback().isBlank(), "failed case has explanatory feedback");
                });
            });

            checkFx("switching task language translates existing statuses and preserves the draft results and grammar", () -> {
                String source = editor.getText();
                editor.selectRange(4, 1);
                List<GameResult.CaseResult> results = List.copyOf(cases.getItems());
                GameGrammarView grammar = node("game-grammar", GameGrammarView.class);
                String originalGrammar = grammar.getText();
                String originalToolbox = node("game-toolbox", TextArea.class).getText();
                String originalFeedback = feedback();
                Set<GameFeature> notices = progress().unreadGrammarFeatures();
                TabPane work = node("game-work", TabPane.class);
                work.getSelectionModel().select(3);
                taskLanguage().setValue(GameTaskLanguage.GERMAN);
                GameLevel first = levels.getItems().get(0);
                var german = GameTranslations.text(first, GameTaskLanguage.GERMAN);
                assertTaskText(first, GameTaskLanguage.GERMAN);
                expect(work.getSelectionModel().getSelectedIndex() == 3, "language switch keeps Task details open");
                expect(node("game-hint", Label.class).getText().equals("Hinweis 1/3: " + german.hints().get(0)),
                        "the already revealed hint is translated without resetting it");
                expect(editor.getText().equals(source) && editor.getAnchor() == 4 && editor.getCaretPosition() == 1,
                        "translation preserves source and editor selection");
                expect(cases.getItems().equals(results), "raw execution evidence remains language independent");
                expect(!feedback().equals(originalFeedback) && feedback().startsWith("Testfall ")
                                && feedback().contains("Eingaben") && !feedback().contains(" Inputs "),
                        "existing failed-case feedback is rendered entirely in German");
                expect(grammar.getText().equals(originalGrammar), "grammar remains unchanged");
                expect(node("game-toolbox", TextArea.class).getText().equals(originalToolbox),
                        "toolbox source comments remain unchanged with the code listing");
                expect(node("game-world", Label.class).getText().startsWith("Welt ")
                                && node("game-world-progress", Label.class).getText().contains("Aufgaben"),
                        "world labels and counts are translated");
                expect(node("game-language-version", Label.class).getText().equals(message(FLOWCHART_EDITOR) + " " + message(EARLY_EDITOR)), "flowchart and language-version status is translated");
                expect(node("game-save-status", Label.class).getText().equals(message(SAVED)), "save status is translated");
                expect(progress().completedCount() == 0 && progress().unreadGrammarFeatures().equals(notices),
                        "translation neither grants completion nor acknowledges unlock notices");
                node("show-hint", Button.class).fire();
                expect(node("game-hint", Label.class).getText().equals("Hinweis 2/3: " + german.hints().get(1)), "next hint continues in German");
                taskLanguage().setValue(GameTaskLanguage.ENGLISH);
                assertTaskText(first, GameTaskLanguage.ENGLISH);
                expect(work.getSelectionModel().getSelectedIndex() == 3, "switching back preserves the selected details tab");
                expect(node("game-hint", Label.class).getText().equals("Hint 2/3: " + first.hints().get(1)),
                        "switching back retains the number of revealed hints");
                expect(feedback().equals(originalFeedback) && cases.getItems().equals(results),
                        "switching back restores the English status without rerunning tests");
                expect(node("game-toolbox", TextArea.class).getText().equals(originalToolbox), "English empty-toolbox notice restored");
                expect(new GameProgress(progressFile, GameCatalog.levels()).taskLanguage() == GameTaskLanguage.ENGLISH,
                        "a manual choice is saved immediately");
                work.getSelectionModel().select(0);
            });

            check("locked syntax fails before execution and points at the source", () -> {
                submit("var x = 0;\nbegin:\nwhile(x != 0) { DEC x; }\nHALT;\n");
                onFx(() -> {
                    expect(feedback().toLowerCase().contains("locked"), "unearned loop reports its locked feature");
                    expect(cases.getItems().isEmpty(), "locked syntax does not execute any test cases");
                    expect(editor.getExecutionRange() != null, "locked syntax highlights its source location");
                    expect(progress().completedCount() == 0, "locked syntax cannot earn progress");
                });
            });

            check("malformed source reports a recoverable check failure", () -> {
                submit("var x = 0;\nbegin:\nINC missing;\nHALT;\n");
                onFx(() -> {
                    expect(feedback().contains("missing"), "diagnostic identifies the unknown variable");
                    expect(cases.getItems().isEmpty() && progress().completedCount() == 0, "invalid source neither runs cases nor earns progress");
                    expect(editor.isEditable() && !node("check-solution", Button.class).isDisabled(), "editor and checking recover after failure");
                });
            });

            check("an infinite program stops at the lesson step limit", () -> {
                submit(INFINITE);
                onFx(() -> {
                    expect(feedback().toLowerCase().contains("step limit"), "infinite execution reports its bound");
                    expect(cases.getItems().size() == 1 && !cases.getItems().get(0).passed(), "infinite execution reports the failing input");
                    expect(cases.getItems().get(0).steps() == levels.getItems().get(0).maxSteps(), "execution obeys the lesson's exact step budget");
                    expect(progress().completedCount() == 0 && editor.getExecutionRange() != null, "bounded failure marks source without completing the level");
                });
            });

            check("background checking leaves the FX queue responsive and can be canceled", () -> {
                String busySource = INFINITE.replace("loop:", "NOP;\n".repeat(2000) + "loop:");
                CountDownLatch responsive = new CountDownLatch(1);
                CountDownLatch releaseWorker = new CountDownLatch(1);
                // Keep the worker occupied so cancellation never races a fast computer's result.
                Field workerField = LanguageGame.class.getDeclaredField("checker");
                workerField.setAccessible(true);
                ((ExecutorService) workerField.get(application)).submit(() -> {
                    try { releaseWorker.await(10, TimeUnit.SECONDS); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                });
                try {
                    onFx(() -> {
                        editor.setText(busySource);
                        node("check-solution", Button.class).fire();
                        expect(node("check-solution", Button.class).isDisabled() && !node("cancel-check", Button.class).isDisabled(), "check starts asynchronously with cancel available");
                        expect(node("reset-game", Button.class).isDisabled(), "a running check cannot reset the game");
                        Platform.runLater(() -> {
                            node("cancel-check", Button.class).fire();
                            responsive.countDown();
                        });
                    });
                    expect(responsive.await(5, TimeUnit.SECONDS), "FX queue must respond while submission is being checked");
                } finally {
                    releaseWorker.countDown();
                }
                awaitIdle();
                onFx(() -> {
                    expect(feedback().toLowerCase().contains("cancel"), "cancellation is reported");
                    expect(busySource.equals(editor.getText()) && editor.isEditable(), "cancel preserves the draft and restores editing");
                    expect(progress().completedCount() == 0 && node("cancel-check", Button.class).isDisabled(), "cancel earns no progress and clears busy state");
                    expect(!node("reset-game", Button.class).isDisabled(), "reset becomes available again after cancellation");
                });
            });

            check("debug opens the selected input snapshot without awarding a level", () -> {
                onFx(() -> {
                    editor.setText(ANSWER);
                    @SuppressWarnings("unchecked") ComboBox<Integer> inputs = node("debug-case", ComboBox.class);
                    inputs.getSelectionModel().select(Integer.valueOf(2));
                    node("debug-solution", Button.class).fire();
                    expect(node("debug-solution", Button.class).isDisabled(), "debug compilation uses the background worker");
                });
                awaitIdle();
                onFx(() -> {
                    Stage debugger = Window.getWindows().stream().filter(window -> window instanceof Stage candidate
                            && candidate.getTitle().startsWith("Language Game Debugger")).map(window -> (Stage) window)
                            .findFirst().orElseThrow(() -> new AssertionError("debug window was not opened: " + feedback()));
                    try {
                        TabPane sources = (TabPane) debugger.getScene().lookup("#debug-sources");
                        SourceEditor frozen = (SourceEditor) sources.getTabs().get(0).getContent();
                        expect(!frozen.isEditable() && frozen.getText().contains("var x = 8;"),
                                "debugger receives selected test inputs and read-only source");
                        ((Button) debugger.getScene().lookup("#debug-step")).fire();
                        expect(progress().completedCount() == 0 && ANSWER.equals(editor.getText()),
                                "stepping neither edits the draft nor awards completion");
                        editor.setText(ANSWER + "// changed after opening debug mode\n");
                        expect(!frozen.getText().contains("changed after opening"), "the active debug snapshot is independent of later edits");
                        ((Button) debugger.getScene().lookup("#debug-restart")).fire();
                        expect(progress().completedCount() == 0, "restarting debugging does not complete the level");
                    } finally {
                        debugger.close();
                    }
                    editor.setText(ANSWER);
                });
            });

            check("a correct solution unlocks the next lesson and persists its rewards", () -> {
                submit(ANSWER);
                onFx(() -> {
                    GameLevel first = levels.getItems().get(0);
                    expect(cases.getItems().size() == first.cases().size() && cases.getItems().stream().allMatch(GameResult.CaseResult::passed), "every advertised case passes");
                    expect(progress().completedCount() == 1 && progress().features().containsAll(first.unlocks()), "successful check earns catalog rewards");
                    expect(!node("next-level", Button.class).isDisabled(), "successful check enables next level");
                    expect(node("game-progress", ProgressBar.class).getProgress() == 1, "successful checking fills its progress bar");
                    expect(node("game-grammar", GameGrammarView.class).getText().contains("Block:\n"), "earned block grammar appears immediately");
                    assertRewardSuccess(first, 0);
                    expect(node("grammar-new", Label.class).isVisible(), "grammar tab advertises unread additions");
                    expect(!progress().unreadGrammarFeatures().isEmpty(), "new syntax stays unread until the tab is opened");
                    GameProgress saved = new GameProgress(progressFile, GameCatalog.levels());
                    expect(saved.completedCount() == 1 && ANSWER.equals(saved.draft(first)), "checked solution and completion are saved together");
                });
                if (screenshot != null) {
                    awaitDelay(750);
                    onFx(() -> writeSnapshot(screenshot));
                }
            });

            check("per-level drafts and selected available lesson survive navigation and restart", () -> {
                String firstDraft = ANSWER + "// retained first draft\n";
                String secondDraft = "// second draft — Grüße 🌱\n";
                onFx(() -> {
                    editor.setText(firstDraft);
                    node("next-level", Button.class).fire();
                    expect(levels.getSelectionModel().getSelectedIndex() == 1, "Next opens newly available lesson");
                    expect(editor.getText().equals(levels.getItems().get(1).starter()), "new lesson begins with its own starter");
                    editor.setText(secondDraft);
                    levels.getSelectionModel().select(0);
                    expect(editor.getText().equals(firstDraft), "revisiting restores first draft");
                    levels.getSelectionModel().select(1);
                    expect(editor.getText().equals(secondDraft), "returning restores second draft");
                });
                awaitDelay(650);
                onFx(() -> {
                    GameProgress saved = new GameProgress(progressFile, GameCatalog.levels());
                    expect(saved.selectedIndex() == 1 && saved.draft(levels.getItems().get(1)).equals(secondDraft), "debounced edit saves selection and Unicode draft");
                    open(progressFile);
                    expect(levels.getSelectionModel().getSelectedIndex() == 1 && editor.getText().equals(secondDraft), "restart restores the selected draft");
                    expect(node("grammar-new", Label.class).isVisible(), "unread grammar survives navigation and restart");
                    node("game-work", TabPane.class).getSelectionModel().select(1);
                    expect(!node("grammar-new", Label.class).isVisible() && progress().unreadGrammarFeatures().isEmpty(),
                            "opening grammar acknowledges the tab notice");
                    expect(!node("game-grammar", GameGrammarView.class).lookupAll(".grammar-new").isEmpty(),
                            "additions remain highlighted while being read");
                    node("game-work", TabPane.class).getSelectionModel().select(0);
                    levels.getSelectionModel().select(2);
                    expect(levels.getSelectionModel().getSelectedIndex() == 1 && progress().completedCount() == 1, "future lesson remains locked after restart");
                });
            });

            if (screenshot != null) {
                onFx(() -> node("game-work", TabPane.class).getSelectionModel().select(1));
                awaitDelay(750);
                onFx(() -> {
                    writeSnapshot(screenshot.resolveSibling("LanguageGame-grammar.png"));
                    node("game-work", TabPane.class).getSelectionModel().select(0);
                });
            }

            check("the complete journey unlocks every feature and finishes in the GUI", () -> {
                onFx(() -> { open(journeyProgress); taskLanguage().setValue(GameTaskLanguage.GERMAN); });
                var solutions = GameCurriculumTest.solutions();
                List<GameLevel> catalog = GameCatalog.levels();
                for (int index = 0; index < catalog.size(); index++) {
                    GameLevel level = catalog.get(index);
                    int selected = index;
                    onFx(() -> {
                        assertRewardPreview(level, selected);
                        var text = GameTranslations.text(level, GameTaskLanguage.GERMAN);
                        assertTaskText(level, GameTaskLanguage.GERMAN);
                        node("show-hint", Button.class).fire();
                        expect(node("game-hint", Label.class).getText().contains(text.hints().get(0)),
                                "German teaching hint is available for " + level.id());
                    });
                    if (List.of("clear-block", "clear-do-while", "else-practice", "while-practice", "comparison-practice").contains(level.id())) {
                        submit(level.starter());
                        onFx(() -> {
                            expect(!progress().isCompleted(level.id()), "the unchanged working starter cannot complete a syntax rewrite");
                            if (level.id().equals("clear-block")) {
                                expect(!progress().features().contains(GameFeature.DO_WHILE), "braces must earn do/while first");
                                expect(feedback().toLowerCase().contains("block"), "missing block has useful feedback");
                            } else if (level.id().equals("else-practice") || level.id().equals("comparison-practice")) {
                                GameFeature required = level.id().equals("else-practice") ? GameFeature.ELSE : GameFeature.COMPARISONS;
                                expect(feedback().equals(GameMessages.diagnostic(GameTaskLanguage.GERMAN,
                                                "This level asks you to use " + required.title() + " in your solution.")),
                                        "the missing new syntax is explained in German");
                            } else if (level.id().equals("while-practice")) {
                                expect(feedback().toLowerCase().contains("while"), "the missing while rewrite is explained");
                                StructuredDiagramView diagram = node("game-diagram", StructuredDiagramView.class);
                                expect(diagram.lookupAll(".diagram-if").size() == 1
                                                && diagram.lookupAll(".diagram-do-while").size() == 1
                                                && diagram.lookupAll(".diagram-while").isEmpty(),
                                        "the unchanged starter displays its guarded do/while structure");
                            }
                        });
                    }
                    if (level.id().equals("clear-do-while")) {
                        onFx(() -> expect(editor.getLanguageVersion() == LanguageVersion.BEFORE_DO_WHILE,
                                "the old rewrite draft is colored with the earlier profile"));
                        debugVersion(LanguageVersion.BEFORE_DO_WHILE);
                    }
                    submit(solutions.get(level.id()));
                    if (level.id().equals("clear-do-while")) {
                        onFx(() -> expect(editor.getLanguageVersion() == LanguageVersion.STRUCTURED,
                                "the rewritten draft switches to structured keyword coloring"));
                        debugVersion(LanguageVersion.STRUCTURED);
                    }
                    if (level.id().equals("while-practice")) {
                        onFx(() -> {
                            StructuredDiagramView diagram = node("game-diagram", StructuredDiagramView.class);
                            expect(diagram.lookupAll(".diagram-while").size() == 1
                                            && diagram.lookupAll(".diagram-if").isEmpty()
                                            && diagram.lookupAll(".diagram-do-while").isEmpty(),
                                    "the accepted rewrite replaces old diagram controls with one while loop");
                            String source = editor.getText();
                            editor.selectRange(4, 1);
                            List<GameResult.CaseResult> results = List.copyOf(cases.getItems());
                            Set<GameFeature> features = Set.copyOf(progress().features());
                            String germanAnnouncement = "Aufgabe abgeschlossen! Als Nächstes: Einen Gleichheitstest aufbauen.";
                            expect(node("game-unlocks", Label.class).getText().equals(germanAnnouncement),
                                    "while-practice completion and next-task prefix are entirely German");
                            String germanFeedback = feedback();
                            expect(germanFeedback.equals(message(PASSED_ALL, level.cases().size()) + " " + message(NEXT_UNLOCKED)),
                                    "successful test status and unlock notice are German");
                            taskLanguage().setValue(GameTaskLanguage.ENGLISH);
                            expect(node("game-unlocks", Label.class).getText().equals("Level complete! Next: Build an equality test."),
                                    "existing completion banner rerenders in English");
                            expect(feedback().equals(message(PASSED_ALL, level.cases().size()) + " " + message(NEXT_UNLOCKED)),
                                    "existing success feedback rerenders in English");
                            taskLanguage().setValue(GameTaskLanguage.GERMAN);
                            expect(node("game-unlocks", Label.class).getText().equals(germanAnnouncement)
                                            && feedback().equals(germanFeedback), "switching back restores both German statuses");
                            expect(editor.getText().equals(source) && editor.getAnchor() == 4 && editor.getCaretPosition() == 1,
                                    "language switching preserves the successful source and caret selection");
                            expect(cases.getItems().equals(results) && progress().features().equals(features)
                                            && progress().isCompleted("while-practice"),
                                    "switching completion status language does not alter results or earned progress");
                        });
                    }
                    int completed = index + 1;
                    onFx(() -> {
                        expect(progress().completedCount() == completed,
                                level.id() + " should complete through the GUI: " + feedback());
                        expect(cases.getItems().size() == level.cases().size()
                                && cases.getItems().stream().allMatch(GameResult.CaseResult::passed),
                                "all cases pass for " + level.id());
                        assertRewardSuccess(level, completed - 1);
                        if (level.id().equals("clear-block")) {
                            expect(progress().features().contains(GameFeature.DO_WHILE)
                                            && !progress().features().contains(GameFeature.IF)
                                            && !progress().features().contains(GameFeature.WHILE),
                                    "the brace exercise unlocks only do/while before later conditionals and pre-test loops");
                            String grammar = node("game-grammar", GameGrammarView.class).getText();
                            expect(grammar.contains("do Block while ( Condition ) ;"), "earned do/while appears immediately");
                        }
                        if (level.id().equals("zero-test")) {
                            expect(progress().features().contains(GameFeature.IF)
                                    && !progress().features().contains(GameFeature.WHILE),
                                    "the zero-test lesson earns if before while");
                            String grammar = node("game-grammar", GameGrammarView.class).getText();
                            expect(grammar.contains("if ( Condition )") && !grammar.contains("\n    while ( Condition ) Block\n"),
                                    "zero-test success exposes if while pre-test loops remain locked");
                        }
                        if (!level.unlocks().isEmpty()) {
                            expect(node("grammar-new", Label.class).isVisible(), "each newly earned syntax addition has an unread cue");
                            node("game-work", TabPane.class).getSelectionModel().select(1);
                            expect(!node("grammar-new", Label.class).isVisible(), "viewing new grammar clears its cue");
                        }
                        if (!level.rewards().isEmpty()) {
                            expect(node("toolbox-new", Label.class).isVisible(), "new helper rewards have their own unread cue");
                            node("game-work", TabPane.class).getSelectionModel().select(2);
                            expect(!node("toolbox-new", Label.class).isVisible(), "viewing new helpers clears their cue");
                        }
                        node("game-work", TabPane.class).getSelectionModel().select(0);
                        if (completed < catalog.size()) {
                            node("next-level", Button.class).fire();
                            GameLevel next = levels.getSelectionModel().getSelectedItem();
                            expect(next.id().equals(catalog.get(completed).id()), "Next follows the curriculum order");
                            expect(next.requiredFeatures().containsAll(level.unlocks()),
                                    "the next challenge requires every feature just unlocked by " + level.id());
                        }
                    });
                }
                onFx(() -> {
                    expect(feedback().equals(message(JOURNEY_COMPLETE_LONG)), "last success celebrates completion in German");
                    expect(node("next-level", Button.class).isDisabled(), "no next level after the final challenge");
                    expect(progress().features().size() == GameFeature.values().length, "every grammar feature is earned");
                    levels.getSelectionModel().select(1);
                    expect(editor.getLanguageVersion() == LanguageVersion.BEFORE_DO_WHILE,
                            "revisiting the early label exercise retains its profile after all unlocks");
                    levels.getSelectionModel().select(catalog.size() - 1);
                    expect(new GameProgress(journeyProgress, catalog).completedCount() == catalog.size(), "entire journey survives reload");
                });
                if (screenshot != null) {
                    awaitDelay(750);
                    onFx(() -> writeSnapshot(screenshot.resolveSibling("LanguageGame-complete.png")));
                }
            });

            check("canceling whole-game reset preserves the completed journey and saved drafts", () -> {
                String draft = GameCurriculumTest.solutions().get(GameCatalog.levels().get(GameCatalog.levels().size() - 1).id())
                        + "\n// Keep this completed-game draft — Grüße\n";
                onFx(() -> editor.setText(draft));
                awaitDelay(650);
                String savedBefore = Files.readString(journeyProgress);
                onFx(() -> {
                    int selected = levels.getSelectionModel().getSelectedIndex();
                    String grammarBefore = node("game-grammar", GameGrammarView.class).getText();
                    String helpersBefore = node("game-toolbox", TextArea.class).getText();
                    chooseReset(false);
                    expect(progress().completedCount() == GameCatalog.levels().size(), "Cancel preserves every completed lesson");
                    expect(levels.getSelectionModel().getSelectedIndex() == selected && editor.getText().equals(draft),
                            "Cancel preserves selection and the current draft");
                    expect(node("game-grammar", GameGrammarView.class).getText().equals(grammarBefore)
                                    && node("game-toolbox", TextArea.class).getText().equals(helpersBefore),
                            "Cancel preserves unlocked syntax and helpers");
                    expect(Files.readString(journeyProgress).equals(savedBefore), "Cancel leaves the saved game untouched");
                });
            });

            check("confirmed whole-game reset clears the completed journey and all previous drafts", () -> {
                onFx(() -> {
                    node("show-hint", Button.class).fire();
                    editor.setExecutionRange(0, 1, false);
                    chooseReset(true);
                    assertResetState(journeyProgress);
                    GameProgress restored = new GameProgress(journeyProgress, GameCatalog.levels());
                    for (GameLevel level : GameCatalog.levels()) {
                        expect(restored.draft(level).equals(level.starter()), "reset drops saved draft for " + level.id());
                    }
                });
                if (screenshot != null) {
                    awaitDelay(750);
                    onFx(() -> writeSnapshot(screenshot.resolveSibling("LanguageGame-reset.png")));
                }
            });

            check("reset also restores a modified draft when the first level is already selected", () -> {
                String draft = "// Unfinished first-level work\nvar unfinished = 3;\n";
                onFx(() -> {
                    editor.setText(draft);
                    node("show-hint", Button.class).fire();
                });
                awaitDelay(650);
                onFx(() -> {
                    expect(new GameProgress(journeyProgress, GameCatalog.levels()).draft(GameCatalog.levels().get(0)).equals(draft),
                            "fixture stores the edited first-level draft");
                    chooseReset(true);
                    assertResetState(journeyProgress);
                });
            });

            check("legacy countdown progress retains while and Next returns to the first unfinished lesson", () -> {
                Path legacy = directory.resolve("legacy-countdown.properties");
                Files.writeString(legacy, "version=1\ncompleted.first-steps=true\ncompleted.countdown=true\n"
                        + "selected=countdown\ndraft.countdown=// retained legacy countdown draft\n");
                onFx(() -> {
                    open(legacy);
                    expect(levels.getSelectionModel().getSelectedItem().id().equals("countdown"),
                            "migration restores the previously selected completed lesson");
                    expect(progress().isCompleted("first-steps") && progress().isCompleted("countdown")
                                    && progress().features().contains(GameFeature.WHILE)
                                    && !progress().features().contains(GameFeature.IF),
                            "migration retains old loop credit without granting the newly earlier conditional lesson");
                    expect(editor.getText().equals("// retained legacy countdown draft"), "migration preserves the legacy draft");
                    expect(!node("next-level", Button.class).isDisabled(), "Next is available for the unfinished earlier lesson");
                    node("next-level", Button.class).fire();
                    expect(levels.getSelectionModel().getSelectedItem().id().equals("clear-block"),
                            "Next goes to the new block exercise instead of the still-locked lesson following countdown");
                    assertRewardPreview(levels.getSelectionModel().getSelectedItem(), 1);
                });
            });

            check("unreadable progress uses a separate recovery file without overwriting it", () -> {
                Path invalid = directory.resolve("future-version.properties");
                String original = "version=999\ncompleted.future=true\n";
                Files.writeString(invalid, original);
                onFx(() -> {
                    open(invalid);
                    expect(node("game-save-status", Label.class).getText().contains("original file is untouched"), "load recovery is explained to the player");
                    expect(progress().completedCount() == 0, "invalid saved progress cannot unlock the game");
                    editor.setText(ANSWER);
                    application.stop();
                    expect(Files.readString(invalid).equals(original), "recovery never overwrites the original progress file");
                });
                try (var files = Files.list(directory)) {
                    expect(files.anyMatch(path -> path.getFileName().toString().startsWith("future-version.properties.recovery-")), "recovery session saves to a distinct sibling file");
                }
            });

            check("save failure is visible while successful checks retain in-memory progress", () -> {
                Path blockedParent = directory.resolve("blocked-parent");
                onFx(() -> {
                    open(blockedParent.resolve("progress.properties"));
                    Files.writeString(blockedParent, "preserve this file");
                });
                submit(ANSWER);
                onFx(() -> {
                    expect(progress().completedCount() == 1 && !node("next-level", Button.class).isDisabled(), "failed save does not discard a successful in-memory result");
                    expect(node("game-save-status", Label.class).getText().toLowerCase().contains("not saved"), "failed save is visible in the status");
                    expect(editor.getText().equals(ANSWER) && Files.readString(blockedParent).equals("preserve this file"), "save failure preserves draft and existing filesystem content");
                });
            });

            check("a failed reset preserves the current game and existing filesystem contents", () -> {
                Path blockedParent = directory.resolve("blocked-parent");
                onFx(() -> {
                    String draft = editor.getText();
                    String grammarBefore = node("game-grammar", GameGrammarView.class).getText();
                    String announcement = node("game-unlocks", Label.class).getText();
                    int selected = levels.getSelectionModel().getSelectedIndex();
                    expect(progress().completedCount() == 1, "fixture has earned progress before failed reset");
                    chooseReset(true);
                    expect(progress().completedCount() == 1 && levels.getSelectionModel().getSelectedIndex() == selected,
                            "failed reset preserves completion and selection");
                    expect(editor.getText().equals(draft) && progress().draft(GameCatalog.levels().get(0)).equals(draft),
                            "failed reset preserves the editor and model draft");
                    expect(node("game-grammar", GameGrammarView.class).getText().equals(grammarBefore)
                                    && node("game-unlocks", Label.class).getText().equals(announcement),
                            "failed reset preserves unlocked syntax and its announcement");
                    expect(feedback().contains("Could not reset")
                                    && node("game-save-status", Label.class).getText().contains("not reset"),
                            "reset write failure is visible without claiming success");
                    expect(Files.readString(blockedParent).equals("preserve this file"), "failed reset never replaces obstructing filesystem content");
                });
            });

            check("JavaFX reports no asynchronous game exceptions", () -> {
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
                Locale.setDefault(Locale.Category.DISPLAY, originalDisplayLocale);
            }
        }
        System.out.println("LanguageGameTest: " + passed + " passed, " + failed + " failed");
        if (failed != 0) throw new AssertionError(failed + " language-game GUI check(s) failed");
    }

    @SuppressWarnings("unchecked")
    private static void open(Path path) throws Exception {
        if (application != null) application.stop();
        if (stage != null) stage.hide();
        application = new TestGame(path);
        stage = new Stage();
        application.start(stage);
        levels = node("game-levels", ListView.class);
        editor = node("game-editor", SourceEditor.class);
        cases = node("game-cases", TableView.class);
        stage.getScene().getRoot().applyCss();
        stage.getScene().getRoot().layout();
    }

    private static void submit(String source) throws Exception {
        onFx(() -> {
            editor.setText(source);
            node("check-solution", Button.class).fire();
        });
        awaitIdle();
    }

    @SuppressWarnings("unchecked")
    private static ComboBox<GameTaskLanguage> taskLanguage() {
        return node("task-language", ComboBox.class);
    }

    private static void debugVersion(LanguageVersion expected) throws Exception {
        int[] completed = new int[1];
        onFx(() -> { completed[0] = progress().completedCount(); node("debug-solution", Button.class).fire(); });
        awaitIdle();
        onFx(() -> {
            Stage debugger = Window.getWindows().stream().filter(window -> window instanceof Stage candidate
                    && candidate.getTitle().startsWith("Language Game Debugger")).map(window -> (Stage) window)
                    .findFirst().orElseThrow(() -> new AssertionError("debug profile did not compile: " + feedback()));
            try {
                expect(debugger.getTitle().contains(GameTranslations.text(levels.getSelectionModel().getSelectedItem(), progress().taskLanguage()).title()),
                        "debugger task title follows the selected teaching language");
                TabPane sources = (TabPane) debugger.getScene().lookup("#debug-sources");
                SourceEditor source = (SourceEditor) sources.getTabs().get(0).getContent();
                expect(source.getLanguageVersion() == expected, "debug compiler and source editor share " + expected);
                expect(progress().completedCount() == completed[0], "profile selection for debugging does not award progress");
            } finally { debugger.close(); }
        });
    }

    private static void assertTaskText(GameLevel level, GameTaskLanguage language) {
        var text = GameTranslations.text(level, language);
        expect(node("game-title", Label.class).getText().equals(text.title()), "top heading contains only the task title for " + level.id());
        expect(node("game-summary", Label.class).getText().equals(GameTaskSummaries.text(level, language)), "concise objective follows task language for " + level.id());
        TabPane work = node("game-work", TabPane.class);
        expect(work.getTabs().size() == 4 && work.getTabs().get(2).getText().equals("Earned toolbox"), "details immediately follows the toolbox");
        var details = work.getTabs().get(3);
        expect(details.getId().equals("task-details-tab") && details.getText().equals("Task details"), "details has a stable adjacent tab with an English control label");
        Node content = details.getContent();
        expect(((Label) content.lookup("#game-task-title")).getText().equals(text.title()), "details retains the translated title");
        expect(((Label) content.lookup("#game-goal")).getText().equals(text.goal()), "details retains the complete goal for " + level.id());
        expect(((Label) content.lookup("#game-lesson")).getText().equals(text.lesson()), "details retains the complete explanation for " + level.id());
        expect(((Label) content.lookup("#game-contract")).getText().equals(GameMessages.text(language, CONTRACT, level.cases().size(), level.maxSteps())),
                "details retains the localized execution contract");
        expect(node("game-introduction", Node.class).lookup("#game-goal") == null
                        && node("game-introduction", Node.class).lookup("#game-lesson") == null,
                "verbose teaching text is kept out of the compact introduction");
    }

    private static void assertRewardPreview(GameLevel level, int index) {
        Label preview = node("game-rewards", Label.class);
        expect(preview.isVisible() && preview.isManaged(), "unfinished level shows its reward preview");
        List<String> rewards = rewardNames(level);
        expect(preview.getText().startsWith(message(COMPLETE_TO_UNLOCK,
                        rewards.isEmpty() ? message(NEXT_CHALLENGE_REWARD) : String.join(" · ", rewards))),
                "preview explains when rewards are earned in the task language");
        level.unlocks().forEach(feature -> expect(preview.getText().contains(GameMessages.featureTitle(taskLanguage().getValue(), feature)),
                "preview names the translated feature " + feature.title()));
        level.rewards().keySet().forEach(name -> expect(preview.getText().contains(name), "preview names helper " + name));
        if (index + 1 < GameCatalog.levels().size()) {
            expect(preview.getText().contains(message(NEXT_CHALLENGE,
                            GameTranslations.text(GameCatalog.levels().get(index + 1), taskLanguage().getValue()).title())),
                    "preview names the next challenge even without a syntax reward");
        }
    }

    private static void assertRewardSuccess(GameLevel level, int index) {
        Label announcement = node("game-unlocks", Label.class);
        expect(announcement.isVisible() && announcement.isManaged(), "success has a persistent visible reward announcement");
        expect(!node("game-rewards", Label.class).isVisible(), "completed level hides its pending reward preview");
        if (!level.unlocks().isEmpty() || !level.rewards().isEmpty()) {
            expect(announcement.getText().contains(message(NEW_TOOLS_UNLOCKED, String.join(" · ", rewardNames(level)))),
                    "tool rewards have an explicit translated success announcement");
            level.unlocks().forEach(feature -> expect(announcement.getText().contains(GameMessages.featureTitle(taskLanguage().getValue(), feature)),
                    "success names the translated feature " + feature.title()));
            level.rewards().keySet().forEach(name -> expect(announcement.getText().contains(name), "success names helper " + name));
        } else if (index + 1 < GameCatalog.levels().size()) {
            expect(announcement.getText().contains(GameTranslations.text(GameCatalog.levels().get(index + 1), taskLanguage().getValue()).title()),
                    "practice completion announces the next challenge");
        }
    }

    private static List<String> rewardNames(GameLevel level) {
        List<String> names = new ArrayList<>();
        level.unlocks().stream().sorted().forEach(feature -> names.add(GameMessages.featureTitle(taskLanguage().getValue(), feature)));
        level.rewards().keySet().forEach(name -> names.add(message(HELPER, name)));
        return names;
    }

    private static String message(GameMessages.Key key, Object... arguments) {
        return GameMessages.text(taskLanguage().getValue(), key, arguments);
    }

    private static void assertResetState(Path path) throws Exception {
        GameLevel first = GameCatalog.levels().get(0);
        expect(progress().completedCount() == 0 && progress().features().isEmpty() && progress().completedIds().isEmpty(),
                "reset clears completions, grammar unlocks, and earned rewards");
        expect(levels.getSelectionModel().getSelectedIndex() == 0 && progress().selectedIndex() == 0,
                "reset returns navigation to the first level");
        expect(editor.getText().equals(first.starter()) && editor.getExecutionRange() == null,
                "reset restores the first starter and clears source highlights");
        expect(node("game-hint", Label.class).getText().isEmpty() && !node("show-hint", Button.class).isDisabled(),
                "reset clears shown hints and makes the first hint available again");
        expect(node("game-progress", ProgressBar.class).getProgress() == 0 && node("next-level", Button.class).isDisabled(),
                "reset clears the check indicator and relocks future navigation");
        expect(cases.getItems().size() == Math.min(3, first.cases().size())
                        && cases.getItems().stream().allMatch(row -> row.actual().isEmpty() && row.message().equals("Example")),
                "reset replaces prior execution results with initial input/output examples");
        expect(!node("game-grammar", GameGrammarView.class).getText().contains("Block:\n")
                        && GameCatalog.library(progress().completedIds()).get("toolbox").isBlank(),
                "reset removes earned block syntax and helper definitions");
        expect(!node("game-toolbox", TextArea.class).getText().contains("void "), "toolbox view drops all earned macros");
        expect(!node("game-unlocks", Label.class).isVisible(), "old success announcement disappears on reset");
        expect(!node("grammar-new", Label.class).isVisible() && !node("toolbox-new", Label.class).isVisible(),
                "reset clears both unread notices");
        assertRewardPreview(first, 0);
        GameProgress restored = new GameProgress(path, GameCatalog.levels());
        expect(restored.completedCount() == 0 && restored.features().isEmpty() && restored.selectedIndex() == 0
                        && restored.draft(first).equals(first.starter()), "reset is saved and reloads as a new game");
    }

    /** Fires the actual confirmation button while showAndWait runs its nested FX event loop. */
    private static void chooseReset(boolean confirmed) {
        Button action = node("reset-game", Button.class);
        expect(!action.isDisabled(), "reset action is enabled before asking for confirmation");
        AtomicReference<Throwable> dialogFailure = new AtomicReference<>();
        boolean[] answered = {false};
        Platform.runLater(() -> {
            try {
                DialogPane pane = null;
                Stage dialog = null;
                for (Window window : new ArrayList<>(Window.getWindows())) {
                    if (window != stage && window.isShowing() && window instanceof Stage candidate
                            && candidate.getTitle().equals(message(RESET_TITLE))) {
                        Node found = window.getScene().lookup(".dialog-pane");
                        if (found instanceof DialogPane confirmation) {
                            pane = confirmation;
                            dialog = candidate;
                            break;
                        }
                    }
                }
                expect(pane != null && dialog != null, "reset opens its real confirmation dialog");
                Button cancel = (Button) pane.lookupButton(ButtonType.CANCEL);
                ButtonType resetType = pane.getButtonTypes().stream()
                        .filter(button -> button.getText().equals("Reset game")).findFirst().orElseThrow();
                Button reset = (Button) pane.lookupButton(resetType);
                expect(cancel.isDefaultButton() && !reset.isDefaultButton(), "Cancel is the safe default confirmation choice");
                answered[0] = true;
                (confirmed ? reset : cancel).fire();
            } catch (Throwable error) {
                dialogFailure.set(error);
                for (Window window : new ArrayList<>(Window.getWindows())) if (window != stage) window.hide();
            }
        });
        action.fire();
        if (dialogFailure.get() != null) throw new AssertionError("Reset confirmation failed", dialogFailure.get());
        expect(answered[0], "reset confirmation was answered before the action returned");
    }

    private static void awaitIdle() throws Exception {
        await(() -> !node("check-solution", Button.class).isDisabled());
    }

    private static void await(BooleanSupplier condition) throws Exception {
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        onFx(() -> {
            Timeline poll = new Timeline();
            poll.getKeyFrames().add(new KeyFrame(Duration.millis(20), event -> {
                try {
                    if (condition.getAsBoolean()) {
                        poll.stop();
                        completed.countDown();
                    }
                } catch (Throwable error) {
                    failure.set(error);
                    poll.stop();
                    completed.countDown();
                }
            }));
            poll.setCycleCount(750);
            poll.play();
        });
        expect(completed.await(16, TimeUnit.SECONDS), "background check did not complete");
        if (failure.get() != null) throw new AssertionError("Waiting for GUI failed", failure.get());
    }

    private static void awaitDelay(int milliseconds) throws Exception {
        CountDownLatch settled = new CountDownLatch(1);
        onFx(() -> {
            PauseTransition delay = new PauseTransition(Duration.millis(milliseconds));
            delay.setOnFinished(event -> settled.countDown());
            delay.play();
        });
        expect(settled.await(5, TimeUnit.SECONDS), "JavaFX save/layout delay did not finish");
    }

    private static GameProgress progress() throws Exception {
        Field field = LanguageGame.class.getDeclaredField("progress");
        field.setAccessible(true);
        return (GameProgress) field.get(application);
    }

    private static String feedback() { return node("game-feedback", Label.class).getText(); }

    private static <T extends Node> T node(String id, Class<T> type) {
        Node found = stage.getScene().lookup("#" + id);
        expect(type.isInstance(found), "missing " + type.getSimpleName() + " #" + id);
        return type.cast(found);
    }

    private static void writeSnapshot(Path path) throws Exception {
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
            action.run(); passed++; System.out.println("PASS " + name);
        } catch (Exception | AssertionError error) {
            failed++; System.err.println("FAIL " + name + ": " + error); error.printStackTrace(System.err);
        }
    }

    private static void expect(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

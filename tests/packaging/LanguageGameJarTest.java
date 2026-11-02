package machine;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.Attributes;
import java.util.jar.JarFile;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * Launch the production manifest entry point and exercise only classes bundled in the JAR.
 * Run on a private X11 display with Java 17. No repository classes, JavaFX SDK, module
 * path, native-library path, or testing framework belongs on the runtime class path.
 * Progress is isolated automatically in a new temporary directory.
 *
 * game_jar="$(pwd)/target/language-game-linux-x86_64.jar"
 * jar_test_classes=$(mktemp -d)
 * javac --release 17 -cp "$game_jar" -sourcepath "$jar_test_classes" \
 *     -d "$jar_test_classes" tests/packaging/LanguageGameJarTest.java
 * xvfb-run -a java -Duser.language=de -Duser.country=DE \
 *     -cp "$game_jar:$jar_test_classes" machine.LanguageGameJarTest "$game_jar"
 *
 * The test has its own forty-second watchdog, including production launcher startup.
 */
public final class LanguageGameJarTest {
    private static final String ANSWER = "var x = 0;\nbegin:\nINC x;\nHALT;\n";
    private static final AtomicReference<Throwable> FAILURE = new AtomicReference<>();
    private static volatile String phase = "validating the packaged launcher";
    private static long deadline;

    private LanguageGameJarTest() {}

    public static void main(String[] args) throws Exception {
        require(args.length == 1, "Pass the absolute path of the standalone application JAR");
        Path jar = Path.of(args[0]).toRealPath();
        require(Path.of(args[0]).isAbsolute(), "The application JAR path must be absolute");
        require("de".equals(System.getProperty("user.language")) && "DE".equals(System.getProperty("user.country")),
                "Run with -Duser.language=de -Duser.country=DE to verify the system-language default");
        require(Locale.getDefault(Locale.Category.DISPLAY).getLanguage().equals("de"), "The JVM display locale must be German");

        String entryPoint;
        try (JarFile packaged = new JarFile(jar.toFile())) {
            require(packaged.getManifest() != null, "The application JAR needs a manifest");
            Attributes attributes = packaged.getManifest().getMainAttributes();
            entryPoint = attributes.getValue(Attributes.Name.MAIN_CLASS);
            require(entryPoint != null && !entryPoint.isBlank(), "The manifest must declare Main-Class");
            String dependencies = attributes.getValue(Attributes.Name.CLASS_PATH);
            require(dependencies == null || dependencies.isBlank(), "The standalone manifest must not require external JARs");
        }
        Class<?> launcher = Class.forName(entryPoint);
        require(!Application.class.isAssignableFrom(launcher), "Main-Class must be a plain launcher, not an Application subclass");
        for (Class<?> packagedClass : List.of(launcher, LanguageGame.class, SourceEditor.class,
                Compiler.class, GameTranslations.class, GameMessages.class, GameDiagnostics.class,
                Flowchart.class, FlowchartView.class, Platform.class, Button.class)) {
            require(codeSource(packagedClass).equals(jar), packagedClass.getName() + " must load from the application JAR");
        }

        Path progressFile = Files.createTempDirectory("language-game-jar-test-").resolve("progress.properties");
        require(!Files.exists(progressFile), "The packaged run must start with fresh progress");
        System.setProperty("language.game.progress", progressFile.toString());
        Thread.UncaughtExceptionHandler previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> {
            FAILURE.compareAndSet(null, failure);
            failure.printStackTrace(System.err);
        });
        CountDownLatch observed = new CountDownLatch(1);
        CountDownLatch exited = new CountDownLatch(1);
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40);
        Thread watchdog = new Thread(() -> {
            try {
                if (exited.await(40, TimeUnit.SECONDS)) return;
                System.err.println("LanguageGameJarTest timed out while " + phase);
                Throwable failure = FAILURE.get();
                if (failure != null) failure.printStackTrace(System.err);
                System.exit(1);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }, "language-game-jar-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        Thread observer = new Thread(() -> {
            try {
                exerciseApplication(progressFile);
            } catch (Throwable failure) {
                FAILURE.compareAndSet(null, failure);
            } finally {
                try {
                    onFx(() -> {
                        for (Window window : List.copyOf(Window.getWindows())) {
                            if (window instanceof Stage stage) stage.close(); else window.hide();
                        }
                        Platform.exit();
                        return null;
                    });
                } catch (Throwable cleanup) {
                    FAILURE.compareAndSet(null, cleanup);
                    Platform.exit();
                }
                observed.countDown();
            }
        }, "language-game-jar-observer");
        observer.setDaemon(true);
        observer.start();
        try {
            // This invokes the actual packaged entry point, including Application.launch.
            launcher.getMethod("main", String[].class).invoke(null, (Object) new String[0]);
        } catch (InvocationTargetException launchFailure) {
            FAILURE.compareAndSet(null, launchFailure.getCause());
            Platform.exit();
        } catch (Throwable failure) {
            FAILURE.compareAndSet(null, failure);
            Platform.exit();
        } finally {
            try {
                require(observed.await(remainingNanos(), TimeUnit.NANOSECONDS), "The GUI observer did not finish");
            } finally {
                exited.countDown();
                Thread.setDefaultUncaughtExceptionHandler(previousHandler);
            }
        }
        checkAsynchronousFailure();
        GameProgress saved = new GameProgress(progressFile, GameCatalog.levels());
        require(saved.completedIds().equals(Set.of("first-steps")), "Check must save one completion; Debug must not award another");
        require(saved.features().equals(Set.of(GameFeature.BLOCKS, GameFeature.EMPTY_STATEMENTS)), "The packaged run must save its earned tools");
        require(saved.draft(GameCatalog.levels().get(0)).equals(ANSWER), "Debug input substitution must not alter the saved program");
        System.out.println("LanguageGameJarTest: packaged launch, German tasks and status messages, compilation, unlocks, and debugger passed");
    }

    private static void exerciseApplication(Path progressFile) throws Exception {
        phase = "waiting for the production main window";
        Stage main = awaitStage("Language Game", false);
        onFx(() -> {
            main.getScene().getRoot().applyCss();
            main.getScene().getRoot().layout();
            require(node(main, "task-language", ComboBox.class).getValue() == GameTaskLanguage.GERMAN,
                    "A fresh German JVM must select German task text");
            GameLevel first = GameCatalog.levels().get(0);
            require(node(main, "game-title", Label.class).getText().equals("Ein Schritt vorwärts"),
                    "The packaged heading must contain only the German task title");
            require(node(main, "game-summary", Label.class).getText().equals(GameTaskSummaries.text(first, GameTaskLanguage.GERMAN)),
                    "The concise German objective must load from the package");
            require(node(main, "game-feedback", Label.class).getText().equals(
                            "Verwende die verfügbare Grammatik. Bestehe alle Tests, um die nächsten Werkzeuge freizuschalten."),
                    "The current gameplay status must use the German task language");
            TableView<?> examples = node(main, "game-cases", TableView.class);
            require(examples.getColumns().get(4).getCellData(0).equals("Beispiel"),
                    "The packaged example status must be German");
            require(node(main, "check-solution", Button.class).getText().equals("Check solution")
                            && node(main, "debug-solution", Button.class).getText().equals("Debug"),
                    "Task translation must leave controls in English");
            TabPane work = node(main, "game-work", TabPane.class);
            require(work.getTabs().stream().map(Tab::getText).toList()
                            .equals(List.of("Your program", "Unlocked grammar", "Earned toolbox", "Task details")), "Task details must be adjacent to the toolbox with English control labels");
            Tab details = work.getTabs().get(3);
            require(details.getId().equals("task-details-tab"), "Task details has a stable tab identifier");
            var german = GameTranslations.text(first, GameTaskLanguage.GERMAN);
            require(((Label) details.getContent().lookup("#game-goal")).getText().equals(german.goal())
                            && ((Label) details.getContent().lookup("#game-lesson")).getText().equals(german.lesson()),
                    "The package retains the complete German goal and explanation in Task details");
            require(((Label) details.getContent().lookup("#game-contract")).getText().equals(
                            GameMessages.text(GameTaskLanguage.GERMAN, GameMessages.Key.CONTRACT, first.cases().size(), first.maxSteps())),
                    "The execution contract remains available in Task details");
            GameGrammarView grammar = grammar(work);
            require(grammar.getText().contains("INC Variable") && grammar.getText().contains("Statement")
                            && !grammar.getText().contains("while ( Condition )"), "The English base grammar must render without locked syntax");
            SourceEditor editor = node(main, "game-editor", SourceEditor.class);
            require(editor.getText().equals(GameCatalog.levels().get(0).starter())
                            && editor.getText().contains("// Change x using a machine command."), "German tasks must not translate starter source");
            require(node(main, "next-level", Button.class).isDisabled(), "A fresh packaged game must start with the next level locked");
            String starter = editor.getText();
            editor.selectRange(4, 1);
            List<?> originalExamples = List.copyOf(examples.getItems());
            work.getSelectionModel().select(details);
            @SuppressWarnings("unchecked") ComboBox<GameTaskLanguage> language = node(main, "task-language", ComboBox.class);
            language.setValue(GameTaskLanguage.ENGLISH);
            require(node(main, "game-title", Label.class).getText().equals(first.title())
                            && node(main, "game-summary", Label.class).getText().equals(GameTaskSummaries.text(first, GameTaskLanguage.ENGLISH))
                            && ((Label) details.getContent().lookup("#game-goal")).getText().equals(first.goal())
                            && ((Label) details.getContent().lookup("#game-lesson")).getText().equals(first.lesson()),
                    "Changing task language refreshes the summary and full details together");
            language.setValue(GameTaskLanguage.GERMAN);
            require(work.getSelectionModel().getSelectedItem() == details && editor.getText().equals(starter)
                            && editor.getAnchor() == 4 && editor.getCaretPosition() == 1 && examples.getItems().equals(originalExamples),
                    "Details language changes retain the selected tab, draft, caret and example data");
            require(((Label) details.getContent().lookup("#game-lesson")).getText().equals(german.lesson()),
                    "Returning to German restores the complete packaged explanation");
            work.getSelectionModel().select(0);
            editor.setText(ANSWER);
            FlowchartView flowchart = node(main, "game-flowchart", FlowchartView.class);
            require(flowchart.isSynchronized() && flowchart.getSource().equals(ANSWER),
                    "The packaged World 1 flowchart must follow the authored program");
            node(main, "check-solution", Button.class).fire();
            return null;
        });

        phase = "checking the first solution through the packaged compiler";
        awaitFx(() -> !node(main, "check-solution", Button.class).isDisabled());
        onFx(() -> {
            TableView<?> cases = node(main, "game-cases", TableView.class);
            require(cases.getItems().size() == GameCatalog.levels().get(0).cases().size(), "Every first-level input must be checked");
            for (Object item : cases.getItems()) {
                require(item instanceof GameResult.CaseResult, "Execution evidence must use packaged case results");
                GameResult.CaseResult result = (GameResult.CaseResult) item;
                require(result.passed() && result.actual().get("x") == result.inputs().get("x") + 1,
                        "The packaged compiler and machine must increment each actual input");
            }
            require(node(main, "game-feedback", Label.class).getText().equals(
                            "Alle 3 Testfälle bestanden. Freigeschaltet: Blöcke, Leere Anweisungen."),
                    "The entire successful check status, including earned features, must be German");
            require(cases.getColumns().get(4).getCellData(0).equals("Bestanden."),
                    "The per-case result status must be translated without changing execution evidence");
            require(!node(main, "next-level", Button.class).isDisabled(), "Successful checking must unlock the next level");
            require(node(main, "game-unlocks", Label.class).isVisible(), "Earned tools must be visible");
            require(node(main, "game-unlocks", Label.class).getText().equals(
                            "Neue Werkzeuge freigeschaltet: Blöcke · Leere Anweisungen. Als Nächstes: Die Löschschleife in einen Block fassen.\n"
                            + "Deine neuen Möglichkeiten findest du in der freigeschalteten Grammatik und bei deinen erworbenen Hilfsmakros."),
                    "The entire completion banner must be German, including its prefix, next challenge, and follow-up instruction");
            require(node(main, "game-save-status", Label.class).getText().equals("Fortschritt und Entwürfe lokal gespeichert."),
                    "The saved-progress status must follow the task language");
            require(grammar(node(main, "game-work", TabPane.class)).getText().contains("Block"), "The unlocked grammar must include blocks");
            ComboBox<?> inputs = node(main, "debug-case", ComboBox.class);
            inputs.getSelectionModel().select(2);
            require(inputs.getValue().equals(2), "Debug must select the third actual input case");
            node(main, "debug-solution", Button.class).fire();
            return null;
        });
        require(Files.isRegularFile(progressFile), "The successful check must save isolated progress");

        phase = "opening the packaged debugger";
        Stage debug = awaitStage("Language Game Debugger", true);
        onFx(() -> {
            debug.getScene().getRoot().applyCss();
            debug.getScene().getRoot().layout();
            require(debug.getTitle().equals("Language Game Debugger — Ein Schritt vorwärts — Testfall 3 — Vor do/while"),
                    "The debugger must retain the German task title and selected case");
            require(node(main, "game-feedback", Label.class).getText().equals(
                            "Testfall 3 wird in einem eigenen Fenster untersucht. Prüfe deine Lösung, um die Aufgabe abzuschließen."),
                    "The active debugging status must be completely German");
            require(node(debug, "debug-state", Label.class).getText().contains(" · Schritte = 0 /")
                            && node(debug, "debug-state", Label.class).getText().endsWith(" · Bereit · Vor do/while"),
                    "The initial debugger state and language version must be German");
            require(node(debug, "debug-inputs", Label.class).getText().equals(
                            "Schreibgeschützte Momentaufnahme · Testeingaben: {x=8}"),
                    "The debugger's input status must translate prose while retaining variable names and values");
            TabPane sources = node(debug, "debug-sources", TabPane.class);
            require(!sources.getTabs().isEmpty() && sources.getTabs().get(0).getContent() instanceof SourceEditor,
                    "The packaged debugger must reuse the source editor");
            SourceEditor source = (SourceEditor) sources.getTabs().get(0).getContent();
            require(!source.isEditable() && source.getText().contains("var x = 8;"),
                    "Debug source must be read-only and show the actual selected input");
            require(source.getExecutionRange() != null, "The debugger must display instruction provenance");
            require(node(debug, "debug-step", Button.class).getText().equals("Step"), "Debugger controls must remain English");
            node(debug, "debug-step", Button.class).fire();
            require(node(debug, "debug-state", Label.class).getText().endsWith(" · Angehalten · Vor do/while"),
                    "A packaged machine step must reach HALT with German state text");
            require(node(debug, "debug-message", Label.class).getText().equals(
                            "HALT erreicht. Die Ausgaben stimmen mit diesem Testfall überein. Die Fehlersuche schließt keine Aufgabe ab."),
                    "The complete halted debugger message must be German");
            require(portValue(node(debug, "debug-ports", TableView.class), "Now", "x").equals("9"),
                    "The debugger must display the incremented selected input");
            require(node(main, "game-editor", SourceEditor.class).getText().equals(ANSWER), "Debugging must preserve the authored draft");
            return null;
        });
        phase = "closing the packaged application";
    }

    private static GameGrammarView grammar(TabPane work) {
        return (GameGrammarView) work.getTabs().stream().filter(tab -> "grammar-tab".equals(tab.getId()))
                .findFirst().orElseThrow().getContent();
    }

    private static Stage awaitStage(String title, boolean prefix) throws Exception {
        while (true) {
            checkAsynchronousFailure();
            Stage stage = onFx(() -> Window.getWindows().stream().filter(Window::isShowing)
                    .filter(window -> window instanceof Stage).map(window -> (Stage) window)
                    .filter(window -> prefix ? window.getTitle().startsWith(title) : window.getTitle().equals(title))
                    .findFirst().orElse(null));
            if (stage != null) return stage;
            remainingNanos();
            Thread.sleep(40);
        }
    }

    private static void awaitFx(Callable<Boolean> ready) throws Exception {
        while (true) {
            checkAsynchronousFailure();
            if (onFx(ready)) return;
            remainingNanos();
            Thread.sleep(40);
        }
    }

    private static <T> T onFx(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        while (true) {
            try {
                Platform.runLater(task);
                break;
            } catch (IllegalStateException notStarted) {
                checkAsynchronousFailure();
                remainingNanos();
                Thread.sleep(20);
            }
        }
        try {
            return task.get(remainingNanos(), TimeUnit.NANOSECONDS);
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof Exception exception) throw exception;
            if (failure.getCause() instanceof Error error) throw error;
            throw new AssertionError(failure.getCause());
        }
    }

    private static long remainingNanos() throws TimeoutException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new TimeoutException("Timed out while " + phase);
        return remaining;
    }

    private static <T extends Node> T node(Stage stage, String id, Class<T> type) {
        Node node = stage.getScene().lookup("#" + id);
        require(type.isInstance(node), "Missing " + id + " in " + stage.getTitle());
        return type.cast(node);
    }

    @SuppressWarnings("unchecked")
    private static String portValue(TableView<?> rawTable, String columnName, String rowName) {
        TableView<String> table = (TableView<String>) rawTable;
        TableColumn<String, ?> column = table.getColumns().stream().filter(candidate -> candidate.getText().equals(columnName))
                .findFirst().orElseThrow();
        require(table.getItems().contains(rowName), "Missing named debug port " + rowName);
        return String.valueOf(column.getCellObservableValue(rowName).getValue());
    }

    private static Path codeSource(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
    }

    private static void checkAsynchronousFailure() {
        Throwable failure = FAILURE.get();
        if (failure != null) throw new AssertionError("Packaged application failed while " + phase, failure);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

package machine;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
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
import javafx.scene.control.Hyperlink;
import javafx.scene.control.IndexRange;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.FlowPane;
import javafx.stage.Stage;
import javafx.util.Duration;

/**
 * Standalone debugger GUI checks. Run on a private/headless JavaFX display only.
 * Compilation and debugging use in-memory fixtures; no progress file is touched.
 * An optional argument exports a PNG while stopped inside an imported macro.
 *
 * fx_modules=/usr/share/java/openjfx/javafx.base.jar:/usr/share/java/openjfx/javafx.graphics.jar:/usr/share/java/openjfx/javafx.controls.jar
 * gui_build=$(mktemp -d)
 * javac --release 17 --module-path "$fx_modules" --add-modules javafx.controls \
 *     -d "$gui_build" -classpath "$gui_build" -sourcepath . tests/GameDebuggerTest.java
 * timeout 60s java --module-path "$fx_modules" --add-modules javafx.controls \
 *     -Dglass.platform=Monocle -Dmonocle.platform=Headless -Dprism.order=sw \
 *     -Djava.library.path=/usr/lib64/openjfx -cp "$gui_build" machine.GameDebuggerTest
 *
 * The local JavaFX natives require Java 17 and process-local
 * LD_PRELOAD=libfreetype.so.6:libpango-1.0.so.0:libpangoft2-1.0.so.0:libm.so.6.
 */
public final class GameDebuggerTest {
    private static final Path MAIN = Path.of("solution.a26");
    private static final Path LIBRARY_PATH = Path.of("toolbox", "toolbox");
    private static final String BASIC = "var input=99;\nvar out=0;\nbegin:\nINC input; INC out; HALT;\n";
    private static final String LOOP = "var input=99; var out=0; begin:\nagain: JNZ input again;\nHALT;\n";
    private static final String LIBRARY = "void leaf(value) {\nINC value; DEC value;\n}\n"
            + "void outer(value) {\nleaf(value);\n}\n";
    private static int passed;
    private static int failed;
    private static GameDebugger debugger;
    private static GameDebugSession session;
    private static Stage stage;

    @FunctionalInterface
    private interface Check { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        Path screenshot = args.length == 0 ? null : Path.of(args[0]);
        GameLevel basicLevel = level("basic", List.of(
                example(0, Map.of("input", 1, "out", 1)), example(7, Map.of("input", 8, "out", 1))), 100);
        GameDebugSession basic = prepare(basicLevel, BASIC, Set.of(), Map.of(), 1);
        GameDebugSession statement = prepare(level("initializer", List.of(example(7, Map.of("out", 1))), 100),
                "var input=99; var out=0; begin:\nvar work=3;\nINC out;\nHALT;\n",
                Set.of(GameFeature.LOCAL_VARIABLES), Map.of(), 0);
        Map<String, String> libraries = new LinkedHashMap<>(Map.of("toolbox", LIBRARY));
        GameDebugSession imported = prepare(level("imported", List.of(example(3, Map.of("input", 3))), 100),
                "import \"toolbox\";\nvar input=99;\nbegin:\nNOP;\nouter(input);\nHALT;\n",
                EnumSet.of(GameFeature.MACROS, GameFeature.IMPORTS), libraries, 0);
        libraries.put("toolbox", "// changes made after preparation must not affect this snapshot\n");
        GameDebugSession bounded = prepare(level("bounded", List.of(example(1, Map.of("out", 0))), 5), LOOP, Set.of(), Map.of(), 0);
        GameDebugSession running = prepare(level("running", List.of(example(1, Map.of("out", 0))), 2_000_000), LOOP, Set.of(), Map.of(), 0);
        GameDebugSession overflow = prepare(level("overflow", List.of(example(Integer.MAX_VALUE, Map.of("input", Integer.MAX_VALUE))), 100),
                "var input=0; begin:\nINC input;\nHALT;\n", Set.of(), Map.of(), 0);
        GameDebugSession halted = prepare(level("halted", List.of(example(7, Map.of("input", 7))), 100),
                "var input=99; begin: HALT;", Set.of(), Map.of(), 0);
        GameLevel rewrite = GameCatalog.levels().stream().filter(level -> level.id().equals("clear-do-while")).findFirst().orElseThrow();
        GameDebugSession oldForm = prepare(rewrite, rewrite.starter(), EnumSet.allOf(GameFeature.class), Map.of(), 0);
        GameDebugSession newForm = prepare(rewrite,
                "var dummy=0; var x=0; begin: INC dummy; ; do { DEC x; } while(x!=0); HALT;",
                EnumSet.allOf(GameFeature.class), Map.of(), 0);

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
            checkFx("selected case displays its actual inputs in a read-only compiled snapshot", () -> {
                open(basic);
                SourceEditor source = editor(MAIN);
                expect(session.getCaseIndex() == 1 && stage.getTitle().contains("case 2"), "window identifies the selected case");
                expect(stage.getTitle().contains(session.getLanguageVersion().toString())
                                && node("debug-state", Label.class).getText().contains(session.getLanguageVersion().toString()),
                        "window identifies the source language version");
                expect(source.getText().contains("var input=7;") && !source.getText().contains("input=99"),
                        "the displayed source contains the selected input substitution");
                expect(source.getText().equals(session.getProgram().sources().get(MAIN)) && !source.isEditable(),
                        "the editor shows the immutable compilation snapshot");
                expect(!memory().isEditable() && !ports().isEditable(), "memory and port tables are read-only");
                expectRange(source, "INC input;");
                expectMemory("input", 7);
                expect(tableValue(ports(), "Input", "input").equals("7")
                                && tableValue(ports(), "Expected", "input").equals("8")
                                && tableValue(ports(), "Now", "input").equals("7"),
                        "the case's input expected and current values remain distinguishable");
                expectState();
            });

            checkFx("Step updates instruction pointer memory and exact source ranges through HALT", () -> {
                SourceEditor source = editor(MAIN);
                source.selectRange(8, 2);
                int initialPointer = session.getInstructionPointer();
                button("debug-step").fire();
                expect(session.getStepCount() == 1 && session.getInstructionPointer() == initialPointer + 2,
                        "one click executes one INC opcode");
                expectMemory("input", 8);
                expect(tableValue(ports(), "Now", "input").equals("8"), "port value refreshes after Step");
                expectRange(source, "INC out;");
                expect(source.getAnchor() == 8 && source.getCaretPosition() == 2, "following execution preserves selection");
                expectState();
                button("debug-step").fire();
                expect(session.getStatus() == GameDebugSession.Status.HALTED && session.getStepCount() == 2,
                        "HALT is reached without counting it as an instruction");
                expectMemory("out", 1);
                expectRange(source, "HALT;");
                expect(source.isExecutionHalted() && node("debug-message", Label.class).getText().contains("Outputs match"),
                        "halted source and matching output are visible");
                expectStoppedControls();
                button("debug-step").fire();
                expect(session.getStepCount() == 2, "disabled Step cannot execute after HALT");
            });

            checkFx("Restart restores the chosen case from the same compiled snapshot", () -> {
                CompilationResult snapshot = session.getProgram();
                String text = editor(MAIN).getText();
                button("debug-restart").fire();
                expect(session.getProgram() == snapshot && editor(MAIN).getText().equals(text), "restart does not recompile or replace source");
                expect(session.getStatus() == GameDebugSession.Status.READY && session.getStepCount() == 0
                                && session.getInstructionPointer() == snapshot.codeStart(), "restart restores the first instruction");
                expectMemory("input", 7);
                expectMemory("out", 0);
                expectRange(editor(MAIN), "INC input;");
                expect(!button("debug-step").isDisabled() && !button("debug-run").isDisabled(), "restart re-enables execution");
                expectState();
            });

            checkFx("Step statement crosses the generated instructions of one initializer", () -> {
                open(statement);
                expectRange(editor(MAIN), "var work=3;");
                button("debug-statement-step").fire();
                expect(session.getStepCount() > 1 && session.getStatus() == GameDebugSession.Status.READY,
                        "source stepping executes the initializer's generated instruction sequence");
                expectRange(editor(MAIN), "INC out;");
                expectMemory("out", 0);
                expectState();
            });

            checkFx("old and rewritten loops display their selected language in the debugger", () -> {
                for (GameDebugSession form : List.of(oldForm, newForm)) {
                    open(form);
                    LanguageVersion expected = form == oldForm ? LanguageVersion.BEFORE_DO_WHILE : LanguageVersion.STRUCTURED;
                    expect(form.getLanguageVersion() == expected, "prepared form uses its corresponding grammar");
                    expect(stage.getTitle().contains(expected.toString())
                                    && node("debug-state", Label.class).getText().contains(expected.toString()),
                            "title and execution state show the active grammar");
                    expect(editor(MAIN).getLanguageVersion() == expected, "source highlighting uses the compiled language");
                    button("debug-step").fire();
                    expect(form.getStepCount() == 1, "both profiles execute the common first instruction");
                }
            });

            check("imported macro execution follows frozen source and exposes caller links", () -> {
                onFx(() -> {
                    open(imported);
                    expect(editor(LIBRARY_PATH).getText().equals(LIBRARY), "later library edits cannot replace the prepared snapshot");
                    expectRange(editor(MAIN), "NOP;");
                    button("debug-step").fire();
                    expect(tabs().getSelectionModel().getSelectedItem() == tab(LIBRARY_PATH), "execution follows the imported macro body");
                    expectRange(editor(LIBRARY_PATH), "INC value;");
                    expectCaller(editor(MAIN), "outer(input);");
                    expectCaller(editor(LIBRARY_PATH), "leaf(value);");
                    List<Hyperlink> links = callerLinks();
                    expect(links.size() == 2, "nested calls expose outer and inner callers");
                    links.get(0).fire();
                    expect(tabs().getSelectionModel().getSelectedItem() == tab(MAIN), "outer caller link selects main source");
                    links.get(1).fire();
                    expect(tabs().getSelectionModel().getSelectedItem() == tab(LIBRARY_PATH), "inner caller link selects imported source");
                    layout();
                });
                if (screenshot != null) {
                    awaitDelay(750);
                    onFx(() -> writeSnapshot(screenshot));
                }
                onFx(() -> {
                    button("debug-step").fire();
                    expectMemory("input", 4);
                    expectRange(editor(LIBRARY_PATH), "DEC value;");
                    button("debug-step").fire();
                    expectRange(editor(MAIN), "HALT;");
                    expectMemory("input", 3);
                    expect(callerLinks().isEmpty() && editor(LIBRARY_PATH).getExecutionRange() == null
                                    && editor(LIBRARY_PATH).getCallerRanges().isEmpty(), "return clears imported execution and caller markers");
                });
            });

            check("Run stops at the case bound and Restart recovers", () -> {
                onFx(() -> { open(bounded); button("debug-run").fire(); });
                await(() -> session.isStopped());
                onFx(() -> {
                    expect(session.getStatus() == GameDebugSession.Status.LIMIT_REACHED && session.getStepCount() == 5,
                            "Run obeys the selected case's exact step budget");
                    expect(node("debug-message", Label.class).getText().contains("step limit"), "the bound is explained in the window");
                    expectStoppedControls();
                    button("debug-restart").fire();
                    expect(session.getStatus() == GameDebugSession.Status.READY && session.getStepCount() == 0,
                            "Restart clears the limit state");
                });
            });

            checkFx("an unsafe arithmetic step stops before mutation and disables execution", () -> {
                open(overflow);
                button("debug-step").fire();
                expect(session.getStatus() == GameDebugSession.Status.ERROR && session.getStepCount() == 0,
                        "overflow is stopped before executing an instruction");
                expectMemory("input", Integer.MAX_VALUE);
                expect(node("debug-message", Label.class).getText().contains("overflow"), "the arithmetic error is visible");
                expectRange(editor(MAIN), "INC input;");
                expectStoppedControls();
            });

            check("Pause and closing the debugger stop its run timer", () -> {
                onFx(() -> {
                    open(running);
                    button("debug-run").fire();
                    expect(button("debug-run").getText().equals("Pause") && button("debug-step").isDisabled(),
                            "running offers Pause and disables competing step actions");
                });
                await(() -> session.getStepCount() > 0);
                int[] stoppedAt = {0};
                onFx(() -> {
                    button("debug-run").fire();
                    stoppedAt[0] = session.getStepCount();
                    expect(button("debug-run").getText().equals("Run") && !button("debug-step").isDisabled(),
                            "Pause restores manual controls");
                });
                awaitDelay(120);
                onFx(() -> {
                    expect(session.getStepCount() == stoppedAt[0], "Pause leaves the machine stationary across pulses");
                    button("debug-run").fire();
                });
                await(() -> session.getStepCount() > stoppedAt[0]);
                onFx(() -> { debugger.close(); stoppedAt[0] = session.getStepCount(); });
                awaitDelay(120);
                onFx(() -> expect(!stage.isShowing() && session.getStepCount() == stoppedAt[0], "closing stops background execution"));
            });

            checkFx("a snapshot beginning at HALT starts stopped without consuming a step", () -> {
                open(halted);
                expect(session.getStatus() == GameDebugSession.Status.HALTED && session.getStepCount() == 0,
                        "initial HALT is represented immediately");
                expectRange(editor(MAIN), "HALT;");
                expectStoppedControls();
                expectState();
            });

            checkFx("German execution statuses and source locations preserve the exact compiled snapshot", () -> {
                GameDebugSession german = prepare(basicLevel, BASIC, Set.of(), Map.of(), 1);
                CompilationResult snapshot = german.getProgram();
                open(german, GameTaskLanguage.GERMAN);
                expect(stage.getTitle().startsWith("Language Game Debugger — ")
                                && stage.getTitle().contains("Testfall 2") && stage.getTitle().endsWith("Vor do/while"),
                        "German window title identifies selected case and language profile");
                expect(node("debug-title", Label.class).getText().endsWith("Testfall 2"), "task heading uses German case label");
                expect(node("debug-inputs", Label.class).getText().equals("Schreibgeschützte Momentaufnahme · Testeingaben: {input=7}"),
                        "snapshot status translates prose while preserving input names and values");
                expect(node("debug-state", Label.class).getText().contains("Schritte = 0 / 100 · Bereit · Vor do/while"),
                        "state summary translates step label status and profile");
                expect(node("debug-message", Label.class).getText().equals("Bereit für den nächsten Schritt."), "ready diagnostic is German");
                expect(node("debug-location", Label.class).getText().startsWith("Als Nächstes: solution.a26:"),
                        "next-source prefix translates and filename stays exact");
                expect(editor(MAIN).getText().equals(snapshot.sources().get(MAIN)), "localization never modifies source code");
                editor(MAIN).selectRange(8, 2);
                button("debug-step").fire();
                button("debug-step").fire();
                expect(session.getProgram() == snapshot && editor(MAIN).getAnchor() == 8 && editor(MAIN).getCaretPosition() == 2,
                        "stepping a translated debugger preserves snapshot identity and selection");
                expect(node("debug-state", Label.class).getText().contains("Schritte = 2 / 100 · Angehalten · Vor do/while"),
                        "halted state summary is German");
                expect(node("debug-message", Label.class).getText().equals("HALT erreicht. Die Ausgaben stimmen mit diesem Testfall überein. Die Fehlersuche schließt keine Aufgabe ab."),
                        "halt and output-match guidance are entirely German");
                expect(node("debug-location", Label.class).getText().startsWith("Angehalten: solution.a26:"), "halted location prefix is German");
                expect(tableValue(ports(), "Now", "input").equals("8") && button("debug-restart").getText().equals("Restart"),
                        "static controls and numerical inspection remain unchanged");
                button("debug-restart").fire();
                expect(node("debug-message", Label.class).getText().equals("Bereit für den nächsten Schritt."), "restart restores translated ready status");
            });

            checkFx("German mismatch step-limit and arithmetic diagnostics are complete and do not change execution", () -> {
                GameDebugSession wrong = prepare(level("german-mismatch", List.of(example(7, Map.of("input", 8, "out", 1))), 100),
                        "var input=99; begin: HALT;", Set.of(), Map.of(), 0);
                open(wrong, GameTaskLanguage.GERMAN);
                expect(node("debug-message", Label.class).getText().equals("HALT erreicht. Die Ausgaben weichen von diesem Testfall ab. Vergleiche die erwarteten und aktuellen Werte."),
                        "output mismatch guidance does not refer to English interface prose");
                expect(tableValue(ports(), "Now", "out").equals("nicht deklariert"), "missing output status is translated");
                bounded.restart();
                open(bounded, GameTaskLanguage.GERMAN);
                for (int index = 0; index < 5; index++) button("debug-step").fire();
                expect(session.getStatus() == GameDebugSession.Status.LIMIT_REACHED && session.getStepCount() == 5,
                        "localized debugger retains the exact execution budget");
                expect(node("debug-state", Label.class).getText().contains("Schrittgrenze erreicht"), "limit enum becomes readable German");
                expect(node("debug-message", Label.class).getText().equals("Die Ausführung hat das Schrittlimit erreicht."), "limit diagnostic is German");
                overflow.restart();
                open(overflow, GameTaskLanguage.GERMAN);
                button("debug-step").fire();
                expect(session.getStatus() == GameDebugSession.Status.ERROR && session.getStepCount() == 0,
                        "overflow still stops before mutation");
                expect(node("debug-state", Label.class).getText().contains(" · Fehler · "), "error enum translates");
                expect(node("debug-message", Label.class).getText().equals("Die Rechnung würde den Bereich nichtnegativer ganzer Zahlen überschreiten."),
                        "overflow diagnostic translates fully");
            });

            checkFx("German macro caller statuses and both language profiles retain working source links", () -> {
                imported.restart();
                open(imported, GameTaskLanguage.GERMAN);
                button("debug-step").fire();
                List<Hyperlink> links = callerLinks();
                expect(links.size() == 2 && links.stream().allMatch(link -> link.getText().startsWith("Aufgerufen von ")),
                        "each macro-call status translates its prefix");
                expect(links.get(0).getText().contains("solution.a26:") && links.get(1).getText().contains("toolbox:"),
                        "source paths and locations stay exact");
                links.get(0).fire();
                expect(tabs().getSelectionModel().getSelectedItem() == tab(MAIN), "translated outer caller link still navigates to main");
                links.get(1).fire();
                expect(tabs().getSelectionModel().getSelectedItem() == tab(LIBRARY_PATH), "translated inner caller link still navigates to library");
                expect(editor(LIBRARY_PATH).getText().equals(LIBRARY), "imported source remains unchanged");
                for (GameDebugSession form : List.of(oldForm, newForm)) {
                    form.restart();
                    open(form, GameTaskLanguage.GERMAN);
                    String expected = form == oldForm ? "Vor do/while" : "Strukturiert";
                    expect(stage.getTitle().endsWith(expected) && node("debug-state", Label.class).getText().endsWith(expected),
                            "German profile name agrees in title and execution state");
                    expect(editor(MAIN).getLanguageVersion() == form.getLanguageVersion(), "translated profile keeps its original compiler version");
                }
            });

            if (asynchronousError.get() != null) throw new AssertionError("Uncaught JavaFX failure", asynchronousError.get());
        } finally {
            onFx(() -> { if (debugger != null) debugger.close(); });
            Platform.exit();
            Thread.setDefaultUncaughtExceptionHandler(previousHandler);
        }
        System.out.println("GameDebuggerTest: " + passed + " passed, " + failed + " failed");
        if (failed != 0) throw new AssertionError(failed + " debugger GUI checks failed");
    }

    private static GameLevel level(String id, List<GameLevel.TestCase> cases, int maxSteps) {
        return new GameLevel(id, "GUI tests", id, "Inspect the selected case", "", "", List.of(), Set.of(), Set.of(), Map.of(), cases, maxSteps);
    }

    private static GameLevel.TestCase example(int input, Map<String, Integer> expected) {
        return new GameLevel.TestCase(Map.of("input", input), expected);
    }

    private static GameDebugSession prepare(GameLevel level, String source, Set<GameFeature> features,
            Map<String, String> libraries, int caseIndex) throws Exception {
        return GameDebugSession.prepare(level, source, features, libraries, caseIndex, () -> false);
    }

    private static void open(GameDebugSession prepared) {
        open(prepared, GameTaskLanguage.ENGLISH);
    }

    private static void open(GameDebugSession prepared, GameTaskLanguage language) {
        if (debugger != null) debugger.close();
        session = prepared;
        debugger = new GameDebugger(session, language);
        debugger.show(null);
        stage = debugger.getStage();
        layout();
    }

    private static void layout() {
        stage.getScene().getRoot().applyCss();
        stage.getScene().getRoot().layout();
    }

    private static TabPane tabs() { return node("debug-sources", TabPane.class); }
    private static Tab tab(Path path) {
        return tabs().getTabs().stream().filter(tab -> Objects.equals(path, tab.getUserData())).findFirst().orElseThrow();
    }
    private static SourceEditor editor(Path path) { return (SourceEditor) tab(path).getContent(); }
    private static Button button(String id) { return node(id, Button.class); }
    @SuppressWarnings("unchecked")
    private static TableView<Integer> memory() { return node("debug-memory", TableView.class); }
    @SuppressWarnings("unchecked")
    private static TableView<String> ports() { return node("debug-ports", TableView.class); }

    private static <T> Object tableValue(TableView<T> table, String columnName, T row) {
        int index = table.getItems().indexOf(row);
        expect(index >= 0, "table contains row " + row);
        TableColumn<T, ?> column = table.getColumns().stream().filter(candidate -> candidate.getText().equals(columnName)).findFirst().orElseThrow();
        return column.getCellObservableValue(index).getValue();
    }

    private static void expectMemory(String name, int value) {
        int address = session.getNamedAddresses().get(name);
        expect(session.getMemoryValue(address) == value && tableValue(memory(), "Value", address).equals(value),
                "memory and its displayed value agree for " + name);
    }

    private static void expectState() {
        String state = node("debug-state", Label.class).getText();
        expect(state.contains("i = " + session.getInstructionPointer() + " ·")
                        && state.contains("steps = " + session.getStepCount() + " /"), "state displays the current instruction pointer and step count");
        expect(tableValue(memory(), "Value", 0).equals(session.getInstructionPointer())
                        && tableValue(memory(), "Region", 0).equals("i"), "memory cell zero visibly contains i");
    }

    private static void expectStoppedControls() {
        for (String id : List.of("debug-step", "debug-statement-step", "debug-run")) expect(button(id).isDisabled(), id + " is disabled when execution stops");
        expect(!button("debug-restart").isDisabled(), "Restart remains available");
    }

    private static void expectRange(SourceEditor editor, String text) {
        IndexRange range = editor.getExecutionRange();
        expect(range != null && editor.getText().substring(range.getStart(), range.getEnd()).equals(text), "execution highlights exactly " + text);
    }

    private static void expectCaller(SourceEditor editor, String text) {
        expect(editor.getCallerRanges().stream().anyMatch(range -> editor.getText().substring(range.getStart(), range.getEnd()).equals(text)),
                "caller highlights " + text);
    }

    private static List<Hyperlink> callerLinks() {
        return node("debug-callers", FlowPane.class).getChildren().stream().filter(Hyperlink.class::isInstance).map(Hyperlink.class::cast).toList();
    }

    private static <T extends Node> T node(String id, Class<T> type) {
        Node found = stage.getScene().lookup("#" + id);
        expect(type.isInstance(found), "missing " + type.getSimpleName() + " #" + id);
        return type.cast(found);
    }

    private static void await(BooleanSupplier condition) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        onFx(() -> {
            Timeline poll = new Timeline();
            poll.getKeyFrames().add(new KeyFrame(Duration.millis(20), event -> {
                if (condition.getAsBoolean()) { poll.stop(); done.countDown(); }
            }));
            poll.setCycleCount(250);
            poll.play();
        });
        expect(done.await(6, TimeUnit.SECONDS), "debugger did not reach the expected state");
    }

    private static void awaitDelay(int milliseconds) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        onFx(() -> {
            PauseTransition pause = new PauseTransition(Duration.millis(milliseconds));
            pause.setOnFinished(event -> done.countDown());
            pause.play();
        });
        expect(done.await(5, TimeUnit.SECONDS), "JavaFX delay did not finish");
    }

    private static void writeSnapshot(Path path) throws Exception {
        layout();
        WritableImage snapshot = stage.getScene().snapshot(null);
        BufferedImage image = new BufferedImage((int) snapshot.getWidth(), (int) snapshot.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++)
            image.setRGB(x, y, snapshot.getPixelReader().getArgb(x, y));
        expect(ImageIO.write(image, "png", path.toFile()), "PNG writer is available");
    }

    private static void onFx(Check action) throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> { action.run(); return null; });
        Platform.runLater(task);
        try { task.get(10, TimeUnit.SECONDS); }
        catch (ExecutionException failure) {
            if (failure.getCause() instanceof Exception exception) throw exception;
            if (failure.getCause() instanceof Error error) throw error;
            throw new AssertionError(failure.getCause());
        }
    }

    private static void checkFx(String name, Check action) { check(name, () -> onFx(action)); }
    private static void check(String name, Check action) {
        try { action.run(); passed++; System.out.println("PASS " + name); }
        catch (Exception | AssertionError error) { failed++; System.err.println("FAIL " + name + ": " + error); error.printStackTrace(System.err); }
    }
    private static void expect(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}

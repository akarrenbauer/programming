package machine;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import javafx.animation.AnimationTimer;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.IndexRange;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;

/** A read-only debugger assembled from the simulator's editor, machine, and source-map components. */
public final class GameDebugger {
    private final GameDebugSession session;
    private final GameTaskLanguage language;
    private final Stage stage = new Stage();
    private final TabPane sources = new TabPane();
    private final Map<Path, SourceEditor> editors = new LinkedHashMap<>();
    private final Map<Path, Tab> tabs = new LinkedHashMap<>();
    private final TableView<Integer> memory = new TableView<>();
    private final TableView<String> ports = new TableView<>();
    private final Button step = new Button("Step");
    private final Button statement = new Button("Step statement");
    private final Button stepOver = new Button("Step over");
    private final Button run = new Button("Run");
    private final Button restart = new Button("Restart");
    private final Label state = new Label();
    private final Label message = new Label();
    private final Label location = new Label();
    private final FlowPane callers = new FlowPane(8, 3);
    private AnimationTimer timer;
    private SourceTrace displayedTrace;
    private boolean initializedOwner;

    /** Construct and use this window on the JavaFX application thread. Preparation belongs on a worker. */
    public GameDebugger(GameDebugSession session) {
        this(session, GameTaskLanguage.ENGLISH);
    }

    /** Task text and execution status follow this choice; static controls and source stay in English. */
    public GameDebugger(GameDebugSession session, GameTaskLanguage language) {
        this.session = Objects.requireNonNull(session, "session");
        this.language = Objects.requireNonNull(language, "language");
        String taskTitle = GameTranslations.text(session.getLevel(), language).title();
        String testCase = text("case ", "Testfall ") + (session.getCaseIndex() + 1);
        stage.setTitle("Language Game Debugger — " + taskTitle + " — " + testCase + " — " + languageVersionTitle());
        stage.setMinWidth(900);
        stage.setMinHeight(650);
        stage.setOnHidden(event -> pause());
        BorderPane root = new BorderPane();
        root.setPadding(new Insets(12));
        root.setStyle("-fx-font-family: sans-serif;");
        Label title = new Label(taskTitle + " · " + testCase);
        title.setId("debug-title");
        title.setStyle("-fx-font-size: 19px; -fx-font-weight: bold;");
        Label inputs = new Label(text("Read-only snapshot · Test inputs: ", "Schreibgeschützte Momentaufnahme · Testeingaben: ")
                + session.getTestCase().inputs());
        inputs.setId("debug-inputs");
        inputs.setWrapText(true);
        step.setId("debug-step");
        statement.setId("debug-statement-step");
        stepOver.setId("debug-step-over");
        run.setId("debug-run");
        restart.setId("debug-restart");
        state.setId("debug-state");
        message.setId("debug-message");
        message.setWrapText(true);
        location.setId("debug-location");
        location.setWrapText(true);
        callers.setId("debug-callers");
        step.setOnAction(event -> { session.step(); refresh(); });
        statement.setTooltip(new Tooltip("Run until the source range or macro call changes, stopping after at most 10,000 instructions."));
        statement.setOnAction(event -> { session.stepStatement(10_000); refresh(); });
        stepOver.setTooltip(new Tooltip("Finish the macro call from your program, including nested calls, or advance one source statement. Use Pause to stop a long call."));
        stepOver.setOnAction(event -> startExecution(session.newStepOver()));
        run.setOnAction(event -> { if (timer == null) startRun(); else pause(); });
        restart.setOnAction(event -> {
            pause();
            session.restart();
            displayedTrace = null;
            refresh();
        });
        VBox heading = new VBox(7, title, inputs, new HBox(8, step, statement, stepOver, run, restart), state);
        heading.setPadding(new Insets(0, 0, 10, 0));
        root.setTop(heading);
        configureSources();
        configureMemory();
        configurePorts();
        Label portTitle = new Label("Variables");
        portTitle.setId("debug-variables-title");
        portTitle.setTooltip(new Tooltip("All declared cells. Local names include their memory address; hover a row for its declaration and callers."));
        Label memoryTitle = new Label("Memory · address 0 contains i");
        VBox inspection = new VBox(7, portTitle, ports, memoryTitle, memory);
        ports.setPrefHeight(320);
        ports.setMinHeight(120);
        VBox.setVgrow(ports, Priority.ALWAYS);
        VBox.setVgrow(memory, Priority.ALWAYS);
        SplitPane split = new SplitPane(sources, inspection);
        split.setDividerPositions(0.65);
        root.setCenter(split);
        VBox footer = new VBox(5, location, callers, message);
        footer.setPadding(new Insets(10, 0, 0, 0));
        root.setBottom(footer);
        stage.setScene(new Scene(root, 1220, 820));
        refresh();
    }

    public Stage getStage() { return stage; }

    public void show(Window owner) {
        if (!initializedOwner) {
            if (owner != null) stage.initOwner(owner);
            initializedOwner = true;
        }
        stage.show();
        stage.toFront();
    }

    public void close() {
        pause();
        stage.close();
    }

    private void configureSources() {
        sources.setId("debug-sources");
        sources.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        session.getProgram().sources().forEach((path, text) -> {
            SourceEditor editor = new SourceEditor(text);
            editor.setLanguageVersion(session.getLanguageVersion());
            editor.setEditable(false);
            editor.setAccessibleText(text("Debug source snapshot for ", "Quelltext-Momentaufnahme für ") + path);
            Tab tab = new Tab(path.getFileName().toString(), editor);
            tab.setUserData(path);
            tab.setTooltip(new Tooltip(path.toString()));
            editors.put(path, editor);
            tabs.put(path, tab);
            sources.getTabs().add(tab);
        });
    }

    private void configureMemory() {
        memory.setId("debug-memory");
        memory.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        TableColumn<Integer, Integer> addresses = new TableColumn<>("Address");
        addresses.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue()));
        TableColumn<Integer, Integer> values = new TableColumn<>("Value");
        values.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(session.getMemoryValue(cell.getValue())));
        TableColumn<Integer, String> regions = new TableColumn<>("Region");
        regions.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue() == 0 ? "i"
                : cell.getValue() < session.getProgram().codeStart() ? "Data" : "Code"));
        memory.getColumns().add(addresses);
        memory.getColumns().add(values);
        memory.getColumns().add(regions);
        for (int address = 0; address < session.getMemorySize(); address++) memory.getItems().add(address);
        memory.setRowFactory(table -> new TableRow<>() {
            @Override protected void updateItem(Integer address, boolean empty) {
                super.updateItem(address, empty);
                setStyle(!empty && address != null && address == session.getInstructionPointer()
                        ? "-fx-background-color: #d9efdf;" : "");
            }
        });
    }

    private void configurePorts() {
        ports.setId("debug-ports");
        ports.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        addPortColumn("Cell", name -> name);
        addPortColumn("Address", name -> format(session.getNamedAddresses().get(name)));
        addPortColumn("Input", name -> format(session.getTestCase().inputs().get(name)));
        addPortColumn("Expected", name -> format(session.getTestCase().expected().get(name)));
        addPortColumn("Now", name -> {
            Integer address = session.getNamedAddresses().get(name);
            return address == null ? text("undefined", "nicht deklariert") : String.valueOf(session.getMemoryValue(address));
        });
        Set<String> names = new LinkedHashSet<>(session.getTestCase().inputs().keySet());
        names.addAll(session.getTestCase().expected().keySet());
        names.addAll(session.getNamedAddresses().keySet());
        ports.getItems().addAll(names);
        ports.setRowFactory(table -> new TableRow<>() {
            @Override protected void updateItem(String name, boolean empty) {
                super.updateItem(name, empty);
                SourceTrace source = empty || name == null ? null : session.getVariableSource(name);
                if (source == null) { setTooltip(null); return; }
                StringBuilder detail = new StringBuilder(text("Declared at ", "Deklariert in ")).append(describe(source.location()));
                for (SourceLocation caller : source.callers()) detail.append('\n').append(text("Called from ", "Aufgerufen von ")).append(describe(caller));
                setTooltip(new Tooltip(detail.toString()));
            }
        });
    }

    private void addPortColumn(String title, Function<String, String> text) {
        TableColumn<String, String> column = new TableColumn<>(title);
        column.setPrefWidth(title.equals("Cell") ? 140 : 65);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(text.apply(cell.getValue())));
        ports.getColumns().add(column);
    }

    private static String format(Integer value) { return value == null ? "—" : value.toString(); }

    private void startRun() {
        startExecution(null);
    }

    private void startExecution(GameDebugSession.StepOver operation) {
        if (session.isStopped() || timer != null) return;
        timer = new AnimationTimer() {
            @Override public void handle(long now) {
                long deadline = System.nanoTime() + 6_000_000;
                int instructions = 0;
                while (!session.isStopped() && instructions++ < 2000 && System.nanoTime() < deadline) {
                    if (operation == null) session.step();
                    else {
                        operation.step(1);
                        if (operation.isComplete()) break;
                    }
                }
                if (session.isStopped() || operation != null && operation.isComplete()) pause();
                refresh();
                if (operation != null && !session.isStopped()) revealStepOverCaller();
            }
        };
        timer.start();
        refreshControls();
        if (operation != null) revealStepOverCaller();
    }

    private void revealStepOverCaller() {
        SourceTrace trace = session.getCurrentTrace();
        if (trace != null && !trace.callers().isEmpty()) reveal(trace.callers().get(0));
    }

    private void pause() {
        if (timer != null) {
            timer.stop();
            timer = null;
        }
        refreshControls();
    }

    private void refreshControls() {
        boolean running = timer != null;
        step.setDisable(running || session.isStopped());
        statement.setDisable(running || session.isStopped());
        stepOver.setDisable(running || session.isStopped());
        run.setDisable(session.isStopped());
        run.setText(running ? "Pause" : "Run");
    }

    private void refresh() {
        refreshControls();
        state.setText("i = " + session.getInstructionPointer() + text(" · steps = ", " · Schritte = ") + session.getStepCount()
                + " / " + session.getMaxSteps() + " · " + statusTitle() + " · " + languageVersionTitle());
        String detail = GameMessages.diagnostic(language, session.getMessage());
        if (session.getStatus() == GameDebugSession.Status.HALTED) {
            detail += session.getOutputValues().equals(session.getTestCase().expected())
                    ? text(" Outputs match this case. Debugging does not complete a level.",
                            " Die Ausgaben stimmen mit diesem Testfall überein. Die Fehlersuche schließt keine Aufgabe ab.")
                    : text(" Outputs differ from this case. Compare Expected and Now.",
                            " Die Ausgaben weichen von diesem Testfall ab. Vergleiche die erwarteten und aktuellen Werte.");
        }
        message.setText(detail);
        message.setStyle(session.getStatus() == GameDebugSession.Status.ERROR || session.getStatus() == GameDebugSession.Status.LIMIT_REACHED
                ? "-fx-text-fill: #a62c2c;" : "");
        ports.refresh();
        memory.refresh();
        memory.scrollTo(Math.max(0, session.getInstructionPointer() - 2));
        showTrace();
    }

    private void showTrace() {
        SourceTrace trace = session.getCurrentTrace();
        if (trace != null && Objects.equals(displayedTrace, trace)) {
            // Keep a queued offscreen reveal alive across refreshes of the same instruction range.
            SourceEditor editor = editors.get(trace.location().file());
            if (editor != null) editor.setExecutionRange(trace.location().startOffset(), trace.location().endOffset(),
                    session.getStatus() == GameDebugSession.Status.HALTED);
            location.setText(locationPrefix() + describe(trace.location()));
            return;
        }
        editors.values().forEach(SourceEditor::clearExecutionHighlights);
        callers.getChildren().clear();
        if (trace == null) {
            location.setText(text("No source location is available for this instruction pointer.",
                    "Für diesen Befehlszeiger ist keine Quelltextstelle verfügbar."));
            displayedTrace = null;
            return;
        }
        SourceLocation current = trace.location();
        SourceEditor currentEditor = editors.get(current.file());
        if (currentEditor != null) {
            currentEditor.setExecutionRange(current.startOffset(), current.endOffset(), session.getStatus() == GameDebugSession.Status.HALTED);
            if (!Objects.equals(displayedTrace, trace)) reveal(current);
        }
        Map<Path, List<IndexRange>> callerRanges = new LinkedHashMap<>();
        for (SourceLocation caller : trace.callers()) {
            callerRanges.computeIfAbsent(caller.file(), unused -> new ArrayList<>()).add(new IndexRange(caller.startOffset(), caller.endOffset()));
            Hyperlink link = new Hyperlink(text("Called from ", "Aufgerufen von ") + describe(caller));
            link.setOnAction(event -> reveal(caller));
            callers.getChildren().add(link);
        }
        callerRanges.forEach((path, ranges) -> {
            SourceEditor editor = editors.get(path);
            if (editor != null) editor.setCallerRanges(ranges);
        });
        location.setText(locationPrefix() + describe(current));
        displayedTrace = trace;
    }

    private String text(String english, String german) {
        return language == GameTaskLanguage.GERMAN ? german : english;
    }

    private String languageVersionTitle() {
        if (language != GameTaskLanguage.GERMAN) return session.getLanguageVersion().toString();
        return session.getLanguageVersion() == LanguageVersion.BEFORE_DO_WHILE ? "Vor do/while" : "Strukturiert";
    }

    private String statusTitle() {
        if (language != GameTaskLanguage.GERMAN) return session.getStatus().toString();
        return switch (session.getStatus()) {
            case READY -> "Bereit";
            case HALTED -> "Angehalten";
            case LIMIT_REACHED -> "Schrittgrenze erreicht";
            case ERROR -> "Fehler";
        };
    }

    private String locationPrefix() {
        return session.getStatus() == GameDebugSession.Status.HALTED
                ? text("Halted: ", "Angehalten: ") : text("Next: ", "Als Nächstes: ");
    }

    private static String describe(SourceLocation location) {
        return location.file().getFileName() + ":" + location.line() + ":" + location.column();
    }

    private void reveal(SourceLocation source) {
        SourceEditor editor = editors.get(source.file());
        if (editor == null) return;
        sources.getSelectionModel().select(tabs.get(source.file()));
        editor.revealSourceOffset(source.startOffset());
    }
}

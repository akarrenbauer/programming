package machine;

import javafx.application.Application;
import javafx.application.Platform;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import javafx.geometry.*;

import javafx.scene.layout.*;
import javafx.scene.shape.*;
import javafx.scene.control.*;
import javafx.scene.Scene;
import javafx.scene.Node;
import javafx.scene.paint.Color;
import javafx.scene.input.MouseButton;
import javafx.scene.input.*;
import javafx.scene.layout.VBox;
import javafx.scene.layout.Region;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.ScrollBar;

import javafx.util.converter.IntegerStringConverter;

import javafx.stage.Stage;
import javafx.stage.FileChooser;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MachineGUI extends Application {

    private final Machine machine = new Machine();
    private TableView<MemoryEntry> memoryTable;
    private ObservableList<MemoryEntry> memoryData;
    StackPane tableWithTopBtn;
    private Label cpuLabel;
    private Label instructionSymbolLabel;
    private CustomPane customPane;
    private ScrollBar vScrollBar;
    private Stage currentStage;

    private AnimationTimer runTimer;
    private volatile boolean stopRequested = false;

    private Label stepCounterLabel;

    private SourceWorkspace sourceWorkspace;
    private TabPane sourceTabs;
    private Label sourcePathLabel;
    private Label sourceStatusLabel;
    private Label executionStatusLabel;
    private FlowPane executionCallers;
    private CheckBox followExecution;
    private CompilationResult compiledProgram;
    private FlowchartExecution diagramExecution;
    private SourceTrace followedTrace;
    private SourceTrace displayedTrace;
    private boolean displayedHalted;
    private Button saveSourceButton;
    private Button compileButton;
    private final Map<SourceWorkspace.Document, Tab> sourceEditors = new LinkedHashMap<>();
    private ComboBox<String> diagramMode;
    private SplitPane sourceDiagramEditors;
    private SplitPane machineWorkspace;
    private double sourceOnlyDivider = .45;
    private final Map<SourceWorkspace.Document, SourceDiagrams> sourceDiagrams = new LinkedHashMap<>();
    private SourceWorkspace.Document displayedDiagramDocument;
    private String displayedDiagramMode;
    private boolean applyingDiagramSource;
    private final List<Control> controlsDisabledWhileRunning = new ArrayList<>();
    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage primaryStage) {
        primaryStage.setTitle("Machine Simulator");
        currentStage = primaryStage;

        customPane = new CustomPane();

        memoryTable = new TableView<>();
        memoryTable.setRowFactory(tv -> {
                    TableRow<MemoryEntry> row = new TableRow<>();
                    row.itemProperty().addListener((obs, prevItem, currentItem) -> {
                                if (row.getIndex() == 0) {
                                    // Set the background of the first row to light orange
                                    row.setStyle("-fx-background-color: #FFD580;");
                                    row.getStyleClass().add("instruction-pointer-register");
                                } else if (row.getIndex() == machine.getInstructionPointer()) {
                                    // Set the background of the first row to light orange
                                    row.setStyle("-fx-background-color: #90EE90;");
                                } else if (machine.getInstructionPointer() < row.getIndex() && 
                                row.getIndex() <= machine.getInstructionPointer()+machine.getNumberOfArguments()) {
                                    // Set the background of the first row to light orange
                                    row.setStyle("-fx-background-color: #CBF7C7;");
                                } else {
                                    // Reset the style for other rows
                                    row.setStyle("");
                                }
                        });
                    return row;
            });

        Node contentNode = memoryTable;

        // Wrap with an overlay container
        tableWithTopBtn = new StackPane(contentNode);

        Button toTopBtn = new Button("T");
        toTopBtn.setFocusTraversable(false);
        toTopBtn.setTooltip(new Tooltip("Scroll to top"));
        toTopBtn.getStyleClass().add("table-top-btn"); // optional style

        toTopBtn.setOnAction(e -> {
                    memoryTable.scrollTo(0);
            });

        // Position at top-right
        StackPane.setAlignment(toTopBtn, Pos.TOP_RIGHT);

        tableWithTopBtn.getChildren().add(toTopBtn);
        tableWithTopBtn.setStyle("""
                -fx-padding: 2 8 2 8;
                -fx-font-size: 11px;
                -fx-background-radius: 14;""");

        memoryData = FXCollections.observableArrayList();
        memoryData.add( new MemoryEntry(0, 0 ) );
        machine.set( 0, 0 );

        memoryTable.setItems(memoryData);
        memoryTable.setEditable(true);

        TableColumn<MemoryEntry, Integer> addressColumn = new TableColumn<>("Address");
        addressColumn.setCellValueFactory(new PropertyValueFactory<>("address"));
        TableColumn<MemoryEntry, Integer> valueColumn = new TableColumn<>("Value");
        valueColumn.setCellValueFactory(new PropertyValueFactory<>("value"));
        // Enable double-click on empty cells in value column to add a new entry
        valueColumn.setCellFactory(column -> {
                    TableCell<MemoryEntry, Integer> cell = new TextFieldTableCell<>(new IntegerStringConverter());
                    cell.setOnMouseClicked(event -> {
                                if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 1 && cell.isEmpty()) {
                                    // Calculate the next address based on current data size
                                    int nextAddress = memoryData.size();
                                    MemoryEntry newEntry = new MemoryEntry(nextAddress, null); // Start with an empty value
                                    memoryData.add(newEntry);

                                    // Add the new entry to the machine's memory model
                                    machine.set(nextAddress, 0); // Initialize with zero or any default value
                                    updateSourceExecution();

                                    // Refresh the table view and start editing the new cell
                                    memoryTable.refresh();
                                    memoryTable.edit(nextAddress, valueColumn);
                                }
                        });
                    return cell;
            });
        valueColumn.setOnEditCommit(event -> {
                    MemoryEntry entry = event.getRowValue();
                    int newValue = event.getNewValue();
                    int previousValue = machine.get(entry.getAddress());
                    entry.setValue(newValue);

                    // Update the underlying machine memory
                    machine.set(entry.getAddress(), newValue);
                    if (entry.getAddress() == 0 && previousValue != newValue && diagramExecution != null) {
                        diagramExecution.resetHistory(machine);
                    }

                    // Refresh table view to reflect updated value
                    memoryTable.refresh();
                    updateSourceExecution();
            });
        // Bind the table's width to the sum of its column widths (with some padding)
        memoryTable.prefWidthProperty().bind(addressColumn.widthProperty().add(valueColumn.widthProperty()).add(15));

        memoryTable.getColumns().addAll(addressColumn, valueColumn);

        cpuLabel = new Label("CPU");
        cpuLabel.setStyle("-fx-background-color: white; -fx-border-color: black; -fx-padding: 10px;");

        primaryStage.setScene(createScene(primaryStage));
        primaryStage.setOnCloseRequest(event -> {
            if (!confirmSaveSources()) {
                event.consume();
            }
        });
        primaryStage.show();
    }

    @Override
    public void stop() {
        if (runTimer != null) {
            runTimer.stop();
            runTimer = null;
        }
    }

    private Scene createScene(Stage primaryStage) {

        Button saveButton = new Button("Save");
        Button loadButton = new Button("Load");
        Button reloadButton = new Button("Reload");
        Button openButton = new Button("Open");
        openButton.setId("open-source");
        compileButton = new Button("Compile");
        compileButton.setId("compile");
        compileButton.setDisable(true);
        Button resetButton = new Button("Reset");
        Button runButton = new Button("Run");
        Button stopButton = new Button("Stop");
        Button stepButton = new Button("Step");
        Button stepNButton = new Button("Step n");

        saveButton.setOnAction(e -> saveMemoryToFile());

        loadButton.setOnAction(e -> loadMemoryFromFile(primaryStage));

        reloadButton.setOnAction(e -> {
                    machine.reload();
                    invalidateSourceMapping("Memory reloaded. Compile to restore source tracking.");
                    updateMemoryDisplay();
                    sourceStatusLabel.setText("Memory reloaded. Compile to run the source tabs.");
            });

        openButton.setOnAction(e -> openSourceChooser(primaryStage));

        compileButton.setOnAction(e -> compileSourceTabs());
        compileButton.setTooltip(new Tooltip("Compile the active main file and its imports from the source tabs"));

        resetButton.setOnAction(e -> {
                    machine.reset();
                    invalidateSourceMapping("Memory reset. Compile to restore source tracking.");
                    updateMemoryDisplay();
                    sourceStatusLabel.setText("Memory reset. Compile to run the source tabs.");
            });

        stopButton.setDisable(true); // only enabled while running
        stopButton.setOnAction(e -> {
                    // Stop right now ? don?t wait for next frame
                    finishRun(runButton, stopButton, stepButton, stepNButton );
            });

        stepButton.setOnAction(e -> {
                    if (runTimer != null) return; // ignore while running
                    stepMachine(1);
                    updateMemoryDisplay();
            });

        stepNButton.setOnAction(e -> {
                    TextInputDialog dialog = new TextInputDialog("1");
                    dialog.setTitle("Step n");
                    dialog.setHeaderText("How many steps?");
                    dialog.setContentText("n:");

                    dialog.showAndWait().ifPresent(input -> {
                                try {
                                    int n = Integer.parseInt(input.trim());
                                    if (n < 0) throw new NumberFormatException("negative");
                                    stepMachine(n);
                                    updateMemoryDisplay();
                                } catch (NumberFormatException ex) {
                                    Alert alert = new Alert(Alert.AlertType.ERROR, "Please enter a non-negative integer.", ButtonType.OK);
                                    alert.setTitle("Invalid number");
                                    alert.setHeaderText("Invalid number");
                                    alert.showAndWait();
                                }
                        });
            });

        runButton.setOnAction(e -> {
                    if (machine.isHalted()) {
                        new Alert(Alert.AlertType.INFORMATION, "Machine is already halted.", ButtonType.OK).showAndWait();
                        return;
                    }
                    if (runTimer != null) return;   // already running

                    stopRequested = false;

                    // UI while running
                    runButton.setDisable(true);
                    stopButton.setDisable(false);
                    stepButton.setDisable(true);
                    stepNButton.setDisable(true);
                    controlsDisabledWhileRunning.forEach(button -> button.setDisable(true));

                    runTimer = new AnimationTimer() {
                        private long lastUpdateNs = 0;

                        @Override
                        public void handle(long now) {
                            if (stopRequested || machine.isHalted()) {
                                finishRun(runButton, stopButton, stepButton, stepNButton );
                                return;
                            }
                            try {
                                stepMachine(10000);
                            } catch (Exception ex) {
                                // stop & report
                                finishRun(runButton, stopButton, stepButton, stepNButton );
                                new Alert(Alert.AlertType.ERROR, "Error while stepping:\n" + ex.getMessage(), ButtonType.OK).showAndWait();
                                return;
                            }

                            // Throttle UI refresh ~20 fps
                            if (now - lastUpdateNs > 50_000_000L) {
                                updateMemoryDisplay();
                                lastUpdateNs = now;
                            }
                        }
                    };
                    runTimer.start();
            });

        // Initialize the instruction symbol label
        instructionSymbolLabel = new Label(machine.getInstructionSymbolAndArguments());
        instructionSymbolLabel.setStyle("-fx-padding: 5px;");

        // NEW: step counter label
        stepCounterLabel = new Label("Steps executed: " + machine.getStepCounter());
        stepCounterLabel.setStyle("-fx-padding: 5px;");

        // Create processor VBox and add all labels
        VBox processor = new VBox(1, cpuLabel, instructionSymbolLabel, stepCounterLabel);
        processor.setAlignment(Pos.CENTER_LEFT);

        HBox hbox = new HBox(2, tableWithTopBtn, processor);
        HBox.setHgrow(tableWithTopBtn, Priority.ALWAYS);
        HBox.setHgrow(processor, Priority.NEVER);
        HBox.setMargin(processor, new Insets(0, 0, 0, 50)); // Add some spacing between the table and the box.

        customPane.getChildren().add(hbox);
        customPane.setMinWidth(400);
        hbox.prefWidthProperty().bind(customPane.widthProperty());
        tableWithTopBtn.prefHeightProperty().bind(customPane.heightProperty().subtract(10));

        machineWorkspace = new SplitPane(customPane, createSourceEditor());
        machineWorkspace.setDividerPositions(sourceOnlyDivider);
        FlowPane buttonBox = new FlowPane(5, 5, saveButton, loadButton, reloadButton, openButton,
                compileButton, resetButton, runButton, stopButton, stepButton, stepNButton);
        buttonBox.setPadding(new Insets(6));
        VBox vbox = new VBox(2, buttonBox, machineWorkspace);
        VBox.setVgrow(machineWorkspace, Priority.ALWAYS);
        controlsDisabledWhileRunning.addAll(List.of(saveButton, loadButton, reloadButton,
                openButton, compileButton, resetButton));

        Scene scene = new Scene(vbox, 1100, 650);
        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.HOME && !event.isControlDown()
                    && !event.isAltDown() && !event.isMetaDown() && !event.isShiftDown()
                    && !(scene.getFocusOwner() instanceof TextInputControl)) {
                memoryTable.scrollTo(0);
                event.consume();
            }
        });
        scene.getAccelerators().put(new KeyCodeCombination(KeyCode.S, KeyCombination.SHORTCUT_DOWN),
                this::saveSelectedSource);

        return scene;
    }

    private void stopRunnerIfActive(Button runButton, Button stopButton, Button stepButton, Button stepNButton ) {
        stopRequested = true;
        if (runTimer != null) {
            runTimer.stop();
            runTimer = null;
        }
        runButton.setDisable(false);
        stopButton.setDisable(true);
        stepButton.setDisable(false);
        stepNButton.setDisable(false);
        restoreSourceControls();
        updateMemoryDisplay();
    }

    private void finishRun(Button runButton, Button stopButton, Button stepButton, Button stepNButton ) {
        if (runTimer != null) {
            try { runTimer.stop(); } catch (Exception ignore) {}
            runTimer = null;
        }
        stopRequested = false;             // clear the flag so Step works again
        runButton.setDisable(false);
        stopButton.setDisable(true);
        stepButton.setDisable(false);
        stepNButton.setDisable(false);
        restoreSourceControls();
        updateMemoryDisplay();
    }

    private void restoreSourceControls() {
        controlsDisabledWhileRunning.forEach(button -> button.setDisable(false));
        updateCompileAvailability();
    }

    private void updateCompileAvailability() {
        compileButton.setDisable(runTimer != null || sourceWorkspace == null
                || sourceWorkspace.getMainDocument() == null);
    }

    // Save memory data to a file
    private void saveMemoryToFile() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Save Memory Data");
        fileChooser.setInitialFileName(machine.getFilename() != null ? machine.getFilename() : "program.txt");
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Text Files", "*.txt"));

        File file = fileChooser.showSaveDialog(currentStage);
        if (file != null) {
            try (FileWriter writer = new FileWriter(file)) {
                for (MemoryEntry entry : memoryData) {
                    writer.write( (entry.getValue() != null ? entry.getValue() : 0) + "\n");
                }
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }
    }

    private void loadMemoryFromFile(Stage primaryStage) {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Open Memory File");
        fileChooser.setInitialDirectory(new File(System.getProperty("user.dir"))); // Starts in the current directory
        File selectedFile = fileChooser.showOpenDialog(primaryStage);

        if (selectedFile != null) {
            machine.load(selectedFile.getAbsolutePath());
            invalidateSourceMapping("Memory loaded. Compile to restore source tracking.");
            updateMemoryDisplay();
            sourceStatusLabel.setText("Memory loaded. Compile to run the source tabs.");
            //refreshScene(primaryStage);
        }
    }

    private void openSourceChooser(Stage primaryStage) {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Open Source File");
        fileChooser.setInitialDirectory(new File(System.getProperty("user.dir"))); // Starts in the current directory
        fileChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("A26 source files", "*.a26"),
                new FileChooser.ExtensionFilter("Legacy source files", "*.ad04"),
                new FileChooser.ExtensionFilter("All files", "*"));
        File selectedFile = fileChooser.showOpenDialog(primaryStage);

        if (selectedFile != null) {
            openSourceFile(selectedFile.toPath());
        }
    }

    private VBox createSourceEditor() {
        sourceTabs = new TabPane();
        sourceTabs.setId("source-tabs");
        sourceTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        sourceTabs.getSelectionModel().selectedItemProperty().addListener(
                (observable, previous, selected) -> updateSourceSelection());
        sourceTabs.addEventHandler(MouseEvent.MOUSE_CLICKED, event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2
                    && event.getTarget() instanceof Node target) {
                // The skin selects the tab on mouse press. Only header clicks
                // activate it; double-clicking editor text still selects words.
                for (Node node = target; node != null && node != sourceTabs; node = node.getParent()) {
                    if (node.getStyleClass().contains("tab")) {
                        activateSource(selectedSourceDocument());
                        event.consume();
                        break;
                    }
                }
            }
        });
        Label emptyMessage = new Label("Choose New to start a file, or Open to edit an existing one.");
        emptyMessage.setPadding(new Insets(16));
        sourceTabs.getTabs().add(new Tab("Source", emptyMessage));

        Button newSourceButton = new Button("New");
        newSourceButton.setId("new-source");
        newSourceButton.setTooltip(new Tooltip("Open an empty, unnamed source tab"));
        newSourceButton.setOnAction(event -> newSource());

        saveSourceButton = new Button("Save source");
        saveSourceButton.setId("save-source");
        saveSourceButton.setDisable(true);
        saveSourceButton.setTooltip(new Tooltip("Save the selected source tab (Ctrl+S / Command+S)"));
        saveSourceButton.setOnAction(event -> saveSelectedSource());
        followExecution = new CheckBox("Follow execution");
        followExecution.setId("follow-execution");
        followExecution.setSelected(true);
        followExecution.setTooltip(new Tooltip("Show the next instruction's source without moving the caret"));
        followExecution.selectedProperty().addListener((observable, previous, selected) -> {
            followedTrace = null;
            updateSourceExecution();
        });
        diagramMode = new ComboBox<>();
        diagramMode.setId("source-diagram-mode");
        diagramMode.getItems().setAll("Source only", "Flowchart", "Nassi–Shneiderman");
        diagramMode.setValue("Source only");
        diagramMode.setAccessibleText("Source diagram view");
        diagramMode.setTooltip(new Tooltip("Show an editable diagram beside the selected source file"));
        diagramMode.valueProperty().addListener((observable, before, after) -> refreshSelectedDiagram());
        FlowPane toolbar = new FlowPane(8, 6, new Label("Source files"), newSourceButton,
                saveSourceButton, followExecution, new Label("Diagram:"), diagramMode);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(6));

        sourcePathLabel = new Label();
        sourcePathLabel.setMaxWidth(Double.MAX_VALUE);
        sourcePathLabel.setPadding(new Insets(4, 8, 0, 8));
        sourceStatusLabel = new Label("No source program open.");
        sourceStatusLabel.setId("source-status");
        sourceStatusLabel.setWrapText(true);
        sourceStatusLabel.setPadding(new Insets(4, 8, 8, 8));

        executionStatusLabel = new Label("Compile a source program to track execution.");
        executionStatusLabel.setId("execution-status");
        executionStatusLabel.setWrapText(true);
        executionStatusLabel.setPadding(new Insets(4, 8, 0, 8));
        executionCallers = new FlowPane(4, 2);
        executionCallers.setId("execution-callers");
        executionCallers.setPadding(new Insets(0, 8, 0, 8));

        sourceDiagramEditors = new SplitPane(sourceTabs);
        sourceDiagramEditors.setId("source-diagram-editors");
        sourceTabs.setMinWidth(180);
        VBox editor = new VBox(toolbar, executionStatusLabel, executionCallers,
                sourceDiagramEditors, sourcePathLabel, sourceStatusLabel);
        editor.setMinWidth(300);
        VBox.setVgrow(sourceDiagramEditors, Priority.ALWAYS);
        return editor;
    }

    private void newSource() {
        SourceWorkspace.Document document;
        if (sourceWorkspace == null) {
            sourceWorkspace = SourceWorkspace.create();
            document = sourceWorkspace.getDocuments().get(0);
            sourceTabs.getTabs().clear();
        } else {
            document = sourceWorkspace.newDocument();
        }
        refreshSourceTabs();
        Tab tab = sourceEditors.get(document);
        sourceTabs.getSelectionModel().select(tab);
        updateCompileAvailability();
        sourceStatusLabel.setText("New source. Save source to choose a filename.");
        Platform.runLater(() -> {
            if (sourceTabs.getSelectionModel().getSelectedItem() == tab) {
                tab.getContent().requestFocus();
            }
        });
    }

    void openSourceFile(Path path) {
        if (runTimer != null) {
            return;
        }
        try {
            SourceWorkspace.Document document;
            if (sourceWorkspace == null) {
                sourceWorkspace = SourceWorkspace.open(path);
                document = sourceWorkspace.getDocuments().get(0);
                sourceTabs.getTabs().clear();
            } else {
                document = sourceWorkspace.openDocument(path);
            }
            refreshSourceTabs();
            sourceTabs.getSelectionModel().select(sourceEditors.get(document));
            if (document.hasBeginMarker()) {
                activateSource(document);
            } else {
                sourceStatusLabel.setText("Opened " + document.getDisplayName()
                        + ". A main file needs a top-level begin: marker.");
            }
        } catch (IOException | RuntimeException exception) {
            showSourceError("Source file error", exception);
        }
    }

    private void activateSource(SourceWorkspace.Document document) {
        if (document == null) {
            return;
        }
        if (runTimer != null) {
            sourceStatusLabel.setText("Stop execution before changing the main file.");
            return;
        }
        if (!document.hasBeginMarker()) {
            sourceStatusLabel.setText("Cannot activate " + document.getDisplayName()
                    + ": a main file needs a top-level begin: marker.");
            return;
        }
        sourceWorkspace.setMainDocument(document);
        invalidateSourceMapping("Main file activated. Compile to track execution.");
        refreshSourceTabs();
        compileSourceTabs();
    }

    private void compileSourceTabs() {
        if (sourceWorkspace == null || runTimer != null) {
            return;
        }
        if (sourceWorkspace.getMainDocument() == null) {
            sourceStatusLabel.setText("No active main file. Add begin: to a source file and double-click its tab.");
            refreshSourceTabs();
            return;
        }
        try {
            SourceWorkspace.Document main = sourceWorkspace.getMainDocument();
            SourceDocument mainSnapshot = new SourceDocument(main.getPath(), main.getText());
            CompilationResult result = sourceWorkspace.compileWithSourceMap(LanguageVersion.STRUCTURED);
            FlowchartExecution execution = new FlowchartExecution(result, mainSnapshot);
            machine.load(result.bytecode());
            compiledProgram = result;
            diagramExecution = execution;
            followedTrace = null;
            refreshSourceTabs();
            updateMemoryDisplay();
            sourceStatusLabel.setText("Compiled " + sourceWorkspace.getMainDocument().getDisplayName()
                    + " from the source tabs.");
        } catch (IOException | ParseException | RuntimeException | TokenMgrError exception) {
            sourceStatusLabel.setText("Compilation failed. Machine memory is unchanged.");
            // Display any readable imports even when later input fails to compile.
            refreshSourceTabs();
            showSourceError("Compilation error", exception);
        } finally {
            refreshSourceTabs();
            revalidateDiagrams(null);
        }
    }

    private void refreshSourceTabs() {
        if (sourceWorkspace == null) {
            return;
        }
        for (SourceWorkspace.Document document : sourceWorkspace.getDocuments()) {
            Tab tab = sourceEditors.get(document);
            if (tab == null) {
                // TextArea removes carriage returns; preserve their line boundaries.
                String editorText = document.getText().replace("\r\n", "\n").replace('\r', '\n');
                SourceEditor editor = new SourceEditor(editorText);
                editor.setLanguageVersion(LanguageVersion.STRUCTURED);
                editor.caretPositionProperty().addListener((observable, before, after) -> highlightDiagramCaret(document));
                editor.focusedProperty().addListener((observable, before, focused) -> {
                    if (focused) highlightDiagramCaret(document);
                });
                tab = new Tab();
                tab.setContent(editor);
                tab.setUserData(document);
                sourceEditors.put(document, tab);
                editor.textProperty().addListener((observable, previous, text) -> {
                    document.setText(text);
                    invalidateSourceMapping("Source changed. Compile to restore source tracking.");
                    if (runTimer == null && sourceWorkspace.getMainDocument() == null
                            && document.hasBeginMarker()) {
                        sourceWorkspace.setMainDocument(document);
                    }
                    refreshSourceTabs();
                    revalidateDiagrams(document);
                    sourceStatusLabel.setText(sourceWorkspace.getMainDocument() == null
                            ? (runTimer == null ? "A main file needs a top-level begin: marker."
                                : "Stop execution, then double-click a file containing begin:.")
                            : "Source changed. Compile to update machine memory.");
                });
                sourceTabs.getTabs().add(tab);
            }
            ((SourceEditor) tab.getContent()).setLanguageVersion(LanguageVersion.STRUCTURED);
            updateSourceTabTitle(document);
        }
        updateSourceSelection();
        updateCompileAvailability();
    }

    private void updateSourceTabTitle(SourceWorkspace.Document document) {
        String title = document.getDisplayName();
        if (document == sourceWorkspace.getMainDocument()) {
            title += " (main)";
        }
        if (document.isModified()) {
            title += " *";
        }
        Tab tab = sourceEditors.get(document);
        tab.setText(title);
        String description = sourceDescription(document);
        tab.setTooltip(new Tooltip(description + "\nDouble-click to activate and compile (requires begin:)."));
        tab.getContent().setAccessibleText("Source editor for " + description);
    }

    private String sourceDescription(SourceWorkspace.Document document) {
        return document.isUntitled() ? document.getDisplayName() + " (not saved)"
                : document.getPath().toString();
    }

    private SourceWorkspace.Document selectedSourceDocument() {
        Tab selected = sourceTabs.getSelectionModel().getSelectedItem();
        return selected != null && selected.getUserData() instanceof SourceWorkspace.Document
                ? (SourceWorkspace.Document) selected.getUserData() : null;
    }

    private void updateSourceSelection() {
        // The initial empty tab is selected while these controls are being created.
        if (saveSourceButton == null || sourcePathLabel == null) {
            return;
        }
        SourceWorkspace.Document selected = selectedSourceDocument();
        saveSourceButton.setDisable(selected == null || (!selected.isUntitled() && !selected.isModified()));
        sourcePathLabel.setText(selected == null ? "" : sourceDescription(selected));
        sourcePathLabel.setTooltip(selected == null ? null : new Tooltip(sourceDescription(selected)));
        refreshSelectedDiagram();
    }

    /** Each file keeps its own views so switching tabs does not discard pending diagram fields. */
    private final class SourceDiagrams {
        final FlowchartView flowchart = new FlowchartView();
        final StructuredDiagramView structured = new StructuredDiagramView();

        SourceDiagrams(SourceWorkspace.Document document) {
            flowchart.setId("machine-flowchart");
            structured.setId("machine-structured-diagram");
            flowchart.setFeatures(EnumSet.allOf(GameFeature.class));
            flowchart.setLanguageVersion(LanguageVersion.STRUCTURED);
            structured.setFeatures(EnumSet.allOf(GameFeature.class));
            flowchart.setValidator(source -> DiagramValidation.validateStandalone(sourceWorkspace, document, source, true));
            structured.setValidator(source -> DiagramValidation.validateStandalone(sourceWorkspace, document, source, false));
            flowchart.setOnSourceChanged(source -> applyDiagramSource(document, flowchart, source));
            structured.setOnSourceChanged(source -> applyDiagramSource(document, structured, source));
            flowchart.setOnSourceFocused(range -> highlightDiagramSource(document, flowchart, range));
            structured.setOnSourceFocused(range -> highlightDiagramSource(document, structured, range));
        }

        void synchronize(SourceWorkspace.Document document) {
            String source = sourceEditor(document).getText();
            flowchart.setSource(source);
            structured.setSource(source);
        }
    }

    private SourceEditor sourceEditor(SourceWorkspace.Document document) {
        Tab tab = sourceEditors.get(document);
        return tab == null ? null : (SourceEditor) tab.getContent();
    }

    private void refreshSelectedDiagram() {
        if (sourceDiagramEditors == null || diagramMode == null) return;
        SourceWorkspace.Document selected = selectedSourceDocument();
        String mode = diagramMode.getValue();
        boolean changed = selected != displayedDiagramDocument || !java.util.Objects.equals(mode, displayedDiagramMode);
        if (changed && displayedDiagramDocument != null) sourceEditor(displayedDiagramDocument).clearLinkedRange();
        displayedDiagramDocument = selected;
        displayedDiagramMode = mode;
        if (selected == null || mode == null || mode.equals("Source only")) {
            if (sourceDiagramEditors.getItems().size() > 1) {
                sourceDiagramEditors.getItems().setAll(sourceTabs);
                machineWorkspace.setDividerPositions(sourceOnlyDivider);
            }
            return;
        }
        SourceDiagrams views = sourceDiagrams.computeIfAbsent(selected, SourceDiagrams::new);
        views.synchronize(selected);
        Node chosen = mode.equals("Flowchart") ? views.flowchart : views.structured;
        if (sourceDiagramEditors.getItems().size() != 2 || sourceDiagramEditors.getItems().get(1) != chosen) {
            boolean alreadyVisible = sourceDiagramEditors.getItems().size() == 2;
            double divider = alreadyVisible ? sourceDiagramEditors.getDividerPositions()[0] : .34;
            if (!alreadyVisible) {
                sourceOnlyDivider = machineWorkspace.getDividerPositions()[0];
                machineWorkspace.setDividerPositions(.3);
            }
            sourceDiagramEditors.getItems().setAll(sourceTabs, chosen);
            sourceDiagramEditors.setDividerPositions(divider);
        }
        if (changed) highlightDiagramCaret(selected);
        // Preview validation may have opened a newly referenced import buffer.
        if (sourceWorkspace.getDocuments().size() > sourceEditors.size()) refreshSourceTabs();
        updateFlowchartExecution();
    }

    /** Import changes recheck cached diagrams against current buffers without committing pending fields. */
    private void revalidateDiagrams(SourceWorkspace.Document changed) {
        sourceDiagrams.forEach((document, views) -> {
            views.synchronize(document);
            if (document != changed) {
                views.flowchart.revalidateSource();
                views.structured.revalidateSource();
            }
            if (isDisplayedDiagram(document, views.flowchart) && !views.flowchart.isSynchronized()
                    || isDisplayedDiagram(document, views.structured) && !views.structured.isSynchronized()) {
                sourceEditor(document).clearLinkedRange();
            }
        });
        if (sourceWorkspace != null && sourceWorkspace.getDocuments().size() > sourceEditors.size()) refreshSourceTabs();
        updateFlowchartExecution();
    }

    private boolean isDisplayedDiagram(SourceWorkspace.Document document, Node view) {
        return selectedSourceDocument() == document && sourceDiagramEditors.getItems().contains(view);
    }

    /** Apply one minimal source edit so ordinary dirty tracking, source mapping and undo remain authoritative. */
    private void applyDiagramSource(SourceWorkspace.Document document, Node view, String source) {
        if (!isDisplayedDiagram(document, view)) return;
        SourceEditor editor = sourceEditor(document);
        if (!editor.isEditable()) return;
        String previous = editor.getText();
        if (previous.equals(source)) return;
        int start = 0, end = previous.length(), newEnd = source.length();
        while (start < end && start < newEnd && previous.charAt(start) == source.charAt(start)) ++start;
        while (end > start && newEnd > start && previous.charAt(end - 1) == source.charAt(newEnd - 1)) { --end; --newEnd; }
        applyingDiagramSource = true;
        try { editor.replaceText(start, end, source.substring(start, newEnd)); }
        finally { applyingDiagramSource = false; }
    }

    private void highlightDiagramSource(SourceWorkspace.Document document, Node view, IndexRange range) {
        if (!isDisplayedDiagram(document, view) || range == null) return;
        SourceEditor editor = sourceEditor(document);
        SourceDiagrams views = sourceDiagrams.get(document);
        boolean valid = view == views.flowchart
                ? views.flowchart.isSynchronized() && views.flowchart.getSource().equals(editor.getText())
                : views.structured.isSynchronized() && views.structured.getSource().equals(editor.getText());
        if (!valid || range.getEnd() > editor.getLength()) return;
        editor.setLinkedRange(range.getStart(), range.getEnd());
        editor.revealSourceOffset(range.getStart());
    }

    private void highlightDiagramCaret(SourceWorkspace.Document document) {
        if (applyingDiagramSource || selectedSourceDocument() != document || sourceDiagramEditors == null) return;
        SourceDiagrams views = sourceDiagrams.get(document);
        if (views == null) return;
        SourceEditor editor = sourceEditor(document);
        editor.clearLinkedRange();
        if (sourceDiagramEditors.getItems().contains(views.flowchart)) views.flowchart.highlightSourceOffset(editor.getCaretPosition());
        else if (sourceDiagramEditors.getItems().contains(views.structured)) views.structured.highlightSourceOffset(editor.getCaretPosition());
    }

    private void saveSelectedSource() {
        SourceWorkspace.Document document = selectedSourceDocument();
        if (document != null) {
            saveSourceDocument(document);
        }
    }

    private boolean saveSourceDocument(SourceWorkspace.Document document) {
        try {
            if (document.isUntitled()) {
                Path path = chooseSourceSavePath(document);
                if (path == null) {
                    return false;
                }
                sourceWorkspace.saveAs(document, path);
                if (compiledProgram != null && document == sourceWorkspace.getMainDocument()) {
                    invalidateSourceMapping("Source file named. Compile to restore source tracking.");
                }
            } else {
                sourceWorkspace.save(document);
            }
            updateSourceTabTitle(document);
            updateSourceSelection();
            revalidateDiagrams(null);
            sourceStatusLabel.setText("Saved " + document.getDisplayName() + ".");
            return true;
        } catch (IOException | RuntimeException exception) {
            showSourceError("Save source error", exception);
            return false;
        }
    }

    // Kept separate from saving so cancellation leaves the document untouched.
    Path chooseSourceSavePath(SourceWorkspace.Document document) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save source");
        chooser.setInitialDirectory(new File(System.getProperty("user.dir")));
        chooser.setInitialFileName(document.getDisplayName() + ".a26");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("A26 source files", "*.a26"),
                new FileChooser.ExtensionFilter("All files", "*"));
        File selected = chooser.showSaveDialog(currentStage);
        return selected == null ? null : selected.toPath();
    }

    private boolean confirmSaveSources() {
        if (sourceWorkspace == null || !sourceWorkspace.isModified()) {
            return true;
        }
        ButtonType saveAll = new ButtonType("Save all", ButtonBar.ButtonData.YES);
        ButtonType discard = new ButtonType("Discard", ButtonBar.ButtonData.NO);
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                "Save changes to the source files before continuing?", saveAll, discard, ButtonType.CANCEL);
        alert.initOwner(currentStage);
        alert.setTitle("Unsaved source changes");
        alert.setHeaderText("Some source tabs have unsaved changes.");
        ButtonType answer = alert.showAndWait().orElse(ButtonType.CANCEL);
        if (answer == discard) {
            return true;
        }
        if (answer != saveAll) {
            return false;
        }
        for (SourceWorkspace.Document document : sourceWorkspace.getDocuments()) {
            if (document.isModified() && !saveSourceDocument(document)) {
                return false;
            }
        }
        return true;
    }

    private void showSourceError(String title, Throwable exception) {
        String message = exception.getMessage() == null ? exception.toString() : exception.getMessage();
        Alert alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK);
        alert.initOwner(currentStage);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.setResizable(true);
        alert.showAndWait();
    }

    private void updateMemoryDisplay() {
        memoryData.clear();
        for (int i = 0; i < machine.getMemorySize(); i++) {
            memoryData.add(new MemoryEntry(i, machine.get(i)));
        }
        scrollToInstructionPointer();
        stepCounterLabel.setText("Steps executed: " + machine.getStepCounter());
        updateSourceExecution();
    }

    /** Record every machine step, including the transitions inside Step n and Run batches. */
    private void stepMachine(int count) {
        if (diagramExecution == null || compiledProgram == null) machine.step(count);
        else diagramExecution.step(machine, count);
    }

    private void invalidateSourceMapping(String reason) {
        compiledProgram = null;
        diagramExecution = null;
        followedTrace = null;
        clearSourceMarkers();
        updateFlowchartExecution();
        executionStatusLabel.setText(reason);
        executionStatusLabel.setTooltip(null);
    }

    private void clearSourceMarkers() {
        displayedTrace = null;
        for (Tab tab : sourceEditors.values()) {
            ((SourceEditor) tab.getContent()).clearExecutionHighlights();
        }
        executionCallers.getChildren().clear();
    }

    private Tab sourceTab(SourceLocation location) {
        // Only the main document can be compiled without a path. Other unnamed
        // tabs are drafts and must not receive its execution markers.
        if (location.file() == null) {
            SourceWorkspace.Document main = sourceWorkspace.getMainDocument();
            return main != null && main.isUntitled() ? sourceEditors.get(main) : null;
        }
        for (Map.Entry<SourceWorkspace.Document, Tab> entry : sourceEditors.entrySet()) {
            if (location.file().equals(entry.getKey().getPath())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private void revealSourceLocation(SourceLocation location) {
        Tab tab = sourceTab(location);
        if (tab != null) {
            sourceTabs.getSelectionModel().select(tab);
            ((SourceEditor) tab.getContent()).revealSourceOffset(location.startOffset());
        }
    }

    private String formatSourceLocation(SourceLocation location) {
        String name = location.file() == null ? sourceWorkspace.getMainDocument().getDisplayName()
                : location.file().getFileName().toString();
        return name + ":" + location.line() + ":" + location.column();
    }

    private Tooltip sourceLocationTooltip(SourceLocation location) {
        return new Tooltip(location.file() == null ? sourceDescription(sourceWorkspace.getMainDocument())
                : location.file().toString());
    }

    private void updateSourceExecution() {
        if (compiledProgram == null) {
            updateFlowchartExecution();
            return;
        }
        // Code and data share memory. A manual edit or a running program can
        // overwrite instructions, in which case their source no longer applies.
        for (int address = compiledProgram.codeStart(); address < compiledProgram.bytecode().size(); address++) {
            if (machine.get(address) != compiledProgram.bytecode().get(address)) {
                invalidateSourceMapping("Compiled instructions changed. Compile to restore source tracking.");
                return;
            }
        }
        int instructionPointer = machine.getInstructionPointer();
        SourceTrace trace = compiledProgram.sourceMap().get(instructionPointer);
        if (trace == null) {
            clearSourceMarkers();
            followedTrace = null;
            executionStatusLabel.setText((machine.isHalted() ? "Halted" : "No source location")
                    + " at i = " + instructionPointer + " (outside mapped instructions).");
            executionStatusLabel.setTooltip(null);
            updateFlowchartExecution();
            return;
        }
        SourceLocation location = trace.location();
        boolean halted = machine.isHalted();
        if (!trace.equals(displayedTrace) || halted != displayedHalted) {
            showSourceTrace(trace, halted);
        }
        executionStatusLabel.setText((halted ? "Halted: " : "Next: ")
                + formatSourceLocation(location) + " · i = " + instructionPointer);
        executionStatusLabel.setTooltip(sourceLocationTooltip(location));
        if (followExecution.isSelected() && !trace.equals(followedTrace)) {
            revealSourceLocation(location);
        }
        followedTrace = trace;
        updateFlowchartExecution();
    }

    /** Runtime decorations belong to the frozen compiled source, independently of editor focus. */
    private void updateFlowchartExecution() {
        sourceDiagrams.forEach((document, views) -> {
            Flowchart graph = views.flowchart.getGraph();
            String compiledSource = diagramExecution == null ? null : diagramExecution.source(document.getPath());
            if (diagramExecution == null || graph == null || compiledSource == null
                    || document.getPath() == null && document != sourceWorkspace.getMainDocument()
                    || !compiledSource.equals(views.flowchart.getSource())) {
                views.flowchart.clearExecutionFrame();
                views.flowchart.clearRuntimeValues();
                return;
            }
            views.flowchart.setRuntimeValues(diagramExecution.values(document.getPath(), machine));
            views.flowchart.setExecutionFrame(diagramExecution.frame(graph, document.getPath(), machine), machine.isHalted());
        });
    }

    private void showSourceTrace(SourceTrace trace, boolean halted) {
        clearSourceMarkers();
        SourceLocation location = trace.location();
        Tab current = sourceTab(location);
        if (current != null) {
            ((SourceEditor) current.getContent()).setExecutionRange(
                    location.startOffset(), location.endOffset(), halted);
        }
        Map<SourceEditor, List<IndexRange>> callerRanges = new LinkedHashMap<>();
        for (SourceLocation caller : trace.callers()) {
            Tab tab = sourceTab(caller);
            if (tab != null) {
                callerRanges.computeIfAbsent((SourceEditor) tab.getContent(), key -> new ArrayList<>())
                        .add(new IndexRange(caller.startOffset(), caller.endOffset()));
            }
            if (executionCallers.getChildren().isEmpty()) {
                executionCallers.getChildren().add(new Label("Called from:"));
            } else {
                executionCallers.getChildren().add(new Label("→"));
            }
            Hyperlink link = new Hyperlink(formatSourceLocation(caller));
            link.setTooltip(sourceLocationTooltip(caller));
            link.setOnAction(event -> revealSourceLocation(caller));
            executionCallers.getChildren().add(link);
        }
        callerRanges.forEach(SourceEditor::setCallerRanges);
        displayedTrace = trace;
        displayedHalted = halted;
    }

    protected void scrollToInstructionPointer() {
        int instructionPointer = machine.getInstructionPointer();
        if (instructionPointer >= 0 && instructionPointer < memoryData.size()) {
            memoryTable.scrollTo(instructionPointer);
        }
    }

    // Simple class to represent memory entries for the TableView
    public static class MemoryEntry {
        private final Integer address;
        private Integer value;

        public MemoryEntry(Integer address, Integer value) {
            this.address = address;
            this.value = value;
        }

        public Integer getAddress() {
            return address;
        }

        public Integer getValue() {
            return value;
        }

        public void setValue(Integer value) {
            this.value = value;
        }

    }

    class CustomPane extends Pane {
        private final double arrowHeadSize = 10.0;
        private Line instructionPointerArrowHorizontalSegment;
        private Line instructionPointerArrowVerticalSegment;
        private Polygon instructionPointerArrowHead;
        private Line currentInstructionArrowStartSegment;
        private Line currentInstructionArrowMiddleSegment;
        private Line currentInstructionArrowEndSegment;
        private Polygon currentInstructionArrowHead;

        public CustomPane() {
            Color lightOrange = Color.rgb(255, 224, 178);
            Color lightGreen = Color.rgb(144, 238, 144);  // This is equivalent to the commonly known 'lightgreen' in web colors.

            instructionPointerArrowHorizontalSegment = new Line();
            instructionPointerArrowVerticalSegment = new Line();
            instructionPointerArrowHead = new Polygon();
            getChildren().addAll(instructionPointerArrowHorizontalSegment, instructionPointerArrowVerticalSegment, instructionPointerArrowHead);
            instructionPointerArrowHorizontalSegment.setStrokeWidth(2);
            instructionPointerArrowHorizontalSegment.setStroke(lightOrange);
            instructionPointerArrowVerticalSegment.setStrokeWidth(2);
            instructionPointerArrowVerticalSegment.setStroke(lightOrange);
            instructionPointerArrowHead.setFill(lightOrange);

            currentInstructionArrowStartSegment = new Line();
            currentInstructionArrowMiddleSegment = new Line();
            currentInstructionArrowEndSegment = new Line();
            currentInstructionArrowHead = new Polygon();
            getChildren().addAll(
                currentInstructionArrowStartSegment,
                currentInstructionArrowMiddleSegment,
                currentInstructionArrowEndSegment,
                currentInstructionArrowHead);
            currentInstructionArrowStartSegment.setStrokeWidth(2);
            currentInstructionArrowStartSegment.setStroke(lightGreen);
            currentInstructionArrowMiddleSegment.setStrokeWidth(2);
            currentInstructionArrowMiddleSegment.setStroke(lightGreen);
            currentInstructionArrowEndSegment.setStrokeWidth(2);
            currentInstructionArrowEndSegment.setStroke(lightGreen);
            currentInstructionArrowHead.setFill(lightGreen);

        }

        @Override
        protected void layoutChildren() {
            super.layoutChildren();

            instructionSymbolLabel.setText(machine.getInstructionSymbolAndArguments());

            // NEW: keep the step counter in sync
            stepCounterLabel.setText("Steps executed: " + machine.getStepCounter());

            Node node = memoryTable.lookup(".instruction-pointer-register");
            if( node instanceof TableRow ) {
                //            if (firstRow != null) {
                TableRow firstRow = (TableRow) node;

                if( vScrollBar == null ) {
                    vScrollBar = (ScrollBar) memoryTable.lookup(".scroll-bar:vertical");
                    if( vScrollBar != null ) {
                        vScrollBar.valueProperty().addListener((observable, oldValue, newValue) -> {
                                    //scrollToInstructionPointer();
                                    //refreshScene(currentStage);  // A method to adjust the Y-coordinate of the arrow's start.
                            });
                    }
                }

                double scrollValue = vScrollBar != null ? vScrollBar.getValue() * ( 1.0 - vScrollBar.getVisibleAmount() ) * machine.getMemorySize() : 0;
                double padding = vScrollBar != null && vScrollBar.isVisible() ? 15 : 0;
                double rowHeight = firstRow.getHeight();

                double startY = firstRow.getParent().getParent()
                    .getParent().getBoundsInParent()
                    .getMinY() + rowHeight / 2  -
                    scrollValue * rowHeight;
                double startX = memoryTable.getBoundsInParent().getMaxX();

                double northX = cpuLabel.getParent().getBoundsInParent().getMinX() + cpuLabel.getWidth() / 2;
                double northY = cpuLabel.getBoundsInParent().getMinY();

                double westX = cpuLabel.getParent().getBoundsInParent().getMinX();
                double westY = cpuLabel.getBoundsInParent().getMinY() + 
                    cpuLabel.getHeight() / 2;

                double endX = startX;
                double endY = startY + 
                    machine.getInstructionPointer() * rowHeight;

                double midX = (westX + endX) / 2;

                instructionPointerArrowHorizontalSegment.setStartX(startX);
                instructionPointerArrowHorizontalSegment.setStartY(startY);
                instructionPointerArrowHorizontalSegment.setEndX(northX);
                instructionPointerArrowHorizontalSegment.setEndY(startY);

                instructionPointerArrowVerticalSegment.setStartX(northX);
                instructionPointerArrowVerticalSegment.setStartY(startY);
                instructionPointerArrowVerticalSegment.setEndX(northX);
                instructionPointerArrowVerticalSegment.setEndY(northY);

                // Define the instructionPointerArrowhead
                instructionPointerArrowHead.getPoints().setAll(
                    northX, northY,
                    northX + arrowHeadSize, northY - arrowHeadSize,
                    northX - arrowHeadSize, northY - arrowHeadSize
                );

                currentInstructionArrowStartSegment.setStartX(westX);
                currentInstructionArrowStartSegment.setStartY(westY);
                currentInstructionArrowStartSegment.setEndX(midX);
                currentInstructionArrowStartSegment.setEndY(westY);

                currentInstructionArrowMiddleSegment.setStartX(midX);
                currentInstructionArrowMiddleSegment.setStartY(westY);
                currentInstructionArrowMiddleSegment.setEndX(midX);
                currentInstructionArrowMiddleSegment.setEndY(endY);

                currentInstructionArrowEndSegment.setStartX(midX);
                currentInstructionArrowEndSegment.setStartY(endY);
                currentInstructionArrowEndSegment.setEndX(endX);
                currentInstructionArrowEndSegment.setEndY(endY);

                currentInstructionArrowHead.getPoints().setAll(
                    endX, endY,
                    endX + arrowHeadSize, endY - arrowHeadSize,
                    endX + arrowHeadSize, endY + arrowHeadSize
                );

            }
        }

    }
}

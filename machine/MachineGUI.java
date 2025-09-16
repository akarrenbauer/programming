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

public class MachineGUI extends Application {

    private final Machine machine = new Machine();
    private TableView<MemoryEntry> memoryTable;
    private ObservableList<MemoryEntry> memoryData;
    private Label cpuLabel;
    private Label instructionSymbolLabel;
    private CustomPane customPane;
    private ScrollBar vScrollBar;
    private Stage currentStage;

    private AnimationTimer runTimer;
    private volatile boolean stopRequested = false;

    private Label stepCounterLabel;

    private String sourceFile;
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
                    entry.setValue(newValue);

                    // Update the underlying machine memory
                    machine.set(entry.getAddress(), newValue);

                    // Refresh table view to reflect updated value
                    memoryTable.refresh();
            });
        // Bind the table's width to the sum of its column widths (with some padding)
        memoryTable.prefWidthProperty().bind(addressColumn.widthProperty().add(valueColumn.widthProperty()).add(15));

        memoryTable.getColumns().addAll(addressColumn, valueColumn);

        cpuLabel = new Label("CPU");
        cpuLabel.setStyle("-fx-background-color: white; -fx-border-color: black; -fx-padding: 10px;");

        primaryStage.setScene(createScene(primaryStage));
        primaryStage.show();
    }

    private Scene createScene(Stage primaryStage) {

        Button saveButton = new Button("Save");
        Button loadButton = new Button("Load");
        Button reloadButton = new Button("Reload");
        Button compileButton = new Button("Compile");
        Button recompileButton = new Button("Recompile");
        Button resetButton = new Button("Reset");
        Button runButton = new Button("Run");
        Button stopButton = new Button("Stop");
        Button stepButton = new Button("Step");
        Button stepNButton = new Button("Step n");

        saveButton.setOnAction(e -> saveMemoryToFile());

        loadButton.setOnAction(e -> loadMemoryFromFile(primaryStage));

        reloadButton.setOnAction(e -> {
                    machine.reload();
                    updateMemoryDisplay();
            });

        compileButton.setOnAction(e -> compileMemoryFromFile(primaryStage));

        recompileButton.setOnAction(e -> {
                    try {
                        machine.load( Compiler.compile( sourceFile ) );
                    } catch(IOException exception ) {
                        System.err.println( exception.getMessage() );
                        Alert alert = new Alert(Alert.AlertType.ERROR, exception.getMessage(), ButtonType.OK);
                        alert.setTitle("File Error");
                        alert.setHeaderText("File Error");
                        alert.showAndWait();
                    } catch(ParseException exception ) {
                        System.err.println( exception.getMessage() );
                        Alert alert = new Alert(Alert.AlertType.ERROR, exception.getMessage(), ButtonType.OK);
                        alert.setTitle("Parse Error");
                        alert.setHeaderText("Parse Error");
                        alert.showAndWait();
                    }
                    updateMemoryDisplay();
            });

        resetButton.setOnAction(e -> {
                    machine.reset();
                    updateMemoryDisplay();
            });

        stopButton.setDisable(true); // only enabled while running
        stopButton.setOnAction(e -> {
                    // Stop right now ? don?t wait for next frame
                    finishRun(runButton, stopButton, stepButton, stepNButton );
            });

        stepButton.setOnAction(e -> {
                    if (runTimer != null) return; // ignore while running
                    machine.step();
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
                                    machine.step(n);
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
                    // if you have stepN: stepNButton.setDisable(true);

                    runTimer = new AnimationTimer() {
                        private long lastUpdateNs = 0;

                        @Override
                        public void handle(long now) {
                            if (stopRequested || machine.isHalted()) {
                                finishRun(runButton, stopButton, stepButton, stepNButton );
                                return;
                            }
                            try {
                                machine.step(10000);
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

        HBox hbox = new HBox(2, memoryTable, processor);
        HBox.setHgrow(memoryTable, Priority.ALWAYS);
        HBox.setHgrow(processor, Priority.NEVER);
        HBox.setMargin(processor, new Insets(0, 0, 0, 50)); // Add some spacing between the table and the box.

        customPane.getChildren().add(hbox);

        HBox buttonBox = new HBox(5, saveButton, loadButton, reloadButton, compileButton, recompileButton, resetButton, runButton, stopButton, stepButton, stepNButton);
        VBox vbox = new VBox(2, buttonBox, customPane);
        VBox.setVgrow(customPane, Priority.ALWAYS);
        memoryTable.prefHeightProperty().bind(vbox.heightProperty().subtract(buttonBox.heightProperty()).subtract(20));

        return new Scene(vbox, 800, 450);
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
        updateMemoryDisplay();
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
            updateMemoryDisplay();
            //refreshScene(primaryStage);
        }
    }

    private void compileMemoryFromFile(Stage primaryStage) {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Open Source File");
        fileChooser.setInitialDirectory(new File(System.getProperty("user.dir"))); // Starts in the current directory
        File selectedFile = fileChooser.showOpenDialog(primaryStage);

        if (selectedFile != null) {
            sourceFile = selectedFile.getAbsolutePath();
            try {
                machine.load( Compiler.compile( sourceFile ) );
            } catch(IOException exception ) {
                System.err.println( exception.getMessage() );
                Alert alert = new Alert(Alert.AlertType.ERROR, exception.getMessage(), ButtonType.OK);
                alert.setTitle("File Error");
                alert.setHeaderText("File Error");
                alert.showAndWait();
            } catch(ParseException exception ) {
                System.err.println( exception.getMessage() );
                Alert alert = new Alert(Alert.AlertType.ERROR, exception.getMessage(), ButtonType.OK);
                alert.setTitle("Parse Error");
                alert.setHeaderText("Parse Error");
                alert.showAndWait();
            }
            updateMemoryDisplay();
            //refreshScene(primaryStage);
        }
    }

    private void refreshScene(Stage primaryStage) {
        primaryStage.setScene(createScene(primaryStage));
    }

    private void updateMemoryDisplay() {
        memoryData.clear();
        for (int i = 0; i < machine.getMemorySize(); i++) {
            memoryData.add(new MemoryEntry(i, machine.get(i)));
        }
        scrollToInstructionPointer();
        stepCounterLabel.setText("Steps executed: " + machine.getStepCounter());
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

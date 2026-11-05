package machine;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

import static machine.GameMessages.Key.*;

import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.IndexRange;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;
import javafx.util.StringConverter;

/** Standalone programming puzzles that grow the instruction language into the full language. */
public class LanguageGame extends Application {
    private final List<GameLevel> levels = GameCatalog.levels();
    private final ExecutorService checker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "language-game-checker");
        thread.setDaemon(true);
        return thread;
    });
    private final PauseTransition saveDelay = new PauseTransition(Duration.millis(500));
    private GameProgress progress;
    private ListView<GameLevel> levelList;
    private ComboBox<String> worldPicker;
    private Label worldProgress;
    private final List<String> worlds = levels.stream().map(GameLevel::world).distinct().toList();
    private boolean selectingWorld;
    private SourceEditor editor;
    private SplitPane programEditors;
    private StructuredDiagramView diagram;
    private FlowchartView flowchart;
    private ComboBox<String> diagramMode;
    private String structuredDiagramMode = "Nassi–Shneiderman";
    private boolean selectingDiagramMode;
    private boolean diagramInitialized;
    private boolean flowchartInitialized;
    private Label editorVersion;
    private GameGrammarView grammar;
    private Tab grammarTab;
    private Tab toolboxTab;
    private Set<GameFeature> grammarHighlights = Set.of();
    private Label grammarNotice;
    private Label toolboxNotice;
    private SourceEditor toolbox;
    private Label worldLabel;
    private Label titleLabel;
    private Label summaryLabel;
    private Label detailTitle;
    private Label goalLabel;
    private Label lessonLabel;
    private Label contractLabel;
    private Label hintLabel;
    private Label feedback;
    private Label saved;
    private Label score;
    private Label rewardPreview;
    private Label unlockAnnouncement;
    private VBox rewardBanner;
    private TabPane work;
    private ProgressBar checking;
    private Button check;
    private Button debug;
    private ComboBox<Integer> debugCase;
    private Button cancel;
    private Button hint;
    private Button next;
    private Button restart;
    private Button reset;
    private Button insertHelpers;
    private ComboBox<GameTaskLanguage> taskLanguage;
    private TableView<GameResult.CaseResult> cases;
    private Task<?> activeCheck;
    private int currentIndex = -1;
    private int shownHints;
    private boolean selecting;
    private boolean loading;
    private boolean applyingDiagramSource;
    private final EditorZoom editorZoom = new EditorZoom();
    private Stage stage;
    private Function<GameTaskLanguage, String> recoveryNotice;
    private Function<GameTaskLanguage, String> feedbackMessage = language -> GameMessages.text(language, FIRST_PUZZLE);
    private Function<GameTaskLanguage, String> savedMessage = language -> GameMessages.text(language, SAVE_INITIAL);
    private boolean announcingNewRewards;

    public static void main(String[] args) {
        launch(args);
    }

    /** Can be overridden by a classroom launcher or tests to isolate saved progress. */
    Path progressPath() {
        String configured = System.getProperty("language.game.progress");
        return configured == null ? Path.of(System.getProperty("user.home"), ".language-game", "progress.properties")
                : Path.of(configured);
    }

    @Override
    public void start(Stage primaryStage) throws IOException {
        stage = primaryStage;
        Path path = progressPath();
        try {
            progress = new GameProgress(path, levels);
        } catch (IOException error) {
            Path separate = path.resolveSibling(path.getFileName() + ".recovery-" + System.currentTimeMillis());
            progress = new GameProgress(separate, levels);
            recoveryNotice = language -> GameMessages.text(language, RECOVERY,
                    GameMessages.diagnostic(language, error.getMessage()), separate);
        }
        BorderPane root = new BorderPane();
        root.setStyle("-fx-font-family: sans-serif; -fx-background-color: #f3f6fa;");
        root.setTop(header());
        root.setLeft(levelNavigation());
        root.setCenter(challenge());
        saved = new Label(savedMessage.apply(progress.taskLanguage()));
        saved.setId("game-save-status");
        saved.setWrapText(true);
        saved.setPadding(new Insets(7, 16, 7, 16));
        root.setBottom(saved);
        saveDelay.setOnFinished(event -> persist());
        stage.setTitle("Language Game");
        stage.setScene(new Scene(root, 1280, 860));
        editorZoom.install(stage.getScene());
        stage.setMinWidth(1000);
        stage.setMinHeight(720);
        stage.setOnCloseRequest(event -> {
            if (!persist()) {
                Alert warning = new Alert(Alert.AlertType.CONFIRMATION,
                        message(CLOSE_UNSAVED), ButtonType.CLOSE, ButtonType.CANCEL);
                warning.initOwner(stage);
                if (warning.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.CLOSE) {
                    event.consume();
                    return;
                }
            }
            cancelCheck();
        });
        levelList.getSelectionModel().select(progress.selectedIndex());
        refreshInventory();
        stage.show();
        levelList.scrollTo(currentIndex);
        if (recoveryNotice != null) {
            setSaved(language -> recoveryNotice.apply(language));
        }
    }

    private HBox header() {
        Label name = new Label("LANGUAGE GAME");
        name.setStyle("-fx-font-size: 25px; -fx-font-weight: bold; -fx-text-fill: white;");
        Label subtitle = new Label("Build the language, one small program at a time.");
        subtitle.setStyle("-fx-text-fill: #b9d8e8;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        score = new Label();
        score.setStyle("-fx-font-size: 15px; -fx-text-fill: white;");
        score.setMinWidth(Region.USE_PREF_SIZE);
        reset = new Button("Reset game");
        reset.setId("reset-game");
        reset.setOnAction(event -> resetGame());
        Label languageLabel = new Label("Task language");
        languageLabel.setStyle("-fx-text-fill: #b9d8e8;");
        taskLanguage = new ComboBox<>();
        taskLanguage.setId("task-language");
        taskLanguage.setAccessibleText("Task language");
        taskLanguage.getItems().setAll(GameTaskLanguage.values());
        taskLanguage.setValue(progress.taskLanguage());
        taskLanguage.setPrefWidth(130);
        taskLanguage.setTooltip(new Tooltip("Translate tasks, hints, and status messages; grammar and code stay in English"));
        taskLanguage.valueProperty().addListener((observable, before, selected) -> {
            if (selected == null || selected == progress.taskLanguage()) return;
            progress.setTaskLanguage(selected);
            levelList.refresh();
            refreshWorldNavigation();
            if (currentIndex >= 0) refreshTaskText();
            refreshInventory();
            feedback.setText(feedbackMessage.apply(selected));
            cases.refresh();
            persist();
        });
        HBox header = new HBox(18, new VBox(3, name, subtitle), spacer, score,
                new VBox(3, languageLabel, taskLanguage), reset);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(16, 22, 16, 22));
        header.setStyle("-fx-background-color: #173449;");
        return header;
    }

    private VBox levelNavigation() {
        Label heading = new Label("YOUR JOURNEY");
        heading.setStyle("-fx-font-weight: bold; -fx-text-fill: #365367;");
        worldPicker = new ComboBox<>();
        worldPicker.setId("game-worlds");
        worldPicker.setAccessibleText("Choose a world");
        worldPicker.setMaxWidth(Double.MAX_VALUE);
        worldPicker.getItems().setAll(worlds);
        worldPicker.setConverter(new StringConverter<>() {
            @Override public String toString(String world) { return world == null ? "" : worldTitle(world); }
            @Override public String fromString(String text) { throw new UnsupportedOperationException(); }
        });
        worldPicker.setCellFactory(view -> worldNavigationCell());
        // A popup can have cached locked cells from before the latest unlock.
        worldPicker.setOnShowing(event -> worldPicker.setCellFactory(view -> worldNavigationCell()));
        worldPicker.valueProperty().addListener((observable, before, world) -> {
            if (selectingWorld || world == null || currentIndex < 0) return;
            int target = -1;
            for (int index = 0; index < levels.size(); index++) {
                GameLevel level = levels.get(index);
                if (level.world().equals(world) && progress.isAvailable(index)) {
                    if (target < 0) target = index;
                    if (!progress.isCompleted(level.id())) { target = index; break; }
                }
            }
            if (activeCheck == null && target >= 0) {
                levelList.getSelectionModel().select(target);
                levelList.scrollTo(target);
            } else {
                refreshWorldNavigation();
                setFeedback(WORLD_LOCKED);
            }
        });
        worldProgress = new Label();
        worldProgress.setId("game-world-progress");
        worldProgress.setWrapText(true);
        levelList = new ListView<>();
        levelList.setId("game-levels");
        levelList.getItems().setAll(levels);
        levelList.setCellFactory(view -> new ListCell<>() {
            @Override protected void updateItem(GameLevel level, boolean empty) {
                super.updateItem(level, empty);
                if (empty || level == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                int index = levels.indexOf(level);
                String state = progress.isCompleted(level.id()) ? "✓" : progress.isAvailable(index) ? "▶" : "○";
                Label name = new Label(state + "  " + (index + 1) + ". " + taskText(level).title());
                name.setWrapText(true);
                name.setMaxWidth(225);
                name.setStyle("-fx-text-fill: " + (progress.isAvailable(index) ? "#143b50" : "#80909a") + ";");
                VBox content = new VBox(5);
                if (index == 0 || !levels.get(index - 1).world().equals(level.world())) {
                    Label world = new Label(worldTitle(level.world()));
                    world.setWrapText(true);
                    world.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #4b7785;");
                    content.getChildren().add(world);
                }
                content.getChildren().add(name);
                content.setPadding(new Insets(5, 2, 5, 2));
                setGraphic(content);
            }
        });
        levelList.getSelectionModel().selectedIndexProperty().addListener((observable, before, after) -> {
            if (selecting || after.intValue() < 0) return;
            if (activeCheck != null || !progress.isAvailable(after.intValue())) {
                selecting = true;
                levelList.getSelectionModel().select(currentIndex);
                selecting = false;
                setFeedback(LEVEL_LOCKED);
                return;
            }
            showLevel(after.intValue());
        });
        VBox navigation = new VBox(10, heading, worldPicker, worldProgress, levelList);
        navigation.setPrefWidth(270);
        navigation.setMinWidth(230);
        navigation.setPadding(new Insets(16, 8, 12, 12));
        VBox.setVgrow(levelList, Priority.ALWAYS);
        return navigation;
    }

    private VBox challenge() {
        worldLabel = new Label();
        worldLabel.setId("game-world");
        worldLabel.setStyle("-fx-text-fill: #367e87; -fx-font-weight: bold;");
        titleLabel = new Label();
        titleLabel.setId("game-title");
        titleLabel.setStyle("-fx-font-size: 21px; -fx-font-weight: bold; -fx-text-fill: #193747;");
        titleLabel.setWrapText(true);
        summaryLabel = new Label();
        summaryLabel.setId("game-summary");
        summaryLabel.setWrapText(true);
        summaryLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #365367;");
        VBox introduction = new VBox(4, worldLabel, titleLabel, summaryLabel);
        introduction.setId("game-introduction");
        introduction.setMinHeight(Region.USE_PREF_SIZE);
        detailTitle = new Label();
        detailTitle.setId("game-task-title");
        detailTitle.setWrapText(true);
        detailTitle.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #193747;");
        goalLabel = new Label();
        goalLabel.setId("game-goal");
        goalLabel.setWrapText(true);
        goalLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");
        lessonLabel = new Label();
        lessonLabel.setId("game-lesson");
        lessonLabel.setWrapText(true);
        contractLabel = new Label();
        contractLabel.setId("game-contract");
        contractLabel.setWrapText(true);
        contractLabel.setStyle("-fx-text-fill: #567080; -fx-font-size: 11px;");
        VBox taskDetails = new VBox(14, detailTitle, goalLabel, lessonLabel, contractLabel);
        taskDetails.setPadding(new Insets(16));
        ScrollPane taskScroll = new ScrollPane(taskDetails);
        taskScroll.setId("game-task-details");
        taskScroll.setFitToWidth(true);
        taskScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        taskScroll.setStyle("-fx-background-color: transparent; -fx-background: #f3f6fa;");
        Tab taskTab = new Tab("Task details", taskScroll);
        taskTab.setId("task-details-tab");

        rewardPreview = new Label();
        rewardPreview.setId("game-rewards");
        rewardPreview.setWrapText(true);
        rewardPreview.managedProperty().bind(rewardPreview.visibleProperty());
        unlockAnnouncement = new Label();
        unlockAnnouncement.setId("game-unlocks");
        unlockAnnouncement.setWrapText(true);
        unlockAnnouncement.managedProperty().bind(unlockAnnouncement.visibleProperty());
        rewardBanner = new VBox(rewardPreview, unlockAnnouncement);
        rewardBanner.setPadding(new Insets(9, 12, 9, 12));
        rewardBanner.setMinHeight(Region.USE_PREF_SIZE);

        editor = new SourceEditor("");
        editor.zoomFactorProperty().bind(editorZoom.factorProperty());
        editor.setId("game-editor");
        editor.setAccessibleText("Your solution program");
        editorVersion = new Label();
        editorVersion.setId("game-language-version");
        editorVersion.setStyle("-fx-text-fill: #567080; -fx-padding: 3 6;");
        editorVersion.setWrapText(true);
        diagram = new StructuredDiagramView();
        diagram.zoomFactorProperty().bind(editorZoom.factorProperty());
        diagram.setId("game-diagram");
        diagram.setOnSourceChanged(source -> applyDiagramSource(diagram, source));
        diagram.setOnSourceFocused(range -> highlightDiagramSource(diagram, range));
        flowchart = new FlowchartView();
        flowchart.zoomFactorProperty().bind(editorZoom.factorProperty());
        flowchart.setId("game-flowchart");
        flowchart.setLanguageVersionResolver(source -> currentIndex < 0 ? LanguageVersion.BEFORE_DO_WHILE
                : GameLanguage.editingVersionFor(current(), progress.features(), source));
        flowchart.setOnSourceChanged(source -> applyDiagramSource(flowchart, source));
        flowchart.setOnSourceFocused(range -> highlightDiagramSource(flowchart, range));
        editor.caretPositionProperty().addListener((observable, before, after) -> highlightDiagramCaret());
        editor.focusedProperty().addListener((observable, before, focused) -> {
            if (focused) highlightDiagramCaret();
        });
        editor.textProperty().addListener((observable, before, after) -> {
            if (!loading && currentIndex >= 0) {
                progress.setDraft(current().id(), after);
                refreshEditorVersion();
                if (diagramInitialized) diagram.setSource(after);
                if (flowchartInitialized) flowchart.setSource(after);
                highlightDiagramCaret();
                saveDelay.playFromStart();
            }
        });
        grammar = new GameGrammarView();
        grammar.setId("game-grammar");
        toolbox = new SourceEditor("");
        toolbox.setId("game-toolbox");
        toolbox.setAccessibleText("Earned helper definitions");
        toolbox.setEditable(false);
        toolbox.zoomFactorProperty().bind(editorZoom.factorProperty());
        insertHelpers = new Button("Insert earned helpers at the top");
        insertHelpers.setId("game-insert-helpers");
        insertHelpers.setOnAction(event -> {
            String library = formattedToolbox();
            if (!library.isBlank()) editor.insertText(0, library + "\n");
        });
        Label toolboxHelp = new Label("Earn helpers by implementing their behavior. Once imports unlock, use import \"toolbox\"; or import \"macros.a26\";.\n"
                + "Before then, insert the earned definitions into your program after macros unlock.");
        toolboxHelp.setWrapText(true);
        VBox tools = new VBox(7, toolboxHelp, insertHelpers, toolbox);
        VBox.setVgrow(toolbox, Priority.ALWAYS);
        grammarTab = new Tab("Unlocked grammar", grammar);
        grammarTab.setId("grammar-tab");
        toolboxTab = new Tab("Earned toolbox", tools);
        toolboxTab.setId("toolbox-tab");
        grammarNotice = newNotice("grammar-new");
        toolboxNotice = newNotice("toolbox-new");
        grammarTab.setGraphic(grammarNotice);
        toolboxTab.setGraphic(toolboxNotice);
        Label sourceHeading = new Label("SOURCE CODE");
        sourceHeading.setStyle("-fx-font-weight: bold; -fx-text-fill: #365367; -fx-padding: 5;");
        VBox sourcePane = new VBox(editor);
        sourcePane.setMinWidth(200);
        VBox.setVgrow(editor, Priority.ALWAYS);
        programEditors = new SplitPane(sourcePane);
        programEditors.setId("game-program-editors");
        diagramMode = new ComboBox<>();
        diagramMode.setId("game-diagram-mode");
        diagramMode.setPrefWidth(150);
        diagramMode.setMinWidth(Region.USE_PREF_SIZE);
        diagramMode.setStyle("-fx-font-size: 11px;");
        diagramMode.setAccessibleText("Diagram view");
        diagramMode.setTooltip(new Tooltip("Choose a view of the same program. Both views use this world's available instructions."));
        diagramMode.valueProperty().addListener((observable, before, selected) -> {
            if (selectingDiagramMode || selected == null || currentIndex < 0 || !GameWorld.usesDiagrams(current())) return;
            structuredDiagramMode = selected;
            refreshDiagram();
        });
        Button undo = new Button("Undo");
        undo.setId("game-undo");
        undo.setTooltip(new Tooltip("Undo the last source or applied diagram edit. In the source: Ctrl+Z (Cmd+Z on macOS)."));
        undo.disableProperty().bind(editor.undoableProperty().not().or(editor.editableProperty().not()));
        undo.setOnAction(event -> editor.undo());
        Button redo = new Button("Redo");
        redo.setId("game-redo");
        redo.setTooltip(new Tooltip("Restore the last undone source or applied diagram edit."));
        redo.disableProperty().bind(editor.redoableProperty().not().or(editor.editableProperty().not()));
        redo.setOnAction(event -> editor.redo());
        Button format = new Button("Format");
        format.setId("game-format");
        format.setTooltip(new Tooltip("Format the program with Java-style braces, line breaks, and two-space indentation. Undo reverses the change."));
        format.disableProperty().bind(editor.editableProperty().not().or(editor.textProperty().isEmpty()));
        format.setOnAction(event -> formatSource());
        HBox sourceActions = new HBox(8, format, undo, redo);
        sourceActions.setAlignment(Pos.CENTER_LEFT);
        FlowPane editorControls = new FlowPane(8, 4, sourceHeading, sourceActions);
        editorControls.setPadding(new Insets(0, 6, 0, 0));
        sourcePane.getChildren().add(0, editorControls);
        VBox program = new VBox(editorVersion, programEditors);
        VBox.setVgrow(programEditors, Priority.ALWAYS);
        work = new TabPane(new Tab("Your program", program), grammarTab, toolboxTab, taskTab);
        work.setId("game-work");
        work.getSelectionModel().selectedItemProperty().addListener((observable, before, selected) -> acknowledgeInventory());
        work.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        work.setMinHeight(150);
        work.setPrefHeight(330);
        VBox.setVgrow(work, Priority.ALWAYS);

        check = new Button("Check solution");
        check.setId("check-solution");
        check.setDefaultButton(true);
        check.setStyle("-fx-background-color: #157f83; -fx-text-fill: white; -fx-font-weight: bold;");
        check.setOnAction(event -> checkSolution());
        cancel = new Button("Cancel check");
        cancel.setId("cancel-check");
        cancel.setDisable(true);
        cancel.setOnAction(event -> cancelCheck());
        hint = new Button("Show hint");
        hint.setId("show-hint");
        hint.setOnAction(event -> showHint());
        next = new Button("Next level →");
        next.setId("next-level");
        next.setOnAction(event -> {
            int index = nextLevelIndex();
            if (index >= 0) levelList.getSelectionModel().select(index);
        });
        restart = new Button("Restore starter");
        restart.setOnAction(event -> {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    message(RESTORE_QUESTION), ButtonType.OK, ButtonType.CANCEL);
            confirm.initOwner(stage);
            if (confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) editor.setText(current().starter());
        });
        debug = new Button("Debug");
        debug.setId("debug-solution");
        debug.setTooltip(new Tooltip("Step through this draft with the selected test inputs; debugging does not complete a level"));
        debug.setOnAction(event -> debugSolution());
        debugCase = new ComboBox<>();
        debugCase.setId("debug-case");
        debugCase.setPrefWidth(230);
        debugCase.setAccessibleText("Test inputs for debugging");
        debugCase.setConverter(new StringConverter<>() {
            @Override public String toString(Integer index) {
                return index == null || currentIndex < 0 || index >= current().cases().size() ? "" : "Case " + (index + 1) + ": " + current().cases().get(index).inputs();
            }
            @Override public Integer fromString(String text) { throw new UnsupportedOperationException(); }
        });
        debugCase.valueProperty().addListener((observable, before, selected) -> {
            if (selected != null && currentIndex >= 0 && selected < current().cases().size()) debugCase.setTooltip(new Tooltip(current().cases().get(selected).inputs().toString()));
        });
        FlowPane actions = new FlowPane(8, 8, check, cancel, hint, restart, next, debugCase, debug);
        hintLabel = new Label();
        hintLabel.setId("game-hint");
        hintLabel.setWrapText(true);
        // An unmanaged but visible label retains its old hit area over the buttons.
        hintLabel.visibleProperty().bind(hintLabel.textProperty().isNotEmpty());
        hintLabel.managedProperty().bind(hintLabel.visibleProperty());
        hintLabel.setStyle("-fx-text-fill: #66511b;");
        feedback = new Label(feedbackMessage.apply(progress.taskLanguage()));
        feedback.setId("game-feedback");
        feedback.setWrapText(true);
        checking = new ProgressBar(0);
        checking.setId("game-progress");
        checking.setMaxWidth(Double.MAX_VALUE);
        cases = new TableView<>();
        cases.setId("game-cases");
        cases.setPrefHeight(145);
        cases.setMinHeight(80);
        cases.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        cases.getSelectionModel().selectedItemProperty().addListener((observable, before, selected) -> {
            if (selected == null || currentIndex < 0) return;
            for (int index = 0; index < current().cases().size(); index++) {
                if (current().cases().get(index).inputs().equals(selected.inputs())) {
                    debugCase.getSelectionModel().select(Integer.valueOf(index));
                    break;
                }
            }
        });
        addColumn("Inputs", row -> row.inputs().toString());
        addColumn("Expected", row -> row.expected().toString());
        addColumn("Actual", row -> row.actual().isEmpty() ? "—" : row.actual().toString());
        addColumn("Steps", row -> row.steps() == 0 ? "—" : Integer.toString(row.steps()));
        addColumn("Result", row -> GameMessages.diagnostic(progress.taskLanguage(), row.message()));
        cases.setRowFactory(table -> new TableRow<>() {
            @Override protected void updateItem(GameResult.CaseResult row, boolean empty) {
                super.updateItem(row, empty);
                setStyle(empty || row == null || row.actual().isEmpty() ? ""
                        : "-fx-background-color: " + (row.passed() ? "#e2f3e9" : "#fbe8e7") + ";");
            }
        });
        VBox center = new VBox(9, introduction, rewardBanner, work, actions, hintLabel, feedback, checking, cases);
        center.setPadding(new Insets(16, 18, 12, 10));
        return center;
    }

    private void formatSource() {
        if (loading || activeCheck != null || !editor.isEditable()) return;
        String source = editor.getText();
        String formatted = SourceFormatter.format(source);
        if (!formatted.equals(source)) {
            int caret = editor.getCaretPosition();
            // A single replacement keeps formatting in the shared source undo history.
            editor.replaceText(0, source.length(), formatted);
            editor.positionCaret(Math.min(caret, formatted.length()));
        }
        editor.requestFocus();
    }

    private String formattedToolbox() {
        return SourceFormatter.format(GameCatalog.library(progress.completedIds()).getOrDefault("toolbox", ""));
    }

    private void addColumn(String title, java.util.function.Function<GameResult.CaseResult, String> value) {
        TableColumn<GameResult.CaseResult, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        column.setCellFactory(view -> new TableCell<>() {
            @Override protected void updateItem(String text, boolean empty) {
                super.updateItem(text, empty);
                setText(empty ? null : text);
                setTooltip(empty || text == null ? null : new Tooltip(text));
            }
        });
        cases.getColumns().add(column);
    }

    private GameLevel current() { return levels.get(currentIndex); }

    private String message(GameMessages.Key key, Object... arguments) {
        return GameMessages.text(progress.taskLanguage(), key, arguments);
    }

    private void setFeedback(GameMessages.Key key, Object... arguments) {
        setFeedback(language -> GameMessages.text(language, key, arguments));
    }

    private void setFeedback(Function<GameTaskLanguage, String> message) {
        feedbackMessage = message;
        feedback.setText(message.apply(progress.taskLanguage()));
    }

    private void setSaved(Function<GameTaskLanguage, String> message) {
        savedMessage = message;
        saved.setText(message.apply(progress.taskLanguage()));
    }

    private String recoverySuffix(GameTaskLanguage language) {
        return recoveryNotice == null ? "" : " " + recoveryNotice.apply(language);
    }

    private String worldTitle(String world) {
        return message(WORLD_TITLE, worlds.indexOf(world) + 1, GameTranslations.world(world, progress.taskLanguage()));
    }

    private ListCell<String> worldNavigationCell() {
        return new ListCell<>() {
            @Override protected void updateItem(String world, boolean empty) {
                super.updateItem(world, empty);
                setText(empty || world == null ? null : worldTitle(world) + (worldAvailable(world) ? "" : " · " + message(LOCKED)));
                setDisable(!empty && world != null && !worldAvailable(world));
            }
        };
    }

    private boolean worldAvailable(String world) {
        for (int index = 0; index < levels.size(); index++) {
            if (levels.get(index).world().equals(world) && progress.isAvailable(index)) return true;
        }
        return false;
    }

    private void refreshWorldNavigation() {
        if (currentIndex < 0 || worldPicker == null) return;
        String world = current().world();
        selectingWorld = true;
        // Refresh the translated button text as well as the popup cells.
        worldPicker.setValue(null);
        worldPicker.setValue(world);
        selectingWorld = false;
        long total = levels.stream().filter(level -> level.world().equals(world)).count();
        long completed = levels.stream().filter(level -> level.world().equals(world)
                && progress.isCompleted(level.id())).count();
        worldProgress.setText(message(WORLD_PROGRESS, completed, total));
    }

    /** Patch only the changed range, keeping native undo and unrelated source formatting. */
    private void applyDiagramSource(Node view, String source) {
        if (loading || activeCheck != null || currentIndex < 0
                || !programEditors.getItems().contains(view)) return;
        String previous = editor.getText();
        if (previous.equals(source)) return;
        int start = 0;
        int end = previous.length();
        int newEnd = source.length();
        while (start < end && start < newEnd && previous.charAt(start) == source.charAt(start)) start++;
        while (end > start && newEnd > start && previous.charAt(end - 1) == source.charAt(newEnd - 1)) {
            end--;
            newEnd--;
        }
        applyingDiagramSource = true;
        try { editor.replaceText(start, end, source.substring(start, newEnd)); }
        finally { applyingDiagramSource = false; }
    }

    /** Link the two views without changing the source selection or taking focus from the diagram. */
    private void highlightDiagramSource(Node view, IndexRange range) {
        if (loading || currentIndex < 0 || !programEditors.getItems().contains(view)
                || range == null || range.getEnd() > editor.getLength()) return;
        boolean valid = view == flowchart
                ? flowchart.isSynchronized() && flowchart.getSource().equals(editor.getText())
                : diagram.isSynchronized() && diagram.getSource().equals(editor.getText());
        if (!valid) return;
        editor.setLinkedRange(range.getStart(), range.getEnd());
        editor.revealSourceOffset(range.getStart());
    }

    private void highlightDiagramCaret() {
        if (loading || applyingDiagramSource || currentIndex < 0 || programEditors == null) return;
        editor.clearLinkedRange();
        if (programEditors.getItems().contains(flowchart)) flowchart.highlightSourceOffset(editor.getCaretPosition());
        else if (programEditors.getItems().contains(diagram)) diagram.highlightSourceOffset(editor.getCaretPosition());
    }

    private void refreshDiagram() {
        if (currentIndex < 0) return;
        boolean structured = GameWorld.usesDiagrams(current());
        List<String> modes = structured ? List.of("Nassi–Shneiderman", "Flowchart") : List.of("Flowchart");
        selectingDiagramMode = true;
        try {
            if (!diagramMode.getItems().equals(modes)) diagramMode.getItems().setAll(modes);
            diagramMode.setValue(structured ? structuredDiagramMode : "Flowchart");
        } finally { selectingDiagramMode = false; }
        diagramMode.setDisable(activeCheck != null || !structured);
        Node chosen = structured && !"Flowchart".equals(diagramMode.getValue()) ? diagram
                : GameWorld.usesFlowcharts(current()) ? flowchart : null;
        // Initialize each view on first use; keep its pending fields when switching back.
        diagramInitialized |= chosen == diagram;
        flowchartInitialized |= chosen == flowchart;
        if (diagramInitialized) {
            diagram.setTaskLanguage(progress.taskLanguage());
            diagram.setFeatures(progress.features());
            diagram.setLibraries(GameCatalog.library(progress.completedIds()));
            diagram.setSource(editor.getText());
        }
        if (flowchartInitialized) {
            flowchart.setTaskLanguage(progress.taskLanguage());
            flowchart.setExplicitJumpsAllowed(!structured);
            flowchart.setFeatures(progress.features());
            flowchart.setLibraries(GameCatalog.library(progress.completedIds()));
            flowchart.setSource(editor.getText());
        }
        if (chosen == null || !programEditors.getItems().contains(chosen)) {
            boolean restoreSelectorFocus = diagramMode.isFocused();
            diagramMode.hide();
            // A JavaFX control has one parent: detach it before transferring it to the other toolbox.
            diagram.setTitleControl(null);
            flowchart.setTitleControl(null);
            if (chosen == diagram) diagram.setTitleControl(diagramMode);
            else if (chosen == flowchart) flowchart.setTitleControl(diagramMode);
            double divider = programEditors.getItems().size() == 2 ? programEditors.getDividerPositions()[0] : .46;
            Node sourcePane = programEditors.getItems().get(0);
            editor.clearLinkedRange();
            diagram.clearSourceHighlight();
            flowchart.clearSourceHighlight();
            if (chosen == null) programEditors.getItems().setAll(sourcePane);
            else programEditors.getItems().setAll(sourcePane, chosen);
            programEditors.setDividerPositions(divider);
            highlightDiagramCaret();
            if (restoreSelectorFocus) diagramMode.requestFocus();
        }
    }

    private GameTranslations.TaskText taskText(GameLevel level) {
        return GameTranslations.text(level, progress.taskLanguage());
    }

    /** Refresh text in place so switching languages preserves the entire working session. */
    private void refreshTaskText() {
        GameLevel level = current();
        GameTranslations.TaskText text = taskText(level);
        worldLabel.setText(worldTitle(level.world())
                + "  ·  " + message(LEVEL_POSITION, currentIndex + 1, levels.size()));
        titleLabel.setText(text.title());
        summaryLabel.setText(GameTaskSummaries.text(level, progress.taskLanguage()));
        detailTitle.setText(text.title());
        goalLabel.setText(text.goal());
        lessonLabel.setText(text.lesson());
        if (shownHints > 0) {
            hintLabel.setText(message(HINT, shownHints, text.hints().size(), text.hints().get(shownHints - 1)));
        }
        contractLabel.setText(message(CONTRACT, level.cases().size(), level.maxSteps()));
        showRewards(announcingNewRewards);
    }

    private void showLevel(int index) {
        if (currentIndex >= 0) progress.setDraft(current().id(), editor.getText());
        currentIndex = index;
        progress.setSelectedIndex(index);
        levelList.scrollTo(index);
        GameLevel level = current();
        debugCase.getItems().setAll(java.util.stream.IntStream.range(0, level.cases().size()).boxed().toList());
        debugCase.getSelectionModel().selectFirst();
        loading = true;
        diagram.resetContext();
        flowchart.resetContext();
        diagramInitialized = flowchartInitialized = false;
        editor.clearExecutionHighlights();
        editor.clearLinkedRange();
        editor.setText(progress.draft(level));
        loading = false;
        shownHints = 0;
        hintLabel.setText("");
        hint.setDisable(level.hints().isEmpty());
        hint.setText("Show hint");
        announcingNewRewards = false;
        refreshTaskText();
        setFeedback(progress.isCompleted(level.id()) ? COMPLETED : READY);
        checking.setProgress(0);
        cases.getItems().clear();
        level.cases().stream().limit(3).forEach(test -> cases.getItems().add(new GameResult.CaseResult(
                test.inputs(), test.expected(), Map.of(), 0, false, "Example")));
        refreshInventory();
        highlightDiagramCaret();
        saveDelay.playFromStart();
    }

    private void showRewards(boolean newlyEarned) {
        announcingNewRewards = newlyEarned;
        GameLevel level = current();
        List<String> earned = new ArrayList<>();
        level.unlocks().stream().sorted().forEach(feature -> earned.add(GameMessages.featureTitle(progress.taskLanguage(), feature)));
        level.rewards().keySet().forEach(name -> earned.add(message(HELPER, name)));
        boolean completed = progress.isCompleted(level.id());
        int nextIndex = completed ? nextLevelIndex() : currentIndex + 1;
        String nextChallenge = nextIndex >= 0 && nextIndex < levels.size()
                ? message(NEXT_CHALLENGE, taskText(levels.get(nextIndex)).title()) : message(JOURNEY_COMPLETE);
        if (nextIndex >= 0 && nextIndex < levels.size() && !level.world().equals(levels.get(nextIndex).world())) {
            GameLevel following = levels.get(nextIndex);
            nextChallenge += "\n" + message(completed ? WORLD_UNLOCKED : NEXT_WORLD, worldTitle(following.world()));
            if (GameWorld.usesDiagrams(following) && !GameWorld.usesDiagrams(level)) {
                nextChallenge += " " + message(STRUCTURED_WORLD_INTRO);
            }
        }
        rewardPreview.setText(message(COMPLETE_TO_UNLOCK,
                earned.isEmpty() ? message(NEXT_CHALLENGE_REWARD) : String.join(" · ", earned))
                + "\n" + nextChallenge);
        unlockAnnouncement.setText((earned.isEmpty() ? message(LEVEL_COMPLETE)
                : message(newlyEarned ? NEW_TOOLS_UNLOCKED : UNLOCKED_BY_LEVEL, String.join(" · ", earned)))
                + " " + nextChallenge + (earned.isEmpty() ? "" : "\n" + message(INVENTORY_PROMPT)));
        rewardPreview.setVisible(!completed);
        unlockAnnouncement.setVisible(completed);
        rewardBanner.setStyle("-fx-background-radius: 6px; -fx-border-radius: 6px; -fx-border-width: 1px; "
                + (completed ? "-fx-background-color: #e0f4e8; -fx-border-color: #4a9b69;"
                        : "-fx-background-color: #e7effb; -fx-border-color: #87a8d4;"));
        rewardPreview.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #204a78;");
        unlockAnnouncement.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #195633;");
    }

    /** A migrated save may have a completed lesson beyond the first unfinished one. */
    private int nextLevelIndex() {
        if (currentIndex < 0) return -1;
        if (progress.isAvailable(currentIndex + 1)) return currentIndex + 1;
        for (int index = 0; index < levels.size(); index++) {
            if (!progress.isCompleted(levels.get(index).id())) {
                return index != currentIndex && progress.isAvailable(index) ? index : -1;
            }
        }
        return -1;
    }

    private void showHint() {
        List<String> hints = taskText(current()).hints();
        if (shownHints < hints.size()) {
            hintLabel.setText(message(HINT, shownHints + 1, hints.size(), hints.get(shownHints++)));
            hint.setText("Next hint");
            hint.setDisable(shownHints == current().hints().size());
        }
    }

    private void refreshInventory() {
        score.setText(message(SCORE, progress.completedCount(), levels.size(), progress.features().size()));
        levelList.refresh();
        refreshWorldNavigation();
        refreshEditorVersion();
        refreshDiagram();
        if (!progress.unreadGrammarFeatures().isEmpty()) grammarHighlights = progress.unreadGrammarFeatures();
        grammar.update(progress.features(), grammarHighlights, currentIndex < 0 || !GameWorld.usesDiagrams(current()));
        acknowledgeInventory();
        String library = formattedToolbox();
        toolbox.setText(library.isBlank() ? "// Solved challenges will add reusable helpers here.\n" : library);
        insertHelpers.setDisable(activeCheck != null || library.isBlank() || !progress.features().contains(GameFeature.MACROS));
        next.setDisable(activeCheck != null || nextLevelIndex() < 0);
        int nextIndex = nextLevelIndex();
        next.setText(nextIndex >= 0 && !current().world().equals(levels.get(nextIndex).world())
                ? "Enter world " + (worlds.indexOf(levels.get(nextIndex).world()) + 1) + " →" : "Next level →");
    }

    private void refreshEditorVersion() {
        if (currentIndex < 0) return;
        LanguageVersion version = GameLanguage.editingVersionFor(current(), progress.features(), editor.getText());
        editor.setLanguageVersion(version);
        editorVersion.setText(GameWorld.usesDiagrams(current()) ? message(STRUCTURED_EDITOR)
                : GameWorld.usesFlowcharts(current())
                    ? message(FLOWCHART_EDITOR) + " " + message(version == LanguageVersion.BEFORE_DO_WHILE ? EARLY_EDITOR : STRUCTURED_VERSION)
                    : message(version == LanguageVersion.BEFORE_DO_WHILE ? EARLY_EDITOR : STRUCTURED_VERSION));
    }

    private static Label newNotice(String id) {
        Label badge = new Label("New");
        badge.setId(id);
        badge.setStyle("-fx-background-color: #fff0b3; -fx-text-fill: #654900; -fx-font-weight: bold; -fx-padding: 1 6; -fx-background-radius: 8;");
        badge.managedProperty().bind(badge.visibleProperty());
        badge.setVisible(false);
        return badge;
    }

    private void acknowledgeInventory() {
        if (work == null || progress == null) return;
        boolean changed = false;
        if (work.getSelectionModel().getSelectedItem() == grammarTab && !progress.unreadGrammarFeatures().isEmpty()) {
            grammar.revealNewAdditions();
            progress.markGrammarRead();
            changed = true;
        }
        if (work.getSelectionModel().getSelectedItem() == toolboxTab && progress.hasUnreadToolbox()) {
            progress.markToolboxRead();
            changed = true;
        }
        grammarNotice.setVisible(!progress.unreadGrammarFeatures().isEmpty());
        toolboxNotice.setVisible(progress.hasUnreadToolbox());
        grammarNotice.setText(message(NEW));
        toolboxNotice.setText(message(NEW));
        grammarTab.setTooltip(new Tooltip(message(grammarNotice.isVisible() ? GRAMMAR_NEW : GRAMMAR_EARNED)));
        toolboxTab.setTooltip(new Tooltip(message(toolboxNotice.isVisible() ? TOOLBOX_NEW : TOOLBOX_EARNED)));
        if (changed) saveDelay.playFromStart();
    }

    private void debugSolution() {
        if (activeCheck != null || currentIndex < 0 || debugCase.getValue() == null) return;
        GameLevel level = current();
        String source = editor.getText();
        int inputCase = debugCase.getValue();
        var available = progress.features();
        var libraries = GameCatalog.library(progress.completedIds());
        progress.setDraft(level.id(), source);
        persist();
        Task<GameDebugSession> task = new Task<>() {
            @Override protected GameDebugSession call() throws Exception {
                return GameDebugSession.prepare(level, source, available, libraries, inputCase,
                        () -> isCancelled() || Thread.currentThread().isInterrupted());
            }
        };
        activeCheck = task;
        setChecking(true);
        checking.setProgress(-1);
        setFeedback(PREPARING_DEBUG, inputCase + 1);
        task.setOnSucceeded(event -> {
            finishCheck(task);
            checking.setProgress(0);
            new GameDebugger(task.getValue(), progress.taskLanguage()).show(stage);
            setFeedback(DEBUGGING, inputCase + 1);
        });
        task.setOnCancelled(event -> {
            finishCheck(task);
            checking.setProgress(0);
            setFeedback(DEBUG_CANCELLED);
        });
        task.setOnFailed(event -> {
            finishCheck(task);
            checking.setProgress(0);
            setFeedback(language -> GameMessages.text(language, DEBUG_FAILED,
                    GameMessages.diagnostic(language, task.getException().getMessage())));
        });
        checker.execute(task);
    }

    private void checkSolution() {
        if (activeCheck != null || currentIndex < 0) return;
        GameLevel level = current();
        String source = editor.getText();
        progress.setDraft(level.id(), source);
        persist();
        editor.clearExecutionHighlights();
        var available = progress.features();
        var libraries = GameCatalog.library(progress.completedIds());
        Task<GameResult> task = new Task<>() {
            @Override protected GameResult call() {
                return GameEngine.check(level, source, available, libraries,
                        () -> isCancelled() || Thread.currentThread().isInterrupted(),
                        done -> updateProgress(done, level.cases().size()));
            }
        };
        activeCheck = task;
        setChecking(true);
        checking.progressProperty().bind(task.progressProperty());
        setFeedback(CHECKING);
        task.setOnSucceeded(event -> {
            finishCheck(task);
            GameResult result = task.getValue();
            cases.getItems().setAll(result.cases());
            setFeedback(language -> GameMessages.diagnostic(language, result.summary()));
            checking.setProgress(result.passed() ? 1 : 0);
            if (result.passed()) {
                boolean first = !progress.isCompleted(level.id());
                progress.complete(level, source);
                persist();
                showRewards(first);
                if (first) {
                    setFeedback(language -> {
                        List<String> earned = new ArrayList<>();
                        level.unlocks().stream().sorted().forEach(feature -> earned.add(GameMessages.featureTitle(language, feature)));
                        earned.addAll(level.rewards().keySet());
                        return GameMessages.text(language, PASSED_ALL, result.cases().size()) + " "
                                + (earned.isEmpty() ? GameMessages.text(language, NEXT_UNLOCKED)
                                    : GameMessages.text(language, UNLOCKED, String.join(", ", earned)));
                    });
                    if (progress.completedCount() == levels.size()) setFeedback(JOURNEY_COMPLETE_LONG);
                }
            } else if (result.location() != null && GameSyntax.MAIN.equals(result.location().file())
                    && result.location().endOffset() <= source.length()) {
                editor.setExecutionRange(result.location().startOffset(), result.location().endOffset(), false);
                editor.revealSourceOffset(result.location().startOffset());
            }
            refreshInventory();
        });
        task.setOnCancelled(event -> {
            finishCheck(task);
            setFeedback(CHECK_CANCELLED);
            checking.setProgress(0);
        });
        task.setOnFailed(event -> {
            finishCheck(task);
            setFeedback(language -> GameMessages.text(language, CHECK_FAILED,
                    GameMessages.diagnostic(language, task.getException().getMessage())));
            checking.setProgress(0);
        });
        checker.execute(task);
    }

    private void finishCheck(Task<?> task) {
        if (activeCheck != task) return;
        checking.progressProperty().unbind();
        activeCheck = null;
        setChecking(false);
        refreshInventory();
    }

    private void setChecking(boolean running) {
        check.setDisable(running);
        debug.setDisable(running);
        debugCase.setDisable(running);
        cancel.setDisable(!running);
        restart.setDisable(running);
        reset.setDisable(running);
        levelList.setDisable(running);
        worldPicker.setDisable(running);
        editor.setEditable(!running);
        diagram.setDisable(running);
        flowchart.setDisable(running);
        diagramMode.setDisable(running || currentIndex < 0 || !GameWorld.usesDiagrams(current()));
        insertHelpers.setDisable(running || !progress.features().contains(GameFeature.MACROS));
        next.setDisable(running || nextLevelIndex() < 0);
    }

    private void resetGame() {
        if (activeCheck != null) return;
        ButtonType confirmReset = new ButtonType("Reset game", ButtonData.OK_DONE);
        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION,
                message(RESET_QUESTION),
                confirmReset, ButtonType.CANCEL);
        confirmation.initOwner(stage);
        confirmation.setTitle(message(RESET_TITLE));
        confirmation.setHeaderText(message(RESET_HEADER));
        ((Button) confirmation.getDialogPane().lookupButton(confirmReset)).setDefaultButton(false);
        ((Button) confirmation.getDialogPane().lookupButton(ButtonType.CANCEL)).setDefaultButton(true);
        if (confirmation.showAndWait().orElse(ButtonType.CANCEL) != confirmReset) return;
        saveDelay.stop();
        try {
            progress.reset();
        } catch (IOException error) {
            setFeedback(language -> GameMessages.text(language, RESET_FAILED, GameMessages.diagnostic(language, error.getMessage())));
            setSaved(language -> GameMessages.text(language, RESET_NOT_SAVED, GameMessages.diagnostic(language, error.getMessage())));
            return;
        }
        grammarHighlights = Set.of();
        structuredDiagramMode = "Nassi–Shneiderman";
        // Do not copy the old editor back into the freshly cleared progress in showLevel.
        currentIndex = -1;
        selecting = true;
        levelList.getSelectionModel().clearSelection();
        selecting = false;
        levelList.getSelectionModel().select(0);
        work.getSelectionModel().select(0);
        setFeedback(RESET_DONE);
        setSaved(language -> GameMessages.text(language, RESET_SAVED) + recoverySuffix(language));
    }

    private void cancelCheck() {
        if (activeCheck != null) activeCheck.cancel(true);
    }

    private boolean persist() {
        if (progress == null) return true;
        if (currentIndex >= 0) progress.setDraft(current().id(), editor.getText());
        try {
            progress.save();
            setSaved(language -> GameMessages.text(language, SAVED) + recoverySuffix(language));
            return true;
        } catch (IOException error) {
            setSaved(language -> GameMessages.text(language, SAVE_FAILED, GameMessages.diagnostic(language, error.getMessage())));
            return false;
        }
    }

    @Override public void stop() {
        saveDelay.stop();
        cancelCheck();
        checker.shutdownNow();
        if (progress != null) persist();
    }
}

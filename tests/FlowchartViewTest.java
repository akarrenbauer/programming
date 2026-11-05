import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javafx.application.Platform;
import javafx.scene.AccessibleAction;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.IndexRange;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TitledPane;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.Region;
import javafx.scene.shape.Path;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Circle;
import javafx.scene.shape.MoveTo;
import javafx.scene.shape.LineTo;
import javafx.stage.Stage;
import javafx.stage.Window;

import machine.DiagramValidation;
import machine.Flowchart;
import machine.FlowchartExecution;
import machine.FlowchartView;
import machine.GameCatalog;
import machine.GameFeature;
import machine.GameLanguage;
import machine.GameLevel;
import machine.GameTaskLanguage;
import machine.LanguageVersion;
import machine.StructuredDiagram;

/** JavaFX flowchart geometry, source transactions, profile conversion and stale-draft protections. */
public final class FlowchartViewTest {
    private static final String LOOP = "// Retain this explanation.\nvar n=2;\nbegin:\nagain: DEC n;\nJNZ n again;\nHALT;\n";
    private static FlowchartView view;
    private static Stage stage;
    private static final List<String> changes = new ArrayList<>();
    private static final List<IndexRange> focusedRanges = new ArrayList<>();
    private static int passed, failed;
    @FunctionalInterface private interface Check { void run() throws Exception; }

    public static void main(String[] arguments) throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        Platform.startup(() -> { Platform.setImplicitExit(false); ready.countDown(); });
        require(ready.await(10, TimeUnit.SECONDS), "JavaFX starts");
        try {
            onFx(() -> {
                view = new FlowchartView();
                view.setOnSourceChanged(source -> { changes.add(source); view.setSource(source); });
                view.setOnSourceFocused(focusedRanges::add);
                stage = new Stage(); stage.setScene(new Scene(new StackPane(view), 650, 950)); stage.show();
            });
            check("early instructions render a true graph with terminal, diamond and jump arrows", () -> {
                reset(LOOP, Set.of(), LanguageVersion.BEFORE_DO_WHILE);
                require(view.isSynchronized(), view.getStatusText());
                require(view.lookupAll(".flowchart-terminal").size() == 2, "start and explicit HALT are terminals without disconnected END");
                require(view.lookupAll(".flowchart-decision").size() == 1 && view.lookupAll(".flowchart-process").size() == 1, "JNZ is a decision and DEC is a process");
                require(view.lookupAll(".flowchart-edge").size() == 4 && view.lookupAll(".flowchart-arrowhead").size() == 4, "every graph edge has an arrowhead");
                Path back = view.lookupAll(".flowchart-edge").stream().map(node -> (Path) node)
                        .filter(path -> ((Flowchart.Edge) path.getUserData()).label().equals("true")).findFirst().orElseThrow();
                Flowchart.Edge jump = (Flowchart.Edge) back.getUserData();
                require(jump.to() < jump.from() && back.getElements().size() >= 4, "JNZ follows its real backward label through a routed path");
                require(((Label) view.lookup(".flowchart-comparison-operator")).getText().equals("≠"), "backward jump displays a nonzero comparison");
                require(((Label) view.lookup(".flowchart-condition-question")).getText().equals("?"), "jump diamond ends its condition with a question mark");
                require(view.lookupAll(".flowchart-edge-label").stream().map(node -> ((Label) node).getText()).filter(text -> !text.isBlank()).collect(java.util.stream.Collectors.toSet()).equals(Set.of("YES", "NO")), "decision edges use YES and NO");
                require(field("DEC n;") != null && field("again: ") != null, "target label is separate from its process instruction");
                require(view.lookupAll(".flowchart-tool").size() == 2 && view.lookup("#flowchart-tool-rectangle").isVisible() && view.lookup("#flowchart-tool-diamond").isVisible(), "toolbox offers exactly a statement rectangle and a decision diamond");
            });
            check("implicit begin target is read-only and links to the real source header", () -> {
                String source = "// keep entry trivia\nvar n=2;\nbegin: // program entry\nhead: DEC n;\nJNZ n begin;\nHALT;\n";
                reset(source, Set.of(), LanguageVersion.BEFORE_DO_WHILE);
                require(view.isSynchronized(), view.getStatusText());
                Node marker = view.lookup("#flowchart-begin-label");
                require(marker instanceof Label && ((Label) marker).getText().equals("begin:") && marker.isFocusTraversable(),
                        "implicit begin is a keyboard-focusable read-only label, not an editable prefix");
                Flowchart graph = Flowchart.from(StructuredDiagram.parse(source, LanguageVersion.BEFORE_DO_WHILE, true));
                Flowchart.SourceSpan span = graph.beginSpan();
                IndexRange header = new IndexRange(span.start(), span.end());
                require(span.text(source).equals("begin:") && span.start() == source.indexOf("begin:"), "begin span refers to the exact header token rather than entry-statement trivia");
                TextInputControl draft = field("DEC n;"); draft.setText("INC n;");
                focusedRanges.clear(); marker.requestFocus();
                require(lastFocused().equals(header) && highlightedVertex().kind() == Flowchart.Kind.START,
                        "begin-label focus links to the header and START oval");
                require(field("INC n;") == draft && source.equals(view.getSource()) && changes.isEmpty(), "focusing the alias preserves another pending field without writing source");
                focusedRanges.clear(); view.highlightSourceOffset(span.start() + 2);
                require(highlightedVertex().kind() == Flowchart.Kind.START && header.equals(view.getHighlightedRange()) && focusedRanges.isEmpty(),
                        "a source caret on begin maps to START without a reverse focus callback");
                require(stage.getScene().getFocusOwner() == marker, "programmatic header selection leaves keyboard focus unchanged");
                enter(draft, 2);
                String edited = source.replace("DEC n;", "INC n;");
                require(view.getSource().equals(edited) && changes.size() == 1,
                        "committing another field keeps the original jump target and inserts no synthetic label");
                Flowchart editedGraph = Flowchart.from(StructuredDiagram.parse(edited, LanguageVersion.BEFORE_DO_WHILE, true));
                Flowchart.Edge jump = editedGraph.edges().stream().filter(editedGraph::isJumpEdge).findFirst().orElseThrow();
                require(editedGraph.isImplicitBeginTarget(jump.to()) && editedGraph.vertex(jump.to()).owner().editText().equals("INC n;"),
                        "begin continues to enter the first real instruction after source-backed editing");
                view.setSource(source); view.applyCss(); view.layout();
                require(view.lookup("#flowchart-begin-label") instanceof Label && field("DEC n;") != null, "source undo restores the implicit begin link without adding declarations");
            });
            check("an explicit begin label remains an ordinary editable declaration", () -> {
                String source = "var n=1;\nbegin:\nNOP;\nbegin : DEC n;\nJNZ n begin;\nHALT;\n";
                reset(source, Set.of(), LanguageVersion.BEFORE_DO_WHILE);
                require(view.isSynchronized(), view.getStatusText());
                require(view.lookup("#flowchart-begin-label") == null && field("begin: ") instanceof TextField,
                        "a later explicit declaration is editable rather than displayed as the built-in alias");
                TextInputControl declaration = field("begin: "); declaration.requestFocus();
                require(lastFocused().getStart() == source.indexOf("begin :") && highlightedVertex().kind() == Flowchart.Kind.PROCESS,
                        "explicit label focus maps to its actual source declaration and entry statement");
            });
            check("label and jump target edits commit together and source echo creates no duplicate transaction", () -> {
                reset(LOOP, Set.of(), LanguageVersion.BEFORE_DO_WHILE);
                TextInputControl process = field("DEC n;");
                field("again: ").setText("loop: ");
                process.setText("INC n;"); editJump("JNZ n loop;");
                require(view.getSource().equals(LOOP.replace("again: DEC n;", "loop: INC n;").replace("JNZ n again;", "JNZ n loop;")), "whole label and instruction spans change atomically");
                require(changes.size() == 1, "one callback includes both edits");
                Node current = field("INC n;"); view.setSource(view.getSource());
                require(field("INC n;") == current, "same-source refresh preserves controls");
                view.setSource(LOOP);
                require(field("DEC n;") != null && view.isSynchronized(), "source-side undo restores labels and jump graph");
            });
            check("retargeting by label updates direction-derived comparisons and branch answers", () -> {
                String code = LOOP.replace("HALT;", "finish: HALT;");
                reset(code, Set.of(), LanguageVersion.BEFORE_DO_WHILE);
                editJump("JNZ n finish;");
                require(((Label) view.lookup(".flowchart-comparison-operator")).getText().equals("="), "forward jump displays zero comparison");
                Label jumpAnswer = view.lookupAll(".flowchart-edge-label").stream().map(Label.class::cast)
                        .filter(label -> ((Flowchart.Edge) label.getUserData()).label().equals("true")).findFirst().orElseThrow();
                require(jumpAnswer.getText().equals("NO"), "canonical nonzero jump answers NO for a displayed zero comparison");
                StructuredDiagram.Node jump = StructuredDiagram.parse(view.getSource(), LanguageVersion.BEFORE_DO_WHILE, true).main().nodes().get(1);
                ((TextInputControl) view.lookup("#flowchart-edit-" + jump.start() + "-label")).setText("self: ");
                editJump("JNZ n self;");
                require(((Label) view.lookup(".flowchart-comparison-operator")).getText().equals("≠") && view.getSource().contains("self: JNZ n self;"),
                        "pending new label and pencil edit form a valid self jump: " + view.getStatusText());
            });
            check("pencil editing preserves pending operands and changes the full jump instruction atomically", () -> {
                String code = LOOP.replace("var n=2;", "var n=2;\nvar m=0;").replace("HALT;", "finish: HALT;");
                reset(code, Set.of(), LanguageVersion.BEFORE_DO_WHILE);
                ((TextInputControl) view.lookup(".flowchart-jump-variable")).setText("m");
                field("DEC n;").setText("INC m;");
                ((Button) view.lookup(".flowchart-edit-instruction")).fire();
                TextInputControl raw = rawField();
                require(raw.getText().equals("JNZ m again;"), "pencil editor includes the pending operand and existing target");
                raw.setText("JNZ m finish;");
                enter(raw, 2);
                require(view.getSource().equals(code.replace("DEC n;", "INC m;").replace("JNZ n again;", "JNZ m finish;")) && changes.size() == 1,
                        "editing the target through the complete instruction preserves other drafts in one transaction");
                require(((Label) view.lookup(".flowchart-comparison-operator")).getText().equals("="), "advanced target edits update displayed jump direction");
            });
            check("Enter inserts NOP before empty statements unlock and semicolons after", () -> {
                reset(LOOP, Set.of(), LanguageVersion.BEFORE_DO_WHILE);
                enter(field("HALT;"), 0);
                require(view.getSource().contains("NOP;"), "early insertion uses an available instruction");
                require(changes.size() == 1, "insertion is one source edit");
            });
            check("inserted process gets focus and selection for replacement", () -> {
                TextInputControl field = field("NOP;");
                require(stage.getScene().getFocusOwner() == field && field.getSelectedText().equals("NOP;"), "new process is ready for typing");
                view.setFeatures(Set.of(GameFeature.BLOCKS, GameFeature.EMPTY_STATEMENTS));
                TextInputControl halt = field("HALT;"); enter(halt, halt.getLength());
                StructuredDiagram parsed = StructuredDiagram.parse(view.getSource(), LanguageVersion.BEFORE_DO_WHILE, true);
                require(parsed.main().nodes().get(parsed.main().nodes().size() - 1).text().equals(";"), "earned empty statement inserted after node");
            });
            check("invalid label changes preserve cached nodes and block stale edits", () -> {
                reset(LOOP, Set.of(), LanguageVersion.BEFORE_DO_WHILE);
                Node decision = view.lookup(".flowchart-decision");
                TextInputControl stale = (TextInputControl) view.lookup(".flowchart-jump-variable");
                String invalid = LOOP.replace("JNZ n again;", "JNZ n missing;"); view.setSource(invalid);
                require(!view.isSynchronized() && view.lookup(".flowchart-decision") == decision, "unresolved jump retains last valid graph node");
                require(decision.getStyleClass().contains("flowchart-damaged") && !stale.isEditable(), "damaged jump is red and read-only");
                stale.setText("HALT;"); enter(stale, 2);
                require(view.getSource().equals(invalid) && changes.isEmpty(), "stale handler cannot overwrite invalid code");
                view.setSource(LOOP); require(view.isSynchronized(), "corrected label restores editing");
                view.resetContext(); view.setSource(invalid);
                require(!view.isSynchronized() && view.lookupAll(".flowchart-node").isEmpty(), "unknown targets without cache never invent graph edges");
            });
            check("empty block entries and source boundaries accept inserted instructions", () -> {
                reset("var n=0;\nbegin:\nloop: {}\nHALT;\n", Set.of(GameFeature.BLOCKS), LanguageVersion.BEFORE_DO_WHILE);
                StructuredDiagram parsed = StructuredDiagram.parse(view.getSource(), LanguageVersion.BEFORE_DO_WHILE, true);
                StructuredDiagram.Node block = parsed.main().nodes().get(0);
                Node target = view.lookup("#flowchart-body-" + block.start());
                require(target != null, "empty BLOCK JOIN has a body target"); target.executeAccessibleAction(AccessibleAction.FIRE);
                parsed = StructuredDiagram.parse(view.getSource(), LanguageVersion.BEFORE_DO_WHILE, true);
                require(parsed.main().nodes().get(0).body().nodes().get(0).text().equals("NOP;"), "placeholder is inside selected block");
            });
            check("flowchart palette contains only rectangle and diamond regardless of unlocked syntax", () -> {
                reset("var n=0;\nbegin:\nHALT;\n", Set.of(), LanguageVersion.BEFORE_DO_WHILE);
                view.lookup("#flowchart-tool-diamond").executeAccessibleAction(AccessibleAction.FIRE);
                view.lookup("#flowchart-main-gap").executeAccessibleAction(AccessibleAction.FIRE);
                require(view.isSynchronized() && view.getSource().contains("again: JNZ n again;"), "new jump creates a unique editable target when no label exists");
                require(view.lookupAll(".flowchart-decision").size() == 1, "jump template is a real decision");
                reset("var n=0;\nbegin:\nHALT;\n", EnumSet.allOf(GameFeature.class), LanguageVersion.STRUCTURED);
                require(view.lookupAll(".flowchart-tool").size() == 2 && view.lookup("#flowchart-tool-if") == null && view.lookup("#flowchart-tool-while") == null,
                        "unlocking structured syntax adds no syntax-specific symbols");
                view.lookup("#flowchart-tool-rectangle").executeAccessibleAction(AccessibleAction.FIRE);
                view.lookup("#flowchart-main-gap").executeAccessibleAction(AccessibleAction.FIRE);
                require(StructuredDiagram.parse(view.getSource(), LanguageVersion.STRUCTURED, true).main().nodes().get(0).text().equals(";"), "rectangle creates an earned empty instruction");
                view.setSource("var n=0;\nbegin:\nif (n == 0) { NOP; }\nHALT;\n");
                require(view.lookupAll(".flowchart-edge-label").stream().map(node -> ((Label) node).getText()).anyMatch("NO"::equals), "conditional false branch has an arrow label");
                TextInputControl label = (TextInputControl) view.lookup("#flowchart-edit-" + StructuredDiagram.parse(view.getSource(), LanguageVersion.STRUCTURED, true).main().nodes().get(0).start() + "-label");
                label.setText("entry: "); enter(label, 2);
                require(view.getSource().contains("entry: if"), "label may be added to an originally unlabeled structured node");
            });
            check("conversion resolver validates pending label and jump edits in the candidate profile", () -> {
                GameLevel level = GameCatalog.levels().stream().filter(item -> item.id().equals("clear-do-while")).findFirst().orElseThrow();
                Set<GameFeature> earned = Set.of(GameFeature.BLOCKS, GameFeature.EMPTY_STATEMENTS, GameFeature.DO_WHILE);
                reset(level.starter(), earned, LanguageVersion.BEFORE_DO_WHILE);
                view.setLanguageVersionResolver(source -> GameLanguage.editingVersionFor(level, earned, source));
                TextInputControl label = view.lookupAll(".flowchart-label-edit").stream().map(node -> (TextInputControl) node)
                        .filter(control -> control.getText().strip().equals("do:")).findFirst().orElseThrow();
                label.setText("do ");
                ((Button) view.lookup(".flowchart-edit-instruction")).fire();
                TextInputControl jump = rawField(); jump.setText("while (x != 0);"); enter(jump, 3);
                require(view.isSynchronized(), view.getStatusText());
                require(StructuredDiagram.parse(view.getSource(), LanguageVersion.STRUCTURED, true).main().nodes().stream()
                                .anyMatch(node -> node.kind() == StructuredDiagram.Kind.DO_WHILE)
                        && changes.size() == 1, "atomic diagram rewrite changes old label and trailing jump in one source edit");
                require(view.lookup(".flowchart-condition-variable") != null && field("x") != null, "new profile renders the post-test decision condition");
            });
            check("pending new labels stay with their node when inserting before or deleting it", () -> {
                String code = "var n=0;\nbegin:\nif (n == 0) { NOP; }\nHALT;\n";
                reset(code, EnumSet.allOf(GameFeature.class), LanguageVersion.STRUCTURED);
                TextInputControl label = (TextInputControl) view.lookup("#flowchart-edit-" + StructuredDiagram.parse(view.getSource(), LanguageVersion.STRUCTURED, true).main().nodes().get(0).start() + "-label");
                label.setText("target: ");
                view.lookup("#flowchart-main-gap").executeAccessibleAction(AccessibleAction.FIRE);
                StructuredDiagram result = StructuredDiagram.parse(view.getSource(), LanguageVersion.STRUCTURED, true);
                require(result.main().nodes().get(0).text().equals(";") && result.main().nodes().get(1).labels().strip().equals("target:"),
                        "before insertion leaves new label attached to original IF");
                reset(code, EnumSet.allOf(GameFeature.class), LanguageVersion.STRUCTURED);
                label = (TextInputControl) view.lookup("#flowchart-edit-" + StructuredDiagram.parse(code, LanguageVersion.STRUCTURED, true).main().nodes().get(0).start() + "-label");
                label.setText("target: ");
                StructuredDiagram.Node conditional = StructuredDiagram.parse(code, LanguageVersion.STRUCTURED, true).main().nodes().get(0);
                ((Button) view.lookup("#flowchart-delete-" + conditional.start())).fire();
                require(!view.getSource().contains("target:") && !view.getSource().contains("if ("), "deleting node removes its pending label too");
            });
            check("language switches retain pending edits and localize validation status", () -> {
                reset(LOOP, Set.of(), LanguageVersion.BEFORE_DO_WHILE);
                TextInputControl edit = field("DEC n;"); edit.setText("INC n;");
                view.setTaskLanguage(GameTaskLanguage.GERMAN);
                require(view.getStatusText().contains("Änderung") && field("INC n;") == edit, "pending status changes without rebuilding fields");
                enter(edit, 3); require(view.getStatusText().equals("Code und Programmablaufplan sind synchronisiert."), "success message is German");
                view.setSource(view.getSource().replace("JNZ n again;", "JNZ n missing;"));
                require(view.getStatusText().contains("schreibgeschützt") && !view.getStatusText().contains("Unresolved"), "invalid-source status is localized");
            });
            check("trash and busy guards operate on the selected source-owned block", () -> {
                reset("var n=0;\nbegin:\nINC n;\nHALT;\n", Set.of(), LanguageVersion.BEFORE_DO_WHILE);
                StructuredDiagram parsed = StructuredDiagram.parse(view.getSource(), LanguageVersion.BEFORE_DO_WHILE, true);
                Button trash = (Button) view.lookup("#flowchart-delete-" + parsed.main().nodes().get(0).start());
                require(trash.getText().isEmpty() && trash.getGraphic() != null, "deletion uses a graphic");
                view.setDisable(true); trash.fire(); require(changes.isEmpty(), "running check prevents source mutation");
                view.setDisable(false); trash.fire(); require(!view.getSource().contains("INC n;") && view.getSource().contains("HALT;"), "deletion preserves neighbor");
            });
            check("entry label fields stay single-line without flattening source trivia", () -> {
                String code = "var n=1;\nbegin:\nfirst // between name and colon\n : // after colon\n second: \n  DEC n;\nJNZ n first;\nHALT;\n";
                reset(code, Set.of(), LanguageVersion.BEFORE_DO_WHILE);
                TextInputControl labels = field("first: second: ");
                require(labels instanceof TextField && !labels.getText().contains("\n"), "multiple entry labels use one projected single-line editor");
                TextInputControl command = field("DEC n;"); command.setText("INC n;"); enter(command, 2);
                require(view.getSource().equals(code.replace("DEC n;", "INC n;")), "an unrelated edit preserves every byte of the original label prefix");
                labels = field("first: second: "); labels.setText("loop: tail: ");
                editJump("JNZ n loop;");
                String renamed = code.replace("DEC n;", "INC n;").replace("first //", "loop //").replace("second:", "tail:").replace("JNZ n first;", "JNZ n loop;");
                require(view.getSource().equals(renamed), "same-count alias renames replace name tokens while preserving comments, newlines and indentation");
                labels = field("loop: tail: "); labels.setText("extra: loop: tail: "); enter(labels, 2);
                require(view.isSynchronized() && view.getSource().contains("// between name and colon\n") && view.getSource().contains("// after colon\n"), "adding an alias retains comments even between a label name and its colon");
                labels = field("extra: loop: tail: "); labels.setText("loop: "); enter(labels, 2);
                require(view.isSynchronized() && view.getSource().contains("// between name and colon\n") && view.getSource().contains("// after colon\n"), "removing aliases retains source comments and line breaks");
            });
            check("do while renders a body and backward decision without a visible do marker", () -> {
                String code = "var n=2;\nbegin:\nouter: do {\n  DEC n;\n} while (n != 0);\nHALT;\n";
                reset(code, EnumSet.allOf(GameFeature.class), LanguageVersion.STRUCTURED);
                Flowchart graph = Flowchart.from(StructuredDiagram.parse(code, LanguageVersion.STRUCTURED, true));
                Flowchart.Edge back = graph.edges().stream().filter(graph::isJumpRoute).findFirst().orElseThrow();
                javafx.scene.layout.Region entry = (javafx.scene.layout.Region) view.lookup("#flowchart-node-" + back.to());
                require(entry.getHeight() == 0 && graph.vertex(back.to()).text().isEmpty(), "loop entry retains mapping but contributes no visible box or marker");
                Path route = view.lookupAll(".flowchart-edge").stream().map(Path.class::cast).filter(path -> back.equals(path.getUserData())).findFirst().orElseThrow();
                javafx.scene.shape.MoveTo tail = (javafx.scene.shape.MoveTo) route.getElements().get(0);
                javafx.scene.layout.Region decision = (javafx.scene.layout.Region) view.lookup("#flowchart-node-" + back.from());
                require(Math.abs(tail.getX() - decision.getLayoutX() - decision.getWidth()) < .01, "loop return exits the right tip of its diamond");
                require(view.lookup("#flowchart-junction-" + back.to()).isVisible() && view.lookup(".flowchart-loop-return").getStyleClass().contains("flowchart-jump-head"), "return reaches an entry bullet and directly representable loop heads are movable");
                require(view.getSource().equals(code) && changes.isEmpty(), "rendering the jump-style loop does not rewrite structured source");
                for (Node diamond : view.lookupAll(".flowchart-decision")) require(((Label) diamond.lookup(".flowchart-condition-question")).getText().equals("?"), "each diamond condition ends in a question mark");
                view.setSource(code.replace("n != 0", "n == 0"));
                require(!view.lookup(".flowchart-loop-return").getStyleClass().contains("flowchart-jump-head") && view.isSynchronized(), "comparison needing more than one JNZ stays editable without advertising unsupported head movement");
                view.setSource("var n=0;\nbegin:\nif (n == 0) { while (n != 0) { do { DEC n; } while (n != 0); } }\nHALT;\n");
                require(view.lookupAll(".flowchart-decision").size() == 3 && view.lookupAll(".flowchart-condition-question").size() == 3,
                        "if, while and do/while diamonds each display the question suffix");
            });
            check("indexed comparison operands link and edit their complete source spans together", () -> {
                String left = "cells // left operand\r\n [ 00 ]";
                String right = "peers // right operand\r\n [ // index\r\n 01 ]";
                String code = "var cells[2]=0; var peers[2]=0; var bound=0;\r\nbegin:\r\n"
                        + "if ( " + left + " // before operator\r\n != // before right\r\n " + right
                        + " // after right\r\n ) { NOP; }\r\nHALT;\r\n";
                reset(code, EnumSet.allOf(GameFeature.class), LanguageVersion.STRUCTURED);
                require(view.isSynchronized(), view.getStatusText());
                TextInputControl leftField = field(left.replace("\r", "")), rightField = field(right.replace("\r", ""));
                require(rightField.getStyleClass().contains("flowchart-condition-literal")
                                && rightField.getAccessibleText().equals("Right comparison operand"),
                        "right operand retains its existing CSS hook with accurate accessible text");
                IndexRange rightRange = new IndexRange(code.indexOf(right), code.indexOf(right) + right.length());
                rightField.requestFocus();
                require(lastFocused().equals(rightRange), "right operand focus includes the comment and closing array bracket");
                view.highlightSourceOffset(rightRange.getEnd() - 1);
                require(view.getHighlightedRange().equals(rightRange) && highlightedVertex().kind() == Flowchart.Kind.DECISION,
                        "source caret at the right closing bracket links to the complete operand");
                enter(rightField, 4);
                require(changes.isEmpty() && view.getSource().equals(code),
                        "applying unchanged multiline fields preserves all CRLF bytes despite TextArea normalization");
                leftField.setText("peers[00]"); rightField.setText("cells[01]"); enter(rightField, 4);
                String changed = code.replace(left, "peers[00]").replace(right, "cells[01]");
                require(view.isSynchronized() && changes.equals(List.of(changed)) && view.getSource().equals(changed),
                        "both pending array edits form one source transaction and preserve surrounding trivia");
                Flowchart graph = view.getGraph();
                Flowchart.ComparisonInfo info = graph.vertices().stream().map(graph::comparisonInfo)
                        .filter(java.util.Objects::nonNull).findFirst().orElseThrow();
                require(info.operandText().equals("peers[00]") && info.rightText().equals("cells[01]"),
                        "the rebuilt graph contains both new indexed operands");
                field("peers[00]").setText("01");
                rightField = field("cells[01]"); rightField.setText("bound"); enter(rightField, 2);
                require(view.getSource().equals(changed.replace("peers[00]", "01").replace("cells[01]", "bound"))
                                && view.isSynchronized(), "a literal left and scalar right can be edited through the same fields");
            });
            check("dynamic loop comparisons keep fixed return heads through operand edits", () -> {
                String code = "var n=1; var zero=0; var cells[1]=0; begin: do { DEC n; } while(n != zero); HALT;";
                reset(code, EnumSet.allOf(GameFeature.class), LanguageVersion.STRUCTURED);
                require(view.isSynchronized() && !view.lookup(".flowchart-loop-return").getStyleClass().contains("flowchart-jump-head"),
                        "zero-initialized scalar is still a dynamic comparison without a movable return head");
                TextInputControl right = field("zero"); right.setText("cells[00]"); enter(right, 4);
                require(view.isSynchronized() && !view.lookup(".flowchart-loop-return").getStyleClass().contains("flowchart-jump-head"),
                        "zero-initialized array value also retains its structured return destination");
                right = field("cells[00]"); right.setText("000"); enter(right, 1);
                require(view.isSynchronized() && view.lookup(".flowchart-loop-return").getStyleClass().contains("flowchart-jump-head"),
                        "only an actual zero literal restores direct nonzero jump editing");
                right = field("000"); right.setText("zero"); enter(right, 2);
                require(view.isSynchronized() && !view.lookup(".flowchart-loop-return").getStyleClass().contains("flowchart-jump-head")
                                && view.getSource().equals(code) && !view.getSource().contains("JNZ"),
                        "returning to a dynamic operand does not lower or rewrite the loop");
            });
            check("keyboard focus links commands, labels, operands and jump heads to exact source spans", () -> {
                reset(LOOP, Set.of(), LanguageVersion.BEFORE_DO_WHILE);
                field("DEC n;").requestFocus();
                require(lastFocused().equals(new IndexRange(LOOP.indexOf("DEC n;"), LOOP.indexOf("DEC n;") + 6)), "process focus emits the command without its label");
                field("again: ").requestFocus();
                require(lastFocused().equals(new IndexRange(LOOP.indexOf("again:"), LOOP.indexOf("DEC n;"))), "label focus emits the exact prefix");
                Node pencil = view.lookup(".flowchart-edit-instruction"); pencil.requestFocus();
                require(lastFocused().equals(new IndexRange(LOOP.indexOf("JNZ"), LOOP.indexOf("JNZ") + "JNZ n again;".length())),
                        "pencil focus links the complete editable jump instruction");
                Node head = view.lookup(".flowchart-jump-head"); require(head.isFocusTraversable(), "jump heads support keyboard focus"); head.requestFocus();
                require(lastFocused().equals(new IndexRange(LOOP.indexOf("JNZ"), LOOP.indexOf("JNZ") + "JNZ n again;".length())), "head focus links back to its jump instruction");
                require(view.lookupAll(".flowchart-source-highlight").size() == 1 && highlightedVertex().kind() == Flowchart.Kind.DECISION, "one corresponding decision is highlighted");
                require(changes.isEmpty(), "focus linkage never changes source");
            });
            check("source caret selection preserves focused drafts and emits no reverse callback", () -> {
                reset(LOOP, Set.of(), LanguageVersion.BEFORE_DO_WHILE);
                TextInputControl draft = field("DEC n;"); draft.requestFocus(); draft.setText("INC n;"); draft.positionCaret(4);
                focusedRanges.clear();
                int operand = LOOP.indexOf("JNZ") + 4;
                view.highlightSourceOffset(operand);
                require(highlightedVertex().kind() == Flowchart.Kind.DECISION && view.getHighlightedRange().equals(new IndexRange(operand, operand + 1)), "source operand maps to its decision and exact editable span");
                require(stage.getScene().getFocusOwner() == draft && draft.getText().equals("INC n;") && draft.getCaretPosition() == 4, "code-driven selection leaves diagram draft focus and caret intact");
                require(focusedRanges.isEmpty() && changes.isEmpty(), "programmatic selection has no callback feedback or source edit");
                Node selected = view.lookup(".flowchart-source-highlight"); view.setSource(LOOP); view.setTaskLanguage(GameTaskLanguage.GERMAN);
                require(view.lookup(".flowchart-source-highlight") == selected && field("INC n;") == draft, "source echo and language switch retain link and pending text");
                view.clearSourceHighlight(); require(view.getHighlightedRange() == null && view.lookupAll(".flowchart-source-highlight").isEmpty(), "explicit clearing removes only the linked highlight");
            });
            check("nested body, do entry, footer and preamble select their own source elements", () -> {
                String code = "var n=2;\nbegin:\nouter: do {\n  if (n == 0) {\n    INC n;\n  }\n} while (n != 0);\nHALT;\n";
                reset(code, EnumSet.allOf(GameFeature.class), LanguageVersion.STRUCTURED);
                view.highlightSourceOffset(code.indexOf("    INC"));
                require(highlightedVertex().owner().editText().equals("INC n;"), "indentation before a nested command selects the deepest statement");
                view.highlightSourceOffset(code.indexOf("outer:"));
                require(highlightedVertex().kind() == Flowchart.Kind.JOIN && highlightedVertex().owner().kind() == StructuredDiagram.Kind.DO_WHILE, "do label selects the loop entry");
                int condition = code.lastIndexOf("n != 0"); view.highlightSourceOffset(condition + 2);
                require(highlightedVertex().kind() == Flowchart.Kind.DECISION && highlightedVertex().owner().kind() == StructuredDiagram.Kind.DO_WHILE, "post-test source condition selects the footer decision");
                Node footer = view.lookup(".flowchart-source-highlight"); footer.requestFocus();
                require(lastFocused().equals(new IndexRange(condition, condition + "n != 0".length())), "footer focus emits only the condition, not the loop subtree");
                view.highlightSourceOffset(code.indexOf("if (")); Node conditional = view.lookup(".flowchart-source-highlight"); conditional.requestFocus();
                int ifCondition = code.indexOf("n == 0");
                require(lastFocused().equals(new IndexRange(ifCondition, ifCondition + "n == 0".length())), "conditional focus emits its header condition only");
                view.highlightSourceOffset(code.indexOf("var n"));
                require(!(view.lookup(".flowchart-source-highlight").getUserData() instanceof Flowchart.Vertex)
                        && ((TitledPane) view.lookup("#flowchart-preamble")).isExpanded(), "preamble caret reveals and highlights its declaration row");
            });
            check("invalid cached source and context resets discard stale linked ranges", () -> {
                reset(LOOP, Set.of(), LanguageVersion.BEFORE_DO_WHILE);
                TextInputControl stale = field("DEC n;"); view.highlightSourceOffset(LOOP.indexOf("DEC"));
                view.setSource(LOOP.replace("JNZ n again;", "JNZ n missing;")); focusedRanges.clear();
                require(view.getHighlightedRange() == null && view.lookupAll(".flowchart-source-highlight").isEmpty(), "invalid source clears mapping while retaining damaged shapes");
                stale.requestFocus(); view.highlightSourceOffset(LOOP.indexOf("DEC"));
                require(focusedRanges.isEmpty() && view.getHighlightedRange() == null, "stale fields and new offsets cannot link old cached spans");
                view.resetContext(); require(view.getHighlightedRange() == null, "new level clears linked selection");
            });
            check("source selection reveals a distant flowchart node without focusing it", () -> {
                String code = "var n=0;\nbegin:\n" + "NOP;\n".repeat(35) + "HALT;\n";
                reset(code, Set.of(), LanguageVersion.BEFORE_DO_WHILE);
                view.highlightSourceOffset(code.indexOf("HALT;"));
            });
            check("distant linked node is scrolled into view after layout", () -> {
                require(((ScrollPane) view.lookup(".scroll-pane")).getVvalue() > .8, "code caret reveals the final node in the graph scroll pane");
                require(highlightedVertex().kind() == Flowchart.Kind.HALT, "revealed node stays linked");
            });
            check("workspace validation refreshes dependencies without discarding valid flowchart drafts", () -> {
                reset(LOOP, Set.of(), LanguageVersion.STRUCTURED);
                AtomicBoolean importsValid = new AtomicBoolean(true); List<String> validated = new ArrayList<>();
                view.setValidator(source -> {
                    validated.add(source); DiagramValidation.Result parsed = documentValidation(source);
                    return importsValid.get() ? parsed : new DiagramValidation.Result(parsed.diagram(), List.of(new DiagramValidation.Diagnostic("Changed helper is invalid", source.indexOf("JNZ"), source.indexOf("JNZ") + 3)));
                });
                TextInputControl draft = field("DEC n;"); draft.setText("INC n;"); view.revalidateSource();
                require(field("INC n;") == draft, "valid same-source dependency checks retain field identities and drafts");
                enter(draft, 2);
                require(validated.contains(LOOP.replace("DEC n;", "INC n;")) && changes.size() == 1, "diagram edits use workspace validation before their source callback");
                Node decision = view.lookup(".flowchart-decision");
                importsValid.set(false); view.revalidateSource();
                require(!view.isSynchronized() && view.lookup(".flowchart-decision") == decision && !field("INC n;").isEditable(), "invalid imports retain the graph read-only");
                importsValid.set(true); view.revalidateSource(); require(view.isSynchronized(), "fixed imports restore normal graph editing");
            });
            check("helper-only sources show editable definitions without invented flowchart terminals", () -> {
                view.resetContext(); view.setValidator(FlowchartViewTest::documentValidation);
                String helper = "// helper source\nvoid bump(value) {\n  INC value;\n}\n";
                view.setSource(helper); changes.clear(); view.applyCss(); view.layout();
                require(view.isSynchronized() && view.lookup("#flowchart-main") == null && view.lookupAll(".flowchart-terminal").isEmpty(), "helper view has no artificial begin/end graph");
                require(((TitledPane) view.lookup("#flowchart-preamble")).isExpanded() && view.lookup("#flowchart-toolbox").isDisabled(), "definitions are expanded and main-only insertion is disabled");
                TextInputControl instruction = field("INC value;"); instruction.setText("DEC value;"); enter(instruction, 3);
                require(view.getSource().equals(helper.replace("INC value;", "DEC value;")) && changes.size() == 1, "helper changes preserve source exactly without inserting a main program");
                view.applyCss(); view.layout();
                view.highlightSourceOffset(view.getSource().indexOf("DEC value;"));
                require(view.getHighlightedRange() != null && view.lookupAll(".flowchart-source-highlight").size() == 1, "helper source offsets remain linked to their declaration/body rows");
            });
            check("execution decorations preserve focused drafts and the observed branch independently of values", () -> {
                reset(LOOP, Set.of(), LanguageVersion.BEFORE_DO_WHILE);
                Flowchart graph = view.getGraph();
                Flowchart.Edge entry = graph.edges().stream().filter(edge -> edge.from() == 0).findFirst().orElseThrow();
                Flowchart.Edge taken = graph.edges().stream().filter(graph::isJumpEdge).findFirst().orElseThrow();
                TextInputControl draft = field("DEC n;"); draft.requestFocus(); draft.setText("INC n;"); draft.selectRange(1, 4);
                IndexRange linked = view.getHighlightedRange();
                view.setExecutionFrame(new FlowchartExecution.Frame(entry.to(), List.of(entry), true));
                view.setRuntimeValues(Map.of(LOOP.indexOf("var n"), "2"));
                require(view.lookupAll(".flowchart-execution-start").size() == 1 && view.lookupAll(".flowchart-execution-next").size() == 1,
                        "fresh execution marks begin and the first real command separately");
                require(((Flowchart.Vertex) view.lookup(".flowchart-execution-next").getUserData()).id() == entry.to()
                        && view.lookup(".flowchart-execution-edge").getUserData().equals(entry), "initial decoration follows the actual entry graph edge");
                view.setExecutionFrame(new FlowchartExecution.Frame(taken.to(), List.of(taken), false));
                view.setRuntimeValues(Map.of(LOOP.indexOf("var n"), "0"));
                require(view.lookupAll(".flowchart-execution-start").isEmpty() && view.lookupAll(".flowchart-execution-edge").size() == 1
                        && view.lookup(".flowchart-execution-edge").getUserData().equals(taken), "changing a live value does not reconstruct or invert the last observed branch");
                require(field("INC n;") == draft && stage.getScene().getFocusOwner() == draft && draft.getSelection().equals(new IndexRange(1, 4)),
                        "runtime updates preserve the exact pending field, focus and selection");
                require(linked.equals(view.getHighlightedRange()) && view.lookupAll(".flowchart-source-highlight").size() == 1,
                        "blue source linkage survives independent execution decoration");
                require(view.getSource().equals(LOOP) && changes.isEmpty(), "runtime annotations never write comments or other edits to source");
                Flowchart.Edge exit = graph.edges().stream().filter(edge -> edge.from() == taken.from() && edge.label().equals("false")).findFirst().orElseThrow();
                view.setExecutionFrame(new FlowchartExecution.Frame(exit.to(), List.of(exit), false), true);
                require(view.isExecutionHalted() && ((Flowchart.Vertex) view.lookup(".flowchart-execution-halted").getUserData()).kind() == Flowchart.Kind.HALT,
                        "halt state decorates the terminal while retaining the arrival edge");
                view.clearExecutionFrame();
                require(view.getExecutionFrame() == null && view.lookupAll(".flowchart-execution-edge").isEmpty()
                        && linked.equals(view.getHighlightedRange()), "clearing execution leaves source focus and drafts alone");
            });
            check("declaration values use read-only comments and a persistent collapsed summary", () -> {
                String code = "var n=2;\nvar data[2]=0;\nbegin:\nHALT;\n";
                reset(code, EnumSet.allOf(GameFeature.class), LanguageVersion.STRUCTURED);
                require(view.isSynchronized(), view.getStatusText());
                TitledPane preamble = (TitledPane) view.lookup("#flowchart-preamble"); preamble.setExpanded(false);
                TextInputControl declaration = field("var n=2;");
                Map<Integer, String> values = Map.of(code.indexOf("var n"), "2", code.indexOf("var data"), "[3, 5]");
                view.setRuntimeValues(values); view.applyCss(); view.layout();
                Label summary = (Label) view.lookup(".flowchart-runtime-summary");
                require(summary.isVisible() && summary.isManaged() && summary.getText().contains("n = 2")
                        && summary.getText().contains("data = [3, 5]"), "collapsed header shows compact variable assignments with current values");
                Label title = (Label) view.lookup(".flowchart-preamble-title");
                require(title.isVisible() && title.getBoundsInParent().getWidth() > 10 && title.getText().contains("Declarations"), "the title remains present beside runtime summary content");
                preamble.setExpanded(true); view.applyCss(); view.layout();
                require(!summary.isManaged() && !summary.isVisible() && title.isVisible(), "expansion keeps the title and moves values to actual declaration rows");
                Set<String> captions = view.lookupAll(".flowchart-runtime-value").stream().map(Label.class::cast).map(Label::getText).collect(java.util.stream.Collectors.toSet());
                require(captions.equals(Set.of("// now: 2", "// now: [3, 5]")), "scalar and array captions are green-style comments outside the editors");
                require(field("var n=2;") == declaration && view.getRuntimeValues().equals(values) && view.getSource().equals(code) && changes.isEmpty(),
                        "annotations preserve source and declaration controls");
                view.clearRuntimeValues();
                require(preamble.isExpanded() && view.lookupAll(".flowchart-runtime-value").isEmpty() && view.getRuntimeValues().isEmpty(),
                        "clearing values does not alter the user's fold state");
            });
            check("invalid cached diagrams and reset discard all runtime mappings", () -> {
                reset(LOOP, Set.of(), LanguageVersion.BEFORE_DO_WHILE);
                Flowchart.Edge first = view.getGraph().edges().get(0);
                FlowchartExecution.Frame initial = new FlowchartExecution.Frame(first.to(), List.of(first), true);
                view.setExecutionFrame(initial); view.setRuntimeValues(Map.of(LOOP.indexOf("var n"), "2"));
                view.setSource(LOOP.replace("JNZ n again;", "JNZ n missing;"));
                require(view.getGraph() == null && view.getExecutionFrame() == null && view.getRuntimeValues().isEmpty(), "invalid source exposes no executable graph or old runtime state");
                view.setExecutionFrame(initial); view.setRuntimeValues(Map.of(LOOP.indexOf("var n"), "99"));
                require(view.lookupAll(".flowchart-execution-next").isEmpty() && view.lookupAll(".flowchart-runtime-value").isEmpty(), "stale frame and value updates cannot decorate cached invalid spans");
                view.setSource(LOOP); view.setExecutionFrame(initial); view.setRuntimeValues(Map.of(LOOP.indexOf("var n"), "2"));
                view.resetContext();
                require(view.getGraph() == null && view.getExecutionFrame() == null && view.getRuntimeValues().isEmpty(), "changing documents clears runtime overlays");
            });
            check("ordinary source boundaries share one insertion target including begin and the final statement", () -> {
                reset(LOOP, Set.of(), LanguageVersion.BEFORE_DO_WHILE);
                StructuredDiagram.Sequence main = view.getGraph().document().main();
                require(view.lookupAll(".flowchart-drop-target").size() == 4 && verifyBoundaryCount(main) == 4,
                        "three statements have exactly four semantic insertion boundaries");
                require(view.lookup("#flowchart-main-gap") != null && view.lookup("#flowchart-before-" + main.nodes().get(0).start()) == null,
                        "begin-to-first has one shared target");
                for (int index = 1; index < main.nodes().size(); ++index) {
                    require(view.lookup("#flowchart-before-" + main.nodes().get(index).start()) != null
                            && view.lookup("#flowchart-after-" + main.nodes().get(index - 1).start()) == null,
                            "before-next replaces the duplicate after-previous target");
                }
                require(view.lookup("#flowchart-after-" + main.nodes().get(main.nodes().size() - 1).start()) != null,
                        "insertion after the final statement remains available");
            });
            check("merged insertion preserves comments, pending label affinity and length-changing edits atomically", () -> {
                String code = "var n=2;\r\nbegin:\r\n  INC n; // keep this explanation\r\n  // next command\r\n  marker: DEC n;\r\n  HALT; // trailing comment\r\n";
                reset(code, Set.of(), LanguageVersion.BEFORE_DO_WHILE);
                int next = view.getGraph().document().main().nodes().get(1).start();
                field("INC n;").setText("INC n; INC n;"); field("marker: ").setText("renamed: ");
                view.lookup("#flowchart-before-" + next).executeAccessibleAction(AccessibleAction.FIRE);
                StructuredDiagram edited = view.getGraph().document();
                require(edited.main().nodes().stream().map(StructuredDiagram.Node::editText).toList().equals(List.of("INC n;", "INC n;", "NOP;", "DEC n;", "HALT;")),
                        "insertion follows the pending expanded instruction at the same source boundary");
                require(edited.main().nodes().get(3).labels().strip().equals("renamed:") && edited.main().nodes().get(2).labels().isBlank(),
                        "a pending label remains attached to the original next node");
                require(view.getSource().contains("INC n; INC n; // keep this explanation\r\n  // next command\r\n")
                        && view.getSource().contains("HALT; // trailing comment\r\n") && changes.size() == 1,
                        "all comment bytes and CRLFs survive one combined transaction");
                int last = edited.main().nodes().get(edited.main().nodes().size() - 1).start();
                view.lookup("#flowchart-after-" + last).executeAccessibleAction(AccessibleAction.FIRE);
                require(view.getSource().indexOf("// trailing comment\r\n") < view.getSource().lastIndexOf("NOP;")
                        && view.isSynchronized() && changes.size() == 2, "end insertion follows trailing line comments instead of splitting or swallowing them");
                view.setSource(code); view.applyCss(); view.layout();
                require(view.lookupAll(".flowchart-drop-target").size() == 4 && field("marker: ") != null,
                        "source undo restores both source boundaries and original labels");
            });
            check("nested sequences and omitted else retain distinct insertion destinations", () -> {
                String code = "var n=0;\nbegin:\nif(n==0) { NOP; } else {}\nwhile(n!=0) {}\ndo { DEC n; } while(n!=0);\n{}\nHALT;\n";
                reset(code, EnumSet.allOf(GameFeature.class), LanguageVersion.STRUCTURED);
                require(view.isSynchronized(), view.getStatusText());
                StructuredDiagram document = view.getGraph().document();
                int expected = verifyBoundaryCount(document.main());
                require(expected == 13 && view.lookupAll(".flowchart-drop-target").size() == expected,
                        "each main/body/else sequence contributes its own n+1 boundaries, including empty bodies");
                StructuredDiagram.Node conditional = document.main().nodes().get(0);
                Node trueEnd = view.lookup("#flowchart-body-" + conditional.start());
                Node falseEmpty = view.lookup("#flowchart-else-" + conditional.start());
                require(trueEnd != null && falseEmpty != null && trueEnd != falseEmpty
                        && trueEnd.getUserData() == conditional.body() && falseEmpty.getUserData() == conditional.alternative(),
                        "true and false destinations stay separate even when the false branch is empty");
                trueEnd.executeAccessibleAction(AccessibleAction.FIRE);
                conditional = view.getGraph().document().main().nodes().get(0);
                require(conditional.body().nodes().size() == 2 && conditional.alternative().nodes().isEmpty(),
                        "body-end insertion remains inside the selected branch");
                view.lookup("#flowchart-else-" + conditional.start()).executeAccessibleAction(AccessibleAction.FIRE);
                conditional = view.getGraph().document().main().nodes().get(0);
                require(conditional.body().nodes().size() == 2 && conditional.alternative().nodes().size() == 1,
                        "empty else still accepts an independent insertion");
                reset("var n=0;\nbegin:\nif(n==0) {}\nHALT;\n", Set.of(GameFeature.BLOCKS, GameFeature.IF), LanguageVersion.STRUCTURED);
                conditional = view.getGraph().document().main().nodes().get(0);
                Node absentElse = view.lookup("#flowchart-else-" + conditional.start());
                require(Boolean.TRUE.equals(absentElse.getProperties().get("flowchart-omitted-else"))
                        && view.lookupAll(".flowchart-drop-target").size() == verifyBoundaryCount(view.getGraph().document().main()) + 1,
                        "creating an omitted else is a separate action, never merged with insertion after the conditional");
                absentElse.executeAccessibleAction(AccessibleAction.FIRE);
                require(changes.isEmpty() && view.getStatusText().contains("locked"), "merged controls preserve the else feature gate");
            });
            check("junctions bisect local incoming connectors including begin and hidden loop entries", () -> {
                for (String code : List.of(LOOP,
                        "var n=2;\nbegin:\nNOP;\nbegin : DEC n;\nJNZ n begin;\nHALT;\n",
                        "var n=2;\nbegin:\nfirst: if(n==0) {} else {}\nouter: do { do {} while(n!=0); } while(n!=0);\nJNZ n first;\nHALT;\n")) {
                    reset(code, EnumSet.allOf(GameFeature.class), LanguageVersion.STRUCTURED);
                    require(view.isSynchronized(), view.getStatusText());
                    for (Node destination : view.lookupAll(".flowchart-head-target")) {
                        int id = (Integer) destination.getUserData();
                        Circle junction = (Circle) view.lookup("#flowchart-junction-" + id);
                        Region previous = (Region) view.lookup("#flowchart-node-" + (id - 1));
                        Region next = (Region) view.lookup("#flowchart-node-" + id);
                        double previousEnd = previous.getLayoutY() + previous.getHeight();
                        double midpoint = (previousEnd + next.getLayoutY()) / 2;
                        double connectorEnd = next.getLayoutY();
                        Flowchart graph = view.getGraph();
                        if (loopEntry(graph.vertex(id))) {
                            int first = id, last = id;
                            while (first > 0 && loopEntry(graph.vertex(first - 1))) --first;
                            while (last + 1 < graph.vertices().size() && loopEntry(graph.vertex(last + 1))) ++last;
                            Region before = (Region) view.lookup("#flowchart-node-" + (first - 1));
                            Region after = (Region) view.lookup("#flowchart-node-" + (last + 1));
                            previousEnd = before.getLayoutY() + before.getHeight(); connectorEnd = after.getLayoutY();
                            midpoint = previousEnd + (connectorEnd - previousEnd) * (id - first + 1) / (last - first + 2);
                            require(Math.abs(next.getLayoutY() - midpoint) < .01, "hidden entries lie on the visible connector rather than adding a do box");
                        }
                        require(Math.abs(junction.getCenterY() - midpoint) < .01,
                                "junction evenly divides the local connector above vertex " + id);
                        Path stem = view.lookupAll(".flowchart-junction-stem").stream().filter(node -> Integer.valueOf(id).equals(node.getUserData()))
                                .map(Path.class::cast).findFirst().orElseThrow();
                        require(Math.abs(((MoveTo) stem.getElements().get(0)).getY() - midpoint) < .01
                                && Math.abs(((LineTo) stem.getElements().get(1)).getY() - next.getLayoutY()) < .01,
                                "incoming stem starts at the midpoint and reaches the actual destination anchor");
                        for (Node handle : view.lookupAll(".flowchart-jump-handle")) if (((Flowchart.Edge) handle.getUserData()).to() == id) {
                            require(handle.getBoundsInParent().getMinY() >= previousEnd - .01 && handle.getBoundsInParent().getMaxY() <= connectorEnd + .01,
                                    "the large pickup area stays inside the connector gap after recentering");
                        }
                    }
                }
            });
            check("insertion bands and arrowhead pickup areas are generous and do not overlap", () -> {
                String code = "var n=2;\nbegin:\nagain: do {\n  DEC n;\n} while (n != 0);\nJNZ n again;\nHALT;\n";
                reset(code, EnumSet.allOf(GameFeature.class), LanguageVersion.STRUCTURED);
                require(view.isSynchronized(), view.getStatusText());
                List<Node> drops = new ArrayList<>(view.lookupAll(".flowchart-drop-target"));
                for (Node drop : drops) require(drop.getBoundsInLocal().getHeight() >= 24, "every insertion slot has at least 24 pixels of vertical pickup area");
                for (int a = 0; a < drops.size(); ++a) for (int b = a + 1; b < drops.size(); ++b) {
                    javafx.geometry.Bounds one = drops.get(a).getBoundsInParent(), two = drops.get(b).getBoundsInParent();
                    require(!one.intersects(two), "different source insertion slots never overlap: " + drops.get(a).getId() + " / " + drops.get(b).getId());
                }
                for (Node candidate : view.lookupAll(".flowchart-jump-handle")) {
                    Region handle = (Region) candidate;
                    require(handle.getWidth() == 32 && handle.getHeight() == 28 && !handle.isMouseTransparent(), "movable heads have a 32 by 28 pointer target");
                    Polygon arrow = view.lookupAll(".flowchart-jump-head").stream().filter(node -> node.getUserData().equals(handle.getUserData())).map(Polygon.class::cast).findFirst().orElseThrow();
                    require(handle.getBoundsInParent().contains(arrow.getBoundsInParent()), "the larger transparent target includes the complete visible arrowhead");
                    for (Node drop : drops) require(!handle.getBoundsInParent().intersects(drop.getBoundsInParent()), "head pickup never covers a toolbox insertion slot");
                }
                require(view.lookupAll(".flowchart-head-target").stream().allMatch(Node::isMouseTransparent), "destination overlays do not steal pointer events before an actual head drag");
            });
            check("restoring the last draft clears the pending status without a source transaction", () -> {
                reset(LOOP, Set.of(), LanguageVersion.BEFORE_DO_WHILE);
                TextInputControl draft = field("DEC n;"); draft.setText("INC n;");
                require(view.getStatusText().contains("pending"), "changed field announces its pending edit");
                draft.setText("DEC n;");
                require(!view.getStatusText().contains("pending") && changes.isEmpty(), "restoring the original text immediately restores synchronized status");
            });
            check("structured mode rejects jumps and removes jump editing without losing unchanged drafts", () -> {
                String code = "var n=2;\nbegin:\nagain: do { DEC n; } while (n != 0);\nHALT;\n";
                reset(code, EnumSet.allOf(GameFeature.class), LanguageVersion.STRUCTURED);
                view.setExplicitJumpsAllowed(false); view.applyCss(); view.layout();
                require(view.isSynchronized() && !view.getGraph().document().allowsExplicitJumps(), "structured mode renders with the world's validation policy");
                require(view.lookup("#flowchart-tool-diamond") == null && view.lookupAll(".flowchart-jump-handle").isEmpty()
                        && view.lookupAll(".flowchart-jump-head").isEmpty() && view.lookupAll(".flowchart-head-target").isEmpty()
                        && view.lookupAll(".flowchart-label-edit").isEmpty(), "structured mode offers no jump insertion, label editors or retarget controls");
                require(view.lookupAll(".flowchart-loop-return").size() == 1, "structured loop keeps its return arrow");
                TextInputControl draft = field("DEC n;"); draft.setText("INC n;");
                view.setExplicitJumpsAllowed(false); view.setSource(code);
                require(field("INC n;") == draft && view.getSource().equals(code) && changes.isEmpty(), "unchanged policy preserves the pending field and source");
                draft.setText("JNZ n again;"); enter(draft, 2);
                require(view.getSource().equals(code) && changes.isEmpty() && view.getStatusText().contains("suspended"), "instruction edits cannot bypass the world's jump ban");
                view.resetContext(); view.setSource(LOOP);
                require(!view.isSynchronized() && view.getStatusText().contains("suspended"), "context reset retains the configured structured policy");
                view.setValidator(FlowchartViewTest::documentValidation);
                require(!view.isSynchronized() && view.getStatusText().contains("suspended"), "custom validation cannot re-enable prohibited jumps");
                view.setExplicitJumpsAllowed(true);
                require(view.isSynchronized() && view.lookup("#flowchart-tool-diamond") != null
                        && !view.lookupAll(".flowchart-jump-handle").isEmpty(), "returning to foundations restores jump validation and editing");
            });
            check("structured palette inserts earned control structures and supports else branches", () -> {
                String code = "// keep setup\nvar n=2;\nbegin:\nHALT;\n";
                for (String kind : List.of("if", "while", "do-while")) {
                    reset(code, EnumSet.allOf(GameFeature.class), LanguageVersion.STRUCTURED);
                    view.setExplicitJumpsAllowed(false);
                    view.lookup("#flowchart-tool-" + kind).executeAccessibleAction(AccessibleAction.FIRE);
                    view.lookup("#flowchart-main-gap").executeAccessibleAction(AccessibleAction.FIRE);
                    StructuredDiagram parsed = StructuredDiagram.parse(view.getSource());
                    require(view.isSynchronized() && parsed.main().nodes().size() == 2
                            && parsed.main().nodes().get(0).kind().name().equals(kind.toUpperCase().replace('-', '_'))
                            && !view.getSource().contains("JNZ") && view.getSource().startsWith("// keep setup\nvar n=2;"), "structured " + kind + " insertion preserves the source and jump ban");
                    require(changes.size() == 1, "inserting a structure emits one source transaction");
                    if (kind.equals("if")) {
                        view.lookup("#flowchart-else-" + parsed.main().nodes().get(0).start()).executeAccessibleAction(AccessibleAction.FIRE);
                        require(StructuredDiagram.parse(view.getSource()).main().nodes().get(0).alternative().nodes().size() == 1,
                                "earned else branch can be added through its separate insertion gap");
                    }
                }
                reset(code, Set.of(GameFeature.BLOCKS, GameFeature.IF), LanguageVersion.STRUCTURED);
                view.setExplicitJumpsAllowed(false);
                require(view.lookup("#flowchart-tool-if") != null && view.lookup("#flowchart-tool-while") == null
                        && view.lookup("#flowchart-tool-do-while") == null && view.lookupAll(".flowchart-tool").size() == 2,
                        "structured toolbox exposes only earned controls and the statement tool");
                view.lookup("#flowchart-tool-if").executeAccessibleAction(AccessibleAction.FIRE);
                view.lookup("#flowchart-main-gap").executeAccessibleAction(AccessibleAction.FIRE);
                require(view.isSynchronized() && view.getSource().contains("NOP;"), "structure template works before empty statements are earned");
            });
            check("diagram zoom scales shapes and hit areas without changing focused drafts or source spans", () -> {
                reset(LOOP, Set.of(), LanguageVersion.BEFORE_DO_WHILE);
                view.setZoomFactor(1); view.applyCss(); view.layout();
                TextInputControl draft = field("DEC n;"); draft.requestFocus(); draft.setText("INC n;"); draft.selectRange(1, 4);
                Node decision = view.lookup(".flowchart-decision"), handle = view.lookup(".flowchart-jump-handle");
                double decisionWidth = decision.localToScene(decision.getBoundsInLocal()).getWidth();
                double handleWidth = handle.localToScene(handle.getBoundsInLocal()).getWidth();
                IndexRange linked = view.getHighlightedRange();
                view.setZoomFactor(1.5); view.applyCss(); view.layout();
                require(Math.abs(decision.localToScene(decision.getBoundsInLocal()).getWidth() - decisionWidth * 1.5) < .1
                        && Math.abs(handle.localToScene(handle.getBoundsInLocal()).getWidth() - handleWidth * 1.5) < .1,
                        "shape text and arrowhead pickup coordinates scale together");
                require(field("INC n;") == draft && stage.getScene().getFocusOwner() == draft && draft.getSelection().equals(new IndexRange(1, 4))
                        && linked.equals(view.getHighlightedRange()), "zoom retains field identity, pending text, focus, selection and source mapping");
                require(view.getSource().equals(LOOP) && changes.isEmpty(), "zoom introduces no source edit or undo transaction");
                ScrollPane scroll = (ScrollPane) view.lookup(".scroll-pane");
                require(scroll.getContent().getLayoutBounds().getWidth() > scroll.getViewportBounds().getWidth(), "scaled graph has a real horizontal scroll extent");
                view.setTaskLanguage(GameTaskLanguage.GERMAN); view.setSource(LOOP);
                require(view.getZoomFactor() == 1.5 && field("INC n;") == draft, "language/source echo keep zoom and draft");
                view.setZoomFactor(1); view.applyCss(); view.layout();
            });
            String screenshot = System.getProperty("flowchart.screenshot");
            if (screenshot != null) onFx(() -> {
                boolean structured = Boolean.getBoolean("flowchart.screenshot.structured");
                String source = structured ? "var n=2;\nbegin:\nif (n == 0) { INC n; }\ndo { DEC n; } while (n != 0);\nHALT;\n" : LOOP;
                reset(source, structured ? EnumSet.allOf(GameFeature.class) : Set.of(),
                        structured ? LanguageVersion.STRUCTURED : LanguageVersion.BEFORE_DO_WHILE);
                view.setExplicitJumpsAllowed(!structured);
                view.setRuntimeValues(Map.of(source.indexOf("var n"), "3"));
                ((TitledPane) view.lookup("#flowchart-preamble")).setExpanded(false);
                view.applyCss(); view.layout();
                javafx.scene.image.WritableImage image = stage.getScene().snapshot(null);
                java.awt.image.BufferedImage pixels = new java.awt.image.BufferedImage((int) image.getWidth(), (int) image.getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
                for (int y = 0; y < pixels.getHeight(); ++y) for (int x = 0; x < pixels.getWidth(); ++x) pixels.setRGB(x, y, image.getPixelReader().getArgb(x, y));
                javax.imageio.ImageIO.write(pixels, "png", java.nio.file.Path.of(screenshot).toFile());
            });
        } finally { onFx(() -> stage.close()); Platform.exit(); }
        System.out.println("FlowchartViewTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) throw new AssertionError(failed + " flowchart view groups failed");
    }
    private static void reset(String source, Set<GameFeature> features, LanguageVersion version) {
        view.resetContext(); view.setValidator(null); view.setDisable(false); view.setTaskLanguage(GameTaskLanguage.ENGLISH);
        view.setExplicitJumpsAllowed(true);
        view.setLanguageVersionResolver(ignored -> version); view.setLanguageVersion(version); view.setFeatures(features); view.setLibraries(Map.of()); view.setSource(source);
        changes.clear(); focusedRanges.clear(); view.applyCss(); view.layout();
    }
    private static DiagramValidation.Result documentValidation(String source) {
        try { return new DiagramValidation.Result(StructuredDiagram.parseDocument(source, LanguageVersion.STRUCTURED, true), List.of()); }
        catch (StructuredDiagram.ParseException error) { return new DiagramValidation.Result(null, List.of(new DiagramValidation.Diagnostic(error.getMessage(), error.offset(), error.offset()))); }
    }
    private static int verifyBoundaryCount(StructuredDiagram.Sequence sequence) {
        List<Node> targets = view.lookupAll(".flowchart-drop-target").stream().filter(node -> node.getUserData() == sequence).toList();
        require(targets.size() == sequence.nodes().size() + 1, "each source sequence has exactly one target per insertion index");
        for (int index = 0; index <= sequence.nodes().size(); ++index) {
            int wanted = index;
            require(targets.stream().filter(node -> Integer.valueOf(wanted).equals(node.getProperties().get("flowchart-boundary-index"))).count() == 1,
                    "sequence index " + index + " is present exactly once");
        }
        int total = targets.size();
        for (StructuredDiagram.Node node : sequence.nodes()) {
            if (node.body() != null) total += verifyBoundaryCount(node.body());
            if (node.alternative() != null) total += verifyBoundaryCount(node.alternative());
        }
        return total;
    }
    private static boolean loopEntry(Flowchart.Vertex vertex) {
        return vertex.kind() == Flowchart.Kind.JOIN && vertex.owner() != null && vertex.owner().kind() == StructuredDiagram.Kind.DO_WHILE;
    }
    private static TextInputControl field(String text) { return view.lookupAll(".flowchart-edit").stream().map(node -> (TextInputControl) node).filter(input -> input.getText().equals(text)).findFirst().orElseThrow(() -> new AssertionError("Missing field " + text + ": " + view.getStatusText())); }
    private static IndexRange lastFocused() { require(!focusedRanges.isEmpty(), "diagram focus emits a source range"); return focusedRanges.get(focusedRanges.size() - 1); }
    private static Flowchart.Vertex highlightedVertex() { require(view.lookupAll(".flowchart-source-highlight").size() == 1, "exactly one linked element"); return (Flowchart.Vertex) view.lookup(".flowchart-source-highlight").getUserData(); }
    private static TextInputControl rawField() { return Window.getWindows().stream().map(Window::getScene).filter(java.util.Objects::nonNull).map(scene -> scene.lookup(".flowchart-jump-raw")).filter(TextInputControl.class::isInstance).map(TextInputControl.class::cast).findFirst().orElseThrow(); }
    private static void editJump(String instruction) {
        ((Button) view.lookup(".flowchart-edit-instruction")).fire();
        TextInputControl raw = rawField(); raw.setText(instruction); enter(raw, 2);
    }
    private static void enter(TextInputControl field, int offset) { field.positionCaret(offset); field.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false)); }
    private static void check(String name, Check check) throws Exception { try { onFx(check); passed++; System.out.println("PASS " + name); } catch (Exception | AssertionError failure) { failed++; System.err.println("FAIL " + name + ": " + failure); failure.printStackTrace(System.err); } }
    private static void onFx(Check check) throws Exception { FutureTask<Void> task = new FutureTask<>(() -> { check.run(); return null; }); Platform.runLater(task); task.get(30, TimeUnit.SECONDS); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}

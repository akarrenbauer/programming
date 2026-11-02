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
import javafx.geometry.Bounds;
import javafx.scene.AccessibleAction;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.IndexRange;
import javafx.scene.control.MenuButton;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.TitledPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import machine.DiagramValidation;
import machine.LanguageVersion;
import machine.GameFeature;
import machine.GameTaskLanguage;
import machine.StructuredDiagram;
import machine.StructuredDiagramView;

/** JavaFX editing checks; DiagramToolboxTest additionally exercises native pointer drag-and-drop. */
public final class StructuredDiagramViewTest {
    private static final String PROGRAM = "// keep the teaching note\nvar n = 3;\nvar result = 0;\nbegin:\n"
            + "while (n != 0) {\n  if (n == 0) {\n    INC result;\n  } else {\n    DEC n;\n  }\n}\n"
            + "do {\n  NOP;\n} while (result != 0);\nHALT;\n";
    private static StructuredDiagramView view;
    private static Stage stage;
    private static final List<String> changed = new ArrayList<>();
    private static final List<IndexRange> focused = new ArrayList<>();
    private static int passed;
    private static int failed;
    @FunctionalInterface private interface Check { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        Platform.startup(() -> { Platform.setImplicitExit(false); started.countDown(); });
        require(started.await(10, TimeUnit.SECONDS), "JavaFX did not start");
        try {
            onFx(() -> {
                view = new StructuredDiagramView();
                view.setOnSourceChanged(source -> { changed.add(source); view.setSource(source); });
                view.setOnSourceFocused(focused::add);
                stage = new Stage();
                stage.setScene(new Scene(new StackPane(view), 750, 950));
                stage.show();
            });
            check("structured shapes use trash graphics and a symbolic toolbox without action menus", () -> {
                reset(PROGRAM);
                require(view.isSynchronized(), view.getStatusText());
                require(view.lookupAll(".diagram-while").size() == 1 && view.lookupAll(".diagram-do-while").size() == 1,
                        "both loop enclosures are present");
                require(view.lookupAll(".diagram-decision").size() == 1 && view.lookupAll(".diagram-branches").size() == 1,
                        "decision has a split header and side-by-side bodies");
                require(view.lookupAll(".diagram-tool").size() == 3, "three symbolic tools available");
                require(view.lookupAll(".diagram-apply").isEmpty() && view.lookupAll(".diagram-add").isEmpty()
                        && view.lookupAll(".diagram-actions").isEmpty(), "no apply/add/ellipsis controls remain");
                require(view.lookupAll(".menu-button").stream().noneMatch(MenuButton.class::isInstance), "diagram has no action menus");
                for (Node node : view.lookupAll(".diagram-delete")) {
                    Button trash = (Button) node;
                    require(trash.getText().isEmpty() && trash.getGraphic() != null && trash.getTooltip() != null,
                            "trash is a graphic with an explanatory tooltip");
                    require(trash.getAccessibleText().startsWith("Delete"), "trash has an accessible action name");
                }
                require(view.getSource().equals(PROGRAM) && changed.isEmpty(), "rendering preserves source exactly");
            });
            check("the IF condition is geometrically centered independently of its trash icon", () -> {
                reset(PROGRAM);
                Node header = view.lookup(".diagram-decision");
                TextField condition = (TextField) field("n == 0");
                Bounds outer = header.localToScene(header.getBoundsInLocal());
                Bounds inner = condition.localToScene(condition.getLayoutBounds());
                require(Math.abs((outer.getMinX() + outer.getMaxX()) / 2 - (inner.getMinX() + inner.getMaxX()) / 2) < 0.1,
                        "condition field midpoint matches complete header midpoint");
                require(condition.getAlignment() == javafx.geometry.Pos.CENTER, "condition text is centered within its field");
            });
            check("Enter inside a field applies once and preserves comments", () -> {
                reset(PROGRAM);
                TextInputControl statement = field("INC result;");
                statement.setText("DEC result;");
                require(view.getSource().equals(PROGRAM), "pending edit has not changed source");
                enter(statement, 3);
                require(view.getSource().equals(PROGRAM.replace("INC result;", "DEC result;")), "only the selected statement changes");
                require(changed.size() == 1, "source callback emits once despite source echo");
                Node control = field("DEC result;");
                view.setSource(view.getSource());
                require(field("DEC result;") == control, "equal-source echo preserves controls");
            });
            check("Enter at boundaries inserts before or after and commits all pending fields atomically", () -> {
                reset("var n=1;\nvar result=0;\nbegin:\nINC n;\nDEC n;\nHALT;\n");
                TextInputControl first = field("INC n;");
                TextInputControl second = field("DEC n;");
                first.setText("INC result;");
                second.setText("DEC result;");
                enter(second, second.getLength());
                List<String> text = StructuredDiagram.parse(view.getSource()).main().nodes().stream().map(StructuredDiagram.Node::text).toList();
                require(text.equals(List.of("INC result;", "DEC result;", ";", "HALT;")), "end inserts after remapping both pending edits");
                require(changed.size() == 1, "all edits and insertion form one callback/undo transaction");
            });
            check("a newly inserted statement receives focus and its placeholder is selected", () -> {
                TextInputControl placeholder = field(";");
                require(stage.getScene().getFocusOwner() == placeholder, "new box owns keyboard focus");
                require(placeholder.getSelectedText().equals(";"), "placeholder can be overwritten directly");
                TextInputControl halt = field("HALT;");
                enter(halt, 0);
                List<String> text = StructuredDiagram.parse(view.getSource()).main().nodes().stream().map(StructuredDiagram.Node::text).toList();
                require(text.equals(List.of("INC result;", "DEC result;", ";", ";", "HALT;")), "beginning inserts before the current box");
            });
            check("invalid pending edits cannot partially apply during insertion", () -> {
                reset(PROGRAM);
                TextInputControl edit = field("INC result;");
                edit.setText("INC missing;");
                enter(edit, edit.getLength());
                require(view.getSource().equals(PROGRAM) && changed.isEmpty(), "neither invalid edit nor new placeholder is applied");
                require(field("INC missing;") == edit && edit.getStyleClass().contains("diagram-field-error"), "pending text remains visible with an error marker");
                require(view.getStatusText().startsWith("Edit not applied:"), "failure is explained");
                edit.setText("DEC result;");
                enter(edit, 2);
                require(changed.size() == 1 && view.getSource().contains("DEC result;"), "corrected pending text can be applied");
            });
            check("trash deletes the chosen node and retains its neighbors", () -> {
                reset(PROGRAM);
                StructuredDiagram before = StructuredDiagram.parse(view.getSource());
                StructuredDiagram.Node loop = before.main().nodes().get(0);
                Button trash = (Button) view.lookup("#diagram-delete-" + loop.start());
                trash.fire();
                List<StructuredDiagram.Node> remaining = StructuredDiagram.parse(view.getSource()).main().nodes();
                require(remaining.size() == 2 && remaining.get(0).kind() == StructuredDiagram.Kind.DO_WHILE
                        && remaining.get(1).text().equals("HALT;"), "whole selected subtree is removed without changing neighbors");
                require(view.getSource().startsWith("// keep the teaching note"), "unrelated comment survives");
            });
            check("selected toolbox structures insert at sequence gaps with earned zero tests", () -> {
                reset("var n=0;\nbegin:\nHALT;\n");
                selectTool("while");
                mainGap(0).executeAccessibleAction(AccessibleAction.FIRE);
                StructuredDiagram current = StructuredDiagram.parse(view.getSource());
                require(current.main().nodes().get(0).kind() == StructuredDiagram.Kind.WHILE, "tool inserts before HALT");
                require(current.main().nodes().get(0).editText().equals("n != 0"), "template uses a declared variable");
                require(current.main().nodes().get(0).body().nodes().get(0).text().equals(";"), "new body starts with an editable empty statement");
                require(current.main().nodes().get(1).text().equals("HALT;"), "following statement preserved");
                view.setFeatures(Set.of(GameFeature.BLOCKS, GameFeature.EMPTY_STATEMENTS, GameFeature.WHILE));
                require(!view.lookup("#diagram-tool-if").isVisible() && !view.lookup("#diagram-tool-do-while").isVisible(), "locked symbols are unavailable");
                require(view.lookup("#diagram-tool-while").isVisible(), "earned symbol remains available");
            });
            check("empty and omitted else branches are crossed and accept a structure drop", () -> {
                reset("var n=0;\nbegin:\nif (n == 0) {\n  NOP;\n}\nHALT;\n");
                require(view.lookupAll(".diagram-empty-else").size() == 1, "omitted false branch is crossed");
                StructuredDiagram.Node decision = StructuredDiagram.parse(view.getSource()).main().nodes().get(0);
                Node target = view.lookup("#diagram-else-drop-" + decision.start());
                selectTool("do-while");
                target.executeAccessibleAction(AccessibleAction.FIRE);
                StructuredDiagram current = StructuredDiagram.parse(view.getSource());
                require(current.main().nodes().get(0).alternative() != null, "dropping materializes else");
                require(current.main().nodes().get(0).alternative().nodes().get(0).kind() == StructuredDiagram.Kind.DO_WHILE,
                        "selected control structure is inside the new branch");
                reset("var n=0;\nbegin:\nif (n == 0) {} else {}\nHALT;\n");
                require(view.lookupAll(".diagram-empty-else").size() == 1, "explicit empty false branch is crossed too");
                view.lookup(".diagram-empty-else").executeAccessibleAction(AccessibleAction.FIRE);
                require(StructuredDiagram.parse(view.getSource()).main().nodes().get(0).alternative().nodes().get(0).text().equals(";"),
                        "Enter/accessible action on empty branch creates a statement without a toolbox selection");
            });
            check("malformed source keeps the last diagram and marks damage without allowing stale writes", () -> {
                reset(PROGRAM);
                Node loop = view.lookup(".diagram-while");
                Node halt = field("HALT;").getParent();
                TextInputControl stale = field("INC result;");
                stale.setText("DEC result;");
                String invalid = PROGRAM.replace("while (n != 0)", "while (n !=");
                view.setSource(invalid);
                require(!view.isSynchronized() && view.getSource().equals(invalid), "invalid source is retained exactly");
                require(view.lookup(".diagram-while") == loop, "last diagram node identity is retained");
                require(loop.getStyleClass().contains("diagram-damaged"), "damaged loop is red");
                require(!halt.getStyleClass().contains("diagram-damaged"), "unrelated following statement is not marked");
                require(!stale.isEditable(), "last diagram is read-only");
                require(stale.getText().equals("INC result;"), "cached diagram shows last-valid text instead of an uncommitted edit");
                stale.setText("DEC result;");
                enter(stale, 2);
                require(view.getSource().equals(invalid) && changed.isEmpty(), "stale diagram cannot overwrite invalid source");
                view.setSource(PROGRAM);
                require(view.isSynchronized() && field("INC result;").isEditable(), "fixing source restores editing");
            });
            check("semantic compiler failures mark the last valid diagram and recover", () -> {
                reset(PROGRAM);
                Node existing = view.lookup(".diagram-if");
                view.setSource(PROGRAM.replace("INC result;", "INC missing;"));
                require(!view.isSynchronized() && view.lookup(".diagram-if") == existing, "semantic errors retain old structure");
                require(!view.lookupAll(".diagram-damaged").isEmpty() && view.getStatusText().contains("missing"), "unknown-variable error marks structure and explains cause");
                view.setSource(PROGRAM);
                require(view.isSynchronized() && view.lookupAll(".diagram-damaged").isEmpty(), "corrected source removes damage markers");
                view.resetContext();
                view.setSource("var n=0; begin: if(");
                require(view.lookupAll(".diagram-node").isEmpty(), "changing levels cannot reuse another level's cached tree");
            });
            check("multiline text keeps comments and Enter applies inside the field", () -> {
                String multiline = "void helper(value) {\n  INC\n    value;\n}\n"
                        + "var n=0;\nbegin:\nmark: while (n // retain this comment\n  != 0) {\n  DEC n;\n}\nHALT;\n";
                reset(multiline);
                require(view.isSynchronized(), view.getStatusText());
                require(view.lookupAll(".diagram-edit-multiline").size() == 2, "multiline spans use multiline controls");
                TextInputControl condition = view.lookupAll(".diagram-edit-multiline").stream().map(node -> (TextInputControl) node)
                        .filter(input -> input.getText().contains("retain this comment")).findFirst().orElseThrow();
                condition.setText(condition.getText().replace("!=", "=="));
                enter(condition, 2);
                require(view.getSource().equals(multiline.replace("!=", "==")), "condition edit preserves newline comment and label");
            });
            check("language changes translate pending and error feedback without discarding fields", () -> {
                reset(PROGRAM);
                TextInputControl edit = field("INC result;");
                edit.setText("DEC result;");
                view.setTaskLanguage(GameTaskLanguage.GERMAN);
                require(view.getStatusText().contains("Änderung") && !view.getStatusText().contains("pending"), "pending status switches to German");
                require(field("DEC result;") == edit && view.getSource().equals(PROGRAM), "language switch preserves uncommitted text");
                enter(edit, 2);
                require(view.getStatusText().equals("Code und Diagramm sind synchronisiert."), "successful edit uses current task language");
                view.setSource(view.getSource().replace("DEC result;", "INC missing;"));
                require(view.getStatusText().contains("schreibgeschützt") && !view.getStatusText().contains("Unknown variable"),
                        "error detail and read-only message are localized");
                view.setTaskLanguage(GameTaskLanguage.ENGLISH);
                require(view.getStatusText().contains("read-only"), "existing error can switch back without revalidation");
            });
            check("disabled editors and oversized drafts cannot create unsafe diagram mutations", () -> {
                reset(PROGRAM);
                TextInputControl edit = field("INC result;");
                view.setDisable(true);
                edit.setText("NOP;");
                enter(edit, 1);
                require(changed.isEmpty() && view.getSource().equals(PROGRAM), "busy diagram does not mutate source");
                view.setDisable(false);
                reset("begin:\n" + "NOP;\n".repeat(1001));
                require(!view.isSynchronized() && view.lookupAll(".diagram-edit").isEmpty(), "oversized source does not instantiate excessive controls");
                require(view.getStatusText().toLowerCase().contains("too many blocks"), "resource limit is explained");
            });
            check("source and diagram focus share exact ranges without stealing pending edits", () -> {
                reset(PROGRAM);
                TextInputControl pending = field("INC result;"); pending.requestFocus();
                int statement = PROGRAM.indexOf("INC result;");
                require(focused.get(focused.size() - 1).equals(new IndexRange(statement, statement + "INC result;".length())), "field focus emits the exact source instruction");
                pending.setText("DEC result;"); pending.positionCaret(3); focused.clear();
                int conditional = PROGRAM.indexOf("n == 0"); view.highlightSourceOffset(conditional + 1);
                require(view.lookupAll(".diagram-source-highlight").size() == 1 && ((StructuredDiagram.Node) view.lookup(".diagram-source-highlight").getUserData()).kind() == StructuredDiagram.Kind.IF,
                        "source condition selects its owning IF box");
                require(view.getHighlightedRange().equals(new IndexRange(conditional, conditional + "n == 0".length())) && focused.isEmpty(), "programmatic mapping reports the exact condition without reverse callbacks");
                require(stage.getScene().getFocusOwner() == pending && pending.getText().equals("DEC result;") && pending.getCaretPosition() == 3,
                        "source navigation preserves diagram focus, caret and uncommitted text");
                view.setSource(PROGRAM); view.setTaskLanguage(GameTaskLanguage.GERMAN); view.revalidateSource();
                require(field("DEC result;") == pending && view.getHighlightedRange() != null, "same-source language and validation refresh retain pending controls and links");
                view.highlightSourceOffset(PROGRAM.indexOf("    INC"));
                require(((StructuredDiagram.Node) view.lookup(".diagram-source-highlight").getUserData()).editText().equals("INC result;"), "indented nested source selects its deepest statement");
                view.setSource(PROGRAM.replace("INC result;", "INC missing;")); focused.clear(); pending.requestFocus();
                require(!view.isSynchronized() && view.getHighlightedRange() == null && view.lookupAll(".diagram-source-highlight").isEmpty() && focused.isEmpty(),
                        "invalid cached diagrams discard stale source links and ignore old field focus");
            });
            check("configurable validation covers candidate edits and preserves drafts across import refreshes", () -> {
                reset(PROGRAM);
                AtomicBoolean importsValid = new AtomicBoolean(true);
                List<String> validated = new ArrayList<>();
                view.setValidator(source -> {
                    validated.add(source);
                    DiagramValidation.Result parsed = documentValidation(source, false);
                    return importsValid.get() ? parsed : new DiagramValidation.Result(parsed.diagram(), List.of(new DiagramValidation.Diagnostic("Changed helper is invalid", 0, 0)));
                });
                TextInputControl draft = field("INC result;"); draft.setText("DEC result;");
                view.revalidateSource(); require(field("DEC result;") == draft, "valid workspace recheck does not discard a draft");
                enter(draft, 3);
                require(validated.contains(PROGRAM.replace("INC result;", "DEC result;")) && changed.size() == 1, "diagram candidate uses the configured workspace validator before emitting one edit");
                Node box = view.lookup(".diagram-if");
                importsValid.set(false); view.revalidateSource();
                require(!view.isSynchronized() && view.lookup(".diagram-if") == box && !field("DEC result;").isEditable(), "invalid changed imports retain a read-only diagram cache");
                importsValid.set(true); view.revalidateSource(); require(view.isSynchronized(), "corrected imports restore editable source-backed boxes");
            });
            check("helper-only documents edit definitions without adding a synthetic main program", () -> {
                view.resetContext(); view.setValidator(source -> documentValidation(source, false));
                view.setFeatures(EnumSet.allOf(GameFeature.class));
                String helper = "// helper source\nvoid bump(value) {\n  INC value;\n}\n";
                view.setSource(helper); changed.clear(); view.applyCss(); view.layout();
                require(view.isSynchronized() && view.lookup("#diagram-main") == null && ((TitledPane) view.lookup("#diagram-preamble")).isExpanded(), "helper definitions are expanded without a begin/main section");
                TextInputControl instruction = field("INC value;"); instruction.setText("DEC value;"); enter(instruction, 3);
                require(view.getSource().equals(helper.replace("INC value;", "DEC value;")) && changed.size() == 1, "helper edit preserves exact file text and never inserts begin");
                view.applyCss(); view.layout();
                view.highlightSourceOffset(view.getSource().indexOf("DEC value;"));
                require(((StructuredDiagram.Node) view.lookup(".diagram-source-highlight").getUserData()).editText().equals("DEC value;"), "helper body source offsets map to the corresponding nested box");
            });
            String screenshot = System.getProperty("diagram.screenshot");
            if (screenshot != null) onFx(() -> {
                reset(PROGRAM);
                javafx.scene.image.WritableImage snapshot = stage.getScene().snapshot(null);
                java.awt.image.BufferedImage pixels = new java.awt.image.BufferedImage((int) snapshot.getWidth(), (int) snapshot.getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
                for (int y = 0; y < pixels.getHeight(); ++y) for (int x = 0; x < pixels.getWidth(); ++x) pixels.setRGB(x, y, snapshot.getPixelReader().getArgb(x, y));
                javax.imageio.ImageIO.write(pixels, "png", java.nio.file.Path.of(screenshot).toFile());
            });
        } finally {
            onFx(() -> { if (stage != null) stage.close(); });
            Platform.exit();
        }
        System.out.println("StructuredDiagramViewTest: " + passed + " passed, " + failed + " failed");
        if (failed != 0) throw new AssertionError(failed + " diagram view groups failed");
    }

    private static void reset(String source) {
        view.resetContext();
        view.setValidator(null);
        view.setDisable(false);
        view.setTaskLanguage(GameTaskLanguage.ENGLISH);
        view.setFeatures(EnumSet.allOf(GameFeature.class));
        view.setLibraries(Map.of());
        view.setSource(source);
        changed.clear(); focused.clear();
        view.applyCss();
        view.layout();
    }

    private static DiagramValidation.Result documentValidation(String source, boolean jumps) {
        try { return new DiagramValidation.Result(StructuredDiagram.parseDocument(source, LanguageVersion.STRUCTURED, jumps), List.of()); }
        catch (StructuredDiagram.ParseException error) { return new DiagramValidation.Result(null, List.of(new DiagramValidation.Diagnostic(error.getMessage(), error.offset(), error.offset()))); }
    }

    private static TextInputControl field(String text) {
        return view.lookupAll(".diagram-edit").stream().map(node -> (TextInputControl) node)
                .filter(node -> node.getText().equals(text)).findFirst().orElseThrow(() -> new AssertionError("Missing field: " + text + "; " + view.getStatusText()));
    }

    private static void enter(TextInputControl field, int caret) {
        field.positionCaret(caret);
        field.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
    }

    private static void selectTool(String kind) {
        view.lookup("#diagram-tool-" + kind).executeAccessibleAction(AccessibleAction.FIRE);
    }

    private static Node mainGap(int index) throws Exception {
        StructuredDiagram current = StructuredDiagram.parse(view.getSource());
        return view.lookup("#diagram-drop-" + current.main().start() + "-" + index);
    }

    private static void check(String name, Check action) throws Exception {
        try { onFx(action); ++passed; System.out.println("PASS " + name); }
        catch (Exception | AssertionError error) { ++failed; System.err.println("FAIL " + name + ": " + error); error.printStackTrace(System.err); }
    }
    private static void onFx(Check action) throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> { action.run(); return null; });
        Platform.runLater(task);
        task.get(30, TimeUnit.SECONDS);
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}

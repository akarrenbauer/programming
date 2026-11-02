import java.util.Map;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.IndexRange;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.DragEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.StackPane;
import javafx.scene.robot.Robot;
import javafx.scene.shape.Circle;
import javafx.stage.Stage;
import javafx.stage.Window;

import machine.Flowchart;
import machine.FlowchartView;
import machine.GameFeature;
import machine.LanguageVersion;
import machine.StructuredDiagram;

/** Real pointer drags from the first-world palette into main and nested source positions. */
public final class FlowchartDragTest {
    private static FlowchartView view;
    private static Stage stage;
    private static Robot robot;
    private static final AtomicReference<String> changed = new AtomicReference<>();
    private static final AtomicReference<Set<TransferMode>> offered = new AtomicReference<>();
    private static final AtomicReference<TransferMode> completed = new AtomicReference<>();
    private static final AtomicReference<Throwable> fxFailure = new AtomicReference<>();
    private static final AtomicInteger callbacks = new AtomicInteger();
    private static final AtomicInteger gestures = new AtomicInteger();
    private static final AtomicInteger drops = new AtomicInteger();
    private static int passed;
    @FunctionalInterface private interface Check { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        Platform.startup(() -> {
            Thread.currentThread().setUncaughtExceptionHandler((thread, error) -> fxFailure.compareAndSet(null, error));
            Platform.setImplicitExit(false);
            ready.countDown();
        });
        require(ready.await(10, TimeUnit.SECONDS), "JavaFX starts");
        try {
            onFx(() -> {
                view = new FlowchartView();
                view.setOnSourceChanged(source -> {
                    callbacks.incrementAndGet();
                    changed.set(source);
                    view.setSource(source);
                });
                stage = new Stage();
                stage.setScene(new Scene(new StackPane(view), 720, 1000));
                stage.show();
                robot = new Robot();
            });

            check("real JNZ drag works before any structured syntax is earned", () -> {
                String starter = "// Preserve this setup.\nvar n=0;\nbegin:\nHALT;\n";
                onFx(() -> {
                    reset(starter, Set.of(), LanguageVersion.BEFORE_DO_WHILE);
                    require(view.lookup("#flowchart-tool-diamond").isVisible()
                                    && view.lookup("#flowchart-tool-rectangle").isVisible(), "first-world palette has both geometric symbols");
                    require(view.lookupAll(".flowchart-tool").size() == 2, "palette contains only the two requested symbols");
                    for (String kind : new String[] {"jnz", "if", "while", "do-while"}) {
                        require(view.lookup("#flowchart-tool-" + kind) == null, "palette has no separate " + kind + " tool");
                    }
                });
                drag("flowchart-tool-diamond", "flowchart-main-gap");
                onFx(() -> {
                    StructuredDiagram document = StructuredDiagram.parse(changed.get(), LanguageVersion.BEFORE_DO_WHILE, true);
                    require(document.main().nodes().size() == 2, "drop inserts exactly one statement");
                    require(document.main().nodes().get(0).text().equals("again: JNZ n again;"), "drop creates a valid editable jump target");
                    require(document.main().nodes().get(1).text().equals("HALT;"), "existing instruction stays after inserted jump");
                    require(changed.get().startsWith("// Preserve this setup.\nvar n=0;\nbegin:"), "comment and declaration remain unchanged");
                    Flowchart graph = Flowchart.from(document);
                    require(graph.edges().stream().anyMatch(edge -> edge.from() == edge.to() && edge.label().equals("true")),
                            "inserted jump has the expected backward execution edge");
                    require(view.lookupAll(".flowchart-decision").size() == 1, "real drop renders its decision diamond");
                });
            });

            check("zoomed flowchart targets accept real pointer drops and keep source focus offsets", () -> {
                String starter = "// Scale the drawing, not the program.\nvar n=0;\nbegin:\nHALT;\n";
                AtomicReference<IndexRange> focused = new AtomicReference<>();
                AtomicReference<TextInputControl> inserted = new AtomicReference<>();
                onFx(() -> {
                    reset(starter, Set.of(), LanguageVersion.BEFORE_DO_WHILE);
                    view.setZoomFactor(1.5);
                    view.setOnSourceFocused(focused::set);
                    stage.getScene().getRoot().applyCss(); stage.getScene().getRoot().layout();
                    Node target = view.lookup("#flowchart-main-gap");
                    Bounds bounds = target.localToScreen(target.getBoundsInLocal());
                    require(bounds.getHeight() >= 35, "the 24px insertion target scales with the drawing");
                });
                drag("flowchart-tool-rectangle", "flowchart-main-gap");
                onFx(() -> {
                    require(Math.abs(view.getZoomFactor() - 1.5) < .001, "source rebuild retains zoom");
                    require(view.getSource().contains("NOP;") && view.getSource().startsWith("// Scale the drawing, not the program.\nvar n=0;"),
                            "zoomed drag inserts only the chosen instruction and preserves setup");
                    TextInputControl field = view.lookupAll(".flowchart-edit").stream()
                            .filter(TextInputControl.class::isInstance).map(TextInputControl.class::cast)
                            .filter(control -> control.getText().equals("NOP;")).findFirst().orElseThrow();
                    inserted.set(field);
                });
                click(inserted.get());
                onFx(() -> {
                    IndexRange range = focused.get();
                    int start = view.getSource().indexOf("NOP;");
                    require(range != null && range.getStart() == start && range.getEnd() == start + 4,
                            "clicking the scaled instruction reports exact unscaled source offsets");
                    require(inserted.get().isFocused(), "the visible scaled field receives pointer focus");
                    view.setOnSourceFocused(rangeIgnored -> { });
                });
            });

            check("zoomed jump handles move labels through transformed destination hit areas", () -> {
                String source = "var n=1;\nbegin:\nhome: NOP;\nJNZ n home;\nHALT;\n";
                AtomicReference<Node> handle = new AtomicReference<>(), destination = new AtomicReference<>();
                AtomicInteger target = new AtomicInteger();
                onFx(() -> {
                    reset(source, Set.of(), LanguageVersion.BEFORE_DO_WHILE);
                    view.setZoomFactor(1.5);
                    Flowchart graph = graph();
                    target.set(graph.vertices().stream().filter(vertex -> vertex.owner() != null
                            && vertex.owner().editText().equals("HALT;")).findFirst().orElseThrow().id());
                    handle.set(head(jump(graph))); destination.set(headTarget(target.get()));
                });
                drag(handle.get(), destination.get(), TransferMode.MOVE, true, true, false);
                onFx(() -> {
                    Flowchart graph = graph();
                    require(jump(graph).to() == target.get() && graph.labels().get("home") == target.get(),
                            "scaled head and destination relocate the original target label");
                    require(view.getSource().contains("JNZ n home;") && graph.vertex(target.get()).owner().editText().equals("HALT;"),
                            "scaled drag changes label ownership without rewriting the jump or target instruction");
                    require(Math.abs(view.getZoomFactor() - 1.5) < .001, "label relocation keeps zoom");
                });
            });

            check("the same two shapes insert available statements and decisions into nested bodies", () -> {
                onFx(() -> reset("var n=0;\nbegin:\nHALT;\n", Set.of(), LanguageVersion.BEFORE_DO_WHILE));
                drag("flowchart-tool-rectangle", "flowchart-main-gap");
                onFx(() -> require(StructuredDiagram.parse(view.getSource(), LanguageVersion.BEFORE_DO_WHILE, true)
                        .main().nodes().get(0).text().equals("NOP;"), "rectangle uses NOP before empty statements are earned"));
                String starter = "// Preserve the enclosing loop.\nvar n=0;\nbegin:\n"
                        + "do {\n  NOP;\n} while (n != 0);\nHALT;\n";
                AtomicReference<String> target = new AtomicReference<>();
                onFx(() -> {
                    reset(starter, Set.of(GameFeature.BLOCKS, GameFeature.EMPTY_STATEMENTS,
                            GameFeature.DO_WHILE, GameFeature.IF), LanguageVersion.STRUCTURED);
                    StructuredDiagram document = StructuredDiagram.parse(starter, LanguageVersion.STRUCTURED, true);
                    target.set("flowchart-body-" + document.main().nodes().get(0).start());
                    require(view.lookupAll(".flowchart-tool").size() == 2, "unlocking controls does not add syntax-specific tools");
                });
                drag("flowchart-tool-rectangle", target.get());
                onFx(() -> {
                    StructuredDiagram document = StructuredDiagram.parse(changed.get(), LanguageVersion.STRUCTURED, true);
                    StructuredDiagram.Node loop = document.main().nodes().get(0);
                    require(loop.kind() == StructuredDiagram.Kind.DO_WHILE && loop.editText().equals("n != 0"), "enclosing loop and condition are retained");
                    require(loop.body().nodes().size() == 2 && loop.body().nodes().get(0).text().equals("NOP;"), "original loop instruction remains first");
                    require(loop.body().nodes().get(1).text().equals(";"), "rectangle inserts the earned empty statement into the selected body");
                    require(document.main().nodes().get(1).text().equals("HALT;"), "outside neighbor stays outside the loop");
                });
                drag("flowchart-tool-diamond", target.get());
                onFx(() -> {
                    StructuredDiagram document = StructuredDiagram.parse(view.getSource(), LanguageVersion.STRUCTURED, true);
                    StructuredDiagram.Node loop = document.main().nodes().get(0);
                    require(loop.body().nodes().size() == 3 && loop.body().nodes().get(2).editText().startsWith("JNZ n "),
                            "diamond inserts a jump-backed decision in the same nested body");
                    require(view.lookupAll(".flowchart-decision").size() == 2, "nested decision and loop condition both render diamonds");
                });
            });

            check("one merged boundary accepts a native insertion between consecutive statements", () -> {
                String original = "// Keep the surrounding explanation.\nvar n=0;\nbegin:\nINC n;\n// next instruction\nDEC n;\nHALT;\n";
                AtomicReference<Node> source = new AtomicReference<>(), target = new AtomicReference<>();
                onFx(() -> {
                    reset(original, Set.of(), LanguageVersion.BEFORE_DO_WHILE);
                    stage.getScene().getRoot().applyCss(); stage.getScene().getRoot().layout();
                    Flowchart graph = graph();
                    Node before = view.lookup("#flowchart-node-" + graph.vertices().stream()
                            .filter(vertex -> vertex.owner() != null && vertex.owner().text().equals("INC n;")).findFirst().orElseThrow().id());
                    Node after = view.lookup("#flowchart-node-" + graph.vertices().stream()
                            .filter(vertex -> vertex.owner() != null && vertex.owner().text().equals("DEC n;")).findFirst().orElseThrow().id());
                    java.util.List<Node> gaps = view.lookupAll(".flowchart-drop-target").stream().filter(gap -> {
                        Bounds bounds = gap.getBoundsInParent();
                        double center = (bounds.getMinY() + bounds.getMaxY()) / 2;
                        return center > before.getBoundsInParent().getMaxY() && center < after.getBoundsInParent().getMinY();
                    }).toList();
                    require(gaps.size() == 1, "adjacent ordinary statements have one merged drop target");
                    source.set(view.lookup("#flowchart-tool-rectangle")); target.set(gaps.get(0));
                });
                drag(source.get(), target.get(), TransferMode.COPY, true, false, true);
                onFx(() -> {
                    StructuredDiagram document = StructuredDiagram.parse(view.getSource(), LanguageVersion.BEFORE_DO_WHILE, true);
                    require(document.main().nodes().stream().map(StructuredDiagram.Node::text).toList()
                                    .equals(java.util.List.of("INC n;", "NOP;", "DEC n;", "HALT;")),
                            "the merged target inserts exactly between the neighboring statements");
                    require(view.getSource().contains("// Keep the surrounding explanation.") && view.getSource().contains("// next instruction"),
                            "merging drop positions preserves existing comments");
                });
            });

            check("then and else branches keep separate insertion targets after ordinary gaps are merged", () -> {
                String original = "var n=0;\nbegin:\nif (n != 0) {\n  INC n;\n} else {\n  DEC n;\n}\nHALT;\n";
                int owner = original.indexOf("if (");
                onFx(() -> reset(original, EnumSet.allOf(GameFeature.class), LanguageVersion.STRUCTURED));
                drag("flowchart-tool-rectangle", "flowchart-body-" + owner);
                onFx(() -> {
                    StructuredDiagram.Node branch = StructuredDiagram.parse(view.getSource(), LanguageVersion.STRUCTURED, true).main().nodes().get(0);
                    require(branch.body().nodes().stream().map(StructuredDiagram.Node::text).toList().equals(java.util.List.of("INC n;", ";")),
                            "then target appends only to the then body");
                    require(branch.alternative().nodes().size() == 1, "then insertion leaves else unchanged");
                });
                drag("flowchart-tool-rectangle", "flowchart-else-" + owner);
                onFx(() -> {
                    StructuredDiagram document = StructuredDiagram.parse(view.getSource(), LanguageVersion.STRUCTURED, true);
                    StructuredDiagram.Node branch = document.main().nodes().get(0);
                    require(branch.alternative().nodes().stream().map(StructuredDiagram.Node::text).toList().equals(java.util.List.of("DEC n;", ";")),
                            "else target appends only to the else body");
                    require(branch.body().nodes().size() == 2 && document.main().nodes().size() == 2
                                    && document.main().nodes().get(1).text().equals("HALT;"),
                            "branch insertions keep the other branch and parent sequence intact");
                });
            });

            check("expanded insertion zones and arrowhead handles accept pointer gestures near their edges", () -> {
                AtomicReference<Node> source = new AtomicReference<>(), target = new AtomicReference<>();
                onFx(() -> {
                    reset("var n=0;\nbegin:\nHALT;\n", Set.of(), LanguageVersion.BEFORE_DO_WHILE);
                    source.set(view.lookup("#flowchart-tool-rectangle")); target.set(view.lookup("#flowchart-main-gap"));
                });
                drag(source.get(), target.get(), TransferMode.COPY, true, false, true);
                onFx(() -> require(StructuredDiagram.parse(view.getSource(), LanguageVersion.BEFORE_DO_WHILE, true)
                        .main().nodes().get(0).text().equals("NOP;"), "drop near the insertion zone's lower edge creates a statement"));
                String original = "var n=1;\nbegin:\ntop: NOP;\nJNZ n top;\nfinish: HALT;\n";
                onFx(() -> {
                    reset(original, Set.of(), LanguageVersion.BEFORE_DO_WHILE);
                    Flowchart graph = graph(); source.set(head(jump(graph))); target.set(headTarget(graph.labels().get("finish")));
                });
                drag(source.get(), target.get(), TransferMode.MOVE, true, true, false);
                onFx(() -> {
                    Flowchart graph = graph();
                    require(view.getSource().contains("JNZ n top;")
                                    && graph.labels().get("top").equals(graph.labels().get("finish")),
                            "grabbing outside the small triangle moves the target label without rewriting the jump");
                    require(graph.vertex(graph.labels().get("begin")).owner().labels().isBlank(),
                            "the target label leaves its old instruction");
                });
            });

            check("comparison symbols are static and pointer clicks preserve code and pending edits", () -> {
                String source = "var n=1;\nvar m=0;\nbegin:\ntop: NOP;\nJNZ n top;\nJNZ n finish;\nfinish: HALT;\n";
                int first = source.indexOf("JNZ"), second = source.indexOf("JNZ", first + 1);
                AtomicReference<Label> operator = new AtomicReference<>();
                AtomicReference<TextInputControl> pending = new AtomicReference<>();
                AtomicReference<TextInputControl> pendingLabel = new AtomicReference<>();
                AtomicReference<Map<String, String>> otherLabels = new AtomicReference<>();
                AtomicInteger firstVertex = new AtomicInteger(), secondVertex = new AtomicInteger();
                onFx(() -> {
                    reset(source, Set.of(), LanguageVersion.BEFORE_DO_WHILE);
                    Flowchart graph = Flowchart.from(StructuredDiagram.parse(source, LanguageVersion.BEFORE_DO_WHILE, true));
                    firstVertex.set(graph.vertices().stream().filter(vertex -> vertex.owner() != null && vertex.owner().start() == first).findFirst().orElseThrow().id());
                    secondVertex.set(graph.vertices().stream().filter(vertex -> vertex.owner() != null && vertex.owner().start() == second).findFirst().orElseThrow().id());
                    operator.set((Label) view.lookup("#flowchart-node-" + firstVertex.get()).lookup(".flowchart-comparison-operator"));
                    require(operator.get() != null && operator.get().getText().equals("≠"), "backward jump displays a static nonzero comparison");
                    require(labels(firstVertex.get()).equals(Map.of("true", "YES", "false", "NO")), "nonzero comparison initially maps YES to jumping");
                    otherLabels.set(labels(secondVertex.get()));
                    pending.set((TextInputControl) view.lookup("#flowchart-jump-variable-" + first));
                    pending.get().setText("m");
                    pendingLabel.set(labelField("top:"));
                    pendingLabel.get().setText("renamed: ");
                    require(view.lookup(".flowchart-jump-target") == null,
                            "decision has no redundant target-label input beside its pencil editor");
                });
                click(operator.get());
                onFx(() -> {
                    require(operator.get().getText().equals("≠"), "pointer click leaves comparison direction unchanged");
                    require(labels(firstVertex.get()).equals(Map.of("true", "YES", "false", "NO")), "pointer click leaves branch answers unchanged");
                    require(labels(secondVertex.get()).equals(otherLabels.get()), "other diamonds retain their branch annotations");
                    require(view.getSource().equals(source) && callbacks.get() == 0 && changed.get() == null, "clicking a static symbol leaves executable source untouched");
                    require(pending.get().getText().equals("m") && pending.get().getScene() != null, "click preserves pending operand editing");
                    require(pendingLabel.get().getText().equals("renamed: ") && pendingLabel.get().getScene() != null,
                            "click also preserves pending junction-label editing");
                });
                String invalid = source.replace("JNZ n top;", "JNZ missing top;");
                onFx(() -> {
                    view.setSource(invalid);
                    require(!view.isSynchronized(), "invalid source retains the cached comparison read-only");
                });
                click(operator.get());
                onFx(() -> require(labels(firstVertex.get()).equals(Map.of("true", "YES", "false", "NO"))
                                && view.getSource().equals(invalid) && callbacks.get() == 0,
                        "a pointer click cannot activate a stale comparison or overwrite invalid source"));
            });

            check("structured comparison symbols also ignore pointer clicks and preserve pending operands", () -> {
                for (String structure : new String[] {"if (n != 0) { NOP; }", "if (n == 0) { NOP; }", "while (n != 0) { NOP; }",
                        "do { NOP; } while (n != 0);"}) {
                    String source = "var n=1;\nvar m=0;\nbegin:\n" + structure + "\nHALT;\n";
                    String expectedOperator = structure.contains("!=") ? "≠" : "=";
                    AtomicReference<Label> operator = new AtomicReference<>();
                    AtomicReference<TextInputControl> variable = new AtomicReference<>(), literal = new AtomicReference<>();
                    AtomicInteger decision = new AtomicInteger();
                    onFx(() -> {
                        reset(source, EnumSet.allOf(GameFeature.class), LanguageVersion.STRUCTURED);
                        Flowchart graph = Flowchart.from(StructuredDiagram.parse(source, LanguageVersion.STRUCTURED, true));
                        Flowchart.Vertex vertex = graph.vertices().stream().filter(item -> item.kind() == Flowchart.Kind.DECISION).findFirst().orElseThrow();
                        decision.set(vertex.id());
                        operator.set((Label) view.lookup("#flowchart-node-" + vertex.id()).lookup(".flowchart-comparison-operator"));
                        require(operator.get() != null && operator.get().getText().equals(expectedOperator), "structured comparison retains its canonical operator");
                        variable.set((TextInputControl) view.lookup(".flowchart-condition-variable"));
                        literal.set((TextInputControl) view.lookup(".flowchart-condition-literal"));
                        variable.get().setText("m"); literal.get().setText("3");
                    });
                    click(operator.get());
                    onFx(() -> {
                        require(operator.get().getText().equals(expectedOperator) && labels(decision.get()).equals(Map.of("true", "YES", "false", "NO")),
                                "structured comparison and branch answers remain unchanged");
                        require(view.getSource().equals(source) && callbacks.get() == 0, "static comparison keeps executable behavior unchanged");
                        require(variable.get().getText().equals("m") && literal.get().getText().equals("3"), "both pending operands remain available to apply");
                    });
                }
            });

            check("a post-test return behaves like a jump and changes source only when its head moves elsewhere", () -> {
                String source = "// Keep the loop body.\nvar n=2;\nbegin:\nagain: do {\n  DEC n;\n} while (n != 0);\nfinish: HALT;\n";
                AtomicReference<Node> loopHead = new AtomicReference<>(), entry = new AtomicReference<>(), destination = new AtomicReference<>();
                onFx(() -> {
                    reset(source, EnumSet.allOf(GameFeature.class), LanguageVersion.STRUCTURED);
                    Flowchart graph = Flowchart.from(StructuredDiagram.parse(source, LanguageVersion.STRUCTURED, true));
                    Flowchart.Edge back = graph.edges().stream().filter(edge -> graph.loopJumpInfo(edge.from()) != null && edge.label().equals("true"))
                            .findFirst().orElseThrow();
                    require(graph.canRetargetJump(back.from()), "direct nonzero post-test loop exposes its translated jump head");
                    loopHead.set(head(back)); entry.set(headTarget(back.to())); destination.set(headTarget(graph.labels().get("finish")));
                });
                drag(loopHead.get(), entry.get(), TransferMode.MOVE, false);
                onFx(() -> require(view.getSource().equals(source) && drops.get() == 1 && completed.get() == TransferMode.MOVE,
                        "dropping the return on its existing entry succeeds without changing structured source"
                                + " (same=" + view.getSource().equals(source) + ", detected=" + gestures.get() + ", dropped=" + drops.get()
                                + ", completed=" + completed.get() + ", status=" + view.getStatusText() + ")"));
                drag(loopHead.get(), destination.get(), TransferMode.MOVE, true);
                onFx(() -> {
                    StructuredDiagram document = StructuredDiagram.parse(view.getSource(), LanguageVersion.STRUCTURED, true);
                    require(document.main().nodes().stream().noneMatch(node -> node.kind() == StructuredDiagram.Kind.DO_WHILE),
                            "moving the loop return replaces the structured loop with its jump form");
                    Flowchart lowered = Flowchart.from(document);
                    Flowchart.JumpInfo jump = lowered.jumpInfo(jump(lowered).from());
                    require(jump != null && jump.variable().equals("n") && jump.targetLabel().equals("again")
                                    && lowered.labels().get("again").equals(lowered.labels().get("finish")),
                            "lowered jump uses the loop's existing label moved to the chosen destination");
                    require(view.getSource().contains("DEC n;") && view.getSource().contains("finish:")
                                    && lowered.vertex(lowered.labels().get("again")).owner().editText().equals("HALT;")
                                    && view.getSource().startsWith("// Keep the loop body."),
                            "loop conversion retains body, destination aliases and comments");
                    requireDirection(lowered, true);
                });
            });

            check("the pencil applies a coherent label rename before head movement preserves other pending edits", () -> {
                String source = "var n=1;\nvar m=0;\nbegin:\ntop: // keep origin comment\n  NOP;\nJNZ n top;\nfinish: HALT;\n";
                AtomicReference<Node> head = new AtomicReference<>(), destination = new AtomicReference<>();
                onFx(() -> {
                    reset(source, Set.of(), LanguageVersion.BEFORE_DO_WHILE);
                    Flowchart graph = graph();
                    Flowchart.Edge jump = jump(graph);
                    labelField("top:").setText("renamed: ");
                    ((Button) view.lookup("#flowchart-jump-source-" + graph.vertex(jump.from()).owner().start())).fire();
                    TextInputControl raw = rawField();
                    require(raw.getText().equals("JNZ n top;"), "pencil exposes the full instruction without a second target field");
                    raw.setText("JNZ n renamed;");
                    raw.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
                    require(view.getSource().contains("JNZ n renamed;") && graph().labels().containsKey("renamed"),
                            "pencil commit applies both the pending declaration rename and matching target atomically");
                    graph = graph(); jump = jump(graph);
                    ((TextInputControl) view.lookup("#flowchart-jump-variable-" + graph.vertex(jump.from()).owner().start())).setText("m");
                    view.lookupAll(".flowchart-edit").stream().filter(TextInputControl.class::isInstance).map(TextInputControl.class::cast)
                            .filter(field -> field.getText().equals("NOP;")).findFirst().orElseThrow().setText("INC m;");
                    head.set(head(jump)); destination.set(headTarget(graph.labels().get("finish")));
                });
                drag(head.get(), destination.get(), TransferMode.MOVE, true);
                onFx(() -> {
                    Flowchart graph = graph();
                    require(view.getSource().contains("JNZ m renamed;")
                                    && graph.labels().get("renamed").equals(graph.labels().get("finish")),
                            "head move applies the pending operand while relocating the unchanged, coherently renamed target");
                    require(view.getSource().contains("// keep origin comment") && view.getSource().contains("INC m;"),
                            "moving only label tokens preserves source comments and unrelated pending statement edits");
                    require(!graph.labels().containsKey("top"), "the prior label name does not survive the coherent rename");
                    requireDirection(graph, true);
                    int original = graph.vertices().stream().filter(vertex -> vertex.owner() != null && vertex.owner().editText().equals("INC m;"))
                            .findFirst().orElseThrow().id();
                    head.set(head(jump(graph))); destination.set(headTarget(original));
                });
                drag(head.get(), destination.get(), TransferMode.MOVE, true);
                onFx(() -> {
                    require(view.getSource().contains("JNZ m renamed;")
                                    && graph().vertex(graph().labels().get("renamed")).owner().editText().equals("INC m;"),
                            "reverse head move returns the same label to the earlier instruction without changing JNZ");
                    requireDirection(graph(), false);
                });
            });

            check("an unmatched pending label rename rejects head movement without losing drafts", () -> {
                String source = "var n=1;\nbegin:\ntop: NOP;\nJNZ n top;\nfinish: HALT;\n";
                AtomicReference<Node> moving = new AtomicReference<>(), destination = new AtomicReference<>();
                AtomicReference<TextInputControl> pending = new AtomicReference<>();
                onFx(() -> {
                    reset(source, Set.of(), LanguageVersion.BEFORE_DO_WHILE);
                    Flowchart graph = graph(); Flowchart.Edge jump = jump(graph);
                    pending.set(labelField("top:"));
                    pending.get().setText("missing: "); moving.set(head(jump)); destination.set(headTarget(graph.labels().get("finish")));
                });
                drag(moving.get(), destination.get(), TransferMode.MOVE, false);
                onFx(() -> require(view.getSource().equals(source) && pending.get().getText().equals("missing: ")
                                && pending.get().getScene() != null && callbacks.get() == 0 && gestures.get() == 1 && drops.get() == 1,
                        "failed relocation retains source and unmatched declaration draft for correction"));
            });

            check("native jump-head dragging relocates begin binding and can restore the implicit entry", () -> {
                String source = "var n=5;\nbegin:\n\tDEC n;\n\tJNZ n begin;\nHALT;\n";
                AtomicReference<Node> movingHead = new AtomicReference<>(), destination = new AtomicReference<>();
                onFx(() -> {
                    reset(source, EnumSet.allOf(GameFeature.class), LanguageVersion.STRUCTURED);
                    Flowchart graph = graph();
                    movingHead.set(head(jump(graph))); destination.set(headTarget(graph.labels().get("begin")));
                });
                drag(movingHead.get(), destination.get(), TransferMode.MOVE, false);
                onFx(() -> {
                    require(view.getSource().equals(source) && drops.get() == 1 && completed.get() == TransferMode.MOVE,
                            "dropping onto the existing implicit begin junction succeeds without rewriting source");
                    Flowchart graph = graph();
                    int halt = graph.vertices().stream().filter(vertex -> vertex.kind() == Flowchart.Kind.HALT).findFirst().orElseThrow().id();
                    movingHead.set(head(jump(graph))); destination.set(headTarget(halt));
                });
                drag(movingHead.get(), destination.get(), TransferMode.MOVE, true);
                onFx(() -> {
                    Flowchart graph = graph();
                    requireDirection(graph, true);
                    require(view.getSource().contains("JNZ n begin;") && graph.labelInfo("begin") != null,
                            "moving begin creates a legal explicit binding while retaining the required main marker and jump text");
                    require(graph.vertex(graph.labels().get("begin")).owner().editText().equals("HALT;")
                                    && graph.edges().stream().anyMatch(edge -> edge.from() == 0 && edge.to() == graph.labels().get("begin")),
                            "the moved begin binding also relocates program startup");
                    int first = graph.vertices().stream().filter(vertex -> vertex.owner() != null && vertex.owner().editText().equals("DEC n;"))
                            .findFirst().orElseThrow().id();
                    movingHead.set(head(jump(graph))); destination.set(headTarget(first));
                });
                drag(movingHead.get(), destination.get(), TransferMode.MOVE, true);
                onFx(() -> {
                    require(graph().jumpInfo(jump(graph()).from()).targetLabel().equals("begin"),
                            "moving the head back uses the built-in entry label");
                    require(view.getSource().contains("begin:\n\tDEC n;\n\tJNZ n begin;"),
                            "returning begin retains its original header and source formatting");
                    require(graph().labelInfo("begin") == null && graph().isImplicitBeginTarget(graph().labels().get("begin")),
                            "returning to the first command removes the explicit rebinding");
                    requireDirection(graph(), false);
                });
            });

            check("moving a jump head to an unlabeled instruction relocates its existing target name", () -> {
                String source = "// Keep this comment.\nvar n=1;\nbegin:\ntop: NOP;\nJNZ n top;\nINC n;\nHALT;\n";
                AtomicReference<Node> head = new AtomicReference<>(), destination = new AtomicReference<>();
                AtomicInteger target = new AtomicInteger();
                onFx(() -> {
                    reset(source, Set.of(), LanguageVersion.BEFORE_DO_WHILE);
                    Flowchart graph = graph();
                    target.set(graph.vertices().stream().filter(vertex -> vertex.owner() != null
                            && vertex.owner().editText().equals("INC n;")).findFirst().orElseThrow().id());
                    head.set(head(jump(graph))); destination.set(headTarget(target.get()));
                });
                drag(head.get(), destination.get(), TransferMode.MOVE, true);
                onFx(() -> {
                    Flowchart graph = graph();
                    require(jump(graph).to() == target.get(), "retargeted jump reaches the chosen instruction");
                    require(!graph.vertex(target.get()).owner().labels().isBlank(), "unlabeled destination receives a source label");
                    require(graph.labels().get("top") == target.get() && graph.jumpInfo(jump(graph).from()).targetLabel().equals("top"),
                            "the existing target label moves without allocating a different name or rewriting JNZ");
                    require(graph.vertex(target.get()).owner().editText().equals("INC n;"), "label relocation preserves the target instruction");
                    require(view.getSource().startsWith("// Keep this comment.\nvar n=1;"), "surrounding source remains intact");
                    requireDirection(graph, true);
                });
            });

            check("moving a selected shared head relocates its label for every reference while preserving other aliases", () -> {
                String source = "var n=1;\nbegin:\nhome: stay: NOP;\nJNZ n home;\nJNZ n home;\nJNZ n stay;\nhome: // keep shadow trivia\n  NOP;\nfinish: HALT;\n";
                AtomicReference<Node> diamond = new AtomicReference<>(), selectedHead = new AtomicReference<>(), destination = new AtomicReference<>();
                onFx(() -> {
                    reset(source, Set.of(), LanguageVersion.BEFORE_DO_WHILE);
                    Flowchart graph = graph();
                    Flowchart.Edge first = graph.edges().stream().filter(edge -> edge.label().equals("true"))
                            .min(java.util.Comparator.comparingInt(Flowchart.Edge::from)).orElseThrow();
                    diamond.set(view.lookup("#flowchart-node-" + first.from()));
                    selectedHead.set(head(first)); destination.set(headTarget(graph.labels().get("finish")));
                });
                click(diamond.get());
                onFx(() -> {
                    require(view.getSource().equals(source) && callbacks.get() == 0,
                            "selecting the earlier jump changes no code or source transaction");
                    require(selectedHead.get().getStyleClass().contains("flowchart-jump-selected"),
                            "selected head is visibly identified before moving it");
                });
                drag(selectedHead.get(), destination.get(), TransferMode.MOVE, true);
                onFx(() -> {
                    Flowchart graph = graph();
                    require(graph.labels().get("home").equals(graph.labels().get("finish"))
                                    && !graph.labels().get("home").equals(graph.labels().get("stay")),
                            "moving the selected head relocates only its named label and preserves the origin's other alias");
                    require(graph.edges().stream().filter(edge -> graph.isJumpEdge(edge)
                                    && graph.jumpInfo(edge.from()).targetLabel().equals("home"))
                                    .allMatch(edge -> edge.to() == graph.labels().get("finish")),
                            "every reference to the moved label follows its new owner");
                    require(view.getSource().lines().filter(line -> line.equals("JNZ n home;")).count() == 2
                                    && view.getSource().contains("JNZ n stay;"),
                            "label movement never rewrites any shared or unrelated jump operands");
                    require(graph.labelDeclarations().stream().filter(label -> label.name().equals("home")).count() == 1
                                    && view.getSource().contains("// keep shadow trivia"),
                            "moving the effective label removes obsolete duplicate bindings while preserving their source comments");
                    Flowchart.Edge first = graph.edges().stream().filter(edge -> edge.label().equals("true"))
                            .min(java.util.Comparator.comparingInt(Flowchart.Edge::from)).orElseThrow();
                    diamond.set(view.lookup("#flowchart-node-" + first.from()));
                    selectedHead.set(head(first)); destination.set(headTarget(graph.labels().get("stay")));
                });
                click(diamond.get());
                drag(selectedHead.get(), destination.get(), TransferMode.MOVE, true);
                onFx(() -> {
                    Flowchart graph = graph();
                    require(graph.labels().get("home").equals(graph.labels().get("stay"))
                                    && graph.vertex(graph.labels().get("home")).owner().editText().equals("NOP;"),
                            "moving back returns the shared label to the earlier junction without displacing its other alias");
                    require(graph.labelDeclarations().stream().filter(label -> label.name().equals("home")).count() == 1,
                            "repeated movement leaves exactly one effective declaration of the moved name");
                    require(view.getSource().lines().filter(line -> line.equals("JNZ n home;")).count() == 2,
                            "shared references retain their original source text after moving back");
                });
            });

            check("invalid or busy views block real jump-head drags", () -> {
                String source = "var n=1;\nbegin:\ntop: NOP;\nJNZ n top;\nfinish: HALT;\n";
                for (boolean invalid : new boolean[] {true, false}) {
                    AtomicReference<Node> head = new AtomicReference<>(), destination = new AtomicReference<>();
                    String expected = invalid ? source.replace("JNZ n top;", "JNZ missing top;") : source;
                    onFx(() -> {
                        reset(source, Set.of(), LanguageVersion.BEFORE_DO_WHILE);
                        Flowchart graph = graph(); head.set(head(jump(graph))); destination.set(headTarget(graph.labels().get("finish")));
                        if (invalid) view.setSource(expected); else view.setDisable(true);
                    });
                    drag(head.get(), destination.get(), TransferMode.MOVE, false);
                    onFx(() -> {
                        require(view.getSource().equals(expected) && callbacks.get() == 0, "blocked head gesture leaves current source untouched");
                        view.setDisable(false);
                    });
                }
            });
            System.out.println("FlowchartDragTest: " + passed + " passed, 0 failed");
        } finally {
            onFx(() -> { if (stage != null) stage.close(); });
            Platform.exit();
        }
    }

    private static void reset(String source, Set<GameFeature> features, LanguageVersion version) {
        view.resetContext();
        view.setZoomFactor(1);
        view.setDisable(false);
        view.setFeatures(features);
        view.setLanguageVersion(version);
        view.setSource(source);
        changed.set(null); offered.set(null); callbacks.set(0); gestures.set(0); drops.set(0);
        require(view.isSynchronized(), view.getStatusText());
    }

    private static void drag(String sourceId, String targetId) throws Exception {
        AtomicReference<Node> source = new AtomicReference<>(), target = new AtomicReference<>();
        onFx(() -> { source.set(view.lookup("#" + sourceId)); target.set(view.lookup("#" + targetId)); });
        drag(source.get(), target.get(), TransferMode.COPY, true);
    }

    private static void drag(Node source, Node target, TransferMode expectedMode, boolean success) throws Exception {
        drag(source, target, expectedMode, success, false, false);
    }

    private static void drag(Node source, Node target, TransferMode expectedMode, boolean success,
            boolean outerPickup, boolean outerDrop) throws Exception {
        AtomicReference<Point2D> start = new AtomicReference<>();
        AtomicReference<Point2D> end = new AtomicReference<>();
        onFx(() -> {
            changed.set(null); offered.set(null); completed.set(null); callbacks.set(0); gestures.set(0); drops.set(0);
            stage.getScene().getRoot().applyCss();
            stage.getScene().getRoot().layout();
            require(source != null && target != null, "drag source and selected destination exist");
            if (source.getProperties().putIfAbsent("test-drag-source", Boolean.TRUE) == null) {
                source.addEventFilter(MouseEvent.DRAG_DETECTED, event -> gestures.incrementAndGet());
                source.addEventFilter(DragEvent.DRAG_DONE, event -> completed.set(event.getTransferMode()));
            }
            if (target.getProperties().putIfAbsent("test-drag-target", Boolean.TRUE) == null) {
                target.addEventFilter(DragEvent.DRAG_OVER, event -> offered.set(Set.copyOf(event.getDragboard().getTransferModes())));
                target.addEventFilter(DragEvent.DRAG_DROPPED, event -> drops.incrementAndGet());
            }
            start.set(center(source)); end.set(center(target));
            if (outerPickup) {
                Bounds handle = source.localToScreen(source.getBoundsInLocal());
                require(source.getStyleClass().contains("flowchart-jump-handle")
                                && handle.getWidth() >= 30 && handle.getHeight() >= 26,
                        "jump pickup has a generous area around the visible arrowhead");
                start.set(new Point2D(handle.getMaxX() - 3, (handle.getMinY() + handle.getMaxY()) / 2));
                Node triangle = view.lookupAll(".flowchart-jump-head").stream()
                        .filter(node -> source.getUserData().equals(node.getUserData())).findFirst().orElseThrow();
                require(!triangle.localToScreen(triangle.getBoundsInLocal()).contains(start.get()),
                        "the regression deliberately grabs outside the original small triangle");
            }
            if (outerDrop) {
                Bounds drop = target.localToScreen(target.getBoundsInLocal());
                require(drop.getHeight() >= 20, "insertion drop zone is at least twenty pixels high");
                end.set(new Point2D((drop.getMinX() + drop.getMaxX()) / 2, drop.getMaxY() - 3));
            }
            if (target.getStyleClass().contains("flowchart-head-target")) {
                Circle junction = (Circle) view.lookup("#flowchart-junction-" + target.getUserData());
                Point2D dot = junction.localToScreen(junction.getCenterX(), junction.getCenterY());
                require(target.localToScreen(target.getBoundsInLocal()).contains(dot), "head drop accepts the visible junction dot itself");
                end.set(dot);
            }
            robot.mouseMove(start.get());
        });
        Thread.sleep(80);
        onFx(() -> robot.mousePress(MouseButton.PRIMARY));
        Thread.sleep(80);
        onFx(() -> robot.mouseMove(start.get().add(60, 0)));
        Thread.sleep(50);
        for (int step = 1; step <= 12; step++) {
            Point2D point = start.get().interpolate(end.get(), step / 12.0);
            onFx(() -> robot.mouseMove(point));
            Thread.sleep(30);
        }
        onFx(() -> robot.mouseRelease(MouseButton.PRIMARY));
        if (!success) {
            Thread.sleep(150);
            onFx(() -> require(callbacks.get() == 0 && changed.get() == null, "blocked drag emits no source transaction"));
            return;
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (changed.get() == null && System.nanoTime() < deadline) Thread.sleep(20);
        onFx(() -> {
            require(gestures.get() == 1 && drops.get() == 1, "native drag gesture reaches the requested drop target"
                    + " (detected=" + gestures.get() + ", dropped=" + drops.get() + ", offered=" + offered.get()
                    + ", callbacks=" + callbacks.get() + ", status=" + view.getStatusText() + ")");
            require(Set.of(expectedMode).equals(offered.get()), "drag offers only its intended transfer mode");
            require(callbacks.get() == 1 && changed.get() != null, "drop emits one source transaction");
            require(view.isSynchronized() && view.getSource().equals(changed.get()), "callback echo keeps source and graph synchronized");
        });
    }

    private static Point2D center(Node node) {
        Bounds bounds = node.localToScreen(node.getBoundsInLocal());
        require(bounds != null && bounds.getWidth() > 0 && bounds.getHeight() > 0, "drag node has visible screen bounds");
        Point2D point = new Point2D((bounds.getMinX() + bounds.getMaxX()) / 2, (bounds.getMinY() + bounds.getMaxY()) / 2);
        Bounds window = stage.getScene().getRoot().localToScreen(stage.getScene().getRoot().getBoundsInLocal());
        require(window.contains(point), "drag target is inside the window");
        return point;
    }

    private static Map<String, String> labels(int vertex) {
        return view.lookupAll(".flowchart-edge-label").stream().map(Label.class::cast)
                .filter(label -> ((Flowchart.Edge) label.getUserData()).from() == vertex)
                .collect(java.util.stream.Collectors.toMap(label -> ((Flowchart.Edge) label.getUserData()).label(), Label::getText));
    }

    private static Flowchart graph() throws Exception {
        return Flowchart.from(StructuredDiagram.parse(view.getSource(), LanguageVersion.BEFORE_DO_WHILE, true));
    }
    private static Flowchart.Edge jump(Flowchart graph) {
        return graph.edges().stream().filter(edge -> edge.label().equals("true")).findFirst().orElseThrow();
    }
    private static TextInputControl labelField(String label) {
        return view.lookupAll(".flowchart-label-edit").stream().filter(TextInputControl.class::isInstance)
                .map(TextInputControl.class::cast).filter(field -> field.getText().strip().equals(label)).findFirst().orElseThrow();
    }
    private static TextInputControl rawField() {
        return Window.getWindows().stream().map(Window::getScene).filter(java.util.Objects::nonNull)
                .map(scene -> scene.lookup(".flowchart-jump-raw")).filter(TextInputControl.class::isInstance)
                .map(TextInputControl.class::cast).findFirst().orElseThrow();
    }
    private static Node head(Flowchart.Edge edge) {
        return view.lookupAll(".flowchart-jump-handle").stream().filter(node -> edge.equals(node.getUserData())).findFirst().orElseThrow();
    }
    private static Node headTarget(int target) { return view.lookup("#flowchart-head-target-" + target); }
    private static void requireDirection(Flowchart graph, boolean forward) {
        Flowchart.Edge jump = jump(graph);
        Label operator = (Label) view.lookup("#flowchart-node-" + jump.from()).lookup(".flowchart-comparison-operator");
        require(operator.getText().equals(forward ? "=" : "≠"), "comparison symbol follows jump direction");
        require(labels(jump.from()).equals(forward ? Map.of("true", "NO", "false", "YES") : Map.of("true", "YES", "false", "NO")),
                "YES and NO preserve behavior for the direction-derived comparison");
    }

    private static void click(Node node) throws Exception {
        onFx(() -> {
            stage.getScene().getRoot().applyCss();
            stage.getScene().getRoot().layout();
            robot.mouseMove(center(node));
        });
        Thread.sleep(60);
        onFx(() -> robot.mousePress(MouseButton.PRIMARY));
        Thread.sleep(60);
        onFx(() -> robot.mouseRelease(MouseButton.PRIMARY));
        Thread.sleep(60);
    }

    private static void check(String name, Check action) throws Exception {
        action.run(); passed++; System.out.println("PASS " + name);
    }
    private static void onFx(Check action) throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> { action.run(); return null; });
        Platform.runLater(task);
        task.get(15, TimeUnit.SECONDS);
        Throwable failure = fxFailure.getAndSet(null);
        if (failure != null) throw new AssertionError("Uncaught JavaFX error", failure);
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}

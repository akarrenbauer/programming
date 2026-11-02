import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;

import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextInputControl;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.LineTo;
import javafx.scene.shape.MoveTo;
import javafx.stage.Stage;

import machine.Flowchart;
import machine.FlowchartView;
import machine.GameFeature;
import machine.LanguageVersion;
import machine.StructuredDiagram;

/** Geometric regression checks for decision answers and labeled jump junctions. */
public final class FlowchartRoutingTest {
    private static final double EPSILON = 1.5;
    private static final String MIXED = "var n=1;\nbegin:\n"
            + "first: if (n != 0) { NOP; } else { NOP; }\n"
            + "while (n != 0) { DEC n; }\n"
            + "do { NOP; } while (n != 0);\nJNZ n first;\nHALT;\n";
    private static FlowchartView view;
    private static Stage stage;
    private static final AtomicReference<Throwable> fxFailure = new AtomicReference<>();
    private static int passed;
    @FunctionalInterface private interface Check { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        Platform.startup(() -> {
            Thread.currentThread().setUncaughtExceptionHandler((thread, error) -> fxFailure.compareAndSet(null, error));
            Platform.setImplicitExit(false); ready.countDown();
        });
        require(ready.await(10, TimeUnit.SECONDS), "JavaFX starts");
        try {
            onFx(() -> {
                view = new FlowchartView();
                stage = new Stage(); stage.setScene(new Scene(new StackPane(view), 760, 1000)); stage.show();
            });
            check("ordinary boundaries have one generous insertion target including begin to first statement", () -> {
                Flowchart graph = render("var n=2;\nbegin:\nINC n;\nDEC n;\nHALT;\n",
                        Set.of(), LanguageVersion.STRUCTURED);
                List<Node> targets = new ArrayList<>(view.lookupAll(".flowchart-drop-target"));
                require(targets.size() == 4, "three statements expose only four sequence boundaries, without duplicated before/after targets");
                for (int index = 0; index + 1 < graph.vertices().size(); ++index) {
                    Region before = vertex(index), after = vertex(index + 1);
                    List<Node> between = targets.stream().filter(target -> {
                        Bounds bounds = target.getBoundsInParent();
                        double center = (bounds.getMinY() + bounds.getMaxY()) / 2;
                        return center > before.getLayoutY() + before.getHeight() && center < after.getLayoutY();
                    }).toList();
                    require(between.size() == 1, "exactly one visible insertion target separates vertices " + index + " and " + (index + 1));
                    require(between.get(0).isVisible() && between.get(0).getBoundsInParent().getHeight() >= 24,
                            "merging preserves the generous visible drop area");
                    if (index == 0) require(between.get(0).getId().equals("flowchart-main-gap"), "begin shares the main-entry gap with the first statement");
                }
                require(targets.stream().filter(target -> target.getBoundsInParent().getMinY()
                        >= vertex(graph.vertices().size() - 1).getLayoutY() + vertex(graph.vertices().size() - 1).getHeight()).count() == 1,
                        "the last statement retains one trailing insertion target");
            });
            for (String[] fixture : new String[][] {
                    {"implicit begin entry", "DEC n;\nJNZ n begin;\nHALT;"},
                    {"first instruction", "first: NOP;\nJNZ n first;\nHALT;"},
                    {"immediately following instruction", "JNZ n next;\nnext: HALT;"},
                    {"forward target", "JNZ n finish;\nNOP;\nfinish: HALT;"},
                    {"backward target", "NOP;\nagain: DEC n;\nJNZ n again;\nHALT;"},
                    {"self target", "again: JNZ n again;\nHALT;"},
                    {"shared target and aliases", "home: alias: NOP;\nJNZ n home;\nJNZ n alias;\nHALT;"}
            }) {
                check("jumps to " + fixture[0] + " exit right and merge at one labeled incoming junction", () -> {
                    String source = "var n=1;\nbegin:\n" + fixture[1] + "\n";
                    Flowchart graph = render(source, Set.of(), LanguageVersion.BEFORE_DO_WHILE);
                    int jumps = 0;
                    for (Flowchart.Edge edge : graph.edges()) {
                        if (!edge.label().equals("true")) continue;
                        verifyJump(graph, edge);
                        jumps++;
                    }
                    require(jumps == (fixture[0].startsWith("shared") ? 2 : 1), "fixture checks all explicit jump edges");
                    verifyAnswers(graph);
                    if (args.length > 0 && fixture[0].startsWith("shared")) screenshot(Path.of(args[0] + "-shared.png"));
                });
            }
            check("all decisions display YES and NO near the outgoing arrow tails", () -> {
                Flowchart graph = render(MIXED, EnumSet.allOf(GameFeature.class), LanguageVersion.STRUCTURED);
                require(graph.vertices().stream().filter(vertex -> vertex.kind() == Flowchart.Kind.DECISION).count() == 4,
                        "fixture contains if, while, do-while and JNZ decisions");
                verifyAnswers(graph);
                for (Flowchart.Edge edge : graph.edges()) {
                    Flowchart.Vertex from = graph.vertex(edge.from());
                    if (from.owner() != null && from.owner().kind() == StructuredDiagram.Kind.STATEMENT && edge.label().equals("true")) {
                        verifyJump(graph, edge);
                    }
                }
                if (args.length > 0) screenshot(Path.of(args[0] + "-mixed.png"));
            });
            check("post-test loops use a trailing diamond and right-side return to the entry junction", () -> {
                for (String[] fixture : new String[][] {{"DEC n;", "!="}, {"NOP;", "=="}, {"", "!="}}) {
                    String source = "var n=2;\nbegin:\nagain: do {\n  " + fixture[0] + "\n} while (n " + fixture[1] + " 0);\nHALT;\n";
                    Flowchart graph = render(source, EnumSet.allOf(GameFeature.class), LanguageVersion.STRUCTURED);
                    require(graph.vertices().stream().noneMatch(vertex -> vertex.text().equals("do")), "loop has no separate do action marker");
                    require(view.lookupAll(".flowchart-process").size() == (fixture[0].isEmpty() ? 0 : 1), "only real body instructions appear as process boxes");
                    Flowchart.Edge back = graph.edges().stream().filter(edge -> graph.loopJumpInfo(edge.from()) != null && edge.label().equals("true"))
                            .findFirst().orElseThrow();
                    verifyJump(graph, back);
                    verifyAnswers(graph);
                    require(view.lookupAll(".flowchart-jump-head").size() == (fixture[1].equals("!=") ? 1 : 0),
                            "only directly translatable nonzero loop heads offer retargeting");
                    if (args.length > 0 && fixture[0].equals("DEC n;")) {
                        screenshot(Path.of(args[0] + "-do-while.png"));
                        screenshot(view, Path.of(args[0] + "-do-while-editor.png"));
                    }
                }
            });
            check("nested explicit and post-test loop routes put enclosing intervals farther right", () -> {
                for (String body : List.of(
                        "outer: NOP; middle: NOP; inner: NOP; JNZ n inner; JNZ n middle; JNZ n outer; HALT;",
                        "outer: do { do { do { DEC n; } while(n!=0); } while(n!=0); } while(n!=0); JNZ n outer; HALT;")) {
                    Flowchart graph = render("var n=2;\nbegin:\n" + body + "\n", EnumSet.allOf(GameFeature.class), LanguageVersion.STRUCTURED);
                    verifyRouteLanes(graph);
                    if (args.length > 0 && body.contains("do {")) screenshot(Path.of(args[0] + "-nested-lanes.png"));
                }
            });
            check("crossing and shared intervals stay separate beyond four lanes and fit the scroll extent", () -> {
                Flowchart crossing = render("var n=2;\nbegin:\nfirst: NOP; second: NOP; JNZ n first; NOP; JNZ n second; HALT;\n",
                        Set.of(), LanguageVersion.STRUCTURED);
                verifyRouteLanes(crossing);
                Flowchart dense = render("var n=2;\nbegin:\nhome: NOP;\n" + "JNZ n home;\n".repeat(12) + "HALT;\n",
                        Set.of(), LanguageVersion.STRUCTURED);
                verifyRouteLanes(dense);
                List<Flowchart.Edge> jumps = dense.edges().stream().filter(dense::isJumpRoute).toList();
                require(jumps.stream().map(FlowchartRoutingTest::laneX).collect(java.util.stream.Collectors.toSet()).size() == 12,
                        "twelve overlapping jump intervals occupy twelve distinct lanes without wrapping");
                Region main = (Region) view.lookup("#flowchart-main");
                double right = jumps.stream().mapToDouble(FlowchartRoutingTest::laneX).max().orElseThrow();
                require(right + 20 <= main.getWidth(), "rightmost route fits inside the graph's expanded extent with margin");
                ScrollPane scroll = (ScrollPane) view.lookup(".scroll-pane");
                require(scroll.getContent().getLayoutBounds().getWidth() > scroll.getViewportBounds().getWidth(),
                        "dense lanes produce real horizontal scrolling instead of clipping");
                if (args.length > 0) screenshot(Path.of(args[0] + "-dense-lanes.png"));
            });
            check("disjoint jump intervals reuse lanes and mixed control branches keep separate right lanes", () -> {
                Flowchart disjoint = render("var n=2;\nbegin:\na: JNZ n a; b: JNZ n b; HALT;\n", Set.of(), LanguageVersion.STRUCTURED);
                List<Flowchart.Edge> jumps = disjoint.edges().stream().filter(disjoint::isJumpRoute).toList();
                close(laneX(jumps.get(0)), laneX(jumps.get(1)), "disjoint intervals can reuse one vertical route lane");
                Flowchart mixed = render(MIXED, EnumSet.allOf(GameFeature.class), LanguageVersion.STRUCTURED);
                verifyRouteLanes(mixed);
                double center = vertex(0).getLayoutX() + vertex(0).getWidth() / 2;
                double ordinaryRight = mixed.edges().stream().filter(edge -> !mixed.isJumpRoute(edge))
                        .filter(edge -> points(path(edge)).size() > 2 && points(path(edge)).get(0).getX() > center)
                        .mapToDouble(FlowchartRoutingTest::laneX).max().orElse(center);
                require(mixed.edges().stream().filter(mixed::isJumpRoute).allMatch(edge -> laneX(edge) > ordinaryRight + 15),
                        "jump routes sit beyond ordinary right-side conditional branches");
            });
            System.out.println("FlowchartRoutingTest: " + passed + " passed, 0 failed");
        } finally {
            onFx(() -> { if (stage != null) stage.close(); });
            Platform.exit();
        }
    }

    private static Flowchart render(String source, Set<GameFeature> features, LanguageVersion version) throws Exception {
        view.resetContext(); view.setFeatures(features); view.setLanguageVersion(version); view.setSource(source);
        require(view.isSynchronized(), view.getStatusText());
        stage.getScene().getRoot().applyCss(); stage.getScene().getRoot().layout();
        return Flowchart.from(StructuredDiagram.parse(source, version, true));
    }

    private static void verifyJump(Flowchart graph, Flowchart.Edge edge) {
        Region source = vertex(edge.from()), target = vertex(edge.to());
        List<Point2D> route = points(path(edge));
        Point2D rightTip = new Point2D(source.getLayoutX() + source.getWidth(), source.getLayoutY() + source.getHeight() / 2);
        close(route.get(0), rightTip, "jump starts at the diamond's right tip");
        Circle junction = (Circle) view.lookup("#flowchart-junction-" + edge.to());
        require(junction != null, "jump target has a junction bullet");
        require(view.lookupAll(".flowchart-junction").stream().filter(node -> Integer.valueOf(edge.to()).equals(node.getUserData())).count() == 1,
                "all jumps to a target share exactly one junction");
        Point2D bullet = junction.localToParent(junction.getCenterX(), junction.getCenterY());
        close(route.get(route.size() - 1), bullet, "jump ends at the incoming junction instead of the process side");
        Flowchart.Vertex destination = graph.vertex(edge.to());
        boolean hiddenLoopEntry = destination.kind() == Flowchart.Kind.JOIN
                && destination.owner() != null && destination.owner().kind() == StructuredDiagram.Kind.DO_WHILE;
        if (hiddenLoopEntry) {
            int previousId = edge.to() - 1, nextId = edge.to() + 1;
            while (vertex(previousId).getHeight() == 0) --previousId;
            while (vertex(nextId).getHeight() == 0) ++nextId;
            Region previous = vertex(previousId), next = vertex(nextId);
            double from = previous.getLayoutY() + previous.getHeight(), to = next.getLayoutY();
            close(bullet.getY(), from + (to - from) * (edge.to() - previousId) / (nextId - previousId),
                    "hidden loop entry divides the full connector between visible boxes");
            close(bullet.getY(), target.getLayoutY(), "hidden loop-entry anchor shares its visible junction position");
        } else {
            require(bullet.getY() + junction.getRadius() < target.getLayoutY(), "junction lies above the target box");
            Region previous = vertex(edge.to() - 1);
            close(bullet.getY(), (previous.getLayoutY() + previous.getHeight() + target.getLayoutY()) / 2,
                    "junction is halfway along the local incoming gap above its destination");
        }
        close(bullet.getX(), target.getLayoutX() + target.getWidth() / 2, "junction aligns with incoming center line");
        Node stem = view.lookupAll(".flowchart-junction-stem").stream()
                .filter(node -> Integer.valueOf(edge.to()).equals(node.getUserData())).findFirst().orElseThrow();
        List<Point2D> stemPoints = points(stem);
        close(stemPoints.get(0), bullet, "incoming stem starts at the junction");
        close(stemPoints.get(stemPoints.size() - 1), new Point2D(bullet.getX(), target.getLayoutY()), "incoming stem reaches the target's top edge");
        if (graph.isImplicitBeginTarget(edge.to()) && destination.owner().labels().isBlank()) {
            Label entryLabel = view.lookupAll(".flowchart-begin-label").stream().filter(Label.class::isInstance)
                    .map(Label.class::cast).filter(label -> label.getText().equals("begin:")).findFirst().orElseThrow();
            require(entryLabel.getBoundsInParent().getMaxX() <= bullet.getX() - junction.getRadius() + EPSILON,
                    "read-only built-in begin label sits to the left of its incoming junction");
            require(view.lookupAll(".flowchart-label-edit").stream().filter(TextInputControl.class::isInstance)
                            .map(TextInputControl.class::cast).noneMatch(label -> label.getText().contains("begin")),
                    "the built-in main entry is not exposed as an editable declared label");
        } else {
            require(destination.owner() != null && !destination.owner().labels().isBlank(), "target owns its declared labels");
            TextInputControl label = view.lookupAll(".flowchart-junction-label").stream().filter(TextInputControl.class::isInstance)
                    .map(TextInputControl.class::cast).filter(control -> Integer.valueOf(destination.owner().start()).equals(control.getUserData()))
                    .findFirst().orElseThrow();
            require(label.getText().strip().equals(destination.owner().labels().strip()), "junction label preserves all original aliases");
            Bounds labelBounds = label.getBoundsInParent();
            require(labelBounds.getMaxX() <= bullet.getX() - junction.getRadius() + EPSILON, "target label is left of the junction");
        }
        require(target.lookupAll(".flowchart-label-edit").isEmpty(), "label is outside its instruction box");
        for (Flowchart.Edge incoming : graph.edges()) {
            if (incoming.to() == edge.to()) {
                List<Point2D> incomingPoints = points(path(incoming));
                close(incomingPoints.get(incomingPoints.size() - 1), bullet, "every incoming path merges at the shared junction");
            }
        }
    }

    private static void verifyAnswers(Flowchart graph) {
        for (Flowchart.Vertex vertex : graph.vertices()) {
            if (vertex.kind() != Flowchart.Kind.DECISION) continue;
            List<Flowchart.Edge> branches = graph.edges().stream().filter(edge -> edge.from() == vertex.id()).toList();
            require(branches.size() == 2, "decision has two outgoing execution paths");
            boolean jump = vertex.owner() != null && vertex.owner().kind() == StructuredDiagram.Kind.STATEMENT;
            boolean forwardJump = jump && branches.stream().anyMatch(edge -> edge.label().equals("true") && edge.to() > vertex.id());
            Label operator = (Label) vertex(vertex.id()).lookup(".flowchart-comparison-operator");
            String expectedOperator = jump ? forwardJump ? "=" : "≠" : graph.comparisonInfo(vertex).canonicalNotEqual() ? "≠" : "=";
            require(operator != null && operator.getText().equals(expectedOperator),
                    "comparison follows jump direction or the structured condition's canonical operator");
            Label question = (Label) vertex(vertex.id()).lookup(".flowchart-condition-question");
            require(question != null && question.getText().equals("?"), "every diamond condition ends with a question mark");
            for (Flowchart.Edge branch : branches) {
                Label answer = view.lookupAll(".flowchart-edge-label").stream().filter(Label.class::isInstance).map(Label.class::cast)
                        .filter(label -> branch.equals(label.getUserData())).findFirst().orElseThrow();
                boolean affirmative = branch.label().equals("true") != forwardJump;
                require(answer.getText().equals(affirmative ? "YES" : "NO"), "branch answer preserves behavior under the displayed comparison");
                Point2D tail = points(path(branch)).get(0);
                Bounds text = answer.getBoundsInParent();
                double dx = Math.max(Math.max(text.getMinX() - tail.getX(), tail.getX() - text.getMaxX()), 0);
                double dy = Math.max(Math.max(text.getMinY() - tail.getY(), tail.getY() - text.getMaxY()), 0);
                require(Math.hypot(dx, dy) <= 45, "answer stays near its outgoing arrow tail");
            }
        }
    }
    private static double laneX(Flowchart.Edge edge) {
        return points(path(edge)).stream().mapToDouble(Point2D::getX).max().orElseThrow();
    }
    private static void verifyRouteLanes(Flowchart graph) {
        List<Flowchart.Edge> jumps = graph.edges().stream().filter(graph::isJumpRoute).toList();
        for (Flowchart.Edge outer : jumps) for (Flowchart.Edge inner : jumps) {
            if (outer.equals(inner)) continue;
            int first = Math.min(outer.from(), outer.to()), last = Math.max(outer.from(), outer.to());
            int from = Math.min(inner.from(), inner.to()), to = Math.max(inner.from(), inner.to());
            if (from <= last && first <= to) require(Math.abs(laneX(outer) - laneX(inner)) >= 17,
                    "overlapping closed intervals use distinct vertical routes: " + outer + " / " + inner);
            if (first <= from && to <= last && (first < from || to < last)) require(laneX(outer) > laneX(inner),
                    "enclosing jump route lies farther right than the contained route");
        }
    }

    private static Region vertex(int id) { return (Region) view.lookup("#flowchart-node-" + id); }
    private static javafx.scene.shape.Path path(Flowchart.Edge edge) {
        return view.lookupAll(".flowchart-edge").stream().filter(javafx.scene.shape.Path.class::isInstance)
                .map(javafx.scene.shape.Path.class::cast).filter(node -> edge.equals(node.getUserData())).findFirst().orElseThrow();
    }
    private static List<Point2D> points(Node node) {
        List<Point2D> result = new ArrayList<>();
        if (node instanceof Line line) {
            result.add(line.localToParent(line.getStartX(), line.getStartY()));
            result.add(line.localToParent(line.getEndX(), line.getEndY()));
        } else if (node instanceof javafx.scene.shape.Path path) {
            for (var element : path.getElements()) {
                if (element instanceof MoveTo point) result.add(path.localToParent(point.getX(), point.getY()));
                else if (element instanceof LineTo point) result.add(path.localToParent(point.getX(), point.getY()));
                else throw new AssertionError("Unexpected non-orthogonal path element " + element);
            }
        } else throw new AssertionError("Unknown edge shape " + node);
        require(result.size() >= 2, "edge has a start and endpoint");
        return result;
    }
    private static void close(Point2D actual, Point2D expected, String message) {
        require(actual.distance(expected) <= EPSILON, message + ": " + actual + " versus " + expected);
    }
    private static void close(double actual, double expected, String message) { require(Math.abs(actual - expected) <= EPSILON, message); }
    private static void screenshot(Path path) throws Exception {
        screenshot(view.lookup("#flowchart-main"), path);
    }
    private static void screenshot(Node node, Path path) throws Exception {
        WritableImage shot = node.snapshot(null, null);
        BufferedImage image = new BufferedImage((int) shot.getWidth(), (int) shot.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) image.setRGB(x, y, shot.getPixelReader().getArgb(x, y));
        require(ImageIO.write(image, "png", path.toFile()), "PNG writer");
    }
    private static void check(String name, Check action) throws Exception { onFx(action); passed++; System.out.println("PASS " + name); }
    private static void onFx(Check action) throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> { action.run(); return null; }); Platform.runLater(task); task.get(15, TimeUnit.SECONDS);
        Throwable failure = fxFailure.getAndSet(null); if (failure != null) throw new AssertionError("Uncaught JavaFX error", failure);
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}

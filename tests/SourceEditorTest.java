package machine;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.IndexRange;
import javafx.scene.control.TextArea;
import javafx.scene.control.skin.TextAreaSkin;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.PathElement;
import javafx.scene.text.Text;
import javafx.stage.Stage;

/**
 * Source highlighting integration checks, separate from core-only tests.
 *
 * fx_modules=/usr/share/java/openjfx/javafx.base.jar:/usr/share/java/openjfx/javafx.graphics.jar:/usr/share/java/openjfx/javafx.controls.jar
 * gui_build=$(mktemp -d)
 * javac --release 17 --module-path "$fx_modules" --add-modules javafx.controls \
 *     -d "$gui_build" -classpath "$gui_build" -sourcepath . tests/SourceEditorTest.java
 * timeout 45s java --module-path "$fx_modules" --add-modules javafx.controls \
 *     -Dglass.platform=Monocle -Dmonocle.platform=Headless -Dprism.order=sw \
 *     -Djava.library.path=/usr/lib64/openjfx -cp "$gui_build" machine.SourceEditorTest
 *
 * The installed distribution's native libraries require Java 17 and process-local
 * LD_PRELOAD=libfreetype.so.6:libpango-1.0.so.0:libpangoft2-1.0.so.0:libm.so.6.
 * An optional first argument saves a PNG of the highlighted example.
 */
public final class SourceEditorTest {
    private static int passed;
    private static int failed;
    private static Stage stage;
    private static SourceEditor editor;
    private static final String EXAMPLE = "// Source colors — Grüße\n"
            + "import \"helpers.ad04\";\n"
            + "void bump(value) {\n\tINC value;\n}\n\n"
            + "var result = 12;\nbegin:\n"
            + "loop: bump(result);\n"
            + "if(result != 0) { DEC result; JNZ result loop; }\nHALT;\n";

    @FunctionalInterface
    private interface Check {
        void run() throws Exception;
    }

    public static void main(String[] args) throws Exception {
        Path screenshot = args.length == 0 ? null : Path.of(args[0]);
        AtomicReference<Throwable> asynchronousError = new AtomicReference<>();
        Thread.UncaughtExceptionHandler previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            asynchronousError.compareAndSet(null, error);
            error.printStackTrace(System.err);
        });
        CountDownLatch started = new CountDownLatch(1);
        Platform.startup(() -> {
            Platform.setImplicitExit(false);
            started.countDown();
        });
        expect(started.await(10, TimeUnit.SECONDS), "JavaFX toolkit did not start");
        try {
            onFx(() -> stage = new Stage());

            checkFx("initial highlighting covers unchanged source with visible token colors", () -> {
                show(EXAMPLE);
                expect(EXAMPLE.equals(editor.getText()), "creating highlights must preserve source text");
                expect(EXAMPLE.equals(renderedText()), "highlighted runs must cover the exact source");
                for (String kind : List.of("keyword", "instruction", "number", "comment", "string",
                        "label", "macro", "operator", "plain")) {
                    Text token = token(kind);
                    expect(token.getFill() instanceof Color && ((Color) token.getFill()).getOpacity() > 0,
                            kind + " tokens must be visible");
                    expectClipMatches(kind);
                }
                expect(!token("keyword").getFill().equals(token("instruction").getFill()),
                        "keywords and instructions must have distinguishable colors");
                expect(!token("number").getFill().equals(token("comment").getFill()),
                        "numbers and comments must have distinguishable colors");
                if (screenshot != null) {
                    writeSnapshot(screenshot);
                }
            });

            checkFx("edits recolor immediately without changing the edited text", () -> {
                show("INC value;\n");
                AtomicInteger textChanges = new AtomicInteger();
                editor.textProperty().addListener((observable, before, after) -> textChanges.incrementAndGet());
                editor.insertText(0, "// ");
                layout();
                expect("// INC value;\n".equals(renderedText()), "edited comment must render completely");
                expectClipMatches("comment");
                expect(highlights().lookup(".syntax-instruction") == null, "comment text must no longer be an instruction token");
                expect(textChanges.get() == 1, "highlighting must not produce additional text changes");
                editor.deleteText(0, 3);
                layout();
                expectClipMatches("instruction");
                expect(textChanges.get() == 2, "only user edits may change the text property");
            });

            checkFx("style and layout preserve caret selection and undo history", () -> {
                show(EXAMPLE);
                String edited = EXAMPLE + "// unsaved draft\n";
                editor.appendText("// unsaved draft\n");
                editor.selectRange(18, 5);
                AtomicInteger textChanges = new AtomicInteger();
                editor.textProperty().addListener((observable, before, after) -> textChanges.incrementAndGet());
                editor.setStyle("-fx-font-family: monospace; -fx-font-size: 19px; -fx-text-fill: #27364b;");
                stage.setWidth(610);
                stage.setHeight(400);
                layout();
                expect(edited.equals(editor.getText()), "styling preserves edited text");
                expect(editor.getAnchor() == 18 && editor.getCaretPosition() == 5,
                        "styling preserves selection direction and caret");
                expect(edited.substring(5, 18).equals(editor.getSelectedText()), "styling preserves selection contents");
                expect(textChanges.get() == 0, "styling must not emit text changes");
                expect(editor.isUndoable(), "styling must retain the user's undo history");
                editor.undo();
                layout();
                expect(EXAMPLE.equals(editor.getText()), "undo removes the edit rather than highlighting");
                editor.redo();
                layout();
                expect(edited.equals(editor.getText()), "redo restores the original edit");
                expect(edited.equals(renderedText()), "redo refreshes highlighting");
            });

            checkFx("native replacement undo and redo preserve tabs Unicode and blank lines", () -> {
                String source = "// Grüße 🌱\n\nvar\tvalue = 7;\nbegin:\n\tINC value;\n\nHALT;\n";
                show(source);
                int start = source.indexOf("7");
                editor.selectRange(start, start + 1);
                editor.replaceSelection("12");
                layout();
                String edited = source.substring(0, start) + "12" + source.substring(start + 1);
                expect(edited.equals(editor.getText()), "replacement changes only the selected text");
                expect(edited.equals(renderedText()), "replacement preserves all source characters in highlights");
                editor.undo();
                layout();
                expect(source.equals(editor.getText()), "undo restores exact original source");
                editor.redo();
                layout();
                expect(edited.equals(editor.getText()), "redo restores exact edited source");
            });

            checkFx("highlight caret and range positions match native tabs and multiline text", () -> {
                show(geometrySource());
                expectAligned();
                editor.setStyle("-fx-font-family: monospace; -fx-font-size: 21px;");
                stage.setWidth(460);
                stage.setHeight(300);
                layout();
                expectAligned();
                for (Node node : highlights().getChildren()) {
                    expect(((Text) node).getFont().equals(editor.getFont()), "all tokens must follow editor font changes");
                }
            });

            checkFx("highlight geometry follows horizontal and vertical scrolling", () -> {
                show(geometrySource().repeat(16));
                stage.setWidth(420);
                stage.setHeight(260);
                layout();
                editor.positionCaret(0);
                editor.setScrollTop(170);
                editor.setScrollLeft(90);
                layout();
                expect(editor.getScrollTop() > 0, "fixture must scroll vertically");
                expect(editor.getScrollLeft() > 0, "fixture must scroll horizontally");
                expectAligned();
                expect(highlights().getParent() == nativeText().getParent().getParent(),
                        "native text and highlights must share the scrolled content");
            });

            checkFx("native selection and caret render above a mouse-transparent overlay", () -> {
                show(EXAMPLE);
                editor.requestFocus();
                editor.selectRange(0, 12);
                layout();
                Group overlay = highlights();
                Text nativeText = nativeText();
                expect(overlay.isMouseTransparent(), "overlay must not intercept editing gestures");
                expect(Color.TRANSPARENT.equals(nativeText.getFill()), "ordinary native glyphs must not duplicate the overlay");
                expect(nativeText.getSelectionFill() instanceof Color
                        && ((Color) nativeText.getSelectionFill()).getOpacity() > 0,
                        "selected native glyphs must remain visible");
                expect(nativeText.getSelectionStart() == 0 && nativeText.getSelectionEnd() == 12,
                        "native text retains selection rendering");
                Parent content = overlay.getParent();
                expect(content.getChildrenUnmodifiable().indexOf(overlay)
                        < content.getChildrenUnmodifiable().indexOf(nativeText.getParent()),
                        "native selection text must render above highlighted text");
                Node caret = content.getChildrenUnmodifiable().stream()
                        .filter(node -> node instanceof javafx.scene.shape.Path path && !path.getElements().isEmpty())
                        .findFirst().orElseThrow(() -> new AssertionError("native caret path must remain present"));
                expect(((TextAreaSkin) editor.getSkin()).getCaretBounds().getHeight() > 0,
                        "standard TextArea skin must retain caret geometry");
                expect(content.getChildrenUnmodifiable().indexOf(overlay)
                        < content.getChildrenUnmodifiable().indexOf(caret),
                        "native caret must render above highlighted text");
            });

            checkFx("execution marker ranges align with native text across font and scroll changes", () -> {
                String source = geometrySource().repeat(10);
                show(source);
                IndexRange current = new IndexRange(source.indexOf("INC"), source.indexOf("HALT") + 5);
                List<IndexRange> callers = List.of(new IndexRange(0, source.indexOf('\n')),
                        new IndexRange(source.indexOf("begin"), source.indexOf("begin") + 6));
                editor.setExecutionRange(current.getStart(), current.getEnd(), false);
                editor.setCallerRanges(callers);
                layout();
                expectExecutionMarkers(current, callers);
                editor.setStyle("-fx-font-family: monospace; -fx-font-size: 20px;");
                stage.setWidth(430);
                stage.setHeight(260);
                layout();
                expectExecutionMarkers(current, callers);
                editor.positionCaret(0);
                editor.setScrollTop(150);
                editor.setScrollLeft(60);
                layout();
                expect(editor.getScrollTop() > 0 && editor.getScrollLeft() > 0,
                        "execution marker fixture must scroll in both directions");
                expectExecutionMarkers(current, callers);
            });

            checkFx("current caller and halted markers retain syntax and native selection layers", () -> {
                show(EXAMPLE);
                AtomicInteger textChanges = new AtomicInteger();
                editor.textProperty().addListener((observable, before, after) -> textChanges.incrementAndGet());
                int instruction = EXAMPLE.indexOf("DEC");
                IndexRange current = new IndexRange(instruction, instruction + "DEC result;".length());
                int call = EXAMPLE.indexOf("loop: bump");
                List<IndexRange> callers = List.of(new IndexRange(call, call + "loop: bump(result);".length()));
                editor.selectRange(18, 5);
                editor.setExecutionRange(current.getStart(), current.getEnd(), false);
                editor.setCallerRanges(callers);
                layout();
                Group markers = executionHighlights();
                javafx.scene.shape.Path currentMarker = executionMarker("execution-current");
                javafx.scene.shape.Path callerMarker = executionMarker("execution-caller");
                expect(markers.isMouseTransparent() && !markers.isManaged(),
                        "execution decorations must not intercept editing or participate in layout");
                expect(!currentMarker.getFill().equals(callerMarker.getFill()),
                        "current statement and macro callers must be distinguishable");
                expect(markers.getChildren().indexOf(callerMarker) < markers.getChildren().indexOf(currentMarker),
                        "primary statement must render above any overlapping caller marker");
                Parent content = markers.getParent();
                expect(content.getChildrenUnmodifiable().indexOf(markers)
                        < content.getChildrenUnmodifiable().indexOf(highlights())
                        && content.getChildrenUnmodifiable().indexOf(markers)
                        < content.getChildrenUnmodifiable().indexOf(nativeText().getParent()),
                        "execution backgrounds must render behind syntax and native selection text");
                Color runningColor = (Color) currentMarker.getFill();
                editor.setExecutionRange(current.getStart(), current.getEnd(), true);
                layout();
                expect(editor.isExecutionHalted() && current.equals(editor.getExecutionRange()),
                        "halted state retains the same source statement");
                expect(!runningColor.equals(executionMarker("execution-current").getFill()),
                        "halted statement must have a distinct decoration");
                expect(callers.equals(editor.getCallerRanges()), "halt styling must retain caller context");
                expect(editor.getAnchor() == 18 && editor.getCaretPosition() == 5
                        && nativeText().getSelectionStart() == 5 && nativeText().getSelectionEnd() == 18,
                        "execution styling must preserve native selection and caret");
                expect(textChanges.get() == 0 && EXAMPLE.equals(editor.getText()),
                        "execution decorations must not modify document text");
                expect(!editor.isUndoable(), "decorations must not introduce undo entries");
                editor.clearExecutionHighlights();
                layout();
                expect(editor.getExecutionRange() == null && editor.getCallerRanges().isEmpty()
                        && !editor.isExecutionHalted() && executionHighlights().getChildren().isEmpty(),
                        "explicit clearing removes primary caller and halted state");
            });

            check("revealing source scrolls without moving focus selection caret or undo history", () -> {
                String original = geometrySource().repeat(16);
                String draft = "// " + "padding ".repeat(40) + "TARGET;\n";
                String edited = original + draft;
                int target = edited.indexOf("TARGET");
                AtomicReference<Button> focusTarget = new AtomicReference<>();
                AtomicInteger textChanges = new AtomicInteger();
                onFx(() -> {
                    show(original);
                    stage.setWidth(420);
                    stage.setHeight(260);
                    editor.appendText(draft);
                    editor.selectRange(18, 5);
                    editor.textProperty().addListener((observable, before, after) -> textChanges.incrementAndGet());
                    Button button = new Button("Keep focus");
                    focusTarget.set(button);
                    StackPane.setAlignment(button, Pos.BOTTOM_RIGHT);
                    ((StackPane) stage.getScene().getRoot()).getChildren().add(button);
                    layout();
                    button.requestFocus();
                });
                awaitLayoutPulse();
                onFx(() -> {
                    expect(stage.getScene().getFocusOwner() == focusTarget.get(), "another control must initially own focus");
                    editor.setScrollTop(0);
                    editor.setScrollLeft(0);
                    editor.setExecutionRange(target, target + "TARGET".length(), false);
                    editor.revealSourceOffset(target);
                    layout();
                });
                awaitLayoutPulse();
                onFx(() -> {
                    expect(editor.getScrollTop() > 0 && editor.getScrollLeft() > 0,
                            "reveal must scroll to the offscreen line and column");
                    Node viewport = editor.lookup(".viewport");
                    expect(viewport != null, "native scroll viewport must exist");
                    Bounds targetBounds = shapeBounds(nativeText(), nativeText().rangeShape(target, target + 1));
                    expect(viewport.localToScene(viewport.getLayoutBounds()).intersects(targetBounds),
                            "revealed source character must enter the viewport");
                    expect(editor.getAnchor() == 18 && editor.getCaretPosition() == 5,
                            "revealing execution must leave caret and selection direction alone");
                    expect(stage.getScene().getFocusOwner() == focusTarget.get(), "reveal must not steal keyboard focus");
                    expect(edited.equals(editor.getText()) && textChanges.get() == 0,
                            "reveal must not change source text");
                    expect(editor.isUndoable(), "reveal must retain prior undo history");
                    editor.undo();
                    expect(original.equals(editor.getText()), "undo after reveal must undo the user's edit");
                });
            });

            checkFx("editing source clears execution markers and undo does not restore stale markers", () -> {
                show(EXAMPLE);
                int instruction = EXAMPLE.indexOf("HALT");
                editor.setExecutionRange(instruction, instruction + 5, true);
                editor.setCallerRanges(List.of(new IndexRange(0, EXAMPLE.indexOf('\n'))));
                layout();
                expect(!executionHighlights().getChildren().isEmpty(), "fixture must start decorated");
                editor.insertText(0, "// draft\n");
                layout();
                expect(editor.getExecutionRange() == null && editor.getCallerRanges().isEmpty()
                        && !editor.isExecutionHalted(), "editing must invalidate all source execution metadata");
                expect(executionHighlights().getChildren().isEmpty(), "editing must remove visible execution markers");
                editor.undo();
                layout();
                expect(EXAMPLE.equals(editor.getText()), "undo restores the user's source");
                expect(editor.getExecutionRange() == null && executionHighlights().getChildren().isEmpty(),
                        "undo must not resurrect stale execution decorations");
            });

            checkFx("disposing and recreating the skin preserves native editing", () -> {
                boolean inheritedDisposalFailure = hasInheritedDisposalFailure();
                show(EXAMPLE);
                Group previousOverlay = highlights();
                if (inheritedDisposalFailure) {
                    Text nativeText = nativeText();
                    ((StackPane) stage.getScene().getRoot()).getChildren().clear();
                    try {
                        editor.getSkin().dispose();
                        throw new AssertionError("expected the independently reproduced TextAreaSkin disposal failure");
                    } catch (UnsupportedOperationException error) {
                        expectInheritedDisposalFailure(error);
                    }
                    expect(previousOverlay.getParent() == null, "our overlay cleanup must precede inherited disposal");
                    expect(nativeText.fillProperty().isBound() && !Color.TRANSPARENT.equals(nativeText.getFill()),
                            "our cleanup must restore native text fill before inherited disposal");
                    expect(EXAMPLE.equals(editor.getText()), "disposing must preserve source text");
                    System.out.println("NOTE skin recreation skipped: plain TextAreaSkin.dispose() also throws UnsupportedOperationException");
                    return;
                }
                editor.setSkin(new TextAreaSkin(editor));
                layout();
                expect(previousOverlay.getParent() == null, "disposed highlight overlay must be removed");
                expect(!Color.TRANSPARENT.equals(nativeText().getFill()), "ordinary native skin must render text again");
                editor.appendText("// edited with native skin\n");
                String edited = editor.getText();
                editor.setSkin(null);
                editor.setSkin(editor.createDefaultSkin());
                layout();
                expect(edited.equals(renderedText()), "new syntax skin must display current source");
                expect(highlights() != previousOverlay, "skin recreation must create a fresh overlay");
                expect(editor.lookupAll(".source-highlights").size() == 1, "only one overlay may remain attached");
            });

            check("JavaFX reports no asynchronous exceptions", () -> {
                if (asynchronousError.get() != null) {
                    throw new AssertionError("JavaFX event or layout failure", asynchronousError.get());
                }
            });
        } finally {
            try {
                onFx(() -> stage.hide());
            } finally {
                Platform.exit();
                Thread.setDefaultUncaughtExceptionHandler(previousHandler);
            }
        }
        System.out.println("SourceEditorTest: " + passed + " passed, " + failed + " failed");
        if (failed != 0) {
            throw new AssertionError(failed + " source highlighting integration check(s) failed");
        }
    }

    private static void show(String text) {
        editor = new SourceEditor(text);
        stage.setScene(new Scene(new StackPane(editor), 720, 360));
        stage.show();
        layout();
    }

    private static void layout() {
        stage.getScene().getRoot().applyCss();
        stage.getScene().getRoot().layout();
        editor.layout();
    }

    private static Group highlights() {
        Node node = editor.lookup(".source-highlights");
        expect(node instanceof Group, "source highlight layers must be present");
        return (Group) node;
    }

    private static Group executionHighlights() {
        Node node = editor.lookup(".execution-highlights");
        expect(node instanceof Group, "execution decoration group must be present");
        return (Group) node;
    }

    private static javafx.scene.shape.Path executionMarker(String styleClass) {
        Node node = executionHighlights().lookup("." + styleClass);
        expect(node instanceof javafx.scene.shape.Path, "missing execution marker " + styleClass);
        return (javafx.scene.shape.Path) node;
    }

    private static void expectExecutionMarkers(IndexRange current, List<IndexRange> callers) {
        expectMarkerRange(executionMarker("execution-current"), current);
        List<Node> callerMarkers = executionHighlights().getChildren().stream()
                .filter(node -> node.getStyleClass().contains("execution-caller")).toList();
        expect(callerMarkers.size() == callers.size(), "each caller needs its own marker");
        for (int i = 0; i < callers.size(); ++i) {
            expectMarkerRange((javafx.scene.shape.Path) callerMarkers.get(i), callers.get(i));
        }
    }

    private static void expectMarkerRange(javafx.scene.shape.Path marker, IndexRange range) {
        Text text = nativeText();
        PathElement[] expected = text.rangeShape(range.getStart(), range.getEnd());
        expect(expected.length == marker.getElements().size(), "marker must include the complete native source range");
        for (int i = 0; i < expected.length; ++i) {
            expect(expected[i].toString().equals(marker.getElements().get(i).toString()),
                    "execution marker element " + i + " must match native range geometry");
        }
        expectBounds(shapeBounds(text, expected), marker.localToScene(marker.getBoundsInLocal()),
                "execution marker origin and bounds");
    }

    private static Text token(String kind) {
        Node node = highlights().lookup(".syntax-" + kind);
        expect(node instanceof Text, "missing highlighted token kind " + kind);
        return (Text) node;
    }

    private static String renderedText() {
        expect(!highlights().getChildren().isEmpty(), "source must have a base text layer");
        for (Node node : highlights().getChildren()) {
            expect(node instanceof Text && ((Text) node).getText().equals(editor.getText()),
                    "every highlight layer must preserve the complete source text");
        }
        return ((Text) highlights().getChildren().get(0)).getText();
    }

    private static Text nativeText() {
        for (Node node : descendants(editor)) {
            if (node instanceof Text text && text.getParent() instanceof Group
                    && text.getText().equals(editor.getText())
                    && text.getStyleClass().stream().noneMatch(style -> style.startsWith("syntax-"))) {
                return text;
            }
        }
        throw new AssertionError("native TextArea glyph node is missing");
    }

    private static List<Node> descendants(Parent parent) {
        List<Node> nodes = new ArrayList<>();
        for (Node node : parent.getChildrenUnmodifiable()) {
            nodes.add(node);
            if (node instanceof Parent child) {
                nodes.addAll(descendants(child));
            }
        }
        return nodes;
    }

    private static String geometrySource() {
        return "var\tvalue = 7;\n\nbegin:\n\tINC\tvalue;\n"
                + "// long line: " + "word ".repeat(45) + "\nHALT;\n";
    }

    private static boolean hasInheritedDisposalFailure() {
        TextArea ordinary = new TextArea("ordinary editor");
        TextAreaSkin skin = new TextAreaSkin(ordinary);
        ordinary.setSkin(skin);
        try {
            skin.dispose();
            return false;
        } catch (UnsupportedOperationException error) {
            expectInheritedDisposalFailure(error);
            return true;
        }
    }

    private static void expectInheritedDisposalFailure(UnsupportedOperationException error) {
        StackTraceElement origin = error.getStackTrace()[0];
        expect(origin.getClassName().equals(TextAreaSkin.class.getName()) && origin.getMethodName().equals("dispose"),
                "only the independently reproduced TextAreaSkin.dispose failure may be tolerated");
    }

    private static void expectAligned() {
        String text = editor.getText();
        Text nativeText = nativeText();
        Text overlay = token("plain");
        int[] offsets = {0, 3, 4, text.indexOf("7"), text.indexOf("begin"),
                text.indexOf("INC"), text.indexOf("INC") + 3, text.indexOf("HALT"), text.length() - 1};
        for (int offset : offsets) {
            expectBounds(shapeBounds(nativeText, nativeText.caretShape(offset, true)),
                    shapeBounds(overlay, overlay.caretShape(offset, true)), "caret at " + offset);
        }
        int first = text.indexOf("value");
        expectBounds(shapeBounds(nativeText, nativeText.rangeShape(first, first + 5)),
                shapeBounds(overlay, overlay.rangeShape(first, first + 5)), "range after tab");
        int instruction = text.indexOf("INC");
        expectBounds(shapeBounds(nativeText, nativeText.rangeShape(instruction, instruction + 3)),
                shapeBounds(overlay, overlay.rangeShape(instruction, instruction + 3)), "instruction range");
        for (Node layer : highlights().getChildren()) {
            for (String style : layer.getStyleClass()) {
                if (style.startsWith("syntax-")) {
                    expectClipMatches(style.substring("syntax-".length()));
                }
            }
        }
    }

    private static void expectClipMatches(String kind) {
        Text layer = token(kind);
        expect(layer.getClip() instanceof javafx.scene.shape.Path, kind + " layer must be clipped to its token ranges");
        javafx.scene.shape.Path clip = (javafx.scene.shape.Path) layer.getClip();
        List<PathElement> expected = new ArrayList<>();
        Text nativeText = nativeText();
        for (SourceSyntax.Span span : SourceSyntax.highlight(editor.getText())) {
            if (span.kind().name().equalsIgnoreCase(kind)) {
                expected.addAll(List.of(nativeText.rangeShape(span.start(), span.end())));
            }
        }
        expect(!expected.isEmpty(), kind + " fixture must contain classified ranges");
        expect(expected.size() == clip.getElements().size(), kind + " clip must include every classified range");
        for (int i = 0; i < expected.size(); ++i) {
            expect(expected.get(i).toString().equals(clip.getElements().get(i).toString()),
                    kind + " clip element " + i + " must match the native token range");
        }
        expectBounds(shapeBounds(nativeText, expected.toArray(PathElement[]::new)),
                shapeBounds(layer, clip.getElements().toArray(PathElement[]::new)), kind + " clip positioning");
    }

    private static Bounds shapeBounds(Node owner, PathElement[] elements) {
        javafx.scene.shape.Path path = new javafx.scene.shape.Path(elements);
        path.setStroke(null);
        path.setFill(Color.BLACK);
        return owner.localToScene(path.getBoundsInLocal());
    }

    private static void expectBounds(Bounds expected, Bounds actual, String description) {
        double tolerance = 0.01;
        expect(Math.abs(expected.getMinX() - actual.getMinX()) <= tolerance
                && Math.abs(expected.getMinY() - actual.getMinY()) <= tolerance
                && Math.abs(expected.getMaxX() - actual.getMaxX()) <= tolerance
                && Math.abs(expected.getMaxY() - actual.getMaxY()) <= tolerance,
                description + " must align with native text; expected " + expected + ", actual " + actual);
    }

    private static void writeSnapshot(Path path) throws Exception {
        WritableImage snapshot = stage.getScene().snapshot(null);
        BufferedImage image = new BufferedImage((int) snapshot.getWidth(), (int) snapshot.getHeight(),
                BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.getHeight(); ++y) {
            for (int x = 0; x < image.getWidth(); ++x) {
                image.setRGB(x, y, snapshot.getPixelReader().getArgb(x, y));
            }
        }
        expect(ImageIO.write(image, "png", path.toFile()), "PNG writer must be available");
    }

    private static void onFx(Check action) throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> {
            action.run();
            return null;
        });
        Platform.runLater(task);
        try {
            task.get(10, TimeUnit.SECONDS);
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            if (cause instanceof Error failure) {
                throw failure;
            }
            throw new AssertionError(cause);
        }
    }

    private static void awaitLayoutPulse() throws Exception {
        CountDownLatch completed = new CountDownLatch(1);
        onFx(() -> new AnimationTimer() {
            private int frames;

            @Override
            public void handle(long now) {
                if (++frames == 2) {
                    stop();
                    completed.countDown();
                }
            }
        }.start());
        expect(completed.await(5, TimeUnit.SECONDS), "JavaFX did not complete a layout pulse");
    }

    private static void checkFx(String name, Check action) {
        check(name, () -> onFx(action));
    }

    private static void check(String name, Check action) {
        try {
            action.run();
            ++passed;
            System.out.println("PASS " + name);
        } catch (Exception | AssertionError error) {
            ++failed;
            System.err.println("FAIL " + name + ": " + error);
            error.printStackTrace(System.err);
        }
    }

    private static void expect(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}

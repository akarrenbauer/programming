import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import javafx.stage.Stage;

import machine.GameFeature;
import machine.GameGrammar;
import machine.GameGrammarView;

/**
 * Separate JavaFX checks; the core GameGrammarTest needs only a JDK.
 * fx_modules=/usr/share/java/openjfx/javafx.base.jar:/usr/share/java/openjfx/javafx.graphics.jar:/usr/share/java/openjfx/javafx.controls.jar
 * test_build=$(mktemp -d)
 * javac --release 17 --module-path "$fx_modules" --add-modules javafx.controls \
 *   -d "$test_build" -classpath "$test_build" -sourcepath . tests/GameGrammarViewTest.java
 * java --module-path "$fx_modules" --add-modules javafx.controls \
 *   -cp "$test_build" GameGrammarViewTest
 * A working desktop or configured Monocle headless display is required.
 */
public final class GameGrammarViewTest {
    private static GameGrammarView view;
    private static Stage stage;
    private static int passed;
    private static int failed;

    @FunctionalInterface
    private interface Check { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        Platform.startup(() -> { Platform.setImplicitExit(false); started.countDown(); });
        expect(started.await(10, TimeUnit.SECONDS), "JavaFX did not start");
        try {
            onFx(() -> {
                view = new GameGrammarView();
                stage = new Stage();
                stage.setScene(new Scene(new StackPane(view), 620, 700));
                stage.show();
            });
            check("initial accessible grammar and literal typography", () -> {
                refresh(Set.of(), Set.of());
                expect(view.getText().equals(GameGrammar.render(Set.of())), "plain text comes from the shared model");
                expect(view.getAccessibleText().contains("Program:"), "accessible grammar is available");
                expect(view.lookupAll(".grammar-new").isEmpty(), "initial grammar has no earned highlights");
                for (Node node : view.lookupAll(".grammar-terminal")) {
                    Label terminal = (Label) node;
                    expect(terminal.getFont().getFamily().toLowerCase().contains("mono"), "terminal font is monospace");
                    expect(terminal.getBackground().getFills().get(0).getFill().equals(Color.web("#eeeeee")), "terminal background is light gray");
                }
                for (Node node : view.lookupAll(".grammar-nonterminal")) {
                    expect(((Text) node).getFont().getStyle().toLowerCase().contains("italic"), "nonterminal font is italic");
                }
                expect(view.lookup(".grammar-legend") != null, "notation has an explanatory legend");
            });
            check("optional else marks only new fragments with bold green text", () -> {
                refresh(Set.of(GameFeature.BLOCKS, GameFeature.IF, GameFeature.ELSE), Set.of(GameFeature.ELSE));
                expect(view.lookupAll(".grammar-new").size() == 4, "four optional-else symbols are new");
                expect(view.lookupAll(".grammar-new-badge").size() == 1, "only Statement changed");
                expect(view.lookupAll(".grammar-new").stream().anyMatch(n -> n.getAccessibleText().contains("else")),
                        "the changed terminal is described accessibly");
                for (Node node : view.lookupAll(".grammar-new")) {
                    expectNewSymbol(node);
                }
                for (Node node : view.lookupAll(".grammar-new-badge")) {
                    Label badge = (Label) node;
                    expect(badge.getTextFill().equals(Color.web("#20713a")), "New badge uses the same green text");
                    expect(badge.getFont().getStyle().toLowerCase().contains("bold"), "New badge is bold");
                    expect(badge.getBackground() == null || badge.getBackground().isEmpty(), "New badge has no background box");
                    expect(badge.getBorder() == null || badge.getBorder().isEmpty(), "New badge has no border");
                }
            });
            check("do appears as a new loop terminal without changing label grammar", () -> {
                refresh(Set.of(GameFeature.BLOCKS), Set.of());
                expect(view.lookupAll(".grammar-terminal").stream().noneMatch(n -> ((Label) n).getText().equals("do")),
                        "ordinary do identifiers do not need a literal grammar alternative");
                expect(view.getText().contains("do is an ordinary identifier"), "the earlier profile explains identifier use");
                refresh(Set.of(GameFeature.BLOCKS, GameFeature.DO_WHILE), Set.of(GameFeature.DO_WHILE));
                Set<Node> terminals = view.lookupAll(".grammar-terminal");
                expect(terminals.stream().filter(n -> ((Label) n).getText().equals("do")).count() == 1,
                        "there is exactly one literal do, in the structured loop");
                Node labelRule = view.lookupAll(".grammar-production").stream()
                        .filter(n -> "Label".equals(n.getUserData())).findFirst().orElseThrow();
                expect(labelRule.lookupAll(".grammar-terminal").isEmpty() && labelRule.lookupAll(".grammar-new").isEmpty(),
                        "Label remains the unchanged Identifier production");
                expect(view.getText().contains("do is reserved for the loop"), "the earned profile explains do reservation");
                expect(terminals.stream().filter(n -> ((Label) n).getText().equals("do"))
                                .allMatch(n -> n.getStyleClass().contains("grammar-new")),
                        "the loop's do terminal receives the new-syntax text style directly");
                terminals.stream().filter(n -> ((Label) n).getText().equals("do")).forEach(GameGrammarViewTest::expectNewSymbol);
            });
            check("literal and optional brackets remain visually distinct when new", () -> {
                refresh(Set.of(GameFeature.ARRAYS), Set.of(GameFeature.ARRAYS));
                expect(view.lookupAll(".grammar-new").size() == 10, "both array suffixes are highlighted");
                expect(view.lookupAll(".grammar-terminal").stream().filter(n -> ((Label) n).getText().equals("[")).count() == 2,
                        "two array opening brackets are literal shaded terminals");
                expect(view.lookupAll(".grammar-notation").stream().filter(n -> ((Text) n).getText().equals("[")).count() == 2,
                        "optional opening brackets are unshaded Text nodes");
                for (Node node : view.lookupAll(".grammar-new")) {
                    expectNewSymbol(node);
                }
            });
            check("acknowledgment clears marks without losing any grammar", () -> {
                Set<GameFeature> all = EnumSet.allOf(GameFeature.class);
                refresh(all, Set.of(GameFeature.IMPORTS, GameFeature.LITERAL_PARAMETERS));
                String before = view.getText();
                expect(!view.lookupAll(".grammar-new").isEmpty(), "new tools are initially marked");
                refresh(all, Set.of());
                expect(view.getText().equals(before), "acknowledgment changes no grammar text");
                expect(view.lookupAll(".grammar-new").isEmpty() && view.lookupAll(".grammar-new-badge").isEmpty(),
                        "all pending change marks disappear");
                for (Node node : view.lookupAll(".grammar-terminal")) {
                    Label terminal = (Label) node;
                    expect(!terminal.getFont().getStyle().toLowerCase().contains("bold")
                                    && !terminal.getTextFill().equals(Color.web("#20713a")),
                            "acknowledgment restores ordinary terminal typography");
                }
                expect(before.contains("macros.a26") && before.contains("toolbox"), "both virtual imports are displayed");
            });
            check("narrow layout wraps rules and lexical explanations", () -> {
                refresh(EnumSet.allOf(GameFeature.class), Set.of());
                Node macroRule = view.lookupAll(".grammar-production").stream()
                        .filter(n -> "MacroDefinition".equals(n.getUserData())).findFirst().orElseThrow();
                Region macroAlternative = (Region) macroRule.lookup(".grammar-alternative");
                double wideHeight = macroAlternative.getHeight();
                // Constrain the child region directly: native window resize events are asynchronous.
                view.setPrefWidth(340);
                view.setMaxWidth(340);
                stage.getScene().getRoot().applyCss();
                stage.getScene().getRoot().layout();
                refresh(EnumSet.allOf(GameFeature.class), Set.of());
                double width = view.getViewportBounds().getWidth();
                expect(width > 200 && width < 360, "test uses a narrow viewport");
                expect(macroAlternative.getHeight() > wideHeight,
                        "the long macro definition must wrap onto additional rows");
                for (Node node : view.lookupAll(".grammar-alternative")) {
                    if (node instanceof Label label) {
                        expect(label.isWrapText() && label.getWidth() <= width, "lexical explanations wrap to the viewport");
                    } else if (node instanceof Region row) {
                        expect(row.getWidth() <= width, "alternative rows fit available width");
                    }
                }
            });
            check("unchanged inventory refresh preserves nodes and scroll position", () -> {
                Set<GameFeature> all = EnumSet.allOf(GameFeature.class);
                refresh(all, Set.of());
                GameGrammar.Model model = view.getModel();
                Node firstRule = view.lookup(".grammar-production");
                view.setVvalue(0.6);
                view.update(EnumSet.allOf(GameFeature.class), Set.of());
                expect(view.getModel() == model && view.lookup(".grammar-production") == firstRule,
                        "equal feature snapshots must not rebuild the view");
                expect(Math.abs(view.getVvalue() - 0.6) < 0.0001, "no-op refresh preserves scroll");
            });
            onFx(() -> {
                refresh(Set.of(GameFeature.BLOCKS, GameFeature.DO_WHILE), Set.of(GameFeature.DO_WHILE));
                view.setVvalue(0);
                view.revealNewAdditions();
            });
            check("opening an unread grammar reveals its first changed rule after layout", () -> {
                view.layout();
                expect(view.getVvalue() > 0, "new syntax below the legend should be scrolled into view");
                Node firstChanged = view.lookupAll(".grammar-production").stream()
                        .filter(n -> "Statement".equals(n.getUserData())).findFirst().orElseThrow();
                Node viewport = view.lookup(".viewport");
                double ruleTop = firstChanged.localToScene(firstChanged.getBoundsInLocal()).getMinY();
                double viewportTop = viewport.localToScene(viewport.getBoundsInLocal()).getMinY();
                expect(Math.abs(ruleTop - viewportTop) < 20, "the changed production heading is near the visible top");
            });
        } finally {
            onFx(() -> { if (stage != null) stage.close(); });
            Platform.exit();
        }
        System.out.println("GameGrammarViewTest: " + passed + " passed, " + failed + " failed");
        if (failed != 0) throw new AssertionError(failed + " grammar view group(s) failed");
    }

    private static void refresh(Set<GameFeature> available, Set<GameFeature> added) {
        view.update(available, added);
        view.applyCss();
        view.layout();
    }

    private static void expectNewSymbol(Node symbol) {
        expect(symbol.getParent().getStyleClass().contains("grammar-alternative"),
                "highlighted symbols sit directly in the grammar row without a highlight box");
        if (symbol instanceof Label terminal) {
            expect(terminal.getStyleClass().contains("grammar-terminal"), "highlighted literal remains a terminal label");
            expect(terminal.getFont().getFamily().toLowerCase().contains("mono"), "new terminal remains monospace");
            expect(terminal.getFont().getStyle().toLowerCase().contains("bold"), "new terminal is bold");
            expect(terminal.getTextFill().equals(Color.web("#20713a")), "new terminal text is green");
            expect(terminal.getBackground() != null && terminal.getBackground().getFills().size() == 1
                            && terminal.getBackground().getFills().get(0).getFill().equals(Color.web("#eeeeee")),
                    "highlighted terminal keeps only its normal gray background");
            expect(terminal.getBorder() == null || terminal.getBorder().isEmpty(), "new terminal has no highlight border");
        } else {
            expect(symbol instanceof Text, "highlighted nonterminals and notation remain unboxed Text nodes");
            Text text = (Text) symbol;
            expect(text.getFont().getStyle().toLowerCase().contains("bold"), "new grammar text is bold");
            expect(text.getFill().equals(Color.web("#20713a")), "new grammar text is green");
            if (text.getStyleClass().contains("grammar-nonterminal")) {
                expect(text.getFont().getStyle().toLowerCase().contains("italic"), "new nonterminal retains italics");
            } else {
                expect(text.getStyleClass().contains("grammar-notation"), "new unshaded symbol is grammar notation");
                expect(!text.getFont().getStyle().toLowerCase().contains("italic"), "notation stays distinct from nonterminals");
            }
        }
    }

    private static void check(String name, Check action) throws Exception {
        try {
            onFx(action);
            ++passed;
            System.out.println("PASS " + name);
        } catch (Exception | AssertionError error) {
            ++failed;
            System.err.println("FAIL " + name + ": " + error);
            error.printStackTrace(System.err);
        }
    }

    private static void onFx(Check action) throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> { action.run(); return null; });
        Platform.runLater(task);
        task.get(15, TimeUnit.SECONDS);
    }

    private static void expect(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import machine.GameCatalog;
import machine.GameEngine;
import machine.GameFeature;
import machine.GameGrammar;
import machine.GameGrammar.Alternative;
import machine.GameGrammar.Fragment;
import machine.GameGrammar.Kind;
import machine.GameGrammar.Model;
import machine.GameGrammar.Production;
import machine.GameLevel;
import machine.GameResult;

/** Checks the displayed language contract at initial, intermediate and final unlock stages. */
public final class GameGrammarTest {
    private static int passed;
    private static int failed;

    @FunctionalInterface
    private interface Check { void run() throws Exception; }

    public static void main(String[] args) {
        check("initial grammar contains only numeric globals commands and labels", () -> {
            String grammar = GameGrammar.render(Set.of());
            expectRule(grammar, "Program", "{ VariableDeclaration ; } begin: { Statement }");
            expectRule(grammar, "VariableDeclaration", "var Identifier = Number");
            expectRule(grammar, "Statement", "Command ;", "Label : Statement");
            expectRule(grammar, "Command", "HALT", "NOP", "INC Variable", "DEC Variable", "JNZ Variable Label");
            expectRule(grammar, "Value", "Number");
            expectRule(grammar, "Variable", "Identifier");
            expectRule(grammar, "Label", "Identifier");
            expect(grammar.contains("while, HALT") && !grammar.contains("while, do, HALT"),
                    "the initial reserved-word list does not reserve do");
            expect(grammar.contains("do is an ordinary identifier"), "the initial profile explains do identifiers");
            for (String locked : List.of("Block", "Condition", "MacroDefinition", "MacroCall", "ImportDirective")) {
                expect(!grammar.contains(locked + ":\n"), "initial grammar must omit locked rule " + locked);
            }
        });

        check("early control flow shows only variable comparisons with zero", () -> {
            String grammar = GameGrammar.render(Set.of(GameFeature.BLOCKS, GameFeature.EMPTY_STATEMENTS,
                    GameFeature.WHILE, GameFeature.IF));
            expectRule(grammar, "Condition", "Variable == 0", "Variable != 0");
            expectRule(grammar, "Value", "Number");
            expect(rule(grammar, "Statement").contains("while ( Condition ) Block")
                    && rule(grammar, "Statement").contains("if ( Condition ) Block"), "earned control flow appears");
            expect(!grammar.contains("else Block") && !grammar.contains("do Block"), "unearned branches and loops remain absent");
        });

        check("general comparisons preserve variables before copying unlocks", () -> {
            String grammar = GameGrammar.render(comparisonFeatures());
            expectRule(grammar, "Condition", "Value == Value", "Value != Value");
            expectRule(grammar, "Value", "Number", "Variable");
            expectRule(grammar, "VariableDeclaration", "var Identifier = Number");
            expect(!rule(grammar, "Statement").contains("VariableDeclaration ;"), "comparison reward does not unlock runtime declarations");
        });

        check("intermediate displayed comparisons agree with actual accepted syntax", () -> {
            String source = "var x = 0;\nvar other = 3;\nvar result = 0;\nbegin:\n"
                    + "if (x == 3) { INC result; }\nif (x != 3) { INC result; }\n"
                    + "if (3 == 3) { INC result; }\nif (4 != 3) { INC result; }\n"
                    + "if (x == other) { INC result; }\nif (x != other) { INC result; }\n"
                    + "if (3 == x) { INC result; }\nif (3 != x) { INC result; }\nHALT;\n";
            GameLevel level = new GameLevel("grammar-check", "Tests", "Comparisons", "", "", source,
                    List.of(), Set.of(), Set.of(), Map.of(), List.of(
                        new GameLevel.TestCase(Map.of("x", 2), Map.of("x", 2, "other", 3, "result", 5)),
                        new GameLevel.TestCase(Map.of("x", 3), Map.of("x", 3, "other", 3, "result", 5))), 5000);
            GameResult result = GameEngine.check(level, source, comparisonFeatures(), Map.of(), () -> false, count -> {});
            expect(result.passed(), "all operand combinations must work before copying unlocks: " + result.summary());
            String copyInitializer = source.replace("var result = 0;", "var result = x;");
            GameResult copying = GameEngine.check(level, copyInitializer, comparisonFeatures(), Map.of(), () -> false, count -> {});
            expect(!copying.passed() && copying.summary().toLowerCase().contains("locked"), "variable initialization stays locked despite general comparisons");
        });

        check("every reachable curriculum stage can express variable conditions", () -> {
            Set<GameFeature> earned = EnumSet.noneOf(GameFeature.class);
            boolean checkedIntermediate = false;
            for (GameLevel level : GameCatalog.levels()) {
                earned.addAll(level.unlocks());
                if (earned.contains(GameFeature.WHILE) || earned.contains(GameFeature.IF)
                        || earned.contains(GameFeature.DO_WHILE)) {
                    String grammar = GameGrammar.render(earned);
                    List<String> conditions = rule(grammar, "Condition");
                    boolean variableDirect = conditions.stream().anyMatch(condition -> condition.startsWith("Variable "));
                    boolean variableViaValue = conditions.stream().anyMatch(condition -> condition.startsWith("Value "))
                            && rule(grammar, "Value").contains("Variable");
                    expect(variableDirect || variableViaValue, "variable conditions disappeared after " + level.id());
                    if (earned.contains(GameFeature.COMPARISONS) && !earned.contains(GameFeature.COPYING)) {
                        checkedIntermediate = true;
                    }
                }
            }
            expect(checkedIntermediate, "curriculum must exercise the general-comparison stage before copying");
        });

        check("do changes from an ordinary identifier to a reserved loop keyword", () -> {
            String blocks = GameGrammar.render(Set.of(GameFeature.BLOCKS, GameFeature.EMPTY_STATEMENTS));
            expectRule(blocks, "Label", "Identifier");
            expect(!blocks.contains("do Block while") && blocks.contains("do is an ordinary identifier"),
                    "do is covered by Identifier before the structured loop is available");
            String loop = GameGrammar.render(Set.of(GameFeature.BLOCKS, GameFeature.EMPTY_STATEMENTS, GameFeature.DO_WHILE));
            expectRule(loop, "Label", "Identifier");
            expect(loop.contains("do Block while ( Condition ) ;"), "the new reward exposes do/while syntax");
            expect(loop.contains("while, do, HALT") && loop.contains("do is reserved for the loop"),
                    "the structured profile reserves do in every identifier position");
            expect(!loop.contains("\n    while ( Condition ) Block\n") && !loop.contains("if ( Condition )"),
                    "pre-test loops and if remain locked at this stage");
            expectRule(loop, "Condition", "Variable == 0", "Variable != 0");
            Model additions = GameGrammar.describe(Set.of(GameFeature.BLOCKS, GameFeature.DO_WHILE), Set.of(GameFeature.DO_WHILE));
            expect(newFragments(production(additions, "Statement"), Set.of(GameFeature.DO_WHILE))
                            .equals(List.of("do", "Block", "while", "(", "Condition", ")", ";")),
                    "do is introduced as a loop terminal only");
            expect(!production(additions, "Label").isNew(Set.of(GameFeature.DO_WHILE)), "the Label production never gains a literal do alternative");
        });

        check("fully unlocked grammar exposes all documented language productions", () -> {
            String grammar = GameGrammar.render(EnumSet.allOf(GameFeature.class));
            expectRule(grammar, "Program", "{ MacroDefinition } { VariableDeclaration ; } begin: { Statement }");
            expectRule(grammar, "VariableDeclaration", "var Identifier [ \"[\" Number \"]\" ] = Value");
            expectRule(grammar, "Value", "Number", "Variable");
            expectRule(grammar, "Variable", "Identifier [ \"[\" Number \"]\" ]");
            expectRule(grammar, "Label", "Identifier");
            expectRule(grammar, "Condition", "Value == Value", "Value != Value");
            expectRule(grammar, "Block", "\"{\" { Statement } \"}\"");
            expectRule(grammar, "Statement", "Command ;", "Label : Statement", ";", "Block",
                    "while ( Condition ) Block", "if ( Condition ) Block [ else Block ]",
                    "do Block while ( Condition ) ;", "VariableDeclaration ;", "MacroCall ;");
            expectRule(grammar, "MacroDefinition", "void Identifier ( [ Parameter { , Parameter } ] ) Block");
            expectRule(grammar, "Parameter", "Identifier", "Number");
            expectRule(grammar, "MacroCall", "Identifier ( [ Argument { , Argument } ] )");
            expectRule(grammar, "Argument", "Variable", "Number");
            expectRule(grammar, "ImportDirective", "import \" LibraryName \" ;");
            expectRule(grammar, "LibraryName", "toolbox", "macros.a26");
            expect(grammar.contains("Imports are expanded before parsing.") && grammar.contains("import \"toolbox\";"), "final grammar documents virtual imports");
        });

        check("literal brackets and grammar notation have distinct symbol kinds", () -> {
            Model model = GameGrammar.describe(EnumSet.allOf(GameFeature.class));
            List<Fragment> array = production(model, "Variable").alternatives().get(0).fragments();
            expect(array.stream().filter(f -> f.text().equals("[")).map(Fragment::kind).toList()
                    .equals(List.of(Kind.NOTATION, Kind.TERMINAL)), "optional and literal opening brackets are distinct");
            List<Fragment> block = production(model, "Block").alternatives().get(0).fragments();
            expect(block.stream().filter(f -> f.text().equals("{")).map(Fragment::kind).toList()
                    .equals(List.of(Kind.TERMINAL, Kind.NOTATION)), "literal block brace and repetition brace are distinct");
            expect(block.get(2).kind() == Kind.NONTERMINAL && block.get(2).text().equals("Statement"), "repeated statement is a nonterminal");
        });

        check("else highlight is limited to its newly added optional suffix", () -> {
            Set<GameFeature> available = Set.of(GameFeature.BLOCKS, GameFeature.IF, GameFeature.ELSE);
            Set<GameFeature> added = Set.of(GameFeature.ELSE);
            Model model = GameGrammar.describe(available, added);
            expect(newFragments(production(model, "Statement"), added).equals(List.of("[", "else", "Block", "]")),
                    "old if prefix must not be highlighted as new");
            expect(!production(model, "Block").isNew(added) && !production(model, "Condition").isNew(added),
                    "shared block and condition rules are unchanged");
        });

        check("array highlights include optional and literal brackets without old declaration parts", () -> {
            Set<GameFeature> added = Set.of(GameFeature.ARRAYS);
            Model model = GameGrammar.describe(added, added);
            for (String name : List.of("VariableDeclaration", "Variable")) {
                expect(newFragments(production(model, name), added).equals(List.of("[", "[", "Number", "]", "]")),
                        "precise array suffix in " + name);
            }
            expect(!production(model, "Program").isNew(added), "array reward does not change program order");
        });

        check("comparison and copying rewards highlight their independent grammar additions", () -> {
            Set<GameFeature> available = EnumSet.of(GameFeature.BLOCKS, GameFeature.IF, GameFeature.COMPARISONS);
            Set<GameFeature> comparisons = Set.of(GameFeature.COMPARISONS);
            Production condition = production(GameGrammar.describe(available, comparisons), "Condition");
            for (Alternative alternative : condition.alternatives().subList(0, 2)) {
                expect(alternative.fragments().stream().filter(f -> f.isNew(comparisons)).map(Fragment::text).toList()
                        .equals(List.of("Value", "Value")), "general comparisons expand both operands");
            }
            Model compared = GameGrammar.describe(available, comparisons);
            expect(newFragments(production(compared, "Value"), comparisons).equals(List.of("Variable")),
                    "comparison values include variables");
            expect(!production(compared, "VariableDeclaration").isNew(comparisons),
                    "comparison reward keeps initializer grammar unchanged");
            available.add(GameFeature.COPYING);
            Set<GameFeature> copying = Set.of(GameFeature.COPYING);
            Model copied = GameGrammar.describe(available, copying);
            expect(!production(copied, "Condition").isNew(copying) && !production(copied, "Value").isNew(copying),
                    "copying does not re-highlight existing comparisons or values");
            expect(newFragments(production(copied, "VariableDeclaration"), copying).equals(List.of("Value")),
                    "copying unlocks variable initializers");
        });

        check("new loops do not re-highlight already available condition grammar", () -> {
            Set<GameFeature> available = Set.of(GameFeature.BLOCKS, GameFeature.IF, GameFeature.WHILE);
            Set<GameFeature> added = Set.of(GameFeature.WHILE);
            Model model = GameGrammar.describe(available, added);
            expect(!production(model, "Condition").isNew(added), "shared conditions already existed with if");
            expect(newFragments(production(model, "Statement"), added).equals(List.of("while", "(", "Condition", ")", "Block")),
                    "new while alternative is highlighted");
        });

        check("every feature has a precise addition and acknowledgment removes highlights", () -> {
            Set<GameFeature> all = EnumSet.allOf(GameFeature.class);
            for (GameFeature feature : all) {
                Set<GameFeature> added = Set.of(feature);
                Model model = GameGrammar.describe(all, added);
                expect(model.productions().stream().anyMatch(p -> p.isNew(added)), "feature is represented: " + feature);
            }
            Model acknowledged = GameGrammar.describe(all, Set.of());
            expect(acknowledged.productions().stream().noneMatch(p -> p.isNew(all)), "acknowledged grammar has no new fragments");
            Model unavailable = GameGrammar.describe(Set.of(), Set.of(GameFeature.WHILE));
            expect(unavailable.productions().stream().noneMatch(p -> p.isNew(all)), "unavailable rewards cannot introduce syntax");
        });

        check("standalone feature sets have no undefined nonterminal references", () -> {
            for (GameFeature feature : GameFeature.values()) {
                Model model = GameGrammar.describe(Set.of(feature));
                Set<String> names = model.productions().stream().map(Production::name).collect(java.util.stream.Collectors.toSet());
                model.productions().stream().flatMap(p -> p.alternatives().stream()).flatMap(a -> a.fragments().stream())
                        .filter(f -> f.kind() == Kind.NONTERMINAL)
                        .forEach(f -> expect(names.contains(f.text()), "missing " + f.text() + " with " + feature));
            }
            expectRule(GameGrammar.render(Set.of(GameFeature.MACROS)), "Argument", "Variable", "Number");
            expectRule(GameGrammar.render(Set.of(GameFeature.MACROS)), "Value", "Number");
        });

        check("grammar snapshots and all nested lists are immutable", () -> {
            Set<GameFeature> features = EnumSet.of(GameFeature.BLOCKS);
            Model model = GameGrammar.describe(features);
            features.clear();
            Production block = production(model, "Block");
            expect(block.isNew(Set.of(GameFeature.BLOCKS)), "model snapshots the caller feature set");
            expectUnsupported(() -> model.productions().clear());
            expectUnsupported(() -> model.notes().clear());
            expectUnsupported(() -> block.alternatives().clear());
            expectUnsupported(() -> block.alternatives().get(0).fragments().clear());
            expectUnsupported(() -> block.alternatives().get(0).fragments().get(0).introducedBy().clear());
        });

        System.out.println("GameGrammarTest: " + passed + " passed, " + failed + " failed");
        if (failed != 0) throw new AssertionError(failed + " grammar check(s) failed");
    }

    private static Set<GameFeature> comparisonFeatures() {
        return Set.of(GameFeature.BLOCKS, GameFeature.EMPTY_STATEMENTS, GameFeature.WHILE,
                GameFeature.IF, GameFeature.ELSE, GameFeature.COMPARISONS);
    }

    private static Production production(Model model, String name) {
        return model.productions().stream().filter(p -> p.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("missing production " + name));
    }

    private static List<String> newFragments(Production production, Set<GameFeature> added) {
        return production.alternatives().stream().flatMap(a -> a.fragments().stream())
                .filter(f -> f.isNew(added)).map(Fragment::text).toList();
    }

    private static void expectUnsupported(Runnable action) {
        try {
            action.run();
        } catch (UnsupportedOperationException expected) {
            return;
        }
        throw new AssertionError("grammar model must be immutable");
    }

    private static List<String> rule(String grammar, String name) {
        String heading = name + ":\n";
        int start = grammar.indexOf(heading);
        expect(start >= 0, "missing grammar rule " + name);
        start += heading.length();
        int end = grammar.indexOf("\n\n", start);
        return grammar.substring(start, end < 0 ? grammar.length() : end).lines().map(String::trim).toList();
    }

    private static void expectRule(String grammar, String name, String... alternatives) {
        List<String> actual = rule(grammar, name);
        expect(actual.equals(List.of(alternatives)), name + " alternatives: expected " + List.of(alternatives) + ", actual " + actual);
    }

    private static void check(String name, Check action) {
        try {
            action.run(); passed++; System.out.println("PASS " + name);
        } catch (Exception | AssertionError error) {
            failed++; System.err.println("FAIL " + name + ": " + error); error.printStackTrace(System.err);
        }
    }

    private static void expect(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

package machine;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static machine.GameFeature.*;

/** The earned language as immutable grammar symbols, independent of JavaFX. */
public final class GameGrammar {
    public enum Kind { TERMINAL, NONTERMINAL, NOTATION, TEXT }

    public record Fragment(String text, Kind kind, Set<GameFeature> introducedBy) {
        public Fragment {
            Objects.requireNonNull(text);
            Objects.requireNonNull(kind);
            introducedBy = Set.copyOf(introducedBy);
        }
        public boolean isNew(Set<GameFeature> newlyEarned) {
            return introducedBy.stream().anyMatch(newlyEarned::contains);
        }
    }

    /** A stable id lets an optional suffix be compared with its previous form. */
    public record Alternative(String id, List<Fragment> fragments) {
        public Alternative {
            Objects.requireNonNull(id);
            fragments = List.copyOf(fragments);
        }
    }

    public record Production(String name, List<Alternative> alternatives) {
        public Production {
            Objects.requireNonNull(name);
            alternatives = List.copyOf(alternatives);
        }
        public boolean isNew(Set<GameFeature> newlyEarned) {
            return alternatives.stream().flatMap(a -> a.fragments().stream()).anyMatch(f -> f.isNew(newlyEarned));
        }
    }

    public record Model(List<Production> productions, List<String> notes) {
        public Model {
            productions = List.copyOf(productions);
            notes = List.copyOf(notes);
        }
    }

    private GameGrammar() { }

    /** Symbols distinguish literal punctuation from the optional/repetition notation. */
    public static Model describe(Set<GameFeature> features) {
        return describe(features, true);
    }

    /** World policy restricts source instructions while preserving earned syntax. */
    public static Model describe(Set<GameFeature> features, boolean explicitJumps) {
        features = Set.copyOf(features);
        List<Production> rules = new ArrayList<>();
        List<Fragment> program = new ArrayList<>();
        if (features.contains(MACROS)) program.addAll(symbols(MACROS, "{ @MacroDefinition }"));
        program.addAll(symbols(null, "{ @VariableDeclaration ; } begin: { @Statement }"));
        rules.add(rule("Program", new Alternative("program", program)));

        List<Fragment> declaration = new ArrayList<>(symbols(null, "var @Identifier"));
        if (features.contains(ARRAYS)) declaration.addAll(arraySuffix());
        declaration.addAll(symbols(null, "= @Value"));
        rules.add(rule("VariableDeclaration", new Alternative("declaration", declaration)));

        List<Alternative> statements = new ArrayList<>(List.of(
            alternative("command", null, "@Command ;"), alternative("label", null, "@Label : @Statement")));
        if (features.contains(EMPTY_STATEMENTS)) statements.add(alternative("empty", EMPTY_STATEMENTS, ";"));
        if (features.contains(BLOCKS)) statements.add(alternative("block", BLOCKS, "@Block"));
        if (features.contains(WHILE)) statements.add(alternative("while", WHILE, "while ( @Condition ) @Block"));
        if (features.contains(IF)) {
            List<Fragment> conditional = new ArrayList<>(symbols(IF, "if ( @Condition ) @Block"));
            if (features.contains(ELSE)) conditional.addAll(symbols(ELSE, "[ else @Block ]"));
            statements.add(new Alternative("if", conditional));
        }
        if (features.contains(DO_WHILE)) statements.add(alternative("do", DO_WHILE, "do @Block while ( @Condition ) ;"));
        if (features.contains(LOCAL_VARIABLES)) statements.add(alternative("local", LOCAL_VARIABLES, "@VariableDeclaration ;"));
        if (features.contains(MACROS)) statements.add(alternative("macro", MACROS, "@MacroCall ;"));
        rules.add(new Production("Statement", statements));
        for (GameFeature owner : List.of(BLOCKS, DO_WHILE, IF, WHILE, MACROS)) {
            if (features.contains(owner)) {
                rules.add(rule("Block", alternative("block", owner, "'{' { @Statement } '}'")));
                break;
            }
        }
        List<Alternative> commands = new ArrayList<>(List.of(alternative("halt", null, "HALT"),
            alternative("nop", null, "NOP"), alternative("inc", null, "INC @Variable"),
            alternative("dec", null, "DEC @Variable")));
        if (explicitJumps) commands.add(alternative("jump", null, "JNZ @Variable @Label"));
        rules.add(new Production("Command", commands));

        List<Alternative> values = new ArrayList<>(List.of(alternative("number", null, "@Number")));
        if (features.contains(COPYING)) values.add(alternative("variable", COPYING, "@Variable"));
        rules.add(new Production("Value", values));
        List<Fragment> variable = new ArrayList<>(symbols(null, "@Identifier"));
        if (features.contains(ARRAYS)) variable.addAll(arraySuffix());
        rules.add(rule("Variable", new Alternative("variable", variable)));
        rules.add(rule("Label", alternative("identifier", null, "@Identifier")));

        Set<GameFeature> controls = EnumSet.noneOf(GameFeature.class);
        for (GameFeature control : List.of(IF, WHILE, DO_WHILE)) if (features.contains(control)) controls.add(control);
        if (!controls.isEmpty()) {
            List<Alternative> conditions = new ArrayList<>();
            for (String operator : List.of("==", "!=")) {
                List<Fragment> condition = new ArrayList<>();
                boolean general = features.contains(COMPARISONS);
                boolean value = general && features.contains(COPYING);
                Set<GameFeature> leftFeatures = EnumSet.copyOf(controls);
                if (value) { leftFeatures.add(COMPARISONS); leftFeatures.add(COPYING); }
                condition.add(new Fragment(value ? "Value" : "Variable", Kind.NONTERMINAL, leftFeatures));
                condition.add(new Fragment(operator, Kind.TERMINAL, controls));
                Set<GameFeature> rightFeatures = EnumSet.copyOf(controls);
                if (general) rightFeatures.add(COMPARISONS);
                condition.add(new Fragment(general ? "Number" : "0", general ? Kind.NONTERMINAL : Kind.TERMINAL, rightFeatures));
                conditions.add(new Alternative(operator, condition));
            }
            if (features.contains(COMPARISONS) && !features.contains(COPYING)) {
                conditions.add(alternative("literal-eq", COMPARISONS, "@Number == @Number"));
                conditions.add(alternative("literal-neq", COMPARISONS, "@Number != @Number"));
            }
            rules.add(new Production("Condition", conditions));
        }
        if (features.contains(MACROS)) {
            rules.add(rule("MacroDefinition", alternative("definition", MACROS, "void @Identifier ( [ @Parameter { , @Parameter } ] ) @Block")));
            List<Alternative> parameters = new ArrayList<>(List.of(alternative("identifier", MACROS, "@Identifier")));
            if (features.contains(LITERAL_PARAMETERS)) parameters.add(alternative("literal", LITERAL_PARAMETERS, "@Number"));
            rules.add(new Production("Parameter", parameters));
            // Macro actuals accept variables and literals independently of initializer syntax.
            rules.add(rule("MacroCall", alternative("call", MACROS, "@Identifier ( [ @Argument { , @Argument } ] )")));
            rules.add(rule("Argument", alternative("variable", MACROS, "@Variable"), alternative("number", MACROS, "@Number")));
        }
        if (features.contains(IMPORTS)) {
            rules.add(rule("ImportDirective", alternative("import", IMPORTS, "import '\"' @LibraryName '\"' ;")));
            rules.add(rule("LibraryName", alternative("toolbox", IMPORTS, "toolbox"), alternative("book", IMPORTS, "macros.a26")));
        }
        rules.add(rule("Identifier", new Alternative("identifier", List.of(new Fragment(
            "ASCII letter or _, then zero or more ASCII letters, digits, or _", Kind.TEXT, Set.of())))));
        rules.add(rule("Number", new Alternative("number", List.of(new Fragment(
            "one or more decimal digits (0 through 9)", Kind.TEXT, Set.of())))));

        boolean structured = features.contains(DO_WHILE);
        List<String> notes = new ArrayList<>(List.of(
            "Names and keywords are case-sensitive. The reserved words var, void, if, else, while, "
                + (structured ? "do, " : "")
                + "HALT, NOP, INC, DEC and JNZ cannot be identifiers; bare begin is an identifier.",
            "Spaces, tabs and line breaks separate tokens. Comments start with // and continue to the end of the line.",
            "The entry marker begin: is one token: no whitespace before its colon.",
            explicitJumps ? "Labels and JNZ are available in Machine foundations for implementing higher-level constructs."
                : "Explicit JNZ is suspended in this world. Use if/else, while and do/while; the compiler generates jumps internally. Code and Nassi–Shneiderman diagrams describe the same structured program."));
        notes.add(structured
            ? "With do/while unlocked, do is reserved for the loop and cannot name a variable, label or macro."
            : "Before do/while is unlocked, do is an ordinary identifier and may name a variable, label or macro.");
        if (features.contains(IMPORTS)) notes.add("Imports are expanded before parsing. Write import \"toolbox\"; or import \"macros.a26\"; on a line of its own before macro definitions and globals. Both names provide the earned virtual library; no disk files are read.");
        return new Model(rules, notes);
    }

    /** Only symbols newly present relative to the previous earned grammar retain feature tags. */
    public static Model describe(Set<GameFeature> available, Set<GameFeature> newlyEarned) {
        return describe(available, newlyEarned, true);
    }

    public static Model describe(Set<GameFeature> available, Set<GameFeature> newlyEarned, boolean explicitJumps) {
        Model current = describe(available, explicitJumps);
        Set<GameFeature> previous = EnumSet.noneOf(GameFeature.class);
        previous.addAll(available);
        previous.removeAll(newlyEarned);
        Map<String, Production> oldRules = new LinkedHashMap<>();
        describe(previous, explicitJumps).productions().forEach(rule -> oldRules.put(rule.name(), rule));
        List<Production> rules = new ArrayList<>();
        for (Production production : current.productions()) {
            Map<String, Alternative> oldAlternatives = new LinkedHashMap<>();
            Production old = oldRules.get(production.name());
            if (old != null) old.alternatives().forEach(a -> oldAlternatives.put(a.id(), a));
            List<Alternative> alternatives = new ArrayList<>();
            for (Alternative alternative : production.alternatives()) {
                Alternative before = oldAlternatives.get(alternative.id());
                List<Fragment> fragments = alternative.fragments();
                boolean[] unchanged = commonSymbols(before == null ? List.of() : before.fragments(), fragments);
                List<Fragment> highlighted = new ArrayList<>();
                for (int index = 0; index < fragments.size(); index++) {
                    Fragment fragment = fragments.get(index);
                    highlighted.add(new Fragment(fragment.text(), fragment.kind(), unchanged[index] ? Set.of() : fragment.introducedBy()));
                }
                alternatives.add(new Alternative(alternative.id(), highlighted));
            }
            rules.add(new Production(production.name(), alternatives));
        }
        return new Model(rules, current.notes());
    }

    /** Plain text remains available to accessibility clients, tests, and non-GUI callers. */
    public static String render(Set<GameFeature> features) {
        return render(features, true);
    }

    public static String render(Set<GameFeature> features, boolean explicitJumps) {
        Model model = describe(features, explicitJumps);
        StringBuilder text = new StringBuilder("[ X ] means optional; { X } means repetition.\n"
            + "Quoted brackets and braces are literal characters.\n\n");
        for (Production production : model.productions()) {
            text.append(production.name()).append(":\n");
            for (Alternative alternative : production.alternatives()) {
                text.append("    ").append(alternative.fragments().stream().map(GameGrammar::plain)
                    .collect(Collectors.joining(" "))).append('\n');
            }
            text.append('\n');
        }
        model.notes().forEach(note -> text.append(note).append('\n'));
        return text.toString();
    }

    private static String plain(Fragment fragment) {
        return fragment.kind() == Kind.TERMINAL && "[]{}".contains(fragment.text())
            ? "\"" + fragment.text() + "\"" : fragment.text();
    }

    private static Production rule(String name, Alternative... alternatives) {
        return new Production(name, List.of(alternatives));
    }

    private static Alternative alternative(String id, GameFeature feature, String symbols) {
        return new Alternative(id, symbols(feature, symbols));
    }

    private static List<Fragment> arraySuffix() {
        return symbols(ARRAYS, "[ '[' @Number ']' ]");
    }

    /** Internal notation: @ introduces a nonterminal; single quotes mark literal brackets. */
    private static List<Fragment> symbols(GameFeature feature, String symbols) {
        List<Fragment> fragments = new ArrayList<>();
        Set<GameFeature> introducedBy = feature == null ? Set.of() : Set.of(feature);
        for (String symbol : symbols.split(" ")) {
            if (symbol.startsWith("@")) fragments.add(new Fragment(symbol.substring(1), Kind.NONTERMINAL, introducedBy));
            else if (symbol.startsWith("'")) fragments.add(new Fragment(symbol.substring(1, symbol.length() - 1), Kind.TERMINAL, introducedBy));
            else if ("[]{}".contains(symbol)) fragments.add(new Fragment(symbol, Kind.NOTATION, introducedBy));
            else fragments.add(new Fragment(symbol, Kind.TERMINAL, introducedBy));
        }
        return List.copyOf(fragments);
    }

    private static boolean[] commonSymbols(List<Fragment> before, List<Fragment> after) {
        int[][] length = new int[before.size() + 1][after.size() + 1];
        for (int old = before.size() - 1; old >= 0; old--) {
            for (int next = after.size() - 1; next >= 0; next--) {
                length[old][next] = sameSymbol(before.get(old), after.get(next))
                    ? 1 + length[old + 1][next + 1] : Math.max(length[old + 1][next], length[old][next + 1]);
            }
        }
        boolean[] unchanged = new boolean[after.size()];
        int old = 0, next = 0;
        while (old < before.size() && next < after.size()) {
            if (sameSymbol(before.get(old), after.get(next))) { unchanged[next++] = true; old++; }
            else if (length[old + 1][next] >= length[old][next + 1]) old++;
            else next++;
        }
        return unchanged;
    }

    private static boolean sameSymbol(Fragment first, Fragment second) {
        return first.kind() == second.kind() && first.text().equals(second.text());
    }
}

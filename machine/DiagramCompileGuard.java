package machine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Conservative allocation/expansion budgets for automatic previews, independent of game language gates. */
final class DiagramCompileGuard {
    static final int MAX_COMPILED_CELLS = 250_000;
    static final int MAX_COMBINED_SOURCE = 262_144;
    static final int MAX_IMPORTS = 32;
    private record Signature(String name, int arity) { }
    private record Cost(long cells, int depth) { }

    private final Map<Signature, Cost> macroCosts = new HashMap<>();
    private final BooleanSupplier cancelled;

    private DiagramCompileGuard(BooleanSupplier cancelled) { this.cancelled = cancelled; }

    static void check(List<StructuredDiagram> sources, BooleanSupplier cancelled) {
        if (sources.size() > MAX_IMPORTS + 1) throw limit();
        DiagramCompileGuard guard = new DiagramCompileGuard(cancelled);
        long sourceSize = 0, cells = 1;
        int macros = 0;
        for (StructuredDiagram source : sources) {
            guard.poll();
            sourceSize += source.source().length();
            if (sourceSize > MAX_COMBINED_SOURCE) throw limit();
            for (StructuredDiagram.Node node : source.preamble().nodes()) {
                if (node.kind() == StructuredDiagram.Kind.MACRO) {
                    if (++macros > 512) throw limit();
                    List<String> signature = tokens(node.editText());
                    Cost cost = guard.sequence(node.body());
                    guard.macroCosts.merge(new Signature(signature.get(1), arity(signature, 2)), cost,
                            (before, after) -> new Cost(Math.max(before.cells, after.cells), Math.max(before.depth, after.depth)));
                    cells = bounded(cells + cost.cells);
                } else if (node.kind() == StructuredDiagram.Kind.DECLARATION) {
                    cells = bounded(cells + length(tokens(node.editText())));
                }
            }
            cells = bounded(cells + guard.sequence(source.main()).cells);
        }
    }

    private Cost sequence(StructuredDiagram.Sequence sequence) {
        long result = 0;
        int depth = 0;
        for (StructuredDiagram.Node node : sequence.nodes()) {
            poll();
            long cost = 8;
            if (node.body() != null) {
                Cost nested = sequence(node.body());
                cost = bounded(128 + nested.cells); depth = Math.max(depth, nested.depth + 1);
            }
            if (node.alternative() != null) {
                Cost nested = sequence(node.alternative());
                cost = bounded(cost + nested.cells); depth = Math.max(depth, nested.depth + 1);
            }
            if (node.body() == null) {
                List<String> tokens = tokens(node.editText());
                if (node.kind() == StructuredDiagram.Kind.DECLARATION) {
                    long length = length(tokens);
                    int value = tokens.indexOf("=") + 1;
                    long initializer = number(tokens.get(value), 128);
                    cost = bounded(length * bounded(initializer * 3 + 32));
                } else if (tokens.size() > 1 && tokens.get(1).equals("(")) {
                    Cost macro = macroCosts.getOrDefault(new Signature(tokens.get(0), arity(tokens, 1)), new Cost(128, 0));
                    cost = macro.cells; depth = Math.max(depth, macro.depth);
                    // Literal substitution may initialize a mutable macro parameter or a runtime local.
                    // A conservative multiplier also covers parameters forwarded through earlier helpers.
                    long largestLiteral = 0;
                    for (int index = 2; index < tokens.size() - 1; ++index) {
                        if (!tokens.get(index - 1).equals("[")) largestLiteral = Math.max(largestLiteral, number(tokens.get(index), 0));
                    }
                    cost = bounded(cost * bounded(largestLiteral + 1));
                }
            }
            result = bounded(result + cost);
            if (depth > 64) throw limit();
        }
        return new Cost(result, depth);
    }

    private void poll() {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancellationException();
    }
    private static long length(List<String> declaration) {
        return declaration.size() > 3 && declaration.get(2).equals("[") ? bounded(number(declaration.get(3), 0)) : 1;
    }
    private static long number(String token, long fallback) {
        if (token.isEmpty() || !Character.isDigit(token.charAt(0))) return fallback;
        try { return Long.parseLong(token); }
        catch (NumberFormatException overflow) { throw limit(); }
    }
    private static long bounded(long value) { if (value < 0 || value > MAX_COMPILED_CELLS) throw limit(); return value; }
    private static IllegalArgumentException limit() {
        return new IllegalArgumentException("Program exceeds the diagram preview's source or expansion limit. Compile it directly to inspect larger programs.");
    }
    private static int arity(List<String> tokens, int opening) {
        if (tokens.get(opening + 1).equals(")")) return 0;
        int count = 1;
        for (int index = opening + 1; index < tokens.size() && !tokens.get(index).equals(")"); ++index) {
            if (tokens.get(index).equals(",")) ++count;
        }
        return count;
    }
    private static List<String> tokens(String source) {
        List<String> result = new ArrayList<>();
        for (int index = 0; index < source.length();) {
            char c = source.charAt(index);
            if (Character.isWhitespace(c)) { ++index; continue; }
            if (c == '/' && index + 1 < source.length() && source.charAt(index + 1) == '/') {
                while (index < source.length() && source.charAt(index) != '\r' && source.charAt(index) != '\n') ++index;
                continue;
            }
            int start = index++;
            if (Character.isLetterOrDigit(c) || c == '_') {
                while (index < source.length() && (Character.isLetterOrDigit(source.charAt(index)) || source.charAt(index) == '_')) ++index;
            }
            result.add(source.substring(start, index));
        }
        return result;
    }
}

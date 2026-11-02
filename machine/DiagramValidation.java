package machine;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded editor validation and mapping of damaged code back to the last valid diagram. */
public final class DiagramValidation {
    public static final int MAX_RENDERED_NODES = 1000;
    private static final int MAX_SOURCE = 65_536;

    /** Offsets refer to the current, unnormalized source; -1 means no reliable main-file location. */
    public record Diagnostic(String message, int startOffset, int endOffset) { }

    /** A structurally valid candidate may be present even when compilation failed. */
    public record Result(StructuredDiagram diagram, List<Diagnostic> errors) {
        public Result { errors = List.copyOf(errors); }
        public boolean valid() { return diagram != null && errors.isEmpty(); }
        public String message() { return errors.isEmpty() ? "Code and diagram are synchronized." : errors.get(0).message(); }
    }

    /** Root flags represent insertions without an old node; unrelated sibling blocks stay unmarked. */
    public record Damage(Set<StructuredDiagram.Node> nodes, boolean main, boolean preamble) {
        public Damage { nodes = Set.copyOf(nodes); }
    }

    private DiagramValidation() { }

    /**
     * Validates a MachineGUI draft against the final compiler grammar and the workspace's open buffers.
     * Candidate text is never installed in a buffer or saved. Helper documents retain their exact source tree.
     */
    public static Result validateStandalone(SourceWorkspace workspace, SourceWorkspace.Document selected,
            String candidate, boolean explicitJumps) {
        return validateStandalone(workspace, selected, candidate, explicitJumps, () -> false);
    }

    public static Result validateStandalone(SourceWorkspace workspace, SourceWorkspace.Document selected,
            String candidate, boolean explicitJumps, BooleanSupplier cancelled) {
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(selected, "selected");
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(cancelled, "cancelled");
        if (!workspace.getDocuments().contains(selected)) throw new IllegalArgumentException("Document does not belong to this workspace");
        if (candidate.length() > MAX_SOURCE) return failed(null, "Source exceeds the diagram editor's size limit.", -1, -1);
        StructuredDiagram diagram;
        try {
            cancellation(cancelled);
            diagram = StructuredDiagram.parseDocument(candidate, LanguageVersion.STRUCTURED, explicitJumps);
        } catch (StructuredDiagram.ParseException error) {
            String message = !explicitJumps && error.getMessage().startsWith("JNZ is suspended")
                    ? "Use the flowchart for explicit JNZ instructions; Nassi–Shneiderman diagrams need structured control flow."
                    : error.getMessage();
            return failed(null, message, error.offset(), error.offset());
        }
        if (diagram.nodeCount() > MAX_RENDERED_NODES) return failed(diagram,
                "This program has too many blocks to display at once (maximum " + MAX_RENDERED_NODES + ").", -1, -1);
        StandaloneContext context = new StandaloneContext(workspace, selected, candidate, cancelled);
        if (!diagram.hasMain()) {
            SourceWorkspace.Document main = workspace.getMainDocument();
            if (main != null && main != selected) {
                try {
                    context.compile(main, context.text(main));
                    if (context.usedSelected) return new Result(diagram, List.of());
                } catch (CancellationException error) { throw error; }
                catch (IOException | ParseException | StructuredDiagram.ParseException | RuntimeException | TokenMgrError error) {
                    if (context.usedSelected) return new Result(diagram, List.of(context.diagnostic(error)));
                }
                context.clearCompilation();
            }
        }
        try {
            // This entry exists only in the compiler snapshot, never in the returned diagram or editable buffer.
            String program = diagram.hasMain() ? candidate : candidate + "\nbegin:\nHALT;\n";
            context.compile(selected, program);
            return new Result(diagram, List.of());
        } catch (CancellationException error) { throw error; }
        catch (IOException | ParseException | StructuredDiagram.ParseException | RuntimeException | TokenMgrError error) {
            return new Result(diagram, List.of(context.diagnostic(error)));
        }
    }

    private static final class StandaloneContext {
        private final SourceWorkspace workspace;
        private final SourceWorkspace.Document selected;
        private final String candidate;
        private final BooleanSupplier cancelled;
        private final Map<SourceWorkspace.Document, String> snapshots = new IdentityHashMap<>();
        private final Map<SourceWorkspace.Document, String> compiledSources = new LinkedHashMap<>();
        private final Map<SourceWorkspace.Document, StructuredDiagram.Node> selectedImports = new IdentityHashMap<>();
        private SourceWorkspace.Document parsing;
        private StructuredDiagram.Node loadingImport;
        private SourceWorkspace.Document loadingOwner;
        private boolean usedSelected;

        StandaloneContext(SourceWorkspace workspace, SourceWorkspace.Document selected, String candidate, BooleanSupplier cancelled) {
            this.workspace = workspace; this.selected = selected; this.candidate = candidate; this.cancelled = cancelled;
            for (SourceWorkspace.Document document : workspace.getDocuments()) snapshots.put(document, document.getText());
            snapshots.put(selected, candidate);
        }
        String text(SourceWorkspace.Document document) { return snapshots.computeIfAbsent(document, SourceWorkspace.Document::getText); }
        void clearCompilation() {
            compiledSources.clear(); selectedImports.clear(); parsing = null;
            loadingImport = null; loadingOwner = null; usedSelected = false;
        }
        void compile(SourceWorkspace.Document root, String program)
                throws IOException, ParseException, StructuredDiagram.ParseException {
            cancellation(cancelled);
            parsing = root;
            StructuredDiagram parsed = StructuredDiagram.parseDocument(program, LanguageVersion.STRUCTURED, true);
            compiledSources.put(root, text(root));
            usedSelected = root == selected;
            Map<Path, SourceDocument> imports = new LinkedHashMap<>();
            List<StructuredDiagram> inputs = new ArrayList<>();
            int combinedSize = program.length();
            for (StructuredDiagram.Node node : parsed.preamble().nodes()) if (node.kind() == StructuredDiagram.Kind.IMPORT) {
                cancellation(cancelled);
                if (inputs.size() >= DiagramCompileGuard.MAX_IMPORTS) throw new IllegalArgumentException(
                        "Too many source imports for a diagram preview (maximum " + DiagramCompileGuard.MAX_IMPORTS + ").");
                loadingOwner = root; loadingImport = node;
                String quoted = lex(node.text()).stream().map(Lexeme::text).filter(token -> token.startsWith("\"")).findFirst().orElseThrow();
                Path requested = Path.of(quoted.substring(1, quoted.length() - 1));
                SourceWorkspace.Document imported = workspace.openDocument(requested);
                usedSelected |= imported == selected;
                if (root == selected) selectedImports.put(imported, node);
                parsing = imported;
                String body = text(imported);
                combinedSize += body.length();
                if (combinedSize > DiagramCompileGuard.MAX_COMBINED_SOURCE) throw new IllegalArgumentException(
                        "Combined source exceeds the diagram preview's size limit.");
                compiledSources.put(imported, body);
                inputs.add(StructuredDiagram.parseDocument(body, LanguageVersion.STRUCTURED, true));
                imports.put(requested, new SourceDocument(imported.getPath(), body));
            }
            inputs.add(parsed);
            parsing = null; loadingOwner = null; loadingImport = null;
            DiagramCompileGuard.check(inputs, cancelled);
            CompilationResult compiled = Compiler.compileWithSourceMap(new SourceDocument(root.getPath(), program), path -> {
                cancellation(cancelled);
                SourceDocument imported = imports.get(path);
                if (imported == null) throw new IOException("Source import was not included in the diagram snapshot: " + path);
                return imported;
            }, null, LanguageVersion.STRUCTURED);
            cancellation(cancelled);
            if (compiled.bytecode().size() > DiagramCompileGuard.MAX_COMPILED_CELLS) {
                throw new IllegalArgumentException("Compiled program exceeds the diagram preview's memory limit.");
            }
        }
        Diagnostic diagnostic(Throwable error) {
            String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            if (error instanceof StructuredDiagram.ParseException parse && parsing != null) {
                return located(parsing, message, parse.offset(), parse.offset());
            }
            if (error instanceof IOException && loadingImport != null) {
                return loadingOwner == selected ? new Diagnostic(message, loadingImport.start(), loadingImport.end())
                        : new Diagnostic(message, -1, -1);
            }
            SourceWorkspace.Document owner = null;
            Diagnostic match = null;
            for (var entry : compiledSources.entrySet()) {
                Diagnostic possible = compilerDiagnostic(entry.getValue(), message, error);
                if (possible.startOffset() < 0) continue;
                if (owner != null) return new Diagnostic(message, -1, -1);
                owner = entry.getKey(); match = possible;
            }
            return owner == null ? new Diagnostic(message, -1, -1) : located(owner, message, match.startOffset(), match.endOffset());
        }
        private Diagnostic located(SourceWorkspace.Document owner, String message, int start, int end) {
            if (owner == selected) return new Diagnostic(message, Math.min(start, candidate.length()), Math.min(end, candidate.length()));
            String qualified = owner.getDisplayName() + ": " + message;
            StructuredDiagram.Node imported = selectedImports.get(owner);
            return imported == null ? new Diagnostic(qualified, -1, -1) : new Diagnostic(qualified, imported.start(), imported.end());
        }
    }

    public static Result validate(String source, Set<GameFeature> features, Map<String, String> libraries) {
        return validate(source, features, libraries, () -> false);
    }

    /** Compiles without execution, input substitution, output contracts, or exercise-specific shape checks. */
    public static Result validate(String source, Set<GameFeature> features, Map<String, String> libraries,
            BooleanSupplier cancelled) {
        return validate(source, features, libraries, LanguageVersion.STRUCTURED, false, cancelled);
    }

    public static Result validate(String source, Set<GameFeature> features, Map<String, String> libraries,
            LanguageVersion version, boolean explicitJumps) {
        return validate(source, features, libraries, version, explicitJumps, () -> false);
    }

    /** The selected world's source profile applies equally to parsing, imports, and compilation. */
    public static Result validate(String source, Set<GameFeature> features, Map<String, String> libraries,
            LanguageVersion version, boolean explicitJumps, BooleanSupplier cancelled) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(features, "features");
        Objects.requireNonNull(libraries, "libraries");
        Objects.requireNonNull(cancelled, "cancelled");
        Objects.requireNonNull(version, "version");
        if (source.length() > MAX_SOURCE) return failed(null, "Source text exceeds the game's resource limit.", -1, -1);
        StructuredDiagram diagram = null;
        StructuredDiagram.ParseException structuralError = null;
        try {
            cancellation(cancelled);
            try { diagram = StructuredDiagram.parse(source, version, explicitJumps); }
            catch (StructuredDiagram.ParseException error) { structuralError = error; }
            if (diagram != null && diagram.nodeCount() > MAX_RENDERED_NODES) {
                return failed(diagram, "This program has too many blocks to display at once (maximum "
                        + MAX_RENDERED_NODES + ").", -1, -1);
            }
            GameSyntax.Analysis syntax = GameSyntax.inspect(source, features, libraries, cancelled,
                    null, null, version, explicitJumps);
            if (structuralError != null) {
                return failed(null, structuralError.getMessage(), structuralError.offset(), structuralError.offset());
            }
            cancellation(cancelled);
            CompilationResult compiled = Compiler.compileWithSourceMap(new SourceDocument(GameSyntax.MAIN, syntax.source()), path -> {
                cancellation(cancelled);
                String imported = syntax.libraries().get(path.toString());
                if (imported == null) throw new IOException("Toolbox library is not available: " + path);
                return new SourceDocument(Path.of("toolbox", path.toString()), imported);
            }, null, version);
            cancellation(cancelled);
            if (compiled.bytecode().size() > GameSyntax.MAX_CODE || compiled.codeStart() > 16_384) {
                return failed(diagram, "Compiled program exceeds the game's memory limit.", -1, -1);
            }
            return new Result(diagram, List.of());
        } catch (GameSyntax.Failure error) {
            SourceLocation location = error.location;
            if (location != null && GameSyntax.MAIN.equals(location.file())) {
                return failed(diagram, error.getMessage(), rawOffset(source, location.startOffset()), rawOffset(source, location.endOffset()));
            }
            if (location != null) {
                Diagnostic imported = importedDiagnostic(source, location.file(), error.getMessage());
                return new Result(diagram, List.of(imported));
            }
            return failed(diagram, error.getMessage(), -1, -1);
        } catch (CancellationException error) {
            throw error;
        } catch (IOException | ParseException | RuntimeException | TokenMgrError error) {
            String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            return new Result(diagram, List.of(compilerDiagnostic(source, message, error)));
        }
    }

    private static Result failed(StructuredDiagram diagram, String message, int start, int end) {
        return new Result(diagram, List.of(new Diagnostic(message, start, end)));
    }

    private static void cancellation(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancellationException();
    }

    private static int rawOffset(String source, int normalized) {
        int raw = 0, logical = 0;
        while (raw < source.length() && logical < normalized) {
            char c = source.charAt(raw++);
            if (c == '\r' && raw < source.length() && source.charAt(raw) == '\n') ++raw;
            ++logical;
        }
        return raw;
    }

    private static Diagnostic importedDiagnostic(String source, Path file, String message) {
        String filename = file == null ? "" : file.getFileName().toString();
        List<Lexeme> tokens = lex(source);
        for (int i = 0; i + 1 < tokens.size(); i++) {
            Lexeme token = tokens.get(i);
            if (token.text.equals("import") && tokens.get(i + 1).text.equals("\"" + filename + "\"")) {
                return new Diagnostic(message, token.start, tokens.get(i + 1).end);
            }
        }
        return new Diagnostic(message, -1, -1);
    }

    /** Compiler diagnostics use expanded line numbers; match original tokens rather than guessing a source line. */
    private static Diagnostic compilerDiagnostic(String source, String message, Throwable error) {
        List<Lexeme> tokens = lex(source);
        Matcher missingMacro = Pattern.compile("Macro substitution failed!\\s*([A-Za-z_][A-Za-z_0-9]*)").matcher(message);
        if (missingMacro.find()) {
            String name = missingMacro.group(1);
            for (int i = 0; i + 1 < tokens.size(); i++) {
                Lexeme token = tokens.get(i);
                if (token.text.equals(name) && tokens.get(i + 1).text.equals("(")
                        && (i == 0 || !tokens.get(i - 1).text.equals("void"))) {
                    return new Diagnostic(message, token.start, token.end);
                }
            }
        }
        String tokenText = null;
        if (error instanceof ParseException parse && parse.currentToken != null && parse.currentToken.next != null) {
            tokenText = parse.currentToken.next.image;
        }
        Matcher unknown = Pattern.compile("Unknown variable ([A-Za-z_][A-Za-z_0-9]*)").matcher(message);
        if (unknown.find()) tokenText = unknown.group(1);
        if (tokenText != null && !tokenText.isEmpty()) {
            Lexeme unique = null;
            for (Lexeme token : tokens) {
                if (!token.text.equals(tokenText)) continue;
                if (unique != null) return new Diagnostic(message, -1, -1);
                unique = token;
            }
            if (unique != null) return new Diagnostic(message, unique.start, unique.end);
        }
        return new Diagnostic(message, -1, -1);
    }

    public static Set<StructuredDiagram.Node> damagedNodes(StructuredDiagram previous, String current, Result result) {
        return damage(previous, current, result).nodes();
    }

    /** Compares with the last valid source, so separate unfinished edits remain marked together. */
    public static Damage damage(StructuredDiagram previous, String current, Result result) {
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(result, "result");
        if (result.valid()) return new Damage(Set.of(), false, false);
        List<Lexeme> before = lex(previous.source());
        List<Lexeme> after = lex(current.length() <= MAX_SOURCE ? current : current.substring(0, MAX_SOURCE));
        Alignment alignment = align(before, after);
        Marker marker = new Marker(previous);
        for (Edit edit : alignment.edits) {
            if (edit.oldStart < edit.oldEnd) {
                for (int i = edit.oldStart; i < edit.oldEnd; i++) marker.token(before.get(i).start);
            } else {
                marker.insertion(boundary(before, edit.oldStart, previous.source().length()));
            }
        }
        // Syntax errors frequently point at a later token or EOF. The changed source
        // spans are more precise than that fallout when structural parsing failed.
        if (result.diagram() != null || alignment.edits.isEmpty()) {
            for (Diagnostic diagnostic : result.errors()) {
                if (diagnostic.startOffset() >= 0) {
                    int index = tokenIndex(after, diagnostic.startOffset());
                    Integer mapped = alignment.newToOld.get(index);
                    if (mapped != null) marker.token(before.get(mapped).start);
                    else {
                        boolean located = false;
                        for (Edit edit : alignment.edits) {
                            if (index >= edit.newStart && index < edit.newEnd) {
                                if (edit.oldStart < edit.oldEnd) marker.token(before.get(edit.oldStart).start);
                                else marker.insertion(boundary(before, edit.oldStart, previous.source().length()));
                                located = true;
                                break;
                            }
                        }
                        if (!located) marker.insertion(previous.source().length());
                    }
                }
            }
        }
        if (marker.nodes.isEmpty() && !marker.main && !marker.preamble) {
            // Resource errors and unmapped compiler errors belong to the sequence,
            // not to an arbitrary, otherwise unchanged sibling statement.
            marker.main = true;
        }
        return new Damage(marker.nodes, marker.main, marker.preamble);
    }

    private static int boundary(List<Lexeme> tokens, int index, int end) {
        return index < tokens.size() ? tokens.get(index).start : end;
    }

    private static int tokenIndex(List<Lexeme> tokens, int offset) {
        for (int i = 0; i < tokens.size(); i++) if (tokens.get(i).end > offset) return i;
        return tokens.size();
    }

    private static final class Marker {
        final StructuredDiagram diagram;
        final Set<StructuredDiagram.Node> nodes = new HashSet<>();
        final Map<StructuredDiagram.Node, StructuredDiagram.Node> parents = new IdentityHashMap<>();
        boolean main;
        boolean preamble;

        Marker(StructuredDiagram diagram) {
            this.diagram = diagram;
            collect(diagram.preamble(), null);
            collect(diagram.main(), null);
        }

        void collect(StructuredDiagram.Sequence sequence, StructuredDiagram.Node parent) {
            for (StructuredDiagram.Node node : sequence.nodes()) {
                parents.put(node, parent);
                if (node.body() != null) collect(node.body(), node);
                if (node.alternative() != null) collect(node.alternative(), node);
            }
        }

        void add(StructuredDiagram.Node node) {
            while (node != null) { nodes.add(node); node = parents.get(node); }
        }

        void token(int offset) {
            StructuredDiagram.Node found = innermost(offset, false);
            if (found != null) add(found); else root(offset);
        }

        void insertion(int offset) {
            StructuredDiagram.Node found = innermost(offset, true);
            if (found != null) add(found); else root(offset);
        }

        StructuredDiagram.Node innermost(int offset, boolean insertion) {
            StructuredDiagram.Node result = null;
            for (StructuredDiagram.Node node : parents.keySet()) {
                if ((insertion ? node.start() < offset : node.start() <= offset) && offset < node.end()
                        && (result == null || node.end() - node.start() < result.end() - result.start())) result = node;
            }
            return result;
        }

        void root(int offset) {
            if (offset < diagram.main().start()) preamble = true; else main = true;
        }
    }

    private record Lexeme(String text, int start, int end) { }
    private record Edit(int oldStart, int oldEnd, int newStart, int newEnd) { }
    private record Match(int oldIndex, int newIndex) { }
    private record Alignment(List<Edit> edits, Map<Integer, Integer> newToOld) { }

    /** Tolerant tokens for differences, not a parser; trivia does not destroy a structure. */
    private static List<Lexeme> lex(String source) {
        List<Lexeme> result = new ArrayList<>();
        int cursor = 0;
        while (cursor < source.length()) {
            char c = source.charAt(cursor);
            if (Character.isWhitespace(c)) { ++cursor; continue; }
            if (c == '/' && cursor + 1 < source.length() && source.charAt(cursor + 1) == '/') {
                while (cursor < source.length() && source.charAt(cursor) != '\n' && source.charAt(cursor) != '\r') ++cursor;
                continue;
            }
            int start = cursor++;
            if (Character.isLetterOrDigit(c) || c == '_') {
                while (cursor < source.length() && (Character.isLetterOrDigit(source.charAt(cursor)) || source.charAt(cursor) == '_')) ++cursor;
            } else if (c == '"') {
                while (cursor < source.length() && source.charAt(cursor) != '"' && source.charAt(cursor) != '\n' && source.charAt(cursor) != '\r') ++cursor;
                if (cursor < source.length() && source.charAt(cursor) == '"') ++cursor;
            } else if ((c == '=' || c == '!') && cursor < source.length() && source.charAt(cursor) == '=') ++cursor;
            result.add(new Lexeme(source.substring(start, cursor), start, cursor));
        }
        return result;
    }

    /** Myers token alignment, bounded to 256 inserted/deleted tokens before a conservative fallback. */
    private static Alignment align(List<Lexeme> old, List<Lexeme> current) {
        int n = old.size(), m = current.size();
        int limit = Math.min(256, n + m), shift = limit + 1;
        int[] v = new int[2 * limit + 3];
        List<int[]> trace = new ArrayList<>();
        int distance = -1;
        outer: for (int d = 0; d <= limit; d++) {
            for (int k = -d; k <= d; k += 2) {
                int x = k == -d || k != d && v[shift + k - 1] < v[shift + k + 1]
                        ? v[shift + k + 1] : v[shift + k - 1] + 1;
                int y = x - k;
                while (x < n && y < m && old.get(x).text.equals(current.get(y).text)) { ++x; ++y; }
                v[shift + k] = x;
                if (x >= n && y >= m) { trace.add(v.clone()); distance = d; break outer; }
            }
            trace.add(v.clone());
        }
        List<Match> matches = new ArrayList<>();
        if (distance >= 0) {
            int x = n, y = m;
            for (int d = distance; d > 0; d--) {
                int[] previous = trace.get(d - 1);
                int k = x - y;
                int previousK = k == -d || k != d && previous[shift + k - 1] < previous[shift + k + 1] ? k + 1 : k - 1;
                int previousX = previous[shift + previousK], previousY = previousX - previousK;
                while (x > previousX && y > previousY) matches.add(new Match(--x, --y));
                x = previousX;
                y = previousY;
            }
            while (x > 0 && y > 0) matches.add(new Match(--x, --y));
            Collections.reverse(matches);
        } else {
            int head = 0;
            while (head < n && head < m && old.get(head).text.equals(current.get(head).text)) {
                matches.add(new Match(head, head)); ++head;
            }
            int tail = 0;
            while (n - tail > head && m - tail > head && old.get(n - tail - 1).text.equals(current.get(m - tail - 1).text)) ++tail;
            for (int i = tail; i > 0; i--) matches.add(new Match(n - i, m - i));
        }
        List<Edit> edits = new ArrayList<>();
        Map<Integer, Integer> map = new HashMap<>();
        int previousOld = 0, previousNew = 0;
        for (Match match : matches) {
            if (match.oldIndex > previousOld || match.newIndex > previousNew) {
                edits.add(new Edit(previousOld, match.oldIndex, previousNew, match.newIndex));
            }
            map.put(match.newIndex, match.oldIndex);
            previousOld = match.oldIndex + 1;
            previousNew = match.newIndex + 1;
        }
        if (previousOld < n || previousNew < m) edits.add(new Edit(previousOld, n, previousNew, m));
        return new Alignment(List.copyOf(edits), Map.copyOf(map));
    }
}

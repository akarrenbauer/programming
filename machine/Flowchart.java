package machine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** A source-owned control-flow graph for the foundations world's editable flowchart. */
public final class Flowchart {
    public enum Kind { START, PROCESS, DECISION, HALT, END, JOIN }

    /** Synthetic vertices have no owner; all editable vertices retain the exact source-tree node. */
    public record Vertex(int id, Kind kind, String text, StructuredDiagram.Node owner, int start, int end) { }
    public record Edge(int from, int to, String label) { }

    /** An exact half-open UTF-16 range in the original source, including any internal trivia. */
    public record SourceSpan(int start, int end) {
        public SourceSpan {
            if (start < 0 || end < start) throw new IllegalArgumentException("Invalid source span");
        }
        public String text(String source) { return source.substring(start, end); }
        public String replace(String source, String replacement) {
            return source.substring(0, start) + Objects.requireNonNull(replacement, "replacement") + source.substring(end);
        }
    }

    /** Display variable is canonical; operand/target retain the exact editable source spans. */
    public record JumpInfo(StructuredDiagram.Node owner, SourceSpan operand, SourceSpan target,
            String variable, String targetLabel) { }

    public record ComparisonInfo(StructuredDiagram.Node owner, SourceSpan operand, SourceSpan operator,
            SourceSpan right, String operandText, String rightText, boolean canonicalNotEqual) { }

    /** A structured post-test return, distinct from an explicit JNZ token in the source. */
    public record LoopJumpInfo(StructuredDiagram.Node owner, int target, boolean retargetable) { }

    /** Each declaration is retained, including aliases and shadowed duplicate declarations. */
    public record LabelInfo(String name, SourceSpan nameSpan, SourceSpan declarationSpan,
            StructuredDiagram.Node owner, int vertex) { }

    private final StructuredDiagram document;
    private final List<Vertex> vertices;
    private final List<Edge> edges;
    private final Map<String, Integer> labels;
    private final Map<Integer, JumpInfo> jumpInfos;
    private final Map<Integer, ComparisonInfo> comparisonInfos;
    private final Map<Integer, LoopJumpInfo> loopJumpInfos;
    private final List<LabelInfo> labelDeclarations;
    private final Map<String, LabelInfo> labelInfos;
    private final Map<StructuredDiagram.Node, NodeSyntax> syntax;
    private final Map<StructuredDiagram.Node, Integer> entryVertices;

    private Flowchart(StructuredDiagram document, List<Vertex> vertices, List<Edge> edges, Map<String, Integer> labels,
            Map<Integer, JumpInfo> jumpInfos, List<LabelInfo> labelDeclarations, Map<String, LabelInfo> labelInfos,
            Map<StructuredDiagram.Node, NodeSyntax> syntax, Map<Integer, ComparisonInfo> comparisonInfos,
            Map<StructuredDiagram.Node, Integer> entryVertices, Map<Integer, LoopJumpInfo> loopJumpInfos) {
        this.document = document;
        this.vertices = List.copyOf(vertices);
        this.edges = List.copyOf(edges);
        this.labels = Collections.unmodifiableMap(new LinkedHashMap<>(labels));
        this.jumpInfos = Map.copyOf(jumpInfos);
        this.comparisonInfos = Map.copyOf(comparisonInfos);
        this.loopJumpInfos = Map.copyOf(loopJumpInfos);
        this.labelDeclarations = List.copyOf(labelDeclarations);
        this.labelInfos = Collections.unmodifiableMap(new LinkedHashMap<>(labelInfos));
        this.syntax = Collections.unmodifiableMap(new IdentityHashMap<>(syntax));
        this.entryVertices = Collections.unmodifiableMap(new IdentityHashMap<>(entryVertices));
    }

    /** Builds the main program; declarations and helper definitions remain available in document().preamble(). */
    public static Flowchart from(StructuredDiagram document) {
        checkSize(document);
        return new Builder(document).build();
    }

    private static void checkSize(StructuredDiagram document) {
        Objects.requireNonNull(document, "document");
        if (document.nodeCount() > DiagramValidation.MAX_RENDERED_NODES) {
            throw new IllegalArgumentException("The diagram can display at most " + DiagramValidation.MAX_RENDERED_NODES + " blocks.");
        }
    }

    public StructuredDiagram document() { return document; }
    /** Source order, with START first; an implicit END is last only when execution can fall through. */
    public List<Vertex> vertices() { return vertices; }
    public List<Edge> edges() { return edges; }
    public Map<String, Integer> labels() { return labels; }
    public Vertex vertex(int id) { return vertices.get(id); }

    /** Null for structured conditions and ordinary statements; the graph's truth values never invert. */
    public JumpInfo jumpInfo(Vertex vertex) { return jumpInfo(vertex.id()); }
    public JumpInfo jumpInfo(int vertex) { return jumpInfos.get(vertex); }
    public ComparisonInfo comparisonInfo(Vertex vertex) { return comparisonInfo(vertex.id()); }
    public ComparisonInfo comparisonInfo(int vertex) { return comparisonInfos.get(vertex); }
    public LoopJumpInfo loopJumpInfo(Vertex vertex) { return loopJumpInfo(vertex.id()); }
    public LoopJumpInfo loopJumpInfo(int vertex) { return loopJumpInfos.get(vertex); }
    public boolean isJumpEdge(Edge edge) { return jumpInfos.containsKey(edge.from()) && edge.label().equals("true"); }
    /** Both explicit jumps and the compiler's post-test return leave to the right and enter a target junction. */
    public boolean isJumpRoute(Edge edge) {
        return edge.label().equals("true") && (jumpInfos.containsKey(edge.from()) || loopJumpInfos.containsKey(edge.from()));
    }
    public boolean canRetargetJump(int vertex) {
        return jumpInfos.containsKey(vertex) || loopJumpInfos.containsKey(vertex) && loopJumpInfos.get(vertex).retargetable();
    }
    public List<LabelInfo> labelDeclarations() { return labelDeclarations; }
    /** The final source declaration, matching the compiler's duplicate-label behavior. */
    public LabelInfo labelInfo(String name) { return labelInfos.get(name); }
    /** The built-in entry label belongs to the first statement's incoming junction, never the START oval. */
    public boolean isImplicitBeginTarget(int vertex) {
        return document.hasMain() && !labelInfos.containsKey("begin") && Objects.equals(labels.get("begin"), vertex);
    }
    /** The actual entry marker; helper-only documents have no implicit begin declaration. */
    public SourceSpan beginSpan() {
        return document.hasMain() ? new SourceSpan(document.main().start() - "begin:".length(), document.main().start()) : null;
    }
    /** Includes all label declarations plus intervening/trailing comments and whitespace; empty if unlabeled. */
    public SourceSpan labelPrefix(StructuredDiagram.Node node) { return syntax(node).prefix; }
    /** Full statement/control text without labels; unlike editStart this starts at if/while/do, not its condition. */
    public SourceSpan statementSpan(StructuredDiagram.Node node) { return syntax(node).statement; }

    /** The source statement's entry, never a do/while footer or a structured continuation join. */
    public int entryVertex(StructuredDiagram.Node owner) { return entryVertices.getOrDefault(owner, -1); }
    public boolean canTarget(Vertex vertex) {
        return vertex != null && vertex.id() >= 0 && vertex.id() < vertices.size()
                && vertices.get(vertex.id()) == vertex && vertex.owner() != null
                && entryVertex(vertex.owner()) == vertex.id();
    }
    public boolean canTarget(int vertex) {
        return vertex >= 0 && vertex < vertices.size() && canTarget(vertices.get(vertex));
    }

    /** Canonical taken-branch destination; presentation can invert a forward jump without changing this edge. */
    public Vertex jumpTarget(int jumpVertex) {
        LoopJumpInfo loop = loopJumpInfo(jumpVertex);
        if (loop != null) return vertex(loop.target());
        JumpInfo jump = jumpInfo(jumpVertex);
        if (jump == null) throw new IllegalArgumentException("Choose a JNZ arrow to retarget.");
        return vertex(labels.get(jump.targetLabel()));
    }
    public Vertex jumpTarget(Vertex jumpVertex) { return jumpTarget(jumpVertex.id()); }
    /** Self jumps count as backward because their target junction is above their own decision. */
    public boolean isForwardJump(int jumpVertex) { return jumpTarget(jumpVertex).id() > jumpVertex; }
    public boolean isForwardJump(Vertex jumpVertex) { return isForwardJump(jumpVertex.id()); }

    public String retargetJump(int jumpVertex, int targetVertex) {
        JumpInfo jump = jumpInfo(jumpVertex);
        LoopJumpInfo loop = loopJumpInfo(jumpVertex);
        if (jump == null && loop == null) throw new IllegalArgumentException("Choose a JNZ arrow to retarget.");
        if (!canTarget(targetVertex)) throw new IllegalArgumentException("Choose an editable statement entry as the jump target.");
        return retargetJump(document, (jump == null ? loop.owner() : jump.owner()).start(), vertex(targetVertex).owner().start());
    }
    public String retargetJump(StructuredDiagram.Node jump, StructuredDiagram.Node target) {
        if (entryVertex(jump) >= 0 && jump.kind() == StructuredDiagram.Kind.DO_WHILE) {
            if (entryVertex(target) < 0) throw new IllegalArgumentException("Choose an editable statement entry as the jump target.");
            return retargetJump(document, jump.start(), target.start());
        }
        return retargetJump(entryVertex(jump), entryVertex(target));
    }

    /**
     * Atomically moves a JNZ's destination label, preserving every reference to that label.
     * A direct variable != 0 post-test is converted to a bare block and JNZ only if its return target changes.
     * Pending label and target edits are applied together; the referenced destination must resolve.
     * Offsets must be exact main-program node starts in this candidate, after any other pending edits.
     */
    public static String retargetJump(StructuredDiagram candidate, int jumpStart, int targetStart) {
        checkSize(candidate);
        Map<Integer, StructuredDiagram.Node> nodes = new LinkedHashMap<>();
        Map<StructuredDiagram.Node, NodeSyntax> syntax = new IdentityHashMap<>();
        Map<String, StructuredDiagram.Node> labels = new LinkedHashMap<>();
        if (candidate.hasMain() && !candidate.main().nodes().isEmpty()) labels.put("begin", candidate.main().nodes().get(0));
        indexTargets(candidate.main(), candidate.source(), nodes, syntax, labels);
        StructuredDiagram.Node jumpNode = nodes.get(jumpStart), targetNode = nodes.get(targetStart);
        if (jumpNode != null && jumpNode.kind() == StructuredDiagram.Kind.DO_WHILE) {
            if (targetNode == null) throw new IllegalArgumentException("Choose an editable statement entry as the jump target.");
            return retargetLoop(candidate, jumpNode, targetNode, syntax, labels);
        }
        JumpParts jump = jumpNode == null ? null : syntax.get(jumpNode).jump;
        if (jump == null) throw new IllegalArgumentException("Choose a JNZ arrow to retarget.");
        if (targetNode == null) throw new IllegalArgumentException("Choose an editable statement entry as the jump target.");
        if (!labels.containsKey(jump.targetLabel)) {
            throw new IllegalArgumentException("Update the jump target to match its label before moving the arrow.");
        }
        // Keep the exact existing alias when it already reaches the requested node.
        if (labels.get(jump.targetLabel) == targetNode) return candidate.source();
        return applyEdits(candidate.source(), moveLabel(candidate, targetNode, syntax, jump.targetLabel));
    }

    private static String explicitLabel(StructuredDiagram.Node target, Map<StructuredDiagram.Node, NodeSyntax> syntax,
            Map<String, StructuredDiagram.Node> labels) {
        for (LabelToken declared : syntax.get(target).labels) if (labels.get(declared.name) == target) return declared.name;
        return null;
    }

    /** Keep the moved declaration's internal trivia, but leave trailing and shadowed-label comments in place. */
    private static List<SourceEdit> moveLabel(StructuredDiagram candidate, StructuredDiagram.Node target,
            Map<StructuredDiagram.Node, NodeSyntax> syntax, String name) {
        List<LabelToken> declarations = syntax.values().stream().flatMap(parts -> parts.labels.stream())
                .filter(label -> label.name.equals(name))
                .sorted(Comparator.comparingInt(label -> label.declarationSpan.start())).toList();
        LabelToken winner = declarations.isEmpty() ? null : declarations.get(declarations.size() - 1);
        boolean restoreBegin = name.equals("begin") && !candidate.main().nodes().isEmpty()
                && target == candidate.main().nodes().get(0);
        List<SourceEdit> edits = new ArrayList<>();
        for (LabelToken label : declarations) {
            if (label == winner && !restoreBegin) {
                edits.add(new SourceEdit(label.declarationSpan, ""));
            } else {
                // Later duplicate declarations would otherwise steal a backwards-moved destination.
                edits.add(new SourceEdit(label.nameSpan, ""));
                edits.add(new SourceEdit(new SourceSpan(label.declarationSpan.end() - 1, label.declarationSpan.end()), ""));
            }
        }
        if (!restoreBegin) {
            String declaration = winner == null ? "begin :" : winner.declarationSpan.text(candidate.source());
            edits.add(new SourceEdit(new SourceSpan(target.start(), target.start()), declaration + " "));
        }
        return edits;
    }

    private static String freshLabel(String source) {
        Set<String> occupied = new LinkedHashSet<>();
        for (Lexeme token : tokens(source, 0, source.length())) occupied.add(token.text);
        String label = "jumpTarget";
        for (int suffix = 2; occupied.contains(label); ++suffix) label = "jumpTarget" + suffix;
        return label;
    }

    private static boolean directlyLowerable(ComparisonInfo condition) {
        return condition.canonicalNotEqual() && condition.rightText().chars().allMatch(c -> c == '0')
                && !Character.isDigit(condition.operandText().charAt(0));
    }

    private record SourceEdit(SourceSpan span, String text) { }

    /** Keep every body byte and comment; only the control tokens change when the return head moves. */
    private static String retargetLoop(StructuredDiagram candidate, StructuredDiagram.Node loop, StructuredDiagram.Node target,
            Map<StructuredDiagram.Node, NodeSyntax> syntax, Map<String, StructuredDiagram.Node> labels) {
        String source = candidate.source();
        if (loop == target) return source;
        NodeSyntax parts = syntax.get(loop);
        ComparisonInfo condition = parts.comparison;
        if (!directlyLowerable(condition)) {
            throw new IllegalArgumentException("Only do/while conditions of the form variable != 0 can be retargeted as JNZ.");
        }
        String label = explicitLabel(loop, syntax, labels);
        List<SourceEdit> edits = new ArrayList<>();
        if (label == null) {
            label = freshLabel(source);
            edits.add(new SourceEdit(new SourceSpan(target.start(), target.start()), label + ": "));
        } else edits.addAll(moveLabel(candidate, target, syntax, label));
        Lexeme keyword = tokens(source, parts.statement.start(), loop.body().start()).get(0);
        List<Lexeme> footer = tokens(source, loop.body().end(), loop.end());
        edits.add(new SourceEdit(keyword.span, "")); // do { body } becomes the same bare block.
        edits.add(new SourceEdit(footer.get(1).span, "JNZ")); // after the body's closing brace
        edits.add(new SourceEdit(footer.get(2).span, " ")); // opening parenthesis
        edits.add(new SourceEdit(condition.operator(), " "));
        edits.add(new SourceEdit(condition.right(), label));
        edits.add(new SourceEdit(footer.get(footer.size() - 2).span, "")); // closing parenthesis
        return applyEdits(source, edits);
    }

    private static String applyEdits(String source, List<SourceEdit> edits) {
        // At equal starts delete the old prefix before inserting its replacement.
        edits.sort(Comparator.comparingInt((SourceEdit edit) -> edit.span.start()).reversed()
                .thenComparing(Comparator.comparingInt((SourceEdit edit) -> edit.span.end()).reversed()));
        StringBuilder changed = new StringBuilder(source);
        for (SourceEdit edit : edits) changed.replace(edit.span.start(), edit.span.end(), edit.text);
        return changed.toString();
    }

    private static void indexTargets(StructuredDiagram.Sequence sequence, String source,
            Map<Integer, StructuredDiagram.Node> nodes, Map<StructuredDiagram.Node, NodeSyntax> syntax,
            Map<String, StructuredDiagram.Node> labels) {
        for (StructuredDiagram.Node node : sequence.nodes()) {
            nodes.put(node.start(), node);
            NodeSyntax parts = analyze(node, source);
            syntax.put(node, parts);
            for (LabelToken label : parts.labels) labels.put(label.name, node);
            if (node.body() != null) indexTargets(node.body(), source, nodes, syntax, labels);
            if (node.alternative() != null) indexTargets(node.alternative(), source, nodes, syntax, labels);
        }
    }

    private NodeSyntax syntax(StructuredDiagram.Node node) {
        NodeSyntax result = syntax.get(node);
        if (result == null) throw new IllegalArgumentException("Use a node from the current diagram.");
        return result;
    }

    private record Port(int from, String label) { }
    private record Fragment(int entry, List<Port> exits) { }
    private record Jump(int from, String target) { }
    private record Lexeme(String text, SourceSpan span) { }
    private record LabelToken(String name, SourceSpan nameSpan, SourceSpan declarationSpan) { }
    private record JumpParts(SourceSpan operand, SourceSpan target, String variable, String targetLabel) { }
    private record NodeSyntax(SourceSpan prefix, SourceSpan statement, List<LabelToken> labels, JumpParts jump,
            ComparisonInfo comparison, boolean halt) { }

    /** Token offsets come from original source, so stripping comments never shifts editable ranges. */
    private static NodeSyntax analyze(StructuredDiagram.Node node, String source) {
        List<Lexeme> tokens = tokens(source, node.start(), node.end());
        List<LabelToken> labels = new ArrayList<>();
        int command = 0;
        while (command + 1 < tokens.size() && tokens.get(command + 1).text.equals(":")) {
            Lexeme name = tokens.get(command), colon = tokens.get(command + 1);
            labels.add(new LabelToken(name.text, name.span, new SourceSpan(name.span.start, colon.span.end)));
            command += 2;
        }
        Lexeme first = tokens.get(command);
        SourceSpan prefix = new SourceSpan(node.start(), first.span.start);
        SourceSpan statement = new SourceSpan(first.span.start, node.end());
        JumpParts jump = null;
        if (first.text.equals("JNZ")) {
            int operand = command + 1;
            int last = operand;
            if (tokens.get(last + 1).text.equals("[")) last += 3;
            StringBuilder variable = new StringBuilder();
            for (int index = operand; index <= last; index++) variable.append(tokens.get(index).text);
            Lexeme target = tokens.get(last + 1);
            jump = new JumpParts(new SourceSpan(tokens.get(operand).span.start, tokens.get(last).span.end),
                    target.span, variable.toString(), target.text);
        }
        ComparisonInfo comparison = null;
        if (node.kind() == StructuredDiagram.Kind.IF || node.kind() == StructuredDiagram.Kind.WHILE
                || node.kind() == StructuredDiagram.Kind.DO_WHILE) {
            List<Lexeme> condition = tokens(source, node.editStart(), node.editEnd());
            int operator = 1;
            while (!condition.get(operator).text.equals("==") && !condition.get(operator).text.equals("!=")) ++operator;
            StringBuilder operand = new StringBuilder();
            for (int index = 0; index < operator; index++) operand.append(condition.get(index).text);
            Lexeme op = condition.get(operator), right = condition.get(operator + 1);
            comparison = new ComparisonInfo(node, new SourceSpan(condition.get(0).span.start, condition.get(operator - 1).span.end),
                    op.span, right.span, operand.toString(), right.text, op.text.equals("!="));
        }
        return new NodeSyntax(prefix, statement, List.copyOf(labels), jump, comparison, first.text.equals("HALT"));
    }

    private static List<Lexeme> tokens(String source, int start, int end) {
        List<Lexeme> tokens = new ArrayList<>();
        int cursor = start;
        while (cursor < end) {
            char c = source.charAt(cursor);
            if (Character.isWhitespace(c)) { ++cursor; continue; }
            if (c == '/' && cursor + 1 < end && source.charAt(cursor + 1) == '/') {
                while (cursor < end && source.charAt(cursor) != '\r' && source.charAt(cursor) != '\n') ++cursor;
                continue;
            }
            int tokenStart = cursor++;
            if (Character.isLetterOrDigit(c) || c == '_') {
                while (cursor < end && (Character.isLetterOrDigit(source.charAt(cursor)) || source.charAt(cursor) == '_')) ++cursor;
            } else if (c == '"') {
                while (cursor < end && source.charAt(cursor) != '"') ++cursor;
                if (cursor < end) ++cursor;
            } else if ((c == '=' || c == '!') && cursor < end && source.charAt(cursor) == '=') ++cursor;
            tokens.add(new Lexeme(source.substring(tokenStart, cursor), new SourceSpan(tokenStart, cursor)));
        }
        return tokens;
    }

    private static final class Builder {
        private final StructuredDiagram document;
        private final List<Vertex> vertices = new ArrayList<>();
        private final List<Edge> edges = new ArrayList<>();
        private final Map<String, Integer> labels = new LinkedHashMap<>();
        private final List<Jump> jumps = new ArrayList<>();
        private final Map<Integer, JumpInfo> jumpInfos = new LinkedHashMap<>();
        private final Map<Integer, ComparisonInfo> comparisonInfos = new LinkedHashMap<>();
        private final Map<Integer, LoopJumpInfo> loopJumpInfos = new LinkedHashMap<>();
        private final List<LabelInfo> labelDeclarations = new ArrayList<>();
        private final Map<String, LabelInfo> labelInfos = new LinkedHashMap<>();
        private final Map<StructuredDiagram.Node, NodeSyntax> syntax = new IdentityHashMap<>();
        private final Map<StructuredDiagram.Node, Integer> entryVertices = new IdentityHashMap<>();

        Builder(StructuredDiagram document) {
            this.document = document;
            index(document.preamble());
            index(document.main());
        }

        void index(StructuredDiagram.Sequence sequence) {
            for (StructuredDiagram.Node node : sequence.nodes()) {
                syntax.put(node, analyze(node, document.source()));
                if (node.body() != null) index(node.body());
                if (node.alternative() != null) index(node.alternative());
            }
        }

        Flowchart build() {
            int start = synthetic(Kind.START, "begin", Math.max(0, document.main().start() - 6), document.main().start());
            Fragment main = sequence(document.main());
            int entry = main.entry;
            if (main.entry < 0 || !main.exits.isEmpty()) {
                int end = synthetic(Kind.END, "End", document.source().length(), document.source().length());
                if (entry < 0) entry = end;
                connect(main.exits, end);
            }
            if (document.hasMain()) labels.putIfAbsent("begin", entry);
            // The compiler's initial instruction pointer shares the begin label;
            // a later legal `begin :` declaration relocates program startup too.
            edge(start, document.hasMain() ? labels.get("begin") : entry, "");
            Set<String> unresolved = new LinkedHashSet<>();
            for (Jump jump : jumps) {
                Integer target = labels.get(jump.target);
                if (target == null) unresolved.add(jump.target); else edge(jump.from, target, "true");
            }
            if (!unresolved.isEmpty()) throw new IllegalArgumentException("Unresolved labels: " + String.join(", ", unresolved));
            return new Flowchart(document, vertices, edges, labels, jumpInfos, labelDeclarations, labelInfos, syntax,
                    comparisonInfos, entryVertices, loopJumpInfos);
        }

        Fragment sequence(StructuredDiagram.Sequence sequence) {
            int entry = -1;
            List<Port> exits = List.of();
            for (StructuredDiagram.Node node : sequence.nodes()) {
                Fragment next = node(node);
                if (entry < 0) entry = next.entry;
                connect(exits, next.entry);
                exits = next.exits;
            }
            return new Fragment(entry, exits);
        }

        Fragment node(StructuredDiagram.Node node) {
            return switch (node.kind()) {
                case IF -> conditional(node);
                case WHILE -> whileLoop(node);
                case DO_WHILE -> doLoop(node);
                case BLOCK -> block(node);
                default -> statement(node);
            };
        }

        Fragment conditional(StructuredDiagram.Node node) {
            int decision = vertex(Kind.DECISION, node.editText(), node);
            bind(node, decision);
            Fragment yes = sequence(node.body());
            Fragment no = node.alternative() == null ? new Fragment(-1, List.of()) : sequence(node.alternative());
            int join = vertex(Kind.JOIN, "", node);
            edge(decision, yes.entry < 0 ? join : yes.entry, "true");
            edge(decision, no.entry < 0 ? join : no.entry, "false");
            connect(yes.exits, join);
            connect(no.exits, join);
            return flowing(decision, join);
        }

        Fragment whileLoop(StructuredDiagram.Node node) {
            int decision = vertex(Kind.DECISION, node.editText(), node);
            bind(node, decision);
            Fragment body = sequence(node.body());
            int join = vertex(Kind.JOIN, "", node);
            edge(decision, body.entry < 0 ? decision : body.entry, "true");
            connect(body.exits, decision);
            edge(decision, join, "false");
            return flowing(decision, join);
        }

        Fragment doLoop(StructuredDiagram.Node node) {
            int entry = vertex(Kind.JOIN, "", node);
            bind(node, entry);
            Fragment body = sequence(node.body());
            int decision = vertex(Kind.DECISION, node.editText(), node);
            edge(entry, body.entry < 0 ? decision : body.entry, "");
            connect(body.exits, decision);
            edge(decision, entry, "true");
            loopJumpInfos.put(decision, new LoopJumpInfo(node, entry, directlyLowerable(syntax.get(node).comparison)));
            return new Fragment(entry, List.of(new Port(decision, "false")));
        }

        Fragment block(StructuredDiagram.Node node) {
            int entry = vertex(Kind.JOIN, "", node);
            bind(node, entry);
            Fragment body = sequence(node.body());
            if (body.entry < 0) return flowing(entry, entry);
            edge(entry, body.entry, "");
            return new Fragment(entry, body.exits);
        }

        Fragment statement(StructuredDiagram.Node node) {
            NodeSyntax statement = syntax.get(node);
            JumpParts jump = statement.jump;
            if (jump != null) {
                int decision = vertex(Kind.DECISION, jump.variable + " != 0", node);
                bind(node, decision);
                jumps.add(new Jump(decision, jump.targetLabel));
                jumpInfos.put(decision, new JumpInfo(node, jump.operand, jump.target, jump.variable, jump.targetLabel));
                return new Fragment(decision, List.of(new Port(decision, "false")));
            }
            int vertex = vertex(statement.halt ? Kind.HALT : Kind.PROCESS, node.editText(), node);
            bind(node, vertex);
            return statement.halt ? new Fragment(vertex, List.of()) : flowing(vertex, vertex);
        }

        /** Labels have program-wide scope inside main, including nested blocks, as in CodeSegment. */
        void bind(StructuredDiagram.Node node, int entry) {
            entryVertices.put(node, entry);
            // A repeated declaration moves the compiler's shared label to its last
            // source occurrence, so bind before visiting child declarations too.
            for (LabelToken label : syntax.get(node).labels) {
                labels.put(label.name, entry);
                LabelInfo info = new LabelInfo(label.name, label.nameSpan, label.declarationSpan, node, entry);
                labelDeclarations.add(info);
                labelInfos.put(label.name, info);
            }
        }

        int vertex(Kind kind, String text, StructuredDiagram.Node owner) {
            int id = vertices.size();
            vertices.add(new Vertex(id, kind, text, owner, owner.start(), owner.end()));
            if (kind == Kind.DECISION && syntax.get(owner).comparison != null) comparisonInfos.put(id, syntax.get(owner).comparison);
            return id;
        }

        int synthetic(Kind kind, String text, int start, int end) {
            int id = vertices.size();
            vertices.add(new Vertex(id, kind, text, null, start, end));
            return id;
        }

        Fragment flowing(int entry, int exit) { return new Fragment(entry, List.of(new Port(exit, ""))); }
        void connect(List<Port> exits, int target) { for (Port exit : exits) edge(exit.from, target, exit.label); }
        void edge(int from, int to, String label) { edges.add(new Edge(from, to, label)); }
    }
}

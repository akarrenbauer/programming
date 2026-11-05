package machine;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A source-backed tree for flowcharts and Nassi–Shneiderman diagrams. Each edit replaces only
 * its source span, so comments, declarations and formatting elsewhere survive.
 * This parser checks structure; the game checker remains responsible for earned
 * syntax, names, resource limits and the execution contract.
 */
public final class StructuredDiagram {
    public enum Kind { STATEMENT, DECLARATION, IMPORT, MACRO, IF, WHILE, DO_WHILE, BLOCK }

    public static final class ParseException extends Exception {
        private final int offset;

        private ParseException(String message, int offset, String source) {
            super(message + " (line " + (1 + source.substring(0, offset).chars().filter(c -> c == '\n').count()) + ")");
            this.offset = offset;
        }

        public int offset() { return offset; }
    }

    /** A sequence has an insertion position even when its body is empty. */
    public static final class Sequence {
        private final List<Node> nodes;
        private final int start;
        private final int end;
        private final String indent;

        private Sequence(List<Node> nodes, int start, int end, String indent) {
            this.nodes = List.copyOf(nodes);
            this.start = start;
            this.end = end;
            this.indent = indent;
        }

        public List<Node> nodes() { return nodes; }
        public int start() { return start; }
        public int end() { return end; }
        public int insertionOffset() { return end; }
    }

    public static final class Node {
        private final Kind kind;
        private final int start;
        private final int end;
        private final int editStart;
        private final int editEnd;
        private final String text;
        private final String editText;
        private final String labels;
        private final Sequence body;
        private final Sequence alternative;

        private Node(Kind kind, int start, int end, int editStart, int editEnd,
                Sequence body, Sequence alternative, String labels, String source) {
            this.kind = kind;
            this.start = start;
            this.end = end;
            this.editStart = editStart;
            this.editEnd = editEnd;
            this.text = source.substring(start, end);
            this.editText = source.substring(editStart, editEnd);
            this.labels = labels;
            this.body = body;
            this.alternative = alternative;
        }

        public Kind kind() { return kind; }
        public int start() { return start; }
        public int end() { return end; }
        public int editStart() { return editStart; }
        public int editEnd() { return editEnd; }
        public String text() { return text; }
        public String editText() { return editText; }
        public String labels() { return labels; }
        public Sequence body() { return body; }
        public Sequence alternative() { return alternative; }
    }

    private final String source;
    private final Sequence preamble;
    private final Sequence main;
    private final LanguageVersion languageVersion;
    private final boolean explicitJumps;
    private final boolean optionalMain;
    private final boolean hasMain;
    private final Set<Node> nodes = new HashSet<>();
    private final Set<Sequence> sequences = new HashSet<>();

    private StructuredDiagram(String source, Sequence preamble, Sequence main, LanguageVersion languageVersion,
            boolean explicitJumps, boolean optionalMain, boolean hasMain) {
        this.source = source;
        this.preamble = preamble;
        this.main = main;
        this.languageVersion = languageVersion;
        this.explicitJumps = explicitJumps;
        this.optionalMain = optionalMain;
        this.hasMain = hasMain;
        collect(preamble);
        collect(main);
    }

    public static StructuredDiagram parse(String source) throws ParseException {
        return parse(source, LanguageVersion.STRUCTURED, false);
    }

    /** Flowcharts can retain explicit machine jumps and the earlier meaning of do. */
    public static StructuredDiagram parse(String source, LanguageVersion version, boolean explicitJumps) throws ParseException {
        return new Parser(Objects.requireNonNull(source, "source"), Objects.requireNonNull(version, "version"), explicitJumps, false).parse();
    }

    /** A standalone source document may contain declarations and helpers without a main program. */
    public static StructuredDiagram parseDocument(String source, LanguageVersion version, boolean explicitJumps) throws ParseException {
        return new Parser(Objects.requireNonNull(source, "source"), Objects.requireNonNull(version, "version"), explicitJumps, true).parse();
    }

    public String source() { return source; }
    public Sequence preamble() { return preamble; }
    public Sequence main() { return main; }
    public int nodeCount() { return nodes.size(); }
    public LanguageVersion languageVersion() { return languageVersion; }
    public boolean allowsExplicitJumps() { return explicitJumps; }
    public boolean hasMain() { return hasMain; }

    /** Changes a condition, macro signature, or complete leaf statement. */
    public StructuredDiagram edit(Node node, String replacement) throws ParseException {
        requireNode(node);
        return splice(node.editStart, node.editEnd, replacement);
    }

    /** Replaces a complete node, including any nested branches. */
    public StructuredDiagram replace(Node node, String replacement) throws ParseException {
        requireNode(node);
        return splice(node.start, node.end, replacement);
    }

    public StructuredDiagram delete(Node node) throws ParseException {
        return replace(node, "");
    }

    /** Inserts before the given child, or at the end when index equals size. */
    public StructuredDiagram insert(Sequence sequence, int index, String statement) throws ParseException {
        if (!sequences.contains(sequence)) throw new IllegalArgumentException("Use a sequence from the current diagram.");
        if (index < 0 || index > sequence.nodes.size()) throw new IndexOutOfBoundsException(index);
        Objects.requireNonNull(statement, "statement");
        int offset = index < sequence.nodes.size() ? sequence.nodes.get(index).start : sequence.end;
        String indent = index < sequence.nodes.size() ? indentation(source, offset) : sequence.indent;
        String newline = source.contains("\r\n") ? "\r\n" : "\n";
        String inserted = statement.strip().replace("\r\n", "\n").replace('\r', '\n')
                .replace("\n", newline + indent);
        // Start on a fresh line, including after a trailing source comment.
        return splice(offset, offset, newline + indent + inserted + newline + indentation(source, offset));
    }

    public StructuredDiagram addElse(Node node) throws ParseException {
        requireNode(node);
        if (node.kind != Kind.IF || node.alternative != null) {
            throw new IllegalArgumentException("Select an if block without an else branch.");
        }
        return splice(node.end, node.end, " else {\n" + indentation(source, node.start) + "}");
    }

    private StructuredDiagram splice(int start, int end, String replacement) throws ParseException {
        Objects.requireNonNull(replacement, "replacement");
        String changed = source.substring(0, start) + replacement + source.substring(end);
        return optionalMain ? parseDocument(changed, languageVersion, explicitJumps) : parse(changed, languageVersion, explicitJumps);
    }

    private void requireNode(Node node) {
        if (!nodes.contains(node)) throw new IllegalArgumentException("Use a node from the current diagram.");
    }

    private void collect(Sequence sequence) {
        sequences.add(sequence);
        for (Node node : sequence.nodes) {
            nodes.add(node);
            if (node.body != null) collect(node.body);
            if (node.alternative != null) collect(node.alternative);
        }
    }

    private static String indentation(String source, int position) {
        int line = source.lastIndexOf('\n', Math.max(-1, position - 1)) + 1;
        int end = line;
        while (end < position && (source.charAt(end) == ' ' || source.charAt(end) == '\t')) ++end;
        return source.substring(line, end);
    }

    private record Token(String text, int start, int end, char kind) { }
    private record Condition(int start, int end) { }

    private static final class Parser {
        private static final Set<String> RESERVED = Set.of("var", "void", "if", "else", "while",
                "HALT", "NOP", "INC", "DEC", "JNZ", "begin:");
        private final String source;
        private final LanguageVersion version;
        private final boolean explicitJumps;
        private final boolean optionalMain;
        private final List<Token> tokens = new ArrayList<>();
        private int position;
        private int depth;

        private Parser(String source, LanguageVersion version, boolean explicitJumps, boolean optionalMain) throws ParseException {
            this.source = source;
            this.version = version;
            this.explicitJumps = explicitJumps;
            this.optionalMain = optionalMain;
            if (source.length() > 65_536) throw failure("Source exceeds the diagram editor's size limit.", 0);
            tokenize();
        }

        private StructuredDiagram parse() throws ParseException {
            List<Node> declarations = new ArrayList<>();
            while (at("import") || at("void")) {
                declarations.add(at("import") ? imported() : macro());
            }
            while (at("var")) declarations.add(declaration());
            if (optionalMain && at("<end>")) {
                return new StructuredDiagram(source, new Sequence(declarations, 0, source.length(), ""),
                        new Sequence(List.of(), source.length(), source.length(), ""), version, explicitJumps, true, false);
            }
            Token begin = need("begin:");
            List<Node> statements = new ArrayList<>();
            while (!at("<end>")) statements.add(statement());
            return new StructuredDiagram(source, new Sequence(declarations, 0, begin.start, ""),
                    new Sequence(statements, begin.end, source.length(), ""), version, explicitJumps, optionalMain, true);
        }

        private Node imported() throws ParseException {
            Token start = need("import");
            if (peek().kind != 's') throw failure("Use a quoted import path.", peek().start);
            Token path = take();
            Token semicolon = need(";");
            int lineStart = Math.max(source.lastIndexOf('\n', start.start - 1), source.lastIndexOf('\r', start.start - 1)) + 1;
            int lineEnd = semicolon.end;
            while (lineEnd < source.length() && source.charAt(lineEnd) != '\n' && source.charAt(lineEnd) != '\r') ++lineEnd;
            if (path.end != semicolon.start || !source.substring(lineStart, start.start).isBlank()
                    || !source.substring(semicolon.end, lineEnd).isBlank()) {
                throw failure("Put each import on its own line, ending with \"; and no trailing comment.", start.start);
            }
            return leaf(Kind.IMPORT, start.start);
        }

        private Node macro() throws ParseException {
            Token start = need("void");
            identifier();
            need("(");
            if (!at(")")) {
                do {
                    if (peek().kind == 'n') numeric(); else identifier();
                } while (accept(","));
            }
            Token close = need(")");
            Sequence body = block();
            return node(Kind.MACRO, start.start, start.start, close.end, body, null);
        }

        private Node declaration() throws ParseException {
            Token start = need("var");
            identifier();
            if (accept("[")) { numeric(); need("]"); }
            need("=");
            value(false);
            need(";");
            return leaf(Kind.DECLARATION, start.start);
        }

        private Node statement() throws ParseException {
            int labeledStart = peek().start;
            while (peek().kind == 'i' && tokens.get(position + 1).text.equals(":")) {
                identifier();
                need(":");
            }
            Node node = statementBody();
            if (labeledStart == node.start) return node;
            return new Node(node.kind, labeledStart, node.end, node.editStart, node.editEnd,
                    node.body, node.alternative, source.substring(labeledStart, node.start).strip(), source);
        }

        private Node statementBody() throws ParseException {
            Token start = peek();
            if (at("JNZ")) {
                if (!explicitJumps) throw failure("JNZ is suspended here; use if/else, while or do/while for the diagram.", start.start);
                take();
                value(true);
                identifier();
                need(";");
                return leaf(Kind.STATEMENT, start.start);
            }
            if (at("var")) return declaration();
            if (at("{")) {
                Sequence body = block();
                return node(Kind.BLOCK, start.start, start.start, previous().end, body, null);
            }
            if (accept("if")) {
                Condition condition = condition();
                Sequence body = block();
                Sequence alternative = accept("else") ? block() : null;
                return node(Kind.IF, start.start, condition.start, condition.end, body, alternative);
            }
            if (accept("while")) {
                Condition condition = condition();
                Sequence body = block();
                return node(Kind.WHILE, start.start, condition.start, condition.end, body, null);
            }
            if (version == LanguageVersion.STRUCTURED && accept("do")) {
                Sequence body = block();
                need("while");
                Condition condition = condition();
                need(";");
                return node(Kind.DO_WHILE, start.start, condition.start, condition.end, body, null);
            }
            if (accept(";")) return leaf(Kind.STATEMENT, start.start);
            if (accept("HALT") || accept("NOP")) {
                need(";");
            } else if (accept("INC") || accept("DEC")) {
                value(true);
                need(";");
            } else {
                identifier();
                need("(");
                if (!at(")")) {
                    do { value(false); } while (accept(","));
                }
                need(")");
                need(";");
            }
            return leaf(Kind.STATEMENT, start.start);
        }

        private Sequence block() throws ParseException {
            Token open = need("{");
            if (++depth > 64) throw failure("Diagram blocks are nested too deeply.", open.start);
            List<Node> children = new ArrayList<>();
            while (!at("}") && !at("<end>")) children.add(statement());
            Token close = need("}");
            --depth;
            return new Sequence(children, open.end, close.start, indentation(source, open.start) + "  ");
        }

        private Condition condition() throws ParseException {
            Token open = need("(");
            value(false);
            if (!accept("==") && !accept("!=")) throw failure("Conditions use == or !=.", peek().start);
            value(false);
            Token close = need(")");
            return new Condition(open.end, close.start);
        }

        private void value(boolean variableOnly) throws ParseException {
            if (!variableOnly && peek().kind == 'n') { numeric(); return; }
            identifier();
            if (accept("[")) { numeric(); need("]"); }
        }

        private void identifier() throws ParseException {
            if (peek().kind != 'i' || RESERVED.contains(peek().text)
                    || version == LanguageVersion.STRUCTURED && at("do")) {
                throw failure("Expected an identifier, found " + peek().text + ".", peek().start);
            }
            take();
        }

        private void numeric() throws ParseException {
            Token token = peek();
            if (token.kind != 'n') throw failure("Expected a nonnegative number.", token.start);
            try {
                if (token.text.length() > 10) throw new NumberFormatException();
                Integer.parseInt(token.text);
            } catch (NumberFormatException invalid) {
                throw failure("Use a nonnegative 32-bit integer with at most ten digits.", token.start);
            }
            take();
        }

        private Node leaf(Kind kind, int start) {
            return node(kind, start, start, previous().end, null, null);
        }

        private Node node(Kind kind, int start, int editStart, int editEnd, Sequence body, Sequence alternative) {
            return new Node(kind, start, previous().end, editStart, editEnd, body, alternative, "", source);
        }

        private Token peek() { return tokens.get(position); }
        private Token previous() { return tokens.get(position - 1); }
        private Token take() { return tokens.get(position++); }
        private boolean at(String text) { return peek().text.equals(text); }
        private boolean accept(String text) { if (!at(text)) return false; take(); return true; }
        private Token need(String text) throws ParseException {
            if (!at(text)) throw failure("Expected " + text + ", found " + peek().text + ".", peek().start);
            return take();
        }

        private ParseException failure(String message, int offset) {
            return new ParseException(message, offset, source);
        }

        private void tokenize() throws ParseException {
            int cursor = 0;
            while (cursor < source.length()) {
                char current = source.charAt(cursor);
                if (current == ' ' || current == '\t' || current == '\r' || current == '\n') { ++cursor; continue; }
                if (current == '/' && cursor + 1 < source.length() && source.charAt(cursor + 1) == '/') {
                    while (cursor < source.length() && source.charAt(cursor) != '\n' && source.charAt(cursor) != '\r') ++cursor;
                    continue;
                }
                int start = cursor;
                char kind = 'p';
                if (letter(current)) {
                    kind = 'i';
                    while (cursor < source.length() && (letter(source.charAt(cursor)) || digit(source.charAt(cursor)))) ++cursor;
                    if (cursor - start == 5 && source.startsWith("begin:", start)) { ++cursor; kind = 'p'; }
                    if (cursor - start > 128) throw failure("Identifier exceeds the game's size limit.", start);
                } else if (digit(current)) {
                    kind = 'n';
                    while (cursor < source.length() && digit(source.charAt(cursor))) ++cursor;
                } else if (current == '"') {
                    kind = 's';
                    ++cursor;
                    while (cursor < source.length() && source.charAt(cursor) != '"'
                            && source.charAt(cursor) != '\r' && source.charAt(cursor) != '\n') ++cursor;
                    if (cursor == source.length() || source.charAt(cursor) != '"') {
                        throw failure("Close the quoted import path.", start);
                    }
                    ++cursor;
                } else if ("{}[](),;:=".indexOf(current) >= 0 || current == '!') {
                    ++cursor;
                    if ((current == '=' || current == '!') && cursor < source.length() && source.charAt(cursor) == '=') ++cursor;
                    else if (current == '!') throw failure("Use != for a comparison.", start);
                } else {
                    throw failure("Unsupported source character: " + current, start);
                }
                tokens.add(new Token(source.substring(start, cursor), start, cursor, kind));
                if (tokens.size() > 12_000) throw failure("Too many tokens for the diagram editor.", start);
            }
            tokens.add(new Token("<end>", source.length(), source.length(), 'e'));
        }

        private static boolean letter(char c) { return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c == '_'; }
        private static boolean digit(char c) { return c >= '0' && c <= '9'; }
    }
}

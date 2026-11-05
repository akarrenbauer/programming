package machine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Whitespace-only formatting for complete programs and unfinished editor drafts. */
public final class SourceFormatter {
    private enum Kind { WORD, NUMBER, STRING, OPEN_STRING, LINE_COMMENT, BLOCK_COMMENT, ENTRY, SYMBOL }
    private enum Block { ORDINARY, DO, MACRO }
    private record Token(String text, Kind kind, int start, int end) { }

    private SourceFormatter() { }

    /** Formats brace nesting with two spaces without parsing, renaming, or inserting source tokens. */
    public static String format(String source) {
        if (source == null || source.isBlank()) return "";
        List<Token> tokens = lex(source);
        Layout layout = new Layout();
        Deque<Block> blocks = new ArrayDeque<>();
        Token previous = null;
        Token previousCode = null;
        String statement = null;
        int parentheses = 0;
        boolean pendingBreak = false;
        boolean importEnd = false;
        boolean macroEnd = false;
        Block closed = null;
        for (Token token : tokens) {
            String text = token.text();
            if (token.kind() == Kind.LINE_COMMENT) {
                boolean trailing = previous != null && sameLine(source, previous.end(), token.start())
                        && !layout.atLineStart() && !importEnd;
                if (!trailing) layout.newline();
                if (!trailing && (macroEnd || previous != null && lineBreaks(source, previous.end(), token.start()) > 1)) {
                    layout.blankLine();
                    macroEnd = false;
                }
                layout.append(text, trailing);
                layout.newline();
                pendingBreak = false;
                importEnd = false;
                previous = token;
                continue;
            }
            boolean joined = pendingBreak && !layout.atLineStart() && closed != null && closed != Block.MACRO
                    && (text.equals("else") || closed == Block.DO && text.equals("while"));
            if (pendingBreak && !joined) layout.newline();
            if (macroEnd && !joined) layout.blankLine();
            else if (pendingBreak && previous != null && lineBreaks(source, previous.end(), token.start()) > 1)
                layout.blankLine();
            pendingBreak = false;
            importEnd = false;
            macroEnd = false;
            closed = null;

            if (token.kind() == Kind.BLOCK_COMMENT) {
                if (previous != null && !sameLine(source, previous.end(), token.start())) layout.newline();
                layout.append(text, !layout.atLineStart());
                if (text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) layout.newline();
                previous = token;
                continue;
            }
            if (text.equals("}")) {
                layout.newline();
                layout.indent = Math.max(0, layout.indent - 1);
                closed = blocks.isEmpty() ? Block.ORDINARY : blocks.pop();
                layout.append(text, false);
                pendingBreak = true;
                macroEnd = closed == Block.MACRO;
                statement = null;
            } else if (text.equals("{")) {
                Block block = "void".equals(statement) ? Block.MACRO
                        : previousCode != null && previousCode.text().equals("do") ? Block.DO : Block.ORDINARY;
                blocks.push(block);
                layout.append(text, !layout.atLineStart());
                layout.indent++;
                pendingBreak = true;
                statement = null;
            } else if (token.kind() == Kind.ENTRY) {
                layout.newline();
                layout.append(text, false);
                pendingBreak = true;
                statement = null;
            } else if (text.equals(":")) {
                // Whitespace distinguishes an ordinary begin label from the entry token begin:.
                layout.append(text, previousCode != null && previousCode.text().equals("begin") && !layout.atLineStart());
                if (parentheses == 0) { pendingBreak = true; statement = null; }
            } else if (text.equals(";")) {
                layout.append(text, false);
                if (parentheses == 0) {
                    pendingBreak = true;
                    importEnd = "import".equals(statement);
                    statement = null;
                }
            } else {
                if (statement == null && token.kind() != Kind.BLOCK_COMMENT) statement = text;
                layout.append(text, needsSpace(previous, token));
                if (text.equals("(")) parentheses++;
                else if (text.equals(")")) parentheses = Math.max(0, parentheses - 1);
                if (token.kind() == Kind.OPEN_STRING) layout.newline();
            }
            previous = token;
            previousCode = token;
        }
        layout.newline();
        return layout.text.toString();
    }

    private static boolean needsSpace(Token previous, Token current) {
        if (previous == null) return false;
        String before = previous.text(), after = current.text();
        if (after.equals(")") || after.equals("]") || after.equals(",")) return false;
        if (after.equals("(")) return before.equals("if") || before.equals("while");
        if (after.equals("[")) return false;
        return !before.equals("(") && !before.equals("[");
    }

    private static boolean sameLine(String source, int start, int end) { return lineBreaks(source, start, end) == 0; }
    private static int lineBreaks(String source, int start, int end) {
        int count = 0;
        for (int index = start; index < end; index++) {
            char ch = source.charAt(index);
            if (ch == '\n' || ch == '\r' && (index + 1 == end || source.charAt(index + 1) != '\n')) count++;
        }
        return count;
    }

    private static List<Token> lex(String source) {
        List<Token> tokens = new ArrayList<>();
        int index = 0;
        while (index < source.length()) {
            char ch = source.charAt(index);
            if (Character.isWhitespace(ch)) { index++; continue; }
            int start = index;
            Kind kind;
            if (ch == '/' && index + 1 < source.length() && source.charAt(index + 1) == '/') {
                index += 2;
                while (index < source.length() && !lineEnd(source.charAt(index))) index++;
                kind = Kind.LINE_COMMENT;
            } else if (ch == '/' && index + 1 < source.length() && source.charAt(index + 1) == '*') {
                int end = source.indexOf("*/", index + 2);
                index = end < 0 ? source.length() : end + 2;
                kind = Kind.BLOCK_COMMENT;
            } else if (ch == '"') {
                index++;
                while (index < source.length() && !lineEnd(source.charAt(index)) && source.charAt(index) != '"') index++;
                if (index < source.length() && source.charAt(index) == '"') { index++; kind = Kind.STRING; }
                else kind = Kind.OPEN_STRING;
            } else if (letter(ch)) {
                index++;
                while (index < source.length() && (letter(source.charAt(index)) || digit(source.charAt(index)))) index++;
                if (source.substring(start, index).equals("begin") && index < source.length() && source.charAt(index) == ':') {
                    index++;
                    kind = Kind.ENTRY;
                } else kind = Kind.WORD;
            } else if (digit(ch)) {
                index++;
                while (index < source.length() && digit(source.charAt(index))) index++;
                kind = Kind.NUMBER;
            } else {
                index += Character.charCount(source.codePointAt(index));
                if ((ch == '=' || ch == '!') && index < source.length() && source.charAt(index) == '=') index++;
                kind = Kind.SYMBOL;
            }
            tokens.add(new Token(source.substring(start, index), kind, start, index));
        }
        return tokens;
    }

    private static boolean letter(char ch) { return ch >= 'a' && ch <= 'z' || ch >= 'A' && ch <= 'Z' || ch == '_'; }
    private static boolean digit(char ch) { return ch >= '0' && ch <= '9'; }
    private static boolean lineEnd(char ch) { return ch == '\n' || ch == '\r'; }

    private static final class Layout {
        final StringBuilder text = new StringBuilder();
        int indent;
        boolean atLineStart() { return text.length() == 0 || text.charAt(text.length() - 1) == '\n'; }
        void append(String token, boolean space) {
            if (atLineStart()) text.append("  ".repeat(indent));
            else if (space) text.append(' ');
            text.append(token);
        }
        void newline() { if (!atLineStart()) text.append('\n'); }
        void blankLine() {
            newline();
            if (text.length() > 0 && (text.length() < 2 || text.charAt(text.length() - 2) != '\n')) text.append('\n');
        }
    }
}

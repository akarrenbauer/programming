package machine;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Tolerant syntax classification for source editing, independent of the compiler parser. */
public final class SourceSyntax {
    public enum Kind {
        PLAIN, KEYWORD, INSTRUCTION, NUMBER, COMMENT, STRING, LABEL, MACRO, OPERATOR
    }

    /** Offsets are UTF-16 indices into the unchanged source; end is exclusive. */
    public record Span(int start, int end, Kind kind) {}

    private static final Set<String> KEYWORDS =
        Set.of("var", "void", "if", "else", "while", "do", "import");
    private static final Set<String> INSTRUCTIONS = Set.of("HALT", "NOP", "INC", "DEC", "JNZ");

    private SourceSyntax() {}

    /** Finds an exact top-level entry marker outside comments and quoted paths. */
    public static boolean hasBeginMarker(String source) {
        if (source == null) {
            return false;
        }
        int position = 0;
        int depth = 0;
        while (position < source.length()) {
            char current = source.charAt(position);
            if (startsComment(source, position)) {
                position = commentEnd(source, position);
            } else if (current == '"') {
                position = stringEnd(source, position);
            } else if (isWordPart(current)) {
                int start = position++;
                while (position < source.length() && isWordPart(source.charAt(position))) {
                    ++position;
                }
                if (depth == 0 && position - start == 5
                        && source.regionMatches(start, "begin", 0, 5)
                        && position < source.length() && source.charAt(position) == ':') {
                    return true;
                }
            } else {
                if (current == '{') {
                    ++depth;
                } else if (current == '}' && depth > 0) {
                    --depth;
                }
                ++position;
            }
        }
        return false;
    }

    /**
     * Returns ordered spans covering the entire source, including whitespace.
     * Null is treated as empty source. Incomplete constructs are accepted.
     * Quotes protect // inside paths; strings have no escapes and end at a quote
     * or line boundary. Adjacent spans of the same kind are combined.
     */
    public static List<Span> highlight(String source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }

        List<Span> spans = new ArrayList<>();
        int position = 0;
        while (position < source.length()) {
            int start = position;
            char current = source.charAt(position);
            Kind kind;

            if (startsComment(source, position)) {
                position = commentEnd(source, position);
                kind = Kind.COMMENT;
            } else if (current == '"') {
                position = stringEnd(source, position);
                kind = Kind.STRING;
            } else if (isWordPart(current)) {
                boolean digitsOnly = isDigit(current);
                ++position;
                while (position < source.length() && isWordPart(source.charAt(position))) {
                    digitsOnly &= isDigit(source.charAt(position));
                    ++position;
                }

                String word = source.substring(start, position);
                if (digitsOnly) {
                    kind = Kind.NUMBER;
                } else if (isDigit(current)) {
                    // Do not color a numeric prefix in an incomplete word such as 12abc.
                    kind = Kind.PLAIN;
                } else if (KEYWORDS.contains(word)) {
                    kind = Kind.KEYWORD;
                } else if (INSTRUCTIONS.contains(word)) {
                    kind = Kind.INSTRUCTION;
                } else if (word.equals("begin") && position < source.length()
                        && source.charAt(position) == ':') {
                    ++position;
                    kind = Kind.LABEL;
                } else {
                    int following = position;
                    while (following < source.length() && isWhitespace(source.charAt(following))) {
                        ++following;
                    }
                    char next = following < source.length() ? source.charAt(following) : '\0';
                    kind = next == ':' ? Kind.LABEL : next == '(' ? Kind.MACRO : Kind.PLAIN;
                }
            } else if (isOperator(current)) {
                ++position;
                kind = Kind.OPERATOR;
            } else {
                ++position;
                while (position < source.length()) {
                    char next = source.charAt(position);
                    if (next == '"' || isWordPart(next) || isOperator(next)
                            || startsComment(source, position)) {
                        break;
                    }
                    ++position;
                }
                kind = Kind.PLAIN;
            }

            append(spans, start, position, kind);
        }
        return List.copyOf(spans);
    }

    private static int commentEnd(String source, int position) {
        position += 2;
        while (position < source.length() && !isLineEnd(source.charAt(position))) {
            ++position;
        }
        return position;
    }

    private static int stringEnd(String source, int position) {
        ++position;
        while (position < source.length() && !isLineEnd(source.charAt(position))) {
            if (source.charAt(position++) == '"') {
                break;
            }
        }
        return position;
    }

    private static boolean startsComment(String source, int position) {
        return source.charAt(position) == '/' && position + 1 < source.length()
            && source.charAt(position + 1) == '/';
    }

    private static boolean isLineEnd(char character) {
        return character == '\r' || character == '\n';
    }

    private static boolean isWhitespace(char character) {
        return character == ' ' || character == '\t' || isLineEnd(character);
    }

    private static boolean isDigit(char character) {
        return character >= '0' && character <= '9';
    }

    private static boolean isWordPart(char character) {
        return character >= 'a' && character <= 'z' || character >= 'A' && character <= 'Z'
            || character == '_' || isDigit(character);
    }

    private static boolean isOperator(char character) {
        return "=!;,{}[]():".indexOf(character) >= 0;
    }

    private static void append(List<Span> spans, int start, int end, Kind kind) {
        if (!spans.isEmpty()) {
            Span previous = spans.get(spans.size() - 1);
            if (previous.kind() == kind) {
                spans.set(spans.size() - 1, new Span(previous.start(), end, kind));
                return;
            }
        }
        spans.add(new Span(start, end, kind));
    }
}

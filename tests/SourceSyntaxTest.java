import java.util.List;
import java.util.Random;

import machine.SourceSyntax;
import machine.SourceSyntax.Kind;
import machine.SourceSyntax.Span;

/**
 * Run from the repository root:
 *
 * test_build=$(mktemp -d)
 * javac -d "$test_build" -classpath "$test_build" -sourcepath . tests/SourceSyntaxTest.java
 * java -cp "$test_build" SourceSyntaxTest
 */
public class SourceSyntaxTest {
    private static int passed;
    private static int failed;

    @FunctionalInterface
    private interface Test {
        void run();
    }

    public static void main(String[] args) {
        check("entry markers are exact case-sensitive whole words", () -> {
            for (String source : List.of("begin:", "var value=0; begin: HALT;", "\tbegin:\r\n", "begin:begin:")) {
                expect(true, SourceSyntax.hasBeginMarker(source), "valid marker in " + source);
            }
            for (String source : List.of("", "begin", "begin :", "begin\n:", "Begin:", "BEGIN:",
                    "_begin:", "mybegin:", "begin2:", "1begin:", "begin_name:")) {
                expect(false, SourceSyntax.hasBeginMarker(source), "not an entry marker: " + source);
            }
            expect(false, SourceSyntax.hasBeginMarker(null), "null has no marker");
        });

        check("entry markers ignore comments and quoted paths", () -> {
            for (String source : List.of("// begin:", "import \"begin:\";", "\"folder//begin:\"",
                    "// \" { begin: }\n\"begin:\"", "\"unfinished begin:")) {
                expect(false, SourceSyntax.hasBeginMarker(source), "protected marker in " + source);
            }
            for (String source : List.of("// begin:\rbegin:", "// begin:\nbegin:", "// begin:\r\nbegin:",
                    "\"unfinished\nbegin:", "import \"folder//file\";\nbegin:")) {
                expect(true, SourceSyntax.hasBeginMarker(source), "marker following protected text");
            }
        });

        check("entry markers must be outside nested braces", () -> {
            for (String source : List.of("{begin:}", "void library() { begin: }", "{{begin:}}",
                    "void library() {\nif(0==0) {\nbegin:\n}\n}\n", "{\"}\" begin:", "{// }\nbegin:")) {
                expect(false, SourceSyntax.hasBeginMarker(source), "nested marker in " + source);
            }
            for (String source : List.of("void library() { { NOP; } }\nbegin:",
                    "void library() {\n// } begin:\n}\nbegin:", "\"{\" begin:", "// {\nbegin:")) {
                expect(true, SourceSyntax.hasBeginMarker(source), "top-level marker after braces");
            }
        });

        check("entry marker scanning tolerates incomplete editing states", () -> {
            for (String source : List.of("/", "//", "\"", "{", "}", "void (") ) {
                expect(false, SourceSyntax.hasBeginMarker(source), "incomplete source has no marker");
            }
            expect(true, SourceSyntax.hasBeginMarker("} begin:"), "an unmatched closing brace does not hide a marker");
            expect(true, SourceSyntax.hasBeginMarker("begin: {"), "incomplete program still has an entry marker");
            String source = "// Grüße 😀\r\nvar broken=;\nbegin:";
            expect(true, SourceSyntax.hasBeginMarker(source), "marker recognition does not validate the whole program");
        });

        check("empty source has no spans", () -> {
            expect(List.of(), SourceSyntax.highlight(""), "empty source");
            expect(List.of(), SourceSyntax.highlight(null), "absent source");
        });

        check("mixed source retains every character and original offset", () -> {
            String source = "var count2 = 12;\r\n// Grüße 😀\rimport \"folder//file.ad04\";\n"
                + "begin:\nrepeat: INC count2;\nwhile(count2 != 0) { DEC count2; }\ncall (count2);\n";
            List<Span> spans = verified(source);
            expectToken(source, spans, "var", Kind.KEYWORD);
            expectToken(source, spans, "count2", Kind.PLAIN);
            expectToken(source, spans, "12", Kind.NUMBER);
            expectToken(source, spans, "// Grüße 😀", Kind.COMMENT);
            expectToken(source, spans, "\r\n", Kind.PLAIN);
        });

        check("all keywords and instructions use their case-sensitive kinds", () -> {
            for (String word : List.of("var", "void", "if", "else", "while", "do", "import")) {
                expectToken(word, verified(word), word, Kind.KEYWORD);
            }
            for (String word : List.of("HALT", "NOP", "INC", "DEC", "JNZ")) {
                expectToken(word, verified(word), word, Kind.INSTRUCTION);
            }
        });

        check("keyword and number prefixes do not split identifiers", () -> {
            for (String word : List.of(
                "Var", "WHILE", "inc", "halt", "variable", "var2", "_var", "HALT2",
                "myINC", "data123", "_123", "123abc", "12_", "1var"
            )) {
                expectToken(word, verified(word), word, Kind.PLAIN);
            }
            for (String number : List.of("0", "001", "2147483647", "999999999999999999999999999")) {
                expectToken(number, verified(number), number, Kind.NUMBER);
            }
        });

        check("labels and macro names are recognized without recoloring keywords", () -> {
            String source = "begin:\nloop: NOP;\nagain \t: ;\nrun (counter);\n"
                + "while(counter != 0) {}\nvoid define(value) {}\ncontinued\n(value);";
            List<Span> spans = verified(source);
            expectToken(source, spans, "begin:", Kind.LABEL);
            expectToken(source, spans, "loop", Kind.LABEL);
            expectRange(spans, source.indexOf("loop:") + 4, source.indexOf("loop:") + 5, Kind.OPERATOR);
            expectToken(source, spans, "again", Kind.LABEL);
            expectToken(source, spans, "run", Kind.MACRO);
            expectToken(source, spans, "define", Kind.MACRO);
            expectToken(source, spans, "continued", Kind.MACRO);
            expectToken(source, spans, "while", Kind.KEYWORD);
            expectToken(source, spans, "void", Kind.KEYWORD);
            expectToken(source, spans, "NOP", Kind.INSTRUCTION);
        });

        check("quoted paths protect slashes while comments absorb quotes", () -> {
            String source = "import \"folder//with spaces/file.ad04\"; // \"not a string\" INC 12\n"
                + "// import \"another//file\";\nHALT;";
            List<Span> spans = verified(source);
            expectToken(source, spans, "\"folder//with spaces/file.ad04\"", Kind.STRING);
            expectToken(source, spans, "// \"not a string\" INC 12", Kind.COMMENT);
            expectToken(source, spans, "// import \"another//file\";", Kind.COMMENT);
            expectToken(source, spans, "HALT", Kind.INSTRUCTION);
        });

        check("backslashes do not escape string delimiters", () -> {
            String quoted = "\"path" + '\\' + "\"";
            String source = "import " + quoted + "; INC value;";
            List<Span> spans = verified(source);
            expectToken(source, spans, quoted, Kind.STRING);
            expectToken(source, spans, "INC", Kind.INSTRUCTION);
        });

        check("incomplete strings stop at line boundaries", () -> {
            String source = "import \"unfinished\r\nINC value;\r\"another\nHALT;";
            List<Span> spans = verified(source);
            expectToken(source, spans, "\"unfinished", Kind.STRING);
            expectToken(source, spans, "\r\n", Kind.PLAIN);
            expectToken(source, spans, "INC", Kind.INSTRUCTION);
            expectToken(source, spans, "\"another", Kind.STRING);
            expectToken(source, spans, "HALT", Kind.INSTRUCTION);
            for (String incomplete : List.of("/", "//", "\"", "\"path", "var x = ", "call(", "!", "begin")) {
                verified(incomplete);
            }
            expectToken("//", verified("//"), "//", Kind.COMMENT);
            expectToken("\"path", verified("\"path"), "\"path", Kind.STRING);
        });

        check("Unicode comments retain UTF-16 offsets and all line boundaries", () -> {
            String comment = "// Grüße 😀 中文 var INC 42";
            String source = comment + "\rHALT;\n" + comment + "\r\nNOP;";
            List<Span> spans = verified(source);
            expectRange(spans, 0, comment.length(), Kind.COMMENT);
            expectRange(spans, comment.length(), comment.length() + 1, Kind.PLAIN);
            expectToken(source, spans, "HALT", Kind.INSTRUCTION);
            expectToken(source, spans, "NOP", Kind.INSTRUCTION);
        });

        check("operators and punctuation are separate from whitespace", () -> {
            String source = "= == != ; , { } [ ] ( ) : !";
            List<Span> spans = verified(source);
            for (int offset = 0; offset < source.length(); ++offset) {
                expectRange(spans, offset, offset + 1,
                    source.charAt(offset) == ' ' ? Kind.PLAIN : Kind.OPERATOR);
            }
        });

        check("arbitrary incomplete text always retains complete coverage", () -> {
            Random random = new Random(20261030L);
            String alphabet = "varHALT012_ ,;:{}[]()=!/\\\"\r\n\t@ä中\uD83D\uDE03";
            for (int sample = 0; sample < 1000; ++sample) {
                StringBuilder source = new StringBuilder();
                int length = random.nextInt(121);
                for (int offset = 0; offset < length; ++offset) {
                    source.append(alphabet.charAt(random.nextInt(alphabet.length())));
                }
                verified(source.toString());
            }
        });

        System.out.println("SourceSyntaxTest: " + passed + " passed, " + failed + " failed");
        if (failed != 0) {
            throw new AssertionError(failed + " syntax highlighting test(s) failed");
        }
    }

    private static void check(String name, Test test) {
        try {
            test.run();
            ++passed;
            System.out.println("PASS " + name);
        } catch (RuntimeException | AssertionError error) {
            ++failed;
            System.err.println("FAIL " + name + ": " + error);
            error.printStackTrace(System.err);
        }
    }

    private static List<Span> verified(String source) {
        List<Span> spans = SourceSyntax.highlight(source);
        int position = 0;
        StringBuilder reconstructed = new StringBuilder();
        for (Span span : spans) {
            if (span.start() != position || span.end() <= span.start()
                    || span.end() > source.length() || span.kind() == null) {
                throw new AssertionError("invalid span coverage at " + position + ": " + span);
            }
            reconstructed.append(source, span.start(), span.end());
            position = span.end();
        }
        expect(source.length(), position, "final covered offset");
        expect(source, reconstructed.toString(), "unchanged source text");
        return spans;
    }

    private static void expectToken(String source, List<Span> spans, String token, Kind kind) {
        int start = source.indexOf(token);
        if (start < 0) {
            throw new AssertionError("test token not found: " + token);
        }
        expectRange(spans, start, start + token.length(), kind);
    }

    private static void expectRange(List<Span> spans, int start, int end, Kind kind) {
        for (Span span : spans) {
            if (span.end() > start && span.start() < end && span.kind() != kind) {
                throw new AssertionError("expected " + kind + " at " + start + ".." + end + ", got " + span);
            }
        }
    }

    private static void expect(Object expected, Object actual, String description) {
        if (!expected.equals(actual)) {
            throw new AssertionError(description + ": expected " + expected + ", got " + actual);
        }
    }
}

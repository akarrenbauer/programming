import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import machine.CompilationResult;
import machine.CompilerParser;
import machine.LanguageVersion;
import machine.Machine;
import machine.ParseException;
import machine.SourceDocument;

import static machine.LanguageVersion.BEFORE_DO_WHILE;
import static machine.LanguageVersion.STRUCTURED;

/**
 * Run from the repository root with fresh compiled sources:
 * test_build=$(mktemp -d)
 * javac --release 17 -d "$test_build" -classpath "$test_build" -sourcepath . tests/CompilerDoLabelTest.java
 * java -cp "$test_build" CompilerDoLabelTest
 */
public class CompilerDoLabelTest {
    private static int passed;
    private static int failed;

    @FunctionalInterface
    private interface Test { void run() throws Exception; }

    public static void main(String[] args) {
        check("earlier labeled block and later do while are behaviorally equivalent", () -> {
            for (int dummy : List.of(0, 4)) {
                for (int input : List.of(0, 1, 7)) {
                    String prefix = "var dummy=" + dummy + "; var x=" + input + "; begin: INC dummy;\n";
                    Machine labeled = execute(prefix + "do: { DEC x; } JNZ x do; HALT;", BEFORE_DO_WHILE);
                    Machine structured = execute(prefix + "do { DEC x; } while(x!=0); HALT;", STRUCTURED);
                    expect(dummy + 1, labeled.get(1), "setup executes once");
                    expect(0, labeled.get(2), "zero and positive inputs clear");
                    expect(structured.get(1), labeled.get(1), "same dummy result across profiles");
                    expect(structured.get(2), labeled.get(2), "same cleared value across profiles");
                }
            }
        });
        check("before profile resolves forward and whitespace-separated do labels", () -> {
            Machine machine = execute("var count=1; var result=0; begin: "
                    + "JNZ count do; INC result; outer: do \t: INC result; HALT;", BEFORE_DO_WHILE);
            expect(1, machine.get(2), "forward jump skips one increment");
        });
        check("before profile treats do as an ordinary scalar array and runtime name", () -> {
            expect(3, execute("var do=2; begin: INC do; HALT;", BEFORE_DO_WHILE).get(1), "scalar do");
            Machine array = execute("var do[2]=1; begin: INC do[1]; HALT;", BEFORE_DO_WHILE);
            expect(1, array.get(1), "first array cell");
            expect(2, array.get(2), "indexed do operand");
            Machine local = execute("var result=0; begin: var do=2; while(do!=0){DEC do; INC result;} HALT;", BEFORE_DO_WHILE);
            expect(2, local.get(1), "runtime do declaration");
        });
        check("before profile accepts do as a macro name parameter and argument", () -> {
            String source = "void do(value) {\nINC value;\n}\n"
                    + "void bump(do) {\nINC do;\n}\n"
                    + "var do=0; begin: do(do); bump(do); HALT;";
            expect(2, execute(source, BEFORE_DO_WHILE).get(1), "macro name, parameter and actual argument");
        });
        check("direct parser applies the selected profile in both passes", () -> {
            String before = "var do=2; begin: do: DEC do; JNZ do do; HALT;";
            String after = "var x=2; begin: do { DEC x; } while(x!=0); HALT;";
            for (boolean preflight : List.of(false, true)) {
                parser(before, BEFORE_DO_WHILE).Program(preflight);
                parser(after, STRUCTURED).Program(preflight);
                expectParserRejection(before, STRUCTURED, preflight);
                expectParserRejection(after, BEFORE_DO_WHILE, preflight);
            }
        });
        check("all default compiler entry points remain structured and reject do labels", () -> {
            String source = "var x=1; begin: do: DEC x; JNZ x do; HALT;";
            expectThrows(ParseException.class, () -> machine.Compiler.compileWithSourceMap(document(source),
                    path -> { throw new AssertionError("unexpected import"); }, null));
            silence(() -> {
                expectThrows(ParseException.class, () -> machine.Compiler.compile(List.of(source)));
                expectThrows(ParseException.class, () -> machine.Compiler.compile(List.of(source), path -> List.of()));
                expectThrows(ParseException.class, () -> machine.Compiler.preProcess(List.of(source)));
            });
            expect(STRUCTURED, new CompilerParser(new StringReader("begin: HALT;")).getLanguageVersion(), "parser default");
        });
        check("structured profile rejects do in every identifier position", () -> {
            for (String source : List.of("var do=0; begin: HALT;", "begin: var do=0; HALT;",
                    "void do() {} begin: HALT;", "void f(do) {} begin: HALT;",
                    "void f(a) {} begin: f(do); HALT;", "begin: do();",
                    "begin: do: HALT;", "var x=1; begin: JNZ x do; HALT;")) {
                expectSyntaxRejection(source, STRUCTURED);
            }
        });
        check("before profile rejects structured loops including unused imported bodies", () -> {
            expectSyntaxRejection("var x=1; begin: do {DEC x;} while(x!=0); HALT;", BEFORE_DO_WHILE);
            SourceDocument library = new SourceDocument(Path.of("loops.a26"), "void clear(x) {\ndo {\nDEC x;\n} while(x!=0);\n}\n");
            expectThrows(ParseException.class, () -> machine.Compiler.compileWithSourceMap(
                    document("import \"loops.a26\";\nbegin: HALT;"), path -> library, null, BEFORE_DO_WHILE));
        });
        check("ordinary unresolved do labels retain final-pass diagnostics", () -> {
            String source = "var x=1; begin: JNZ x do; HALT;";
            parser(source, BEFORE_DO_WHILE).Program(true);
            ParseException error = rejected(source, BEFORE_DO_WHILE);
            expect("Unresolved labels: do", error.getMessage(), "unresolved ordinary identifier");
        });
        check("other reserved words and identifier boundaries do not change", () -> {
            for (LanguageVersion version : LanguageVersion.values()) {
                for (String word : List.of("var", "void", "if", "else", "while", "HALT", "NOP", "INC", "DEC", "JNZ")) {
                    expectSyntaxRejection("begin: " + word + ": HALT;", version);
                    expectSyntaxRejection("var " + word + "=0; begin: HALT;", version);
                }
                expect(1, execute("var do2=0; begin: done: INC do2; HALT;", version).get(1), "ordinary longer identifier");
            }
        });
        check("imported macro locals and backward labels named do remain hygienic", () -> {
            String text = "void clear(value) {\ndo: {\nDEC value;\n}\nJNZ value do;\n}\n"
                    + "void twice(value) {\nvar do=2;\nwhile(do!=0) {\nINC value;\nDEC do;\n}\n}\n"
                    + "void bump(do) {\nINC do;\n}\n";
            SourceDocument library = new SourceDocument(Path.of("library.a26"), text);
            String source = "import \"library.a26\";\nvar a=3; var b=2; var result=0; var do=9;\n"
                    + "begin: clear(a); clear(b); twice(result); twice(result); bump(result); HALT;";
            CompilationResult compiled = machine.Compiler.compileWithSourceMap(document(source), path -> library, null, BEFORE_DO_WHILE);
            Machine vm = execute(compiled);
            expect(0, vm.get(1), "first local backward label");
            expect(0, vm.get(2), "second expansion has a separate label");
            expect(5, vm.get(3), "repeated local do storage and parameter substitution");
            expect(9, vm.get(4), "global do is not captured by local renaming");
            expect(true, compiled.sourceMap().values().stream().anyMatch(trace -> library.file().equals(trace.location().file())
                    && !trace.callers().isEmpty()), "imported instruction retains macro provenance");
            expectThrows(ParseException.class, () -> machine.Compiler.compileWithSourceMap(document(source), path -> library, null, STRUCTURED));
        });
        check("source mapping preserves do operand and do-label ranges", () -> {
            for (String source : List.of("var do=0; begin: INC do; HALT;",
                    "var x=1; begin: do: { DEC x; } JNZ x do; HALT;")) {
                CompilationResult compiled = compile(source, BEFORE_DO_WHILE);
                String statement = source.contains("INC") ? "INC do;" : "JNZ x do;";
                expect(true, compiled.sourceMap().values().stream().anyMatch(trace -> source
                        .substring(trace.location().startOffset(), trace.location().endOffset()).equals(statement)),
                        "exact original statement range");
            }
        });
        check("list compilation and explicit preprocessing select the same earlier profile", () -> silence(() -> {
            List<String> source = List.of("var do=1; begin: INC do; HALT;");
            List<Integer> image = machine.Compiler.compile(source, path -> List.of(), BEFORE_DO_WHILE);
            expect(compile(source.get(0), BEFORE_DO_WHILE).bytecode(), image, "list and mapped APIs agree");
            List<String> expanded = machine.Compiler.preProcess(source, path -> List.of(), BEFORE_DO_WHILE);
            parser(String.join("\n", expanded), BEFORE_DO_WHILE).Program(false);
        }));
        check("profile mutation is rejected after lookahead or token consumption", () -> {
            CompilerParser looked = parser("var do=0; begin: HALT;", BEFORE_DO_WHILE);
            looked.getToken(1);
            expectThrows(IllegalStateException.class, () -> looked.setLanguageVersion(STRUCTURED));
            CompilerParser consumed = parser("begin: HALT;", STRUCTURED);
            consumed.getNextToken();
            expectThrows(IllegalStateException.class, () -> consumed.setLanguageVersion(BEFORE_DO_WHILE));
            CompilerParser fresh = parser("begin: HALT;", BEFORE_DO_WHILE);
            fresh.setLanguageVersion(STRUCTURED);
            expect(STRUCTURED, fresh.getLanguageVersion(), "changing selection before lexing is safe");
        });
        check("shared syntax retains bytecode and source mappings across profiles", () -> {
            String source = "var x=3; var result=0; begin: while(x!=0){DEC x; INC result;} HALT;";
            CompilationResult before = compile(source, BEFORE_DO_WHILE);
            CompilationResult after = compile(source, STRUCTURED);
            expect(before.bytecode(), after.bytecode(), "same machine image");
            expect(before.sourceMap(), after.sourceMap(), "same instruction provenance");
        });
        check("concurrent compilations cannot leak their language profile", () -> {
            var workers = Executors.newFixedThreadPool(4);
            try {
                List<Callable<Boolean>> jobs = new ArrayList<>();
                for (int index = 0; index < 16; index++) {
                    final LanguageVersion version = index % 2 == 0 ? BEFORE_DO_WHILE : STRUCTURED;
                    jobs.add(() -> {
                        String before = "var do=1; begin: INC do; HALT;";
                        String after = "var x=2; begin: do {DEC x;} while(x!=0); HALT;";
                        expect(version == BEFORE_DO_WHILE ? 2 : 0,
                                execute(version == BEFORE_DO_WHILE ? before : after, version).get(1), "parallel execution");
                        rejected(version == BEFORE_DO_WHILE ? after : before, version);
                        return true;
                    });
                }
                for (var result : workers.invokeAll(jobs)) expect(true, result.get(), "parallel profile isolation");
            } finally {
                workers.shutdownNow();
            }
        });

        System.out.println("CompilerDoLabelTest: " + passed + " passed, " + failed + " failed");
        if (failed != 0) throw new AssertionError(failed + " language-profile regression group(s) failed");
    }

    private static SourceDocument document(String source) { return new SourceDocument(Path.of("profile.a26"), source); }

    private static CompilationResult compile(String source, LanguageVersion version) throws Exception {
        return machine.Compiler.compileWithSourceMap(document(source), path -> { throw new AssertionError("unexpected import"); }, null, version);
    }

    private static CompilerParser parser(String source, LanguageVersion version) {
        CompilerParser parser = new CompilerParser(new StringReader(source));
        parser.setLanguageVersion(version);
        return parser;
    }

    private static Machine execute(String source, LanguageVersion version) throws Exception { return execute(compile(source, version)); }

    private static Machine execute(CompilationResult compiled) {
        Machine machine = new Machine();
        machine.load(compiled.bytecode());
        machine.step(5000);
        expect(true, machine.isHalted(), "program halts within the step budget");
        return machine;
    }

    private static ParseException rejected(String source, LanguageVersion version) throws Exception {
        try { compile(source, version); }
        catch (ParseException error) { return error; }
        throw new AssertionError("expected ParseException for " + version + ": " + source);
    }

    private static void expectSyntaxRejection(String source, LanguageVersion version) throws Exception {
        if (rejected(source, version).currentToken == null) throw new AssertionError("expected a parser syntax diagnostic");
    }

    private static void expectParserRejection(String source, LanguageVersion version, boolean preflight) throws Exception {
        expectThrows(ParseException.class, () -> parser(source, version).Program(preflight));
    }

    private static void expectThrows(Class<? extends Throwable> type, Test action) throws Exception {
        try { action.run(); }
        catch (Exception error) {
            if (type.isInstance(error)) return;
            throw error;
        }
        throw new AssertionError("expected " + type.getSimpleName());
    }

    private static void silence(Test action) throws Exception {
        PrintStream previous = System.out;
        try (PrintStream output = new PrintStream(new ByteArrayOutputStream())) {
            System.setOut(output);
            action.run();
        } finally { System.setOut(previous); }
    }

    private static void check(String name, Test test) {
        try { test.run(); ++passed; System.out.println("PASS " + name); }
        catch (Exception | AssertionError error) {
            ++failed; System.err.println("FAIL " + name + ": " + error); error.printStackTrace(System.err);
        }
    }

    private static void expect(Object expected, Object actual, String description) {
        if (!expected.equals(actual)) throw new AssertionError(description + ": expected " + expected + ", got " + actual);
    }
}

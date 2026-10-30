import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import machine.CompilationResult;
import machine.Compiler.SourceResolver;
import machine.Machine;
import machine.ParseException;
import machine.SourceDocument;
import machine.SourceLocation;
import machine.SourceTrace;

/**
 * Run from the repository root:
 * test_build=$(mktemp -d)
 * javac -d "$test_build" -classpath "$test_build" -sourcepath . tests/CompilerSourceMappingTest.java
 * java -cp "$test_build" CompilerSourceMappingTest
 */
public class CompilerSourceMappingTest {
    private static final Path MAIN = Path.of("mapped-main.ad04").toAbsolutePath();
    private static final Path LIBRARY = Path.of("mapped-library.ad04").toAbsolutePath();
    private static int passed;
    private static int failed;

    @FunctionalInterface
    private interface Test { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        check("same-line instructions retain precise separate ranges", () -> {
            SourceDocument source = new SourceDocument(MAIN,
                "var count=1; begin: INC count; DEC count; HALT;");
            CompilationResult result = compile(source);
            int address = result.codeStart();
            expect(location(source, "INC count;"), result.sourceMap().get(address).location(), "increment");
            expect(location(source, "DEC count;"), result.sourceMap().get(address + 2).location(), "decrement");
            expect(location(source, "HALT;"), result.sourceMap().get(address + 4).location(), "halt");
            verifyOpcodeCoverage(result);
        });

        check("statements after same-line block endings exclude preceding braces", () -> {
            SourceDocument source = new SourceDocument(MAIN,
                "var value=0; begin: if(value==0) { INC value; } DEC value; HALT;");
            CompilationResult result = compile(source);
            SourceLocation expected = location(source, "DEC value;");
            if (result.sourceMap().values().stream().noneMatch(trace -> trace.location().equals(expected))) {
                throw new AssertionError("decrement range includes the preceding closing brace");
            }
            expect(0, execute(result).get(1), "structured behavior remains intact");
            verifyOpcodeCoverage(result);
        });

        check("normalized snapshots retain line column and Unicode offsets", () -> {
            SourceDocument source = new SourceDocument(MAIN,
                "// Grüße 😀\r\nvar value=1;\rbegin:\r  INC value; // comment\r\nHALT;");
            CompilationResult result = compile(source);
            SourceLocation instruction = result.sourceMap().get(result.codeStart()).location();
            expect(location(source, "INC value;"), instruction, "normalized offset");
            expect(4, instruction.line(), "line number");
            expect(3, instruction.column(), "column number");
            expect(source.text(), result.sources().get(MAIN), "snapshot text");
            expect(false, result.sources().get(MAIN).contains("\r"), "LF snapshots");
            verifyOpcodeCoverage(result);
        });

        check("global data and instruction operands have no execution entries", () -> {
            SourceDocument source = new SourceDocument(MAIN,
                "var source=7; var copy=source; begin: INC copy; HALT;");
            CompilationResult result = compile(source);
            expect(3, result.codeStart(), "data segment size");
            expect(2, result.sourceMap().size(), "only INC and HALT are executable entries");
            expect(8, execute(result).get(2), "initialization behavior");
            verifyOpcodeCoverage(result);
        });

        check("duplicate imports use canonical snapshots but preserve occurrences", () -> {
            SourceDocument source = new SourceDocument(MAIN,
                "var value=0; begin:\nimport \"first\";\nimport \"alias\";\nHALT;\n");
            SourceDocument imported = new SourceDocument(LIBRARY, "  INC value;\r\n");
            List<Path> requested = new ArrayList<>();
            CompilationResult result = compile(source, path -> {
                requested.add(path);
                return imported;
            });
            expect(List.of(Path.of("first"), Path.of("alias")), requested, "all import requests");
            expect(2, result.sources().size(), "one snapshot per canonical file");
            expect(location(imported, "INC value;"), result.sourceMap().get(result.codeStart()).location(), "first import");
            expect(location(imported, "INC value;"), result.sourceMap().get(result.codeStart() + 2).location(), "second import");
            expect(2, execute(result).get(1), "both occurrences execute");
            verifyOpcodeCoverage(result);
        });

        check("nested and repeated macro calls preserve ordered caller chains", () -> {
            SourceDocument library = nestedLibrary();
            SourceDocument source = new SourceDocument(MAIN,
                "import \"library\";\nvar result=0; begin: outer(result); outer(result); HALT;\n");
            CompilationResult result = compile(source, path -> library);
            List<SourceTrace> increments = tracesForOpcode(result, 2);
            expect(2, increments.size(), "two expanded increments");
            for (int call = 0; call < 2; ++call) {
                expect(location(library, "INC value;"), increments.get(call).location(), "macro definition range");
                expect(List.of(location(source, "outer(result);", call), location(library, "leaf(value);")),
                    increments.get(call).callers(), "outer-to-inner invocation chain");
            }
            expect(2, execute(result).get(1), "nested expansion behavior");
            verifyOpcodeCoverage(result);
        });

        check("local array renaming retains original declaration and element ranges", () -> {
            SourceDocument source = new SourceDocument(MAIN,
                "void work(result) {\nvar tmp[2]=1;\nINC tmp[1];\n"
                + "if(tmp[1]==2) { INC result; }\n}\n"
                + "var tmp=7; var result=0; begin: work(result); INC tmp; HALT;");
            CompilationResult result = compile(source);
            SourceLocation invocation = location(source, "work(result);");
            for (String original : List.of("var tmp[2]=1;", "INC tmp[1];", "INC result;")) {
                SourceLocation expected = location(source, original);
                SourceTrace trace = result.sourceMap().values().stream()
                    .filter(candidate -> candidate.location().equals(expected)).findFirst().orElseThrow();
                expect(List.of(invocation), trace.callers(), "renamed macro provenance");
            }
            Machine machine = execute(result);
            expect(8, machine.get(1), "global remains separate");
            expect(1, machine.get(2), "local-array macro result");
            verifyOpcodeCoverage(result);
        });

        check("synthetic literal temporaries point to the requesting call", () -> {
            SourceDocument source = new SourceDocument(MAIN,
                "void consume(value) {\nwhile(value != 0) {\nDEC value;\n}\n}\n"
                + "begin: consume(2); HALT;\n");
            CompilationResult result = compile(source);
            SourceLocation call = location(source, "consume(2);");
            SourceTrace initialization = result.sourceMap().get(result.codeStart());
            expect(call, initialization.location(), "generated initialization callsite");
            expect(List.of(), initialization.callers(), "top-level callsite ancestry");
            SourceTrace body = result.sourceMap().values().stream()
                .filter(trace -> trace.location().equals(location(source, "DEC value;")))
                .findFirst().orElseThrow();
            expect(List.of(call), body.callers(), "macro body invocation");
            execute(result);
            verifyOpcodeCoverage(result);
        });

        check("generated loop instructions and repeated execution retain their source", () -> {
            SourceDocument source = new SourceDocument(MAIN,
                "var value=2;\nbegin:\nwhile(value != 0) {\nDEC value;\n}\nHALT;\n");
            CompilationResult result = compile(source);
            SourceLocation header = location(source, "while(value != 0) {");
            SourceLocation decrement = location(source, "DEC value;");
            SourceLocation halt = location(source, "HALT;");
            for (SourceTrace trace : result.sourceMap().values()) {
                if (!List.of(header, decrement, halt).contains(trace.location())) {
                    throw new AssertionError("unexpected generated-instruction range " + trace);
                }
            }
            Machine machine = new Machine();
            machine.load(result.bytecode());
            int visits = 0;
            for (int step = 0; step < 1000 && !machine.isHalted(); ++step) {
                if (result.sourceMap().get(machine.getInstructionPointer()).location().equals(decrement)) {
                    ++visits;
                }
                machine.step();
            }
            expect(true, machine.isHalted(), "loop terminates");
            expect(2, visits, "both executions point to the same original statement");
            verifyOpcodeCoverage(result);
        });

        check("mapped and legacy compilation produce identical bytecode", () -> {
            SourceDocument library = nestedLibrary();
            for (SourceDocument source : List.of(
                new SourceDocument(MAIN, "var a=3; begin: while(a != 0) { DEC a; } HALT;"),
                new SourceDocument(MAIN, "import \"library\";\nvar result=0; begin: outer(result); HALT;"),
                new SourceDocument(MAIN, "var a=2; begin: var copied[2]=a; INC copied[1]; HALT;")
            )) {
                CompilationResult mapped = compile(source, path -> library);
                List<Integer> legacy = quietly(() -> machine.Compiler.compile(source.text().lines().toList(),
                    path -> library.text().lines().toList()));
                expect(legacy, mapped.bytecode(), "shared preprocessing bytecode");
                verifyOpcodeCoverage(mapped);
            }
        });

        check("results remain immutable and independent of a subsequent compile failure", () -> {
            SourceDocument source = new SourceDocument(MAIN, "begin: NOP; HALT;");
            CompilationResult result = compile(source);
            expectFailure(UnsupportedOperationException.class, () -> result.bytecode().clear());
            expectFailure(UnsupportedOperationException.class, () -> result.sourceMap().clear());
            expectFailure(UnsupportedOperationException.class, () -> result.sources().clear());
            expectFailure(UnsupportedOperationException.class,
                () -> result.sourceMap().get(result.codeStart()).callers().clear());
            expectFailure(ParseException.class, () -> compile(new SourceDocument(MAIN, "begin: INC ;")));
            expect(source.text(), result.sources().get(MAIN), "previous source snapshot");
            expect(location(source, "NOP;"), result.sourceMap().get(result.codeStart()).location(), "previous source map");
        });

        System.out.println("CompilerSourceMappingTest: " + passed + " passed, " + failed + " failed");
        if (failed != 0) {
            throw new AssertionError(failed + " source mapping test(s) failed");
        }
    }

    private static SourceDocument nestedLibrary() {
        return new SourceDocument(LIBRARY,
            "void leaf(value) {\n  INC value;\n}\nvoid outer(value) {\n  leaf(value);\n}\n");
    }

    private static CompilationResult compile(SourceDocument source) throws Exception {
        return compile(source, path -> { throw new IOException("Unexpected import " + path); });
    }

    private static CompilationResult compile(SourceDocument source, SourceResolver resolver) throws Exception {
        return quietly(() -> machine.Compiler.compileWithSourceMap(source, resolver));
    }

    @FunctionalInterface
    private interface Operation<T> { T run() throws Exception; }

    private static <T> T quietly(Operation<T> operation) throws Exception {
        PrintStream previous = System.out;
        try (PrintStream sink = new PrintStream(new ByteArrayOutputStream())) {
            System.setOut(sink);
            return operation.run();
        } finally {
            System.setOut(previous);
        }
    }

    private static SourceLocation location(SourceDocument source, String fragment) {
        return location(source, fragment, 0);
    }

    private static SourceLocation location(SourceDocument source, String fragment, int occurrence) {
        int start = -1;
        for (int found = 0; found <= occurrence; ++found) {
            start = source.text().indexOf(fragment, start + 1);
            if (start < 0) {
                throw new AssertionError("Missing fixture fragment " + fragment);
            }
        }
        String prefix = source.text().substring(0, start);
        int line = 1 + (int) prefix.chars().filter(character -> character == '\n').count();
        int column = start - prefix.lastIndexOf('\n');
        return new SourceLocation(source.file(), start, start + fragment.length(), line, column);
    }

    private static List<SourceTrace> tracesForOpcode(CompilationResult result, int opcode) {
        return result.sourceMap().entrySet().stream()
            .filter(entry -> result.bytecode().get(entry.getKey()) == opcode)
            .map(Map.Entry::getValue).toList();
    }

    private static void verifyOpcodeCoverage(CompilationResult result) {
        List<Integer> expectedAddresses = new ArrayList<>();
        for (int address = result.codeStart(); address < result.bytecode().size();) {
            expectedAddresses.add(address);
            int opcode = result.bytecode().get(address);
            address += switch (opcode) {
                case 0, 1 -> 1;
                case 2, 3 -> 2;
                case 4 -> 3;
                default -> throw new AssertionError("Invalid opcode in compiled stream " + opcode);
            };
        }
        expect(expectedAddresses, new ArrayList<>(result.sourceMap().keySet()), "opcode-only mapping coverage");
        for (SourceTrace trace : result.sourceMap().values()) {
            for (SourceLocation at : joinLocations(trace)) {
                String source = result.sources().get(at.file());
                if (source == null || at.endOffset() > source.length() || at.startOffset() == at.endOffset()) {
                    throw new AssertionError("Range outside its snapshot " + at);
                }
            }
        }
    }

    private static List<SourceLocation> joinLocations(SourceTrace trace) {
        List<SourceLocation> locations = new ArrayList<>(trace.callers());
        locations.add(trace.location());
        return locations;
    }

    private static Machine execute(CompilationResult result) {
        Machine machine = new Machine();
        machine.load(result.bytecode());
        machine.step(10000);
        expect(true, machine.isHalted(), "program must halt");
        return machine;
    }

    private static void expectFailure(Class<? extends Throwable> type, Test operation) throws Exception {
        try { operation.run(); }
        catch (Exception failure) {
            if (type.isInstance(failure)) return;
            throw failure;
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }

    private static void check(String name, Test test) {
        try {
            test.run(); ++passed; System.out.println("PASS " + name);
        } catch (Exception | AssertionError failure) {
            ++failed; System.err.println("FAIL " + name + ": " + failure); failure.printStackTrace(System.err);
        }
    }

    private static void expect(Object expected, Object actual, String message) {
        if (!expected.equals(actual)) throw new AssertionError(message + ": expected " + expected + ", got " + actual);
    }
}

package machine;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Run from the repository root without using existing machine/*.class files:
 *
 * test_build=$(mktemp -d)
 * javac --release 17 -d "$test_build" -classpath "$test_build" -sourcepath . tests/CompilerInstructionMappingTest.java
 * java -cp "$test_build" machine.CompilerInstructionMappingTest
 */
public final class CompilerInstructionMappingTest {
    private static int passed;
    private static int failed;

    private static final String SIMPLE = "var count = 1;\nvar out[2] = 0;\nbegin:\n"
            + "INC out[1];\nDEC count;\nloop: NOP;\nJNZ count loop;\nHALT;\n";
    private static final String LOCALS = "var source = 2;\nbegin:\n"
            + "var local[2] = 3;\nvar copies[2] = source;\nHALT;\n";
    private static final String NESTED = "var x = 1;\nbegin:\nwhile(x != 0) {\n"
            + "if(x == 1) {\nDEC x;\n} else {\nNOP;\n}\n}\nHALT;\n";
    private static final String DO_WHILE = "var x = 2;\nbegin:\ndo {\n"
            + "if(x == 2) {\nNOP;\n}\nDEC x;\n} while(x != 0);\nHALT;\n";

    @FunctionalInterface
    private interface Check {
        void run() throws Exception;
    }

    public static void main(String[] args) {
        check("simple instructions map only opcode starts after the data segment", () -> {
            Parsed parsed = parse(SIMPLE);
            expect(4, parsed.codeStart(), "code starts after the pointer and three data cells");
            expect(List.of(4, 1, 0, 0, 2, 3, 3, 1, 1, 4, 1, 8, 0), parsed.bytecode(), "simple bytecode");
            expect(Map.of(4, 4, 6, 5, 8, 6, 9, 7, 12, 8), parsed.lines(), "absolute instruction mapping");
            expectCoverage(parsed);
        });

        check("all local initialization instructions retain their declaration line", () -> {
            Parsed parsed = parse(LOCALS);
            expect(7, parsed.codeStart(), "local variables and copy scratch are included in data offset");
            for (Map.Entry<Integer, Integer> entry : parsed.lines().entrySet()) {
                int expectedLine = entry.getKey() < 29 ? 3 : entry.getKey() < 93 ? 4 : 5;
                expect(expectedLine, entry.getValue(), "local initializer instruction at " + entry.getKey());
            }
            expectCoverage(parsed);
        });

        check("nested if and while generated jumps restore their owning header", () -> {
            Parsed parsed = parse(NESTED);
            for (Map.Entry<Integer, Integer> entry : parsed.lines().entrySet()) {
                int address = entry.getKey();
                if (address < 19) {
                    expect(3, entry.getValue(), "outer while condition at " + address);
                } else if (address < 68) {
                    expect(4, entry.getValue(), "inner if comparison at " + address);
                }
            }
            expect(5, parsed.lines().get(68), "then-body decrement");
            expect(4, parsed.lines().get(70), "jump over else belongs to the if header");
            expect(7, parsed.lines().get(73), "else-body NOP");
            expect(3, parsed.lines().get(74), "while backedge belongs to the outer header");
            expect(10, parsed.lines().get(77), "following HALT has its own line");
            expectCoverage(parsed);
        });

        check("do while maps its trailing condition independently from its body", () -> {
            Parsed parsed = parse(DO_WHILE);
            for (Map.Entry<Integer, Integer> entry : parsed.lines().entrySet()) {
                if (entry.getKey() < 54) {
                    expect(4, entry.getValue(), "nested if comparison at " + entry.getKey());
                }
            }
            expect(5, parsed.lines().get(54), "nested NOP");
            expect(7, parsed.lines().get(55), "do-body decrement");
            expect(8, parsed.lines().get(57), "repeat jump belongs to trailing while");
            expect(9, parsed.lines().get(60), "following HALT");
            expectCoverage(parsed);
        });

        check("lowered do while comparison instructions use the trailing while line", () -> {
            Parsed parsed = parse("var x = 0;\nbegin:\ndo {\nINC x;\n} while(x != 1);\nHALT;\n");
            for (Map.Entry<Integer, Integer> entry : parsed.lines().entrySet()) {
                int expectedLine = entry.getKey() == parsed.codeStart() ? 4
                        : entry.getKey() == parsed.bytecode().size() - 1 ? 6 : 5;
                expect(expectedLine, entry.getValue(), "do-while instruction at " + entry.getKey());
            }
            expect(parsed.lines().values().stream().filter(line -> line == 5).count() > 5,
                    "fixture must lower a nontrivial condition");
            expectCoverage(parsed);
        });

        check("a nested do while does not change the enclosing while backedge line", () -> {
            Parsed parsed = parse("var x = 1;\nbegin:\nwhile(x != 0) {\ndo {\n"
                    + "DEC x;\n} while(x != 0);\n}\nHALT;\n");
            expect(3, parsed.lines().get(parsed.bytecode().size() - 4), "outer while backedge");
            expect(8, parsed.lines().get(parsed.bytecode().size() - 1), "subsequent HALT");
            expect(parsed.lines().containsValue(5) && parsed.lines().containsValue(6), "body and trailing condition remain distinct");
            expectCoverage(parsed);
        });

        check("labels blocks empty statements and multiline operands do not steal source lines", () -> {
            Parsed parsed = parse("var x = 1;\nbegin:\nouter:\n{\ninner:\nNOP;\n;\n"
                    + "INC\nx\n;\n}\nHALT;\n");
            expect(Map.of(2, 6, 3, 8, 5, 12), parsed.lines(), "command tokens own their emitted instructions");
            expectCoverage(parsed);
        });

        check("stacked labels share targets without replacing command source lines", () -> {
            Parsed parsed = parse("var x = 1;\nbegin:\nfirst: second: NOP;\nthird:\nfourth:\n"
                    + "INC x;\nJNZ x first;\nJNZ x second;\nJNZ x third;\nJNZ x fourth;\nHALT;\n");
            expect(Map.of(2, 3, 3, 6, 5, 7, 8, 8, 11, 9, 14, 10, 17, 11), parsed.lines(),
                    "same-line and separate-line label prefixes preserve instruction lines");
            expect(2, parsed.bytecode().get(7), "first same-line label target");
            expect(2, parsed.bytecode().get(10), "second same-line label target");
            expect(3, parsed.bytecode().get(13), "first separate-line label target");
            expect(3, parsed.bytecode().get(16), "second separate-line label target");
            expectCoverage(parsed);
        });

        check("repeated labels can prefix every statement and block form", () -> {
            String[] statements = {";", "{\nNOP;\n}", "if(x == 0) {\nNOP;\n}",
                    "while(x != 0) {\nNOP;\n}", "do {\nNOP;\n} while(x != 0);"};
            int[] firstInstructionLines = {6, 6, 5, 5, 6};
            for (int i = 0; i < statements.length; ++i) {
                Parsed parsed = parse("var x = 0;\nbegin:\nfirst:\nsecond:\n" + statements[i]
                        + "\nJNZ x first;\nJNZ x second;\nHALT;\n");
                int size = parsed.bytecode().size();
                expect(parsed.codeStart(), parsed.bytecode().get(size - 5), "first label targets the construct entry");
                expect(parsed.codeStart(), parsed.bytecode().get(size - 2), "second label shares the construct entry");
                expect(firstInstructionLines[i], parsed.lines().get(parsed.codeStart()),
                        "labeled construct maps its own first emitted instruction");
                Machine machine = new Machine();
                machine.load(parsed.bytecode());
                machine.step(1000);
                expect(machine.isHalted(), "labeled statement fixture must finish");
                expectCoverage(parsed);
            }
        });

        check("labels still require a following statement at EOF and block close", () -> {
            for (String source : new String[] {"begin: last:", "begin: first: second:",
                    "begin: { last: }", "begin: { first:\nsecond:\n}"}) {
                try {
                    parse(source);
                    throw new AssertionError("dangling labels must be rejected: " + source);
                } catch (ParseException expected) {
                    // An explicit empty statement ';' is valid, but a missing statement is not.
                }
            }
        });

        check("literal loop conditions and indexed operands retain branch polarity", () -> {
            Parsed parsed = parse("var values[2] = 2; var hits = 0;\nbegin:\n"
                    + "if(2 == 2) { INC hits; }\n"
                    + "while(2 != 2) { INC hits; HALT; }\n"
                    + "do { INC hits; } while(2 != 2);\n"
                    + "if(values[1] != 3) { INC hits; }\n"
                    + "while(values[1] == 2) { INC values[1]; INC hits; }\n"
                    + "do { DEC values[1]; INC hits; } while(values[1] != 2);\nHALT;\n");
            Machine machine = new Machine();
            machine.load(parsed.bytecode());
            machine.step(10000);
            expect(machine.isHalted(), "literal and indexed loop conditions must terminate");
            expect(2, machine.get(1), "unreferenced array element remains unchanged");
            expect(2, machine.get(2), "indexed operand is changed only by loop bodies");
            expect(5, machine.get(3), "all selected bodies execute with the requested branch polarity");
            expectCoverage(parsed);
        });

        check("metadata is immutable and is published only after successful parsing", () -> {
            CompilerParser parser = new CompilerParser(new StringReader("begin: HALT;"));
            expect(-1, parser.getCodeStart(), "unparsed code start is unavailable");
            expect(Map.of(), parser.getInstructionSourceLines(), "unparsed map is empty");
            parser.Program(false);
            expect(1, parser.getCodeStart(), "successful program publishes its offset");
            try {
                parser.getInstructionSourceLines().put(123, 456);
                throw new AssertionError("instruction map must reject mutation");
            } catch (UnsupportedOperationException expected) {
                // Returned metadata cannot be changed by callers.
            }
            CompilerParser broken = new CompilerParser(new StringReader("var x=1; begin: JNZ x missing;"));
            try {
                broken.Program(false);
                throw new AssertionError("undefined label must fail parsing");
            } catch (ParseException expected) {
                expect(-1, broken.getCodeStart(), "failed program must not publish an offset");
                expect(Map.of(), broken.getInstructionSourceLines(), "failed program must not publish a partial map");
            }
            Parsed empty = parse("begin:\n");
            expect(1, empty.codeStart(), "empty program code offset");
            expect(Map.of(), empty.lines(), "empty program has no explicit instructions");
        });

        check("adding source metadata leaves existing executable images unchanged", () -> {
            // Frozen from the parser before metadata was added; includes relocated jump targets.
            String[] sources = {SIMPLE, LOCALS, NESTED, DO_WHILE};
            String[] hashes = {
                "c66296012263650da8a6c6d95741f336d37738df44b3b91389121e3a4b88124d",
                "d5c801df95965be615a87d0513c9e80fdca97c90168479378a22a764858c2a4c",
                "7ea586e19a21f26b5ea7f0f6e8f9a3faa4e1aa2ed46e99ea57e77dbbe596f795",
                "911da6ee176dbbea239fd8d59e5badb903a9e844a1acd5961f132bb1c502a969"
            };
            for (int i = 0; i < sources.length; ++i) {
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                        parse(sources[i]).bytecode().toString().getBytes(StandardCharsets.UTF_8));
                expect(hashes[i], HexFormat.of().formatHex(digest), "unchanged bytecode fixture " + i);
            }
        });

        System.out.println("CompilerInstructionMappingTest: " + passed + " passed, " + failed + " failed");
        if (failed != 0) {
            throw new AssertionError(failed + " instruction mapping regression test(s) failed");
        }
    }

    private record Parsed(List<Integer> bytecode, Map<Integer, Integer> lines, int codeStart) {}

    private static Parsed parse(String source) throws ParseException {
        CompilerParser parser = new CompilerParser(new StringReader(source));
        List<Integer> bytecode = new ArrayList<>();
        for (Number value : parser.Program(false)) {
            bytecode.add(value.getValue());
        }
        return new Parsed(bytecode, parser.getInstructionSourceLines(), parser.getCodeStart());
    }

    private static void expectCoverage(Parsed parsed) {
        int[] widths = {1, 1, 2, 2, 3};
        Set<Integer> starts = new LinkedHashSet<>();
        for (int address = parsed.codeStart(); address < parsed.bytecode().size();) {
            starts.add(address);
            int opcode = parsed.bytecode().get(address);
            expect(opcode >= 0 && opcode < widths.length, "fixture contains a valid opcode");
            address += widths[opcode];
        }
        expect(starts, parsed.lines().keySet(), "map covers every opcode start and no data or operands");
        expect(parsed.lines().values().stream().allMatch(line -> line > 0), "source lines must be one-based");
    }

    private static void check(String name, Check action) {
        try {
            action.run();
            ++passed;
            System.out.println("PASS " + name);
        } catch (Exception | AssertionError error) {
            ++failed;
            System.err.println("FAIL " + name + ": " + error);
            error.printStackTrace(System.err);
        }
    }

    private static void expect(Object expected, Object actual, String description) {
        if (!expected.equals(actual)) {
            throw new AssertionError(description + ": expected " + expected + ", got " + actual);
        }
    }

    private static void expect(boolean condition, String description) {
        if (!condition) {
            throw new AssertionError(description);
        }
    }
}

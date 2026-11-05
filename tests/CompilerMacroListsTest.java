import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import machine.Machine;
import machine.ParseException;
import machine.TokenMgrError;

/**
 * Run from the repository root without using the existing machine/*.class files:
 *
 * test_build=$(mktemp -d)
 * javac -d "$test_build" -classpath "$test_build" -sourcepath . tests/CompilerMacroListsTest.java
 * java -cp "$test_build" CompilerMacroListsTest
 */
public class CompilerMacroListsTest {
    private static int passed;
    private static int failed;
    private static final String[] INVALID_PARAMETERS = {",a", "a,", "a,,b", ","};
    private static final String[] INVALID_ARGUMENTS = {",1", "1,", "1,,2", ","};

    @FunctionalInterface
    private interface Test {
        void run() throws Exception;
    }

    public static void main(String[] args) throws Exception {
        check("malformed unused definitions fail during syntax checking", () -> {
            for (String parameters : INVALID_PARAMETERS) {
                expectSyntaxError("unused definition (" + parameters + ")",
                    "void broken(" + parameters + ") { NOP; }",
                    "begin: HALT;"
                );
            }
        });

        check("malformed called definitions fail during syntax checking", () -> {
            for (String parameters : INVALID_PARAMETERS) {
                expectSyntaxError("called definition (" + parameters + ")",
                    "void broken(" + parameters + ") { NOP; }",
                    "begin: broken(1); HALT;"
                );
            }
        });

        check("malformed calls fail before macro substitution", () -> {
            for (String arguments : INVALID_ARGUMENTS) {
                expectSyntaxError("main call (" + arguments + ")",
                    "void target(a,b) { NOP; }",
                    "begin: target(" + arguments + "); HALT;"
                );
            }
        });

        check("malformed calls in unused macro bodies are rejected", () -> {
            for (String arguments : INVALID_ARGUMENTS) {
                expectSyntaxError("unused-body call (" + arguments + ")",
                    "void target(a,b) { NOP; }",
                    "void wrapper() {",
                    "target(" + arguments + ");",
                    "}",
                    "begin: HALT;"
                );
            }
        });

        check("empty lists remain valid with and without whitespace", () -> {
            Machine machine = execute(
                "void tick( \t ) { INC result; }",
                "var result = 0; begin: tick(); tick( \t ); HALT;"
            );
            expect(2, machine.get(1), "both zero-argument calls must execute");
        });

        check("single entries remain valid", () -> {
            Machine machine = execute(
                "void bump(value) { INC value; }",
                "var result = 0; begin: bump(result); HALT;"
            );
            expect(1, machine.get(1), "single argument must reach its parameter");
        });

        check("multiple entries preserve whitespace and array arguments", () -> {
            Machine machine = execute(
                "void bump( first ,\t second , third ) { INC first; INC second; INC third; }",
                "var result = 0; var values[2] = 0;",
                "begin: bump( result , values[0] ,\t values[1] ); HALT;"
            );
            expect(1, machine.get(1), "first parameter");
            expect(1, machine.get(2), "second parameter with array argument");
            expect(1, machine.get(3), "third parameter with array argument");
        });

        check("numeric specializations remain valid in multiple positions", () -> {
            Machine machine = execute(
                "void choose(1,value,2) { INC value; }",
                "var result = 0; begin: choose( 1 , result , 2 ); HALT;"
            );
            expect(1, machine.get(1), "constant parameters must match their arguments");
        });

        check("add overloads with a later zero selector preserve runtime copies and input values", () -> {
            for (int input : new int[] {0, 1, 6}) {
                Machine machine = execute(
                    "void add(n,0,out) {",
                    "var nn=n;",
                    "while(out!=0) { DEC out; }",
                    "while(nn!=0) { DEC nn; INC out; }",
                    "}",
                    "void add(n,1,out) {",
                    "var nn=n;",
                    "while(out!=0) { DEC out; }",
                    "while(nn!=0) { DEC nn; INC out; }",
                    "INC out;",
                    "}",
                    "var n=" + input + "; var first=9; var second=11;",
                    "begin: add(n,0,first); add(n,1,second); HALT;"
                );
                expect(input, machine.get(1), "both specializations preserve n");
                expect(input, machine.get(2), "zero specialization clears and copies into first");
                expect(input + 1, machine.get(3), "one specialization clears and increments the copy");
            }
        });

        check("zero and one selectors cannot replace earlier named-parameter markers", () -> {
            String[][] signatures = {
                {"cell,0", "result,0"}, {"0,cell", "0,result"},
                {"cell,1", "result,1"}, {"1,cell", "1,result"},
                {"first,cell,1", "other,result,1"}, {"cell,1,0", "result,1,0"},
                {"first,cell,0,1", "other,result,0,1"}
            };
            for (String[] signature : signatures) {
                Machine machine = execute(
                    "void choose(" + signature[0] + ") { INC cell; }",
                    "var result=0; var other=7; begin: choose(" + signature[1] + "); HALT;"
                );
                expect(1, machine.get(1), "named parameter survives signature " + signature[0]);
                expect(7, machine.get(2), "unused named parameter stays unchanged");
            }
            Machine multiple = execute(
                "void choose(a,b,c,0,1,2) { INC a; INC b; INC c; }",
                "var a=0; var b=0; var c=0; begin: choose(a,b,c,0,1,2); HALT;"
            );
            for (int address = 1; address <= 3; address++) expect(1, multiple.get(address), "all colliding marker indices remain independent");
        });

        check("later literal selectors compose through nested helper expansions", () -> {
            for (int input : new int[] {0, 6}) {
                Machine machine = execute(
                    "void add(n,0,out) { var nn=n; while(out!=0) { DEC out; } while(nn!=0) { DEC nn; INC out; } }",
                    "void add(n,1,out) { var nn=n; while(out!=0) { DEC out; } while(nn!=0) { DEC nn; INC out; } INC out; }",
                    "void wrapper(n,0,out) { add(n,0,out); add(out,1,out); }",
                    "var input=" + input + "; var result=12; begin: wrapper(input,0,result); HALT;"
                );
                expect(input, machine.get(1), "nested invocation preserves the input");
                expect(input + 1, machine.get(2), "nested overloads keep arguments and alias-safe runtime copies");
            }
        });

        check("signature selectors leave ordinary constants and array indices unchanged", () -> {
            String body = "var cells[2]=0; INC cells[0]; INC cells[1]; var work=1; "
                    + "while(work!=0) { DEC work; INC out; } "
                    + "if(source==0) { INC out; } if(cells[0]==1) { INC out; } if(cells[1]==1) { INC out; }";
            for (int input : new int[] {0, 6}) {
                String globals = "var input=" + input + "; var result=0;";
                List<Integer> specialized = compile("void inspect(source,0,1,out) { " + body + " }",
                    globals, "begin: inspect(input,0,1,result); HALT;");
                List<Integer> ordinary = compile("void inspect(source,out) { " + body + " }",
                    globals, "begin: inspect(input,result); HALT;");
                if (!specialized.equals(ordinary)) throw new AssertionError("selectors changed body bytecode or local allocation");
                Machine machine = new Machine();
                machine.load(specialized);
                machine.step(1000);
                if (!machine.isHalted()) throw new AssertionError("constant and array-index fixture did not halt");
                expect(input == 0 ? 4 : 3, machine.get(2), "body constants retain their original values");
            }
        });

        check("mutable literal arguments still get storage beside literal selectors", () -> {
            Machine machine = execute(
                "void drain(count,0,out) { while(count!=0) { DEC count; INC out; } }",
                "var result=0; begin: drain(3,0,result); drain(0,0,result); drain(2,0,result); HALT;"
            );
            expect(5, machine.get(1), "named count parameters retain their mutable literal temporaries");
        });

        check("numeric selectors still reject mismatching values spellings and nonliteral arguments", () -> {
            for (String argument : new String[] {"1", "00", "input"}) {
                expectSubstitutionError("void choose(cell,0) { INC cell; }",
                    "var input=0; var result=0; begin: choose(result," + argument + "); HALT;");
            }
            expectSubstitutionError("void choose(cell,1) { INC cell; }",
                "var result=0; begin: choose(result,01); HALT;");
        });

        check("later main-call arguments still undergo variable validation", () -> {
            for (String arguments : new String[] {"known,missing,known", "known,known,missing[0]"}) {
                expectUnknownVariable(
                    "void consume(first,second,third) { NOP; }",
                    "var known = 0; begin: consume(" + arguments + "); HALT;"
                );
            }
        });

        check("later arguments in unused macro bodies still undergo validation", () -> {
            for (String arguments : new String[] {"value,missing,value", "value,value,missing[0]"}) {
                expectUnknownVariable(
                    "void consume(first,second,third) { NOP; }",
                    "void unused(value) {",
                    "consume(" + arguments + ");",
                    "}",
                    "begin: HALT;"
                );
            }
        });

        System.out.println("CompilerMacroListsTest: " + passed + " passed, " + failed + " failed");
        if (failed != 0) {
            throw new AssertionError(failed + " macro list regression test(s) failed");
        }
    }

    private static void check(String name, Test test) {
        try {
            test.run();
            ++passed;
            System.out.println("PASS " + name);
        } catch (Exception | AssertionError | TokenMgrError error) {
            ++failed;
            System.err.println("FAIL " + name + ": " + error.getMessage());
        }
    }

    private static List<Integer> compile(String... source) throws Exception {
        PrintStream previousOutput = System.out;
        try (PrintStream compilerOutput = new PrintStream(new ByteArrayOutputStream())) {
            System.setOut(compilerOutput);
            return machine.Compiler.compile(Arrays.asList(source));
        } finally {
            System.setOut(previousOutput);
        }
    }

    private static Machine execute(String... source) throws Exception {
        Machine machine = new Machine();
        machine.load(new ArrayList<>(compile(source)));
        machine.step(1000);
        if (!machine.isHalted()) {
            throw new AssertionError("program did not halt within 1000 instructions");
        }
        return machine;
    }

    private static void expectSyntaxError(String context, String... source) throws Exception {
        try {
            compile(source);
        } catch (ParseException error) {
            if (error.getMessage() != null && error.getMessage().contains("Macro substitution failed")) {
                throw new AssertionError(context + " reached substitution instead of failing syntax checking");
            }
            if (error.currentToken == null || error.currentToken.next == null) {
                throw new AssertionError(context + " lacked syntax-error token context");
            }
            return;
        }
        throw new AssertionError(context + " was accepted");
    }

    private static void expectUnknownVariable(String... source) throws Exception {
        try {
            compile(source);
        } catch (ParseException error) {
            if (error.getMessage() == null || !error.getMessage().startsWith("Unknown variable missing in line ")) {
                throw new AssertionError("expected an unknown-variable diagnostic, got " + error.getMessage());
            }
            return;
        }
        throw new AssertionError("an unknown variable in a later argument position was accepted");
    }

    private static void expectSubstitutionError(String... source) throws Exception {
        try {
            compile(source);
        } catch (ParseException error) {
            if (error.getMessage() == null || !error.getMessage().startsWith("Macro substitution failed!")) {
                throw new AssertionError("expected a literal-selector mismatch, got " + error.getMessage());
            }
            return;
        }
        throw new AssertionError("compilation accepted a mismatching literal selector");
    }

    private static void expect(int expected, int actual, String description) {
        if (expected != actual) {
            throw new AssertionError(description + ": expected " + expected + ", got " + actual);
        }
    }
}

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import machine.Machine;
import machine.ParseException;

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
        } catch (Exception | AssertionError error) {
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

    private static void expect(int expected, int actual, String description) {
        if (expected != actual) {
            throw new AssertionError(description + ": expected " + expected + ", got " + actual);
        }
    }
}

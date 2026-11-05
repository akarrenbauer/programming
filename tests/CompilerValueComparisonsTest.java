package machine;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Value-to-value comparisons, operand preservation, aliases, and source-owned branch metadata. */
public final class CompilerValueComparisonsTest {
    private static final Path MAIN = Path.of("value-comparisons.a26");
    private static int passed;
    private static int failed;
    @FunctionalInterface private interface Check { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        check("both operators preserve both operands in if else while and do while", () -> {
            for (int left : new int[] {0, 1, 2, 3, 5}) for (int right : new int[] {0, 1, 2, 3, 5}) {
                for (String operator : List.of("==", "!=")) {
                    boolean matches = operator.equals("==") ? left == right : left != right;
                    String condition = "left " + operator + " right";
                    String globals = "var left=" + left + "; var right=" + right + "; var yes=0; var no=0; var remaining=2;\nbegin:\n";
                    for (String body : List.of(
                            "if (" + condition + ") { INC yes; } else { INC no; } HALT;",
                            "while (" + condition + ") { INC yes; HALT; } INC no; HALT;",
                            "do { DEC remaining; JNZ remaining evaluate; INC yes; HALT; evaluate: NOP; } while (" + condition + "); INC no; HALT;")) {
                        Run run = execute(globals + body);
                        expect(run.value("left") == left && run.value("right") == right, "both operands survive " + condition + " with values " + left + ", " + right);
                        expect(run.value("yes") == (matches ? 1 : 0) && run.value("no") == (matches ? 0 : 1), "correct branch polarity for " + body);
                    }
                }
            }
        });

        check("conditions reread a changing RHS including initially zero and post-test loops", () -> {
            Run rising = execute("var left=2; var right=0; var hits=0; begin:\n"
                    + "while (left != right) { INC right; INC hits; }\n"
                    + "if (left == right) { INC hits; }\n"
                    + "do { DEC right; INC hits; } while (left == right); HALT;");
            expect(rising.value("left") == 2 && rising.value("right") == 1 && rising.value("hits") == 4, "RHS is evaluated at every condition visit");
            Run falling = execute("var left=0; var right=3; var hits=0; begin:\n"
                    + "do { DEC right; INC hits; } while (left != right);\n"
                    + "while (left == right) { INC right; INC hits; } HALT;");
            expect(falling.value("left") == 0 && falling.value("right") == 1 && falling.value("hits") == 4, "both loop operators observe RHS mutations");
        });

        check("nested and reused comparisons reset independent scratch state", () -> {
            Run run = execute("var outer=0; var limit=3; var inner=0; var bound=2; var hits=0; begin:\n"
                    + "while (outer != limit) {\n"
                    + "while (inner != 0) { DEC inner; }\n"
                    + "while (inner != bound) { INC inner; if (inner == bound) { INC hits; } }\n"
                    + "if (outer != inner) { INC hits; } INC outer; } HALT;");
            expect(run.value("outer") == 3 && run.value("limit") == 3 && run.value("inner") == 2
                    && run.value("bound") == 2 && run.value("hits") == 5, "nested comparisons restore data and reset result flags on every evaluation");
        });

        check("identical addresses preserve odd even zero and maximum values", () -> {
            for (int value : new int[] {0, 1, 3, 4, Integer.MAX_VALUE}) {
                Run run = execute("var cell[2]=" + value + "; var hits=0; begin:\n"
                        + "if (cell == cell[0]) { INC hits; }\n"
                        + "if (cell[0] != cell[00]) { INC hits; }\n"
                        + "if (cell[1] == cell[1]) { INC hits; }\n"
                        + "while (cell != cell) { INC hits; HALT; }\n"
                        + "do { INC hits; } while (cell != cell[0]); HALT;");
                int address = run.address("cell");
                expect(run.machine().get(address) == value && run.machine().get(address + 1) == value && run.value("hits") == 3,
                        "same-cell aliases are constant comparisons without modifying " + value);
                expect(run.machine().getStepCounter() < 100, "same-address comparisons take bounded time even for the maximum value");
                Run pretest = execute("var cell=" + value + "; var hits=0; begin: while (cell == cell) { INC hits; HALT; } HALT;");
                expect(pretest.value("cell") == value && pretest.value("hits") == 1, "same-address equality enters a pre-test loop");
                Run posttest = execute("var cell=" + value + "; var remaining=2; var hits=0; begin:\n"
                        + "do { DEC remaining; JNZ remaining again; INC hits; HALT; again: NOP; } while (cell == cell); HALT;");
                expect(posttest.value("cell") == value && posttest.value("hits") == 1, "same-address equality repeats a post-test loop");
            }
        });

        check("array cells and literal left operands work against dynamic RHS cells", () -> {
            Run run = execute("var cells[3]=2; var hits=0; begin:\nDEC cells[0]; INC cells[2];\n"
                    + "if (cells[0] != cells[2]) { INC hits; }\n"
                    + "if (2 == cells[1]) { INC hits; }\n"
                    + "if (0 != cells[2]) { INC hits; }\n"
                    + "while (cells[0] != cells[2]) { INC cells[0]; }\n"
                    + "if (cells[0] == cells[2]) { INC hits; } HALT;");
            int address = run.address("cells");
            expect(run.machine().get(address) == 3 && run.machine().get(address + 1) == 2 && run.machine().get(address + 2) == 3
                    && run.value("hits") == 4, "array reads and literal-left comparisons preserve unmodified cells");
        });

        check("imported macro parameters globals locals and aliased calls compare their current values", () -> {
            String library = "void compare(left,right,out) {\nif (left == right) { INC out; }\nif (left != right) { INC out; }\n}\n"
                    + "void outer(a,b,out) {\ncompare(a,b,out);\n}\n"
                    + "void againstGlobal(left,out) {\nif (left == reference) { INC out; }\n}\n"
                    + "void againstLocal(left,out) {\nvar bound=2;\nif (left == bound) { INC out; }\n}\n";
            String source = "import \"comparisons\";\nvar a=2; var b=2; var odd=3; var cells[2]=2; var reference=2; var hits=0; begin:\n"
                    + "compare(a,b,hits);\ncompare(a,a,hits);\ncompare(2,b,hits);\ncompare(a,2,hits);\n"
                    + "compare(cells[0],cells[1],hits);\ncompare(odd,odd,hits);\nouter(a,b,hits);\nagainstGlobal(a,hits);\nagainstLocal(a,hits);\nHALT;";
            Run run = execute(compile(source, Map.of("comparisons", library)));
            expect(run.value("a") == 2 && run.value("b") == 2 && run.value("odd") == 3 && run.value("reference") == 2 && run.value("hits") == 9,
                    "macro substitution works on either side and aliases preserve their cells");
            expect(run.program().sourceMap().values().stream().anyMatch(trace -> trace.callers().size() == 2
                    && trace.location().file().equals(Path.of("toolbox", "comparisons"))), "nested imported comparisons retain definition and call provenance");
        });

        check("large values compare compactly and restore without overflow in either order", () -> {
            Run run = execute("var large=2147483647; var small=2; var hits=0; begin:\n"
                    + "if (large != small) { INC hits; }\nif (small != large) { INC hits; }\n"
                    + "if (0 != large) { INC hits; }\nif (large == small) { NOP; } else { INC hits; } HALT;");
            expect(run.value("large") == Integer.MAX_VALUE && run.value("small") == 2 && run.value("hits") == 4, "large operands survive both comparison orientations");
            expect(run.program().bytecode().size() < 512 && run.machine().getStepCounter() < 300,
                    "generated code is compact and execution depends on the smaller value");
        });

        check("unknown RHS variables and malformed indices fail during preflight", () -> {
            for (String condition : List.of("left == missing", "left != missing[0]", "left == cells[index]", "left ==")) {
                rejected("var left=0; var cells[2]=0; begin: if (" + condition + ") { NOP; } HALT;", Map.of());
            }
            rejected("import \"comparisons\";\nvar left=0; begin: compare(left); HALT;",
                    Map.of("comparisons", "void compare(value) {\nif (value == missing) { NOP; }\n}\n"));
        });

        check("comparison internals retain source mapping and only real control branches have roles", () -> {
            String source = "var left=0; var right=2; var hits=0;\nbegin:\n"
                    + "if (left == right) { INC hits; } else { NOP; }\n"
                    + "while (left != right) { INC left; }\n"
                    + "do { DEC right; } while (left == right);\nHALT;\n";
            CompilationResult program = compile(source, Map.of());
            expect(program.controlRoles().size() == 5 && Set.copyOf(program.controlRoles().values())
                    .equals(Set.of("branch-false", "if-exit", "while-back", "branch-true")), "comparison's internal JNZ instructions do not become diagram branches");
            int opcodes = 0;
            for (int pointer = program.codeStart(); pointer < program.bytecode().size();) {
                SourceTrace trace = program.sourceMap().get(pointer);
                expect(trace != null && trace.location().file().equals(MAIN), "each generated opcode has original provenance");
                SourceLocation location = trace.location();
                String text = source.substring(location.startOffset(), location.endOffset());
                expect(!text.isBlank(), "generated instructions map to a real source range");
                String role = program.controlRoles().get(pointer);
                if ("branch-true".equals(role)) expect(text.contains("while (left == right)"), "post-test branch maps to its trailing condition");
                if ("while-back".equals(role)) expect(text.contains("while (left != right)"), "pre-test return maps to its owning loop");
                int opcode = program.bytecode().get(pointer);
                pointer += opcode == 4 ? 3 : opcode == 2 || opcode == 3 ? 2 : 1;
                opcodes++;
            }
            expect(program.sourceMap().size() == opcodes, "data cells and instruction operands have no source execution entries");
            Run run = execute(program);
            expect(run.value("left") == 2 && run.value("right") == 1, "mapped variable conditions execute correctly");
        });

        System.out.println("CompilerValueComparisonsTest: " + passed + " passed, " + failed + " failed");
        if (failed != 0) throw new AssertionError("Value comparison regressions");
    }

    private record Run(CompilationResult program, Machine machine) {
        int address(String name) {
            return program.variables().stream().filter(variable -> variable.name().equals(name) && variable.source().callers().isEmpty())
                    .findFirst().orElseThrow().address();
        }
        int value(String name) { return machine.get(address(name)); }
    }

    private static CompilationResult compile(String source, Map<String, String> libraries) throws Exception {
        return Compiler.compileWithSourceMap(new SourceDocument(MAIN, source), path -> {
            String library = libraries.get(path.toString());
            if (library == null) throw new IOException("Unexpected import " + path);
            return new SourceDocument(Path.of("toolbox", path.toString()), library);
        }, null);
    }

    private static Run execute(String source) throws Exception { return execute(compile(source, Map.of())); }
    private static Run execute(CompilationResult program) {
        Machine machine = new Machine();
        machine.load(program.bytecode());
        machine.step(100_000);
        expect(machine.isHalted(), "comparison fixture halts within its step bound");
        return new Run(program, machine);
    }

    private static void rejected(String source, Map<String, String> libraries) throws Exception {
        try { compile(source, libraries); throw new AssertionError("Invalid RHS accepted: " + source); }
        catch (ParseException expected) { }
    }

    private static void check(String name, Check check) throws Exception {
        try { check.run(); passed++; System.out.println("PASS " + name); }
        catch (Exception | AssertionError failure) { failed++; System.err.println("FAIL " + name + ": " + failure); failure.printStackTrace(System.err); }
    }
    private static void expect(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}

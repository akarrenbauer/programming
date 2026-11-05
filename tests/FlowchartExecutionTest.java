package machine;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Actual opcode execution, source decisions, and exact allocated declaration cells. */
public final class FlowchartExecutionTest {
    private static final Path MAIN = Path.of("execution-main.a26").toAbsolutePath();
    private static final Path LIBRARY = Path.of("execution-library.a26").toAbsolutePath();
    private static int passed, failed;
    @FunctionalInterface private interface Check { void run() throws Exception; }

    public static void main(String[] args) {
        check("initial begin then exact decrement, taken jump, and zero fallthrough", () -> {
            Run run = run("var x = 5;\nbegin:\n DEC x;\n JNZ x begin;\n HALT;");
            int dec = vertex(run, "DEC x;"), jump = vertex(run, "JNZ x begin;"), halt = vertex(run, "HALT;");
            expect(run.frame().initial() && run.frame().nextVertex() == dec, "initial next decrement");
            expect(run.frame().transitions().equals(List.of(edge(run, 0, dec, ""))), "initial outgoing begin");
            expect(run.values().get(0).equals("5"), "initial global value");
            run.execution.step(run.machine);
            expect(run.frame().nextVertex() == jump && run.values().get(0).equals("4"), "decrement actual data");
            expect(run.frame().transitions().equals(List.of(edge(run, dec, jump, ""))), "decrement to jump");
            run.execution.step(run.machine);
            expect(run.frame().nextVertex() == dec, "taken jump next command");
            expect(run.frame().transitions().equals(List.of(edge(run, jump, dec, "true"))), "actual taken edge");
            run.execution.step(run.machine, 100);
            expect(run.machine.isHalted() && run.frame().nextVertex() == halt && run.values().get(0).equals("0"), "run stops at HALT");
            expect(run.frame().transitions().equals(List.of(edge(run, jump, halt, "false"))), "last batch transition is zero fallthrough");
        });

        check("self jump is a real transition even when instruction pointer is unchanged", () -> {
            Run run = run("var x=1; begin: here: JNZ x here; HALT;");
            int jump = vertex(run, "JNZ x here;");
            run.execution.step(run.machine);
            expect(run.frame().transitions().equals(List.of(edge(run, jump, jump, "true"))), "self branch retained");
            run.machine.set(1, 0);
            expect(run.frame().transitions().get(0).label().equals("true"), "data edit does not rewrite history");
            expect(run.values().get(0).equals("0"), "data edit refreshes value");
            run.execution.step(run.machine);
            expect(run.frame().transitions().get(0).label().equals("false"), "subsequent actual false branch");
        });

        check("manual and externally advanced execution clears history without inventing begin", () -> {
            Run run = run("var x=2; begin: INC x; DEC x; HALT;");
            run.machine.step();
            expect(!run.frame().initial() && run.frame().transitions().isEmpty(), "unobserved step has no inferred transition");
            run.execution.step(run.machine);
            expect(!run.frame().transitions().isEmpty(), "observed next step starts history");
            run.machine.set(0, run.program.codeStart());
            expect(run.frame().transitions().isEmpty() && !run.frame().initial(), "manual PC only highlights next command");
            run.execution.resetHistory(run.machine);
            expect(run.frame().transitions().isEmpty(), "explicit reset retains no fabricated path");
            run.machine.load(run.program.bytecode());
            expect(!run.frame().initial(), "existing tracker never invents a new compilation after reload");
        });

        check("generated comparison jumps keep the decision until its final actual branch", () -> {
            for (String operator : List.of("!=", "==")) for (int input : List.of(0, 2, 3, 4)) {
                String source = "var x=" + input + "; var yes=0; var no=0; begin: if(x " + operator
                        + " 3) { INC yes; } else { INC no; } HALT;";
                Run run = run(source);
                int decision = run.graph.vertices().stream().filter(v -> v.kind() == Flowchart.Kind.DECISION).findFirst().orElseThrow().id();
                boolean expected = operator.equals("==") == (input == 3);
                int steps = 0;
                while (run.frame().nextVertex() == decision && steps++ < 500) {
                    int pointer = run.machine.getInstructionPointer();
                    String role = run.program.controlRoles().get(pointer);
                    run.execution.step(run.machine);
                    if (role == null) expect(run.frame().nextVertex() == decision, "private comparison operation stays on decision");
                }
                expect(steps < 500, "comparison reaches branch");
                expect(run.frame().transitions().get(0).label().equals(expected ? "true" : "false"), "source truth uses real emitted branch polarity");
                run.execution.step(run.machine, 1000);
                expect(run.machine.get(1) == input && run.machine.get(expected ? 2 : 3) == 1, "comparison restores source and takes one arm");
                expect(run.frame().transitions().stream().noneMatch(e -> e.from() == decision), "synthetic if exit never revisits decision");
            }
        });

        check("value comparisons keep private work on the decision and preserve both operands", () -> {
            for (String operator : List.of("!=", "==")) for (int input : List.of(0, 2, 3, 4)) {
                String source = "var x=" + input + "; var bound=3; var yes=0; var no=0; begin: if(x " + operator
                        + " bound) { INC yes; } else { INC no; } HALT;";
                Run run = run(source);
                int decision = run.graph.vertices().stream().filter(v -> v.kind() == Flowchart.Kind.DECISION).findFirst().orElseThrow().id();
                boolean expected = operator.equals("==") == (input == 3);
                int steps = 0;
                while (run.frame().nextVertex() == decision && steps++ < 1000) {
                    int pointer = run.machine.getInstructionPointer();
                    String role = run.program.controlRoles().get(pointer);
                    run.execution.step(run.machine);
                    if (role == null) expect(run.frame().nextVertex() == decision, "private value-comparison work remains on its source diamond");
                }
                expect(steps < 1000, "dynamic comparison reaches its actual branch");
                expect(run.frame().transitions().get(0).label().equals(expected ? "true" : "false"),
                        "dynamic comparison emits the observed source truth edge");
                run.execution.step(run.machine, 1000);
                expect(run.machine.isHalted() && run.machine.get(1) == input && run.machine.get(2) == 3
                                && run.machine.get(expected ? 3 : 4) == 1 && run.machine.get(expected ? 4 : 3) == 0,
                        "comparison preserves both operand variables and executes exactly the matching branch");
                expect(run.values().size() == 4, "private comparison cells never become displayed variables");
            }
        });

        check("array-to-array comparison links complete RHS source and preserves live declaration values", () -> {
            String source = "var left[2]=2; var right[2]=2; var out=0; begin: if(left[01] == right // RHS\n [ 00 ]) { INC out; } HALT;";
            Run run = run(source);
            Flowchart.Vertex decision = run.graph.vertices().stream().filter(v -> v.kind() == Flowchart.Kind.DECISION).findFirst().orElseThrow();
            expect(run.graph.comparisonInfo(decision).right().text(source).equals("right // RHS\n [ 00 ]"),
                    "execution graph keeps the complete indexed right operand source range");
            int increment = vertex(run, "INC out;");
            untilNext(run, increment);
            expect(run.frame().transitions().get(0).from() == decision.id() && run.frame().transitions().get(0).label().equals("true"),
                    "actual array equality marks the source true branch");
            expect(run.values().get(source.indexOf("var left")).equals("[2, 2]")
                            && run.values().get(source.indexOf("var right")).equals("[2, 2]"),
                    "both arrays retain their complete contents after comparison");
            run.execution.step(run.machine, 1000);
            expect(run.machine.isHalted() && run.values().get(source.indexOf("var out")).equals("1"), "array comparison follows its visible branch");
        });

        check("while loops show body-return and exact false exit through joins", () -> {
            Run run = run("var x=2; begin: while(x!=0) { DEC x; } HALT;");
            int decision = run.graph.vertices().stream().filter(v -> v.kind() == Flowchart.Kind.DECISION).findFirst().orElseThrow().id();
            int dec = vertex(run, "DEC x;"), halt = vertex(run, "HALT;");
            untilNext(run, dec);
            expect(run.frame().transitions().get(0).label().equals("true"), "while true enters body");
            run.execution.step(run.machine);
            expect(run.frame().nextVertex() == decision && run.frame().transitions().get(0).from() == dec, "body returns to condition");
            run.execution.step(run.machine, 1000);
            expect(run.frame().nextVertex() == halt && run.frame().transitions().size() == 2, "false exit traverses hidden join");
            expect(run.frame().transitions().get(0).label().equals("false"), "false loop exit recorded");
        });

        check("post-test loops and empty bodies preserve backward route and branch polarity", () -> {
            Run run = run("var x=2; begin: do { DEC x; } while(x!=0); HALT;");
            int dec = vertex(run, "DEC x;");
            run.execution.step(run.machine, 2);
            expect(run.frame().nextVertex() == dec && run.frame().transitions().size() == 2, "footer back edge traverses entry junction");
            expect(run.frame().transitions().get(0).label().equals("true"), "post-test backward truth");
            run.execution.step(run.machine, 10);
            expect(run.machine.isHalted() && run.frame().transitions().get(0).label().equals("false"), "post-test exit");
            Run empty = run("var x=1; begin: do {} while(x!=0); HALT;");
            empty.execution.step(empty.machine);
            expect(empty.frame().transitions().size() == 2 && empty.frame().transitions().get(0).label().equals("true"), "empty loop traverses footer-entry-footer");
            Run equality = run("var x=0; begin: do { INC x; } while(x==0); HALT;");
            equality.execution.step(equality.machine, 100);
            expect(equality.machine.isHalted() && equality.frame().transitions().get(0).label().equals("false"), "equality loop does not invert actual truth");
        });

        check("empty statements and blocks are included in source transitions without invented operations", () -> {
            Run run = run("var x=1; begin: ; {} DEC x; ; {} HALT;");
            expect(run.frame().transitions().size() == 3, "initial route crosses empty statement and block");
            run.execution.step(run.machine);
            expect(run.frame().transitions().size() == 3, "actual DEC transition crosses source-only nodes");
            Run noHalt = run("var x=1; begin: DEC x;");
            noHalt.execution.step(noHalt.machine);
            expect(noHalt.graph.vertex(noHalt.frame().nextVertex()).kind() == Flowchart.Kind.END, "implicit machine HALT maps to graph END");
        });

        check("arrays and local declarations use allocated addresses despite comparison temporaries", () -> {
            String source = "var x=2; var a[3]=7; begin: if(x==2) { var local[2]=x; INC local[1]; } DEC a[2]; HALT;";
            Run run = run(source);
            expect(run.values().get(source.indexOf("var a")).equals("[7, 7, 7]"), "array declaration covers all allocated cells");
            run.execution.step(run.machine, 1000);
            expect(run.values().get(source.indexOf("var a")).equals("[7, 7, 6]"), "individual array cell reads exact address");
            expect(run.values().get(source.indexOf("var local")).equals("[2, 3]"), "local address follows private comparison cells");
            expect(run.values().size() == 3, "anonymous compiler temporaries never appear");
        });

        check("empty alternatives with identical destinations still retain the actual truth edge", () -> {
            for (int value : List.of(0, 1)) {
                Run run = run("var x=" + value + "; begin: if(x!=0) {} else {} HALT;");
                run.execution.step(run.machine, 100);
                expect(run.frame().transitions().size() == 2, "decision and join route retained");
                expect(run.frame().transitions().get(0).label().equals(value == 0 ? "false" : "true"),
                        "identical destinations do not erase measured branch");
            }
        });

        check("nested conditional continuations cross every join without returning to old decisions", () -> {
            Run run = run("var x=1; begin: if(x!=0) { if(x!=0) { DEC x; } else { NOP; } } else { NOP; } HALT;");
            int dec = vertex(run, "DEC x;"), halt = vertex(run, "HALT;");
            untilNext(run, dec);
            run.execution.step(run.machine);
            expect(run.frame().nextVertex() == halt && run.frame().transitions().size() == 3, "nested synthetic continuations reach HALT through two joins");
            List<Flowchart.Edge> last = run.frame().transitions();
            run.execution.step(run.machine, 20);
            expect(run.frame().transitions().equals(last), "hidden continuation bytecode does not invent another transition");
        });

        check("rebinding begin and helper-only contexts do not fabricate start destinations", () -> {
            Run run = run("var x=1; begin: NOP; begin : DEC x; HALT;");
            int dec = vertex(run, "DEC x;");
            expect(run.frame().nextVertex() == dec && run.frame().transitions().get(0).to() == dec, "actual compiled begin binding controls initial arrow");
            Flowchart helper = Flowchart.from(StructuredDiagram.parseDocument("void work(x) { INC x; }", LanguageVersion.STRUCTURED, true));
            expect(run.execution.frame(helper, LIBRARY, run.machine).nextVertex() == -1, "uncompiled helper has no invented execution node");
            Flowchart edited = Flowchart.from(StructuredDiagram.parse("var x=2; begin: HALT;", LanguageVersion.STRUCTURED, true));
            expect(run.execution.frame(edited, MAIN, run.machine).nextVertex() == -1, "different source snapshot has no stale vertex IDs");
        });

        check("macro expansion stays on caller process and local instances select active invocation", () -> {
            String library = "void work(value) {\n var local=1;\n INC value;\n while(local!=0) {\n DEC local;\n }\n}\n";
            String source = "import \"execution-library.a26\";\nvar x=0; begin: NOP; work(x); work(x); HALT;";
            Run run = run(source, library);
            int firstCall = run.graph.vertices().stream().filter(v -> v.owner() != null && v.owner().editText().equals("work(x);")).findFirst().orElseThrow().id();
            run.execution.step(run.machine);
            expect(run.frame().nextVertex() == firstCall, "macro caller maps to process");
            List<Flowchart.Edge> entering = run.frame().transitions();
            expect(run.execution.values(LIBRARY, run.machine).size() == 1, "only current invocation declaration visible");
            for (int steps = 0; steps < 100 && run.frame().nextVertex() == firstCall; ++steps) {
                run.execution.step(run.machine);
                if (run.frame().nextVertex() == firstCall) expect(run.frame().transitions().equals(entering), "macro internals retain last visible transition");
            }
            expect(run.frame().nextVertex() != firstCall && run.frame().transitions().get(0).from() == firstCall, "macro exit reaches next call");
            run.execution.step(run.machine, 1000);
            expect(run.machine.get(1) == 2 && run.execution.values(LIBRARY, run.machine).isEmpty(), "inactive helper instances omitted");
        });

        check("immutable metadata and old constructor remain compatible", () -> {
            Run run = run("var x=1; begin: JNZ x begin; HALT;");
            CompilationResult old = new CompilationResult(run.program.bytecode(), run.program.sourceMap(), run.program.codeStart(), run.program.sources());
            expect(old.variables().isEmpty() && old.controlRoles().isEmpty(), "four-argument compatibility");
            try { run.program.variables().clear(); throw new AssertionError("mutable variable list"); } catch (UnsupportedOperationException expected) { }
            try { run.program.controlRoles().clear(); throw new AssertionError("mutable control map"); } catch (UnsupportedOperationException expected) { }
        });

        check("unsaved main uses an exact frozen snapshot without adding a fake compiler file identity", () -> {
            SourceDocument source = new SourceDocument(null, "var x=2;\r\nbegin: DEC x; JNZ x begin; HALT;");
            CompilationResult program;
            try (PrintStream sink = new PrintStream(OutputStream.nullOutputStream())) {
                program = Compiler.compileWithSourceMap(source, path -> { throw new AssertionError("Unexpected import"); }, sink, LanguageVersion.STRUCTURED);
            }
            expect(program.sources().isEmpty(), "compiler unnamed-source contract unchanged");
            Flowchart graph = Flowchart.from(StructuredDiagram.parse(source.text(), LanguageVersion.STRUCTURED, true));
            Machine machine = new Machine(); machine.load(program.bytecode());
            FlowchartExecution execution = new FlowchartExecution(program, source);
            expect(execution.source(null).equals(source.text()), "separate normalized frozen unnamed snapshot");
            expect(execution.frame(graph, null, machine).initial() && execution.frame(graph, null, machine).nextVertex() == 1,
                    "unsaved program starts on first command");
            expect(execution.values(null, machine).get(0).equals("2"), "unsaved initial value");
            execution.step(machine, 10);
            expect(machine.isHalted() && execution.values(null, machine).get(0).equals("0"), "unsaved stepped value");
            expect(execution.frame(graph, null, machine).transitions().get(0).label().equals("false"), "unsaved last actual edge");
        });

        System.out.println(passed + " passed, " + failed + " failed");
        if (failed != 0) throw new AssertionError("Flowchart execution regressions");
    }

    private record Run(CompilationResult program, Machine machine, Flowchart graph, FlowchartExecution execution) {
        FlowchartExecution.Frame frame() { return execution.frame(graph, MAIN, machine); }
        Map<Integer, String> values() { return execution.values(MAIN, machine); }
    }
    private static Run run(String source) throws Exception { return run(source, null); }
    private static Run run(String source, String library) throws Exception {
        CompilationResult program;
        try (PrintStream sink = new PrintStream(OutputStream.nullOutputStream())) {
            program = Compiler.compileWithSourceMap(new SourceDocument(MAIN, source), path -> {
                if (library == null || !path.getFileName().equals(LIBRARY.getFileName())) throw new AssertionError("Unexpected import " + path);
                return new SourceDocument(LIBRARY, library);
            }, sink, LanguageVersion.STRUCTURED);
        }
        Machine machine = new Machine();
        machine.load(program.bytecode());
        return new Run(program, machine, Flowchart.from(StructuredDiagram.parse(source, LanguageVersion.STRUCTURED, true)), new FlowchartExecution(program));
    }
    private static void untilNext(Run run, int vertex) {
        for (int count = 0; count < 1000 && run.frame().nextVertex() != vertex; ++count) run.execution.step(run.machine);
        expect(run.frame().nextVertex() == vertex, "expected next vertex " + vertex);
    }
    private static int vertex(Run run, String text) {
        return run.graph.vertices().stream().filter(v -> v.owner() != null && v.owner().editText().equals(text)).findFirst().orElseThrow().id();
    }
    private static Flowchart.Edge edge(Run run, int from, int to, String label) {
        return run.graph.edges().stream().filter(e -> e.from() == from && e.to() == to && e.label().equals(label)).findFirst().orElseThrow();
    }
    private static void expect(boolean condition, String detail) { if (!condition) throw new AssertionError(detail); }
    private static void check(String name, Check test) {
        try { test.run(); passed++; System.out.println("PASS " + name); }
        catch (Throwable error) { failed++; System.err.println("FAIL " + name + ": " + error); error.printStackTrace(); }
    }
}

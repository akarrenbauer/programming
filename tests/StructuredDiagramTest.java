package machine;

import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import machine.StructuredDiagram.Kind;
import machine.StructuredDiagram.Node;
import machine.StructuredDiagram.ParseException;

/** Source/diagram round trips, editing contracts, and the complete structured curriculum. */
public final class StructuredDiagramTest {
    private static int passed;
    private static int failed;

    @FunctionalInterface
    private interface Check { void run() throws Exception; }

    public static void main(String[] args) {
        check("nested control flow keeps each branch and post-test condition", () -> {
            String source = "var x=3; var result=0; begin:\nwhile (x != 0) {\n"
                    + "if (x == 1) { INC result; } else { do { DEC x; } while (x != 0); }\n}\nHALT;\n";
            StructuredDiagram diagram = StructuredDiagram.parse(source);
            equal(source, diagram.source(), "parse does not rewrite source");
            equal(2, diagram.preamble().nodes().size(), "declarations");
            Node loop = diagram.main().nodes().get(0);
            equal(Kind.WHILE, loop.kind(), "outer kind");
            equal("x != 0", loop.editText(), "outer condition");
            Node choice = loop.body().nodes().get(0);
            equal(Kind.IF, choice.kind(), "nested choice");
            equal("INC result;", choice.body().nodes().get(0).text(), "true branch");
            Node postTest = choice.alternative().nodes().get(0);
            equal(Kind.DO_WHILE, postTest.kind(), "false branch loop");
            equal("x != 0", postTest.editText(), "footer condition");
            equal("DEC x;", postTest.body().nodes().get(0).text(), "post-test body");
            equal(8, diagram.nodeCount(), "node count includes preamble and both nested branches");
        });

        check("condition edits preserve unrelated comments whitespace declarations and CRLF", () -> {
            String source = "// header: JNZ is mentioned only in prose\r\nvar x = 1;\r\nbegin:\r\n"
                    + "  if ( x == 0 ) { // true\r\n    INC x;\r\n  } else { // false\r\n    NOP;\r\n  }\r\nHALT; // tail\r\n";
            StructuredDiagram diagram = StructuredDiagram.parse(source);
            StructuredDiagram changed = diagram.edit(diagram.main().nodes().get(0), "x != 0");
            equal(source.replace(" x == 0 ", "x != 0"), changed.source(), "only condition span changes");
            equal(source, diagram.source(), "original snapshot is immutable");
        });

        check("both condition operands accept values and array edits retain source trivia", () -> {
            String condition = "left // left operand\r\n [ 01 ] != right // right operand\r\n [ 00 ]";
            String source = "var left[2]=1; var right[2]=0; var limit=0;\r\nbegin:\r\n"
                    + "if (" + condition + ") { NOP; } // keep branch\r\n"
                    + "while (01 == limit) { DEC limit; }\r\n"
                    + "do { INC right[0]; } while (left[1] != right[0]);\r\nHALT; // keep tail\r\n";
            StructuredDiagram diagram = StructuredDiagram.parse(source);
            equal(source, diagram.source(), "parsing preserves both operand comments and CRLF");
            equal(condition, diagram.main().nodes().get(0).editText(), "complete comparison stays editable");
            equal("01 == limit", diagram.main().nodes().get(1).editText(), "literal-to-variable condition");
            equal("left[1] != right[0]", diagram.main().nodes().get(2).editText(), "array-to-array post-test");
            StructuredDiagram changed = diagram.edit(diagram.main().nodes().get(0), "right[01] == left[00]");
            equal(source.replace(condition, "right[01] == left[00]"), changed.source(),
                    "changing both operands changes only the condition span");
            StructuredDiagram legacy = StructuredDiagram.parse("var x=0; var y=0; begin: while(x != y) {} HALT;",
                    LanguageVersion.BEFORE_DO_WHILE, true);
            equal("x != y", legacy.main().nodes().get(0).editText(), "value comparison is not tied to do/while keyword profile");
            rejects(() -> StructuredDiagram.parse("begin: if(x == cells[index]) {}"), "Expected a nonnegative number");
            rejects(() -> StructuredDiagram.parse("begin: if(x == while) {}"), "Expected an identifier");
        });

        check("statement and declaration edits change actual compiled execution", () -> {
            StructuredDiagram diagram = StructuredDiagram.parse("var x=2; var result=0; begin:\n"
                    + "while(x!=0) { DEC x; INC result; }\nHALT;\n");
            equal(2, execute(diagram.source()).get(2), "original result");
            diagram = diagram.edit(diagram.preamble().nodes().get(0), "var x=4;");
            Node body = diagram.main().nodes().get(0).body().nodes().get(1);
            diagram = diagram.edit(body, "INC result; INC result;");
            equal(8, execute(diagram.source()).get(2), "diagram edit compiles and runs");
            equal(3, diagram.main().nodes().get(0).body().nodes().size(), "new source reparses into new nodes");
        });

        check("insert and delete nested statements and add an editable else branch", () -> {
            StructuredDiagram diagram = StructuredDiagram.parse("var x=0; begin: if(x==0) {} HALT; // tail");
            Node choice = diagram.main().nodes().get(0);
            diagram = diagram.insert(choice.body(), 0, "INC x;");
            choice = diagram.main().nodes().get(0);
            equal(1, diagram.main().nodes().get(0).body().nodes().size(), "empty body insertion");
            diagram = diagram.addElse(choice);
            choice = diagram.main().nodes().get(0);
            diagram = diagram.insert(choice.alternative(), 0, "DEC x;");
            equal("DEC x;", diagram.main().nodes().get(0).alternative().nodes().get(0).text(), "else insertion");
            diagram = diagram.delete(diagram.main().nodes().get(0).body().nodes().get(0));
            equal(0, diagram.main().nodes().get(0).body().nodes().size(), "delete only child");
            expect(diagram.source().endsWith("HALT; // tail"), "unrelated trailing source survives");
        });

        check("end insertion escapes a trailing line comment", () -> {
            StructuredDiagram diagram = StructuredDiagram.parse("begin: // still editable");
            diagram = diagram.insert(diagram.main(), 0, "NOP;");
            equal(1, diagram.main().nodes().size(), "inserted instruction is not swallowed by comment");
            expect(diagram.source().contains("// still editable\n"), "comment text remains intact");
        });

        check("plain blocks and empty statements are represented", () -> {
            StructuredDiagram diagram = StructuredDiagram.parse("begin: ; { ; NOP; } HALT;");
            equal(";", diagram.main().nodes().get(0).text(), "empty statement");
            Node block = diagram.main().nodes().get(1);
            equal(Kind.BLOCK, block.kind(), "plain block");
            equal(2, block.body().nodes().size(), "block children");
        });

        check("macro signatures specializations arrays imports and runtime declarations remain editable", () -> {
            String source = "import \"toolbox\";\nvoid choose(0,out) { var scratch[2] = 0; INC out; }\n"
                    + "void choose(mode,out) { if(mode!=0) { DEC out; } }\nvar data[2]=0; begin: choose(0,data[1]); HALT;";
            StructuredDiagram diagram = StructuredDiagram.parse(source);
            equal(Kind.IMPORT, diagram.preamble().nodes().get(0).kind(), "import leaf");
            Node macro = diagram.preamble().nodes().get(1);
            equal("void choose(0,out)", macro.editText(), "specialization header");
            equal(Kind.DECLARATION, macro.body().nodes().get(0).kind(), "runtime declaration");
            diagram = diagram.edit(macro, "void choose(1,out)");
            expect(diagram.source().contains("void choose(1,out) { var scratch[2] = 0; INC out; }"), "body preserved");
            equal("choose(0,data[1]);", diagram.main().nodes().get(0).text(), "array argument leaf");
        });

        check("imports and declarations follow the same program ordering as the checker", () -> {
            StructuredDiagram.parse("\timport \"toolbox\";\r\nvoid f() {}\r\nvar x=0;\r\nbegin: var local=1; f(); HALT;");
            for (String source : List.of("import \"toolbox\"; begin: HALT;", "import \"toolbox\" ;\nbegin:",
                    "import \"toolbox\"; // trailing comment\nbegin:")) {
                rejects(() -> StructuredDiagram.parse(source), "Put each import on its own line");
            }
            for (String source : List.of("var x=0; void f() {} begin: HALT;", "var x=0;\nimport \"toolbox\";\nbegin:",
                    "begin: void f() {} HALT;")) {
                rejects(() -> StructuredDiagram.parse(source), "Expected");
            }
        });

        check("harmless labels annotate statements and survive ordinary edits", () -> {
            StructuredDiagram diagram = StructuredDiagram.parse("var x=1; begin: first: second: INC x;\n"
                    + "loop: while(x!=0) { inside: DEC x; } HALT;");
            Node instruction = diagram.main().nodes().get(0);
            equal("first: second:", instruction.labels(), "multiple labels");
            equal("INC x;", instruction.editText(), "leaf edit excludes labels");
            diagram = diagram.edit(instruction, "NOP;");
            expect(diagram.source().contains("first: second: NOP;"), "labels preserved");
            Node loop = diagram.main().nodes().get(1);
            equal("loop:", loop.labels(), "loop label");
            diagram = diagram.edit(loop, "x == 0");
            expect(diagram.source().contains("loop: while(x == 0)"), "condition edit preserves loop label");
            equal("inside:", diagram.main().nodes().get(1).body().nodes().get(0).labels(), "nested annotation");
        });

        check("leading zeros remain unchanged in values indices and macro specializations", () -> {
            String source = "void choose(01,out) { INC out; }\nvar cells[02]=000; var x=0002; begin:\n"
                    + "choose(01,cells[01]);\nwhile(x!=00) { DEC x; INC cells[00]; } HALT;\n";
            StructuredDiagram diagram = StructuredDiagram.parse(source);
            equal(source, diagram.source(), "literal spelling is preserved exactly");
            equal("void choose(01,out)", diagram.preamble().nodes().get(0).editText(), "literal macro spelling");
            Machine original = execute(source);
            equal(2, original.get(1), "leading-zero loop counter executes");
            equal(1, original.get(2), "leading-zero macro dispatch executes");
            Node condition = diagram.main().nodes().get(1);
            diagram = diagram.edit(condition, "x != 000");
            Machine edited = execute(diagram.source());
            equal(original.get(1), edited.get(1), "equivalent numeric condition preserves execution");
            equal(original.get(2), edited.get(2), "macro specialization survives unrelated diagram edit");
        });

        check("invalid edits never replace the source and old node handles are rejected", () -> {
            StructuredDiagram diagram = StructuredDiagram.parse("var x=1; begin: while(x!=0) {DEC x;} HALT;");
            Node loop = diagram.main().nodes().get(0);
            String before = diagram.source();
            rejects(() -> diagram.edit(loop, "x !="), "Expected an identifier");
            equal(before, diagram.source(), "failed edit preserves source");
            StructuredDiagram next = diagram.edit(loop, "x == 0");
            try {
                next.delete(loop);
                throw new AssertionError("stale edit was accepted");
            } catch (IllegalArgumentException expected) { }
        });

        check("unfinished code reports an offset without inventing or discarding code", () -> {
            for (String source : List.of("", "begin: if(x==0)", "begin: do { NOP; }", "begin: INC x",
                    "begin: while(x != 0) {", "import \"unfinished\nbegin:")) {
                try {
                    StructuredDiagram.parse(source);
                    throw new AssertionError("incomplete source parsed: " + source);
                } catch (ParseException expected) {
                    expect(expected.offset() >= 0 && expected.offset() <= source.length(), "diagnostic source offset");
                    expect(expected.getMessage().contains("line "), "diagnostic line");
                }
            }
        });

        check("explicit jumps are rejected in main nested blocks and macro bodies", () -> {
            for (String source : List.of("var x=1; begin: target: JNZ x target;",
                    "var x=1; begin: while(x!=0) { JNZ x next; } next: HALT;",
                    "void jump(x) { target: JNZ x target; } var x=1; begin: jump(x); HALT;")) {
                rejects(() -> StructuredDiagram.parse(source), "JNZ is suspended");
            }
            StructuredDiagram.parse("import \"JNZ\";\nvar x=0; begin: // JNZ x label;\nHALT;");
        });

        check("accidental large and deeply nested drafts fail in bounded work", () -> {
            rejects(() -> StructuredDiagram.parse("begin:" + " ".repeat(65_537)), "size limit");
            rejects(() -> StructuredDiagram.parse("begin:" + "{".repeat(65) + "}".repeat(65)), "nested too deeply");
            rejects(() -> StructuredDiagram.parse("begin:" + ";".repeat(12_001)), "Too many tokens");
        });

        check("all structured-world starters and reference solutions parse without source loss", () -> {
            Map<String, String> solutions = GameCurriculumTest.solutions();
            Set<GameFeature> earned = EnumSet.noneOf(GameFeature.class);
            Set<GameFeature> controls = Set.of(GameFeature.DO_WHILE, GameFeature.IF, GameFeature.ELSE, GameFeature.WHILE);
            int checked = 0;
            for (GameLevel level : GameCatalog.levels()) {
                if (earned.containsAll(controls)) {
                    equal(level.starter(), StructuredDiagram.parse(level.starter()).source(), "starter " + level.id());
                    String solution = solutions.get(level.id());
                    expect(solution != null, "reference solution for " + level.id());
                    equal(solution, StructuredDiagram.parse(solution).source(), "solution " + level.id());
                    ++checked;
                }
                earned.addAll(level.unlocks());
            }
            expect(checked >= 30, "coverage includes every later world");
        });

        System.out.println("StructuredDiagramTest: " + passed + " passed, " + failed + " failed");
        if (failed != 0) throw new AssertionError(failed + " diagram checks failed");
    }

    private static Machine execute(String source) throws Exception {
        CompilationResult compiled = Compiler.compileWithSourceMap(new SourceDocument(Path.of("diagram.a26"), source),
                path -> { throw new AssertionError("unexpected import"); }, null);
        Machine machine = new Machine();
        machine.load(compiled.bytecode());
        machine.step(10_000);
        expect(machine.isHalted(), "edited program halts");
        return machine;
    }

    private static void rejects(Check action, String message) throws Exception {
        try { action.run(); }
        catch (ParseException expected) {
            expect(expected.getMessage().contains(message), "diagnostic: " + expected.getMessage());
            return;
        }
        throw new AssertionError("expected parse failure containing " + message);
    }

    private static void check(String name, Check action) {
        try { action.run(); ++passed; System.out.println("PASS " + name); }
        catch (Exception | AssertionError error) { ++failed; System.err.println("FAIL " + name + ": " + error); error.printStackTrace(); }
    }

    private static void equal(Object expected, Object actual, String message) {
        expect(expected.equals(actual), message + ": expected " + expected + ", found " + actual);
    }

    private static void expect(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

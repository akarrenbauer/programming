package machine;

import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Pattern;

/** Whitespace/token invariants and execution checks for source and earned-helper formatting. */
public final class SourceFormatterTest {
    private static final Path MAIN = Path.of("formatted.a26");
    private static final Pattern TOKENS = Pattern.compile("//[^\\r\\n]*|/\\*[\\s\\S]*?(?:\\*/|$)|\"[^\"\\r\\n]*(?:\"|(?=\\r|\\n|$))|\\bbegin:|[A-Za-z_][A-Za-z_0-9]*|[0-9]+|==|!=|[^\\s]");
    private static int passed, failed;
    @FunctionalInterface private interface Check { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        check("two-space brace indentation handles nested branches and post-test loops", () -> {
            String source = "var x=2;begin:while(x!=0){if(x==1){INC x;}else{do{DEC x;}while(x!=0);}}HALT;";
            String expected = "var x = 2;\nbegin:\nwhile (x != 0) {\n  if (x == 1) {\n    INC x;\n  } else {\n    do {\n      DEC x;\n    } while (x != 0);\n  }\n}\nHALT;\n";
            expect(SourceFormatter.format(source).equals(expected), SourceFormatter.format(source));
            stable(source);
            String separate = SourceFormatter.format("begin:while(0!=0){}while(0!=0){}do{do{}while(0!=0);}while(0!=0);HALT;");
            expect(separate.contains("}\nwhile (0 != 0) {") && separate.contains("  } while (0 != 0);\n} while (0 != 0);"),
                    "only do-body closing braces join a following while");
        });

        check("macro signatures calls and documentation stay on safe lines", () -> {
            String source = "// first helper\nvoid bump ( out ){INC out;}\n// second helper\n// keeps its argument\nvoid twice( x ){bump(x);bump(x);}\nvar x=0;begin:twice( x );HALT;";
            String formatted = SourceFormatter.format(source);
            expect(formatted.contains("void bump(out) {\n  INC out;\n}\n\n// second helper\n// keeps its argument\nvoid twice(x) {\n  bump(x);\n  bump(x);\n}"), formatted);
            expect(formatted.contains("begin:\ntwice(x);\nHALT;\n"), "macro calls remain complete independent lines");
            sameProgram(source, Map.of(), LanguageVersion.STRUCTURED);
            Machine run = run(compile(formatted, Map.of(), LanguageVersion.STRUCTURED));
            expect(run.get(1) == 2, "formatted calls execute both expansions");
            stable(source);
        });

        check("keyword-prefixed macro names and labels stay ordinary identifiers on their own lines", () -> {
            String source = "import \"toolbox\";\nvoid important(x) { bump(x); }\nvoid import(x) { INC x; }\n"
                    + "var x=2; begin: important(x); import(x); important_label: DEC x; JNZ x important_label; import : NOP; HALT;";
            Map<String, String> libraries = Map.of("toolbox", "void bump(x) {\nINC x;\n}\n");
            String formatted = SourceFormatter.format(source);
            expect(formatted.contains("\nimportant(x);\nimport(x);\nimportant_label:\n") && formatted.contains("\nimport:\nNOP;\n"),
                    "formatting gives identifier calls and labels their own compiler-safe lines");
            sameProgram(source, libraries, LanguageVersion.STRUCTURED);
            expect(run(compile(formatted, libraries, LanguageVersion.STRUCTURED)).get(1) == 0,
                    "real imports, import-named calls, and prefix labels execute normally");
            stable(source);
            String label = "var x=0; begin: { NOP; } void_label: INC x; HALT;";
            sameProgram(label, Map.of(), LanguageVersion.STRUCTURED);
            expect(run(compile(SourceFormatter.format(label), Map.of(), LanguageVersion.STRUCTURED)).get(1) == 1,
                    "moving a void-prefixed label after a closing brace onto its own line preserves execution");
            String macro = "void voided(x) { INC x; }\nvar x=0; begin: voided(x); HALT;";
            sameProgram(macro, Map.of(), LanguageVersion.STRUCTURED);
            expect(run(compile(SourceFormatter.format(macro), Map.of(), LanguageVersion.STRUCTURED)).get(1) == 1,
                    "a void-prefixed call is not mistaken for a macro definition");
        });

        check("macro declarations and private labels after a closing brace retain their scope", () -> {
            String shadow = "void helper(out) {\nif(out==0) { NOP; } var temp=1;\nINC temp;\nINC out;\n}\n"
                    + "var temp=5; var result=0; begin: helper(result); INC temp; HALT;";
            sameProgram(shadow, Map.of(), LanguageVersion.STRUCTURED);
            Machine shadowed = run(compile(SourceFormatter.format(shadow), Map.of(), LanguageVersion.STRUCTURED));
            expect(shadowed.get(1) == 6 && shadowed.get(2) == 1, "a formatted macro local does not capture its same-named global");
            String labels = "void helper(out) {\nvar remaining=2;\nif(out==0) { NOP; } loop: INC out;\n"
                    + "DEC remaining;\nJNZ remaining loop;\n}\nvar x=0; var y=0; begin: helper(x); helper(y); HALT;";
            sameProgram(labels, Map.of(), LanguageVersion.STRUCTURED);
            Machine repeated = run(compile(SourceFormatter.format(labels), Map.of(), LanguageVersion.STRUCTURED));
            expect(repeated.get(1) == 2 && repeated.get(2) == 2, "private labels stay independent in repeated macro calls");
        });

        check("ordinary begin labels legacy do labels and consecutive empty statements survive", () -> {
            String source = "var x=2;var done=0;begin:begin :INC done;do:DEC x;JNZ x do;;;HALT;";
            String formatted = SourceFormatter.format(source);
            expect(formatted.contains("begin:\nbegin :\nINC done;\ndo:\nDEC x;") && formatted.contains("JNZ x do;\n;\n;\nHALT;"), formatted);
            sameProgram(source, Map.of(), LanguageVersion.BEFORE_DO_WHILE);
            Machine run = run(compile(formatted, Map.of(), LanguageVersion.BEFORE_DO_WHILE));
            expect(run.get(1) == 0 && run.get(2) == 1, "formatting preserves entry rebinding and legacy jump destinations");
            stable(source);
            stable("begin // preserve ordinary label\n : NOP;");
        });

        check("comments quoted import paths and literal spelling are unchanged", () -> {
            String path = "dir//name{a}:;\\tool box.a26";
            String source = "import \"" + path + "\";\n// retain   spacing { ; }\r\nvoid put(01,out){INC out;}\r\nvar cells[02]=00;begin:put(01,cells[01]);HALT; // tail  ";
            String formatted = SourceFormatter.format(source);
            expect(formatted.startsWith("import \"" + path + "\";\n") && formatted.contains("// retain   spacing { ; }")
                    && formatted.endsWith("HALT; // tail  \n") && formatted.contains("void put(01, out)") && formatted.contains("cells[02] = 00"), formatted);
            sameProgram(source, Map.of(Path.of(path).toString(), ""), LanguageVersion.STRUCTURED);
            stable(source);
            String withComment = SourceFormatter.format("import \"toolbox\"; // import note\nvar x=0;begin:HALT;");
            expect(withComment.startsWith("import \"toolbox\";\n// import note\n"), "import comments cannot prevent the compiler from reading the import line");
            compile(withComment, Map.of("toolbox", ""), LanguageVersion.STRUCTURED);
        });

        check("tolerant drafts retain separated tokens strings comments and parenthesized semicolons", () -> {
            for (String source : List.of("", "  \t\r\n", "var x =", "begin:if(x != ){INC x;", "}}var x=1;",
                    "begin: call(a;b, c);", "begin: x ! = y; a = = b; / / still_not_a_comment;",
                    "import \"unfinished // { ;\nvar x=0;begin:HALT;", "begin: // comment with } and ;\nINC x;",
                    "/* keep\n * these  bytes\n */ var x=0;begin:HALT;", "var a=12abc; begin : NOP;", "begin: 😀 ;")) {
                stable(source);
            }
            expect(SourceFormatter.format(null).isEmpty(), "null draft is empty");
            expect(SourceFormatter.format("call(a;b,c);").equals("call(a; b, c);\n"), "semicolons inside parentheses do not split a call draft");
            String brokenString = SourceFormatter.format("import \"unfinished\nvar x=0;");
            expect(brokenString.contains("\"unfinished\nvar x = 0;"), "an unterminated quoted path cannot swallow the following line");
            expect(SourceFormatter.format("/* unfinished { ;").equals("/* unfinished { ;\n"), "unterminated block comment remains intact");
        });

        check("mixed incomplete token sequences retain tokens and reach a stable format", () -> {
            Random random = new Random(20261105);
            String[] fragments = {"var", "name", "01", "begin:", "begin", ":", "do", "while", "void", "if", "else",
                    "(", ")", "[", "]", "{", "}", ";", ",", "=", "!=", "!", "/", "*", "// note  ",
                    "/* note */", "\"path//{;}:\"", "\"unfinished", "😀"};
            String[] gaps = {" ", "\t", "\n", "\r\n", "\n\n"};
            for (int example = 0; example < 1_000; example++) {
                StringBuilder draft = new StringBuilder();
                for (int index = 0; index < 20; index++) draft.append(fragments[random.nextInt(fragments.length)])
                        .append(gaps[random.nextInt(gaps.length)]);
                stable(draft.toString());
            }
        });

        check("all curriculum drafts and cumulative libraries are token-preserving and idempotent", () -> {
            Set<String> completed = new LinkedHashSet<>();
            int compilable = 0;
            for (GameLevel level : GameCatalog.levels()) {
                stable(level.starter());
                Map<String, String> libraries = GameCatalog.library(completed);
                LanguageVersion version = GameLanguage.editingVersionFor(level, EnumSet.allOf(GameFeature.class), level.starter());
                CompilationResult before = null;
                try { before = compile(level.starter(), libraries, version); }
                catch (ParseException | IllegalArgumentException | TokenMgrError incomplete) { /* Some starter drafts intentionally need completing. */ }
                if (before != null) {
                    CompilationResult after = compile(SourceFormatter.format(level.starter()), libraries, version);
                    expect(before.bytecode().equals(after.bytecode()), "formatting changes starter bytecode for " + level.id());
                    compilable++;
                }
                completed.add(level.id());
                String toolbox = GameCatalog.library(completed).get("toolbox");
                stable(toolbox);
                sameProgram(toolbox + "\nbegin: HALT;", Map.of(), LanguageVersion.STRUCTURED);
            }
            expect(compilable > 20, "exercise broad coverage of compiling starters");
        });

        check("formatted complete earned toolbox compiles and executes through a virtual import", () -> {
            Set<String> completed = new LinkedHashSet<>();
            GameCatalog.levels().forEach(level -> completed.add(level.id()));
            String toolbox = GameCatalog.library(completed).get("toolbox");
            String formatted = SourceFormatter.format(toolbox);
            String source = "import \"toolbox\";\nvar input=3;var out=0;begin:copyTo(input,out);bump(out);HALT;";
            CompilationResult before = compile(source, Map.of("toolbox", toolbox), LanguageVersion.STRUCTURED);
            CompilationResult after = compile(SourceFormatter.format(source), Map.of("toolbox", formatted), LanguageVersion.STRUCTURED);
            expect(before.bytecode().equals(after.bytecode()), "formatted helpers produce the same executable image");
            Machine run = run(after);
            expect(run.get(1) == 3 && run.get(2) == 4, "earned helper copies and increments without changing its input");
        });

        System.out.println("SourceFormatterTest: " + passed + " passed, " + failed + " failed");
        if (failed != 0) throw new AssertionError("Source formatting regressions");
    }

    private static void stable(String source) {
        String formatted = SourceFormatter.format(source);
        expect(formatted.equals(SourceFormatter.format(formatted)), "formatting is not idempotent:\n" + formatted + "\nTHEN:\n" + SourceFormatter.format(formatted));
        expect(tokens(source).equals(tokens(formatted)), "source tokens changed:\n" + source + "\nTO:\n" + formatted);
        expect(formatted.isEmpty() || formatted.endsWith("\n"), "nonempty formatted source ends in a newline");
    }
    private static List<String> tokens(String source) { return TOKENS.matcher(source).results().map(match -> match.group()).toList(); }
    private static void sameProgram(String source, Map<String, String> libraries, LanguageVersion version) throws Exception {
        expect(compile(source, libraries, version).bytecode().equals(compile(SourceFormatter.format(source), libraries, version).bytecode()),
                "formatting changes executable image:\n" + source);
    }
    private static CompilationResult compile(String source, Map<String, String> libraries, LanguageVersion version) throws Exception {
        return Compiler.compileWithSourceMap(new SourceDocument(MAIN, source), path -> {
            String text = libraries.get(path.toString());
            if (text == null) throw new IOException("Unexpected virtual import " + path);
            return new SourceDocument(Path.of("toolbox", path.toString()), text);
        }, null, version);
    }
    private static Machine run(CompilationResult program) {
        Machine machine = new Machine(); machine.load(program.bytecode()); machine.step(100_000);
        expect(machine.isHalted(), "formatted fixture halts"); return machine;
    }
    private static void check(String name, Check test) throws Exception {
        try { test.run(); passed++; System.out.println("PASS " + name); }
        catch (Exception | AssertionError failure) { failed++; System.err.println("FAIL " + name + ": " + failure); failure.printStackTrace(System.err); }
    }
    private static void expect(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}

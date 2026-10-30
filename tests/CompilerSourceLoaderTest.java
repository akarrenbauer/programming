import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import machine.Compiler.SourceLoader;
import machine.Machine;
import machine.ParseException;
import machine.TokenMgrError;

/**
 * Run from the repository root without using the existing machine/*.class files:
 *
 * test_build=$(mktemp -d)
 * javac -d "$test_build" -classpath "$test_build" -sourcepath . tests/CompilerSourceLoaderTest.java
 * java -cp "$test_build" CompilerSourceLoaderTest
 */
public class CompilerSourceLoaderTest {
    private static int passed;
    private static int failed;

    @FunctionalInterface
    private interface Test {
        void run() throws Exception;
    }

    @FunctionalInterface
    private interface Operation<T> {
        T run() throws Exception;
    }

    public static void main(String[] args) throws Exception {
        Path temporaryDirectory = Files.createTempDirectory("compiler-source-loader-test-");

        check("compilation uses main and import snapshots without writing files", () -> {
            Path imported = temporaryDirectory.resolve("snapshot.ad04");
            Files.writeString(imported, "var value = 1;\n");
            List<String> main = List.of(directive(imported), "begin: INC value; HALT;");
            Map<Path, List<String>> firstSnapshot = Map.of(imported, List.of("var value = 7;"));
            List<Integer> first = compile(main, firstSnapshot::get);
            expect(8, execute(first).get(1), "first import buffer");

            Map<Path, List<String>> secondSnapshot = Map.of(imported, List.of("var value = 10;"));
            List<Integer> second = compile(
                List.of(directive(imported), "begin: DEC value; HALT;"), secondSnapshot::get
            );
            expect(9, execute(second).get(1), "edited main and import buffers");
            expect(8, execute(first).get(1), "previous compilation must remain independent");
            expect("var value = 1;\n", Files.readString(imported), "compilation must not save buffers");
        });

        check("multiple and duplicate imports preserve every occurrence in order", () -> {
            Path first = Path.of("first.ad04");
            Path second = Path.of("second.ad04");
            List<Path> requested = new ArrayList<>();
            SourceLoader loader = path -> {
                requested.add(path);
                return path.equals(first) ? List.of("var first = 1;") : List.of("var second = 2;");
            };
            Machine machine = execute(compile(
                List.of(directive(first), directive(second), directive(first), "begin: HALT;"), loader
            ));
            expect(List.of(first, second, first), requested, "loader invocation order");
            expect(1, machine.get(1), "first import occurrence");
            expect(2, machine.get(2), "second import occurrence");
            expect(1, machine.get(3), "duplicate import must still contribute source");
        });

        check("loader receives relative and absolute paths unchanged", () -> {
            Path relative = Path.of("folder", "..", "library.ad04");
            Path absolute = temporaryDirectory.resolve("absolute.ad04");
            List<Path> requested = new ArrayList<>();
            List<String> expanded = machine.Compiler.includeImports(
                List.of("  import \"  " + relative + "  \";  ", directive(absolute), "  begin:  "),
                path -> {
                    requested.add(path);
                    return List.of("  NOP;  ");
                }
            );
            expect(List.of(relative, absolute), requested, "existing import path interpretation");
            expect(List.of("  NOP;  ", "  NOP;  ", "begin:"), expanded,
                "import text must be retained while original main lines are trimmed");
        });

        check("default file compilation resolves imports from the working directory", () -> {
            Path imported = temporaryDirectory.resolve("cwd-library.ad04");
            Files.writeString(imported, "var value = 7;\n");
            Path workingDirectory = Path.of("").toAbsolutePath();
            Path relative = workingDirectory.relativize(imported);
            Path mainDirectory = temporaryDirectory;
            for (int depth = 0; depth <= workingDirectory.getNameCount(); ++depth) {
                mainDirectory = mainDirectory.resolve("nested");
            }
            Files.createDirectories(mainDirectory);
            Path main = mainDirectory.resolve("main.ad04");
            Files.writeString(main, directive(relative) + "\nbegin: HALT;\n");
            Machine vm = execute(quietly(() -> machine.Compiler.compile(main.toString())));
            expect(7, vm.get(1), "relative import must not use the main file's parent directory");
        });

        check("existing compiler and preprocessor APIs retain disk loading", () -> {
            Path imported = temporaryDirectory.resolve("default-library.ad04");
            Files.writeString(imported, "var value = 4;\n");
            List<String> main = List.of(directive(imported), "begin: INC value; HALT;");
            expect(quietly(() -> machine.Compiler.compile(main)), compile(main, Files::readAllLines),
                "default compile overload");
            expect(quietly(() -> machine.Compiler.preProcess(main)),
                quietly(() -> machine.Compiler.preProcess(main, Files::readAllLines)),
                "default preProcess overload");
            expect(machine.Compiler.includeImports(main),
                machine.Compiler.includeImports(main, Files::readAllLines),
                "default includeImports overload");
        });

        check("loader IO errors are propagated without losing their details", () -> {
            IOException expected = new IOException("The requested source buffer is unavailable");
            try {
                compile(List.of("import \"unavailable.ad04\";", "begin: HALT;"), path -> {
                    throw expected;
                });
                throw new AssertionError("the loader failure was ignored");
            } catch (IOException actual) {
                if (actual != expected) {
                    throw new AssertionError("the loader's IOException was replaced", actual);
                }
            }
            Path missing = temporaryDirectory.resolve("missing.ad04");
            try {
                quietly(() -> machine.Compiler.compile(List.of(directive(missing), "begin: HALT;")));
                throw new AssertionError("a missing disk import was accepted");
            } catch (NoSuchFileException actual) {
                expect(missing.toString(), actual.getFile(), "missing import filename");
            }
        });

        check("malformed import directives fail before invoking the loader", () -> {
            for (String directive : List.of(
                "import missing;", "import \"library.ad04\"", "import \"library.ad04\"; // comment"
            )) {
                List<Path> requested = new ArrayList<>();
                try {
                    compile(List.of(directive, "begin: HALT;"), path -> {
                        requested.add(path);
                        return List.of();
                    });
                    throw new AssertionError("malformed import was accepted: " + directive);
                } catch (IllegalArgumentException error) {
                    if (!error.getMessage().startsWith("import keyword must be followed by")) {
                        throw new AssertionError("import diagnostic changed", error);
                    }
                }
                expect(List.of(), requested, "malformed directive must not invoke the loader");
            }
        });

        check("nested imports remain unexpanded and retain their compile error", () -> {
            Path inner = temporaryDirectory.resolve("inner.ad04");
            Path outer = temporaryDirectory.resolve("outer.ad04");
            Files.writeString(inner, "var value = 1;\n");
            Files.writeString(outer, directive(inner) + "\n");
            List<String> main = List.of(directive(outer), "begin: HALT;");
            List<Path> requested = new ArrayList<>();
            SourceLoader loader = path -> {
                requested.add(path);
                return Files.readAllLines(path);
            };
            expect(List.of(directive(inner), "begin: HALT;"),
                machine.Compiler.includeImports(main, loader), "one-level expansion");
            expect(List.of(outer), requested, "nested file must not be loaded");
            requested.clear();
            Class<?> originalFailure = parseFailure(() -> machine.Compiler.compile(main));
            Class<?> callbackFailure = parseFailure(() -> machine.Compiler.compile(main, loader));
            expect(originalFailure, callbackFailure, "nested-import error type");
            expect(List.of(outer), requested, "compilation must also load only direct imports");
        });

        check("commented imports do not load files", () -> {
            List<Integer> bytecode = compile(
                List.of("// import \"unused.ad04\";", "var value = 2;", "begin: HALT;"),
                path -> { throw new AssertionError("commented import invoked the loader"); }
            );
            expect(2, execute(bytecode).get(1), "ordinary comment removal");
        });

        System.out.println("CompilerSourceLoaderTest: " + passed + " passed, " + failed + " failed");
        if (failed != 0) {
            throw new AssertionError(failed + " source loader regression test(s) failed");
        }
    }

    private static String directive(Path path) {
        return "import \"" + path + "\";";
    }

    private static Class<?> parseFailure(Operation<?> action) throws Exception {
        try {
            quietly(action);
        } catch (ParseException | TokenMgrError error) {
            return error.getClass();
        }
        throw new AssertionError("the nested import was accepted");
    }

    private static void check(String name, Test test) {
        try {
            test.run();
            ++passed;
            System.out.println("PASS " + name);
        } catch (Exception | AssertionError error) {
            ++failed;
            System.err.println("FAIL " + name + ": " + error);
            error.printStackTrace(System.err);
        }
    }

    private static List<Integer> compile(List<String> source, SourceLoader loader) throws Exception {
        return quietly(() -> machine.Compiler.compile(source, loader));
    }

    private static <T> T quietly(Operation<T> action) throws Exception {
        PrintStream previousOutput = System.out;
        try (PrintStream compilerOutput = new PrintStream(new ByteArrayOutputStream())) {
            System.setOut(compilerOutput);
            return action.run();
        } finally {
            System.setOut(previousOutput);
        }
    }

    private static Machine execute(List<Integer> bytecode) {
        Machine machine = new Machine();
        machine.load(new ArrayList<>(bytecode));
        machine.step(1000);
        if (!machine.isHalted()) {
            throw new AssertionError("program did not halt within 1000 instructions");
        }
        return machine;
    }

    private static void expect(Object expected, Object actual, String description) {
        if (!expected.equals(actual)) {
            throw new AssertionError(description + ": expected " + expected + ", got " + actual);
        }
    }
}

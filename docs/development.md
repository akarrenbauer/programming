# Development guide

[README](../README.md) · [Machine model](machine-model.md) · [Language](language.md)

## Quick start without a GUI

The compiler and simulator need only the JDK; JavaFX is needed for the GUI.
From the repository root, compile the core into a fresh directory so existing
class files cannot mask changes to the sources:

```sh
machine_build=$(mktemp -d)
javac --release 17 -d "$machine_build" \
  -classpath "$machine_build" -sourcepath . \
  machine/Compiler.java machine/Machine.java
```

## Source layout and compilation

| Tracked source | Role |
| --- | --- |
| [Compiler.java](../machine/Compiler.java) | Public compilation API, imports, preprocessing, and textual macro expansion. |
| [CompilerParser.jj](../machine/CompilerParser.jj) | JavaCC grammar, semantic checks, and code generation; parser source of truth. |
| [CompilerParser.java](../machine/CompilerParser.java), [CompilerParserTokenManager.java](../machine/CompilerParserTokenManager.java), [CompilerParserConstants.java](../machine/CompilerParserConstants.java) | Generated parser and lexer. |
| [CodeSegment.java](../machine/CodeSegment.java) | Instructions, forward-label placeholders, and code relocation. |
| [DataSegment.java](../machine/DataSegment.java) | Initial values and addresses for variables and scratch cells. |
| [Segment.java](../machine/Segment.java), [Number.java](../machine/Number.java) | Symbol tables and mutable integer references used during compilation. |
| [Machine.java](../machine/Machine.java), [MachineGUI.java](../machine/MachineGUI.java) | Instruction execution and its JavaFX interface. |
| [package.bluej](../package.bluej), [machine/package.bluej](../machine/package.bluej) | BlueJ project metadata. |

`Compiler.compile(...)` first inserts imports and strips comments. A fresh parser
runs `Program(true)` to check the source before macro expansion. Macro-body
global references are validated after the global declarations. Expansion then
substitutes arguments and renames macro-local variables and labels. Another fresh
parser runs `Program(false)` on the expanded source and rejects unresolved labels.

The memory image contains data followed by code; cell zero holds the entry point.
Variable lookup returns an address, whereas reading `dataSegment.get(address)`
returns its initial value. Code-label addresses share mutable `machine.Number`
objects: placing a forward label updates every instruction using that object.
Final relocation adds the data-segment size to code addresses, including the
entry point. Data addresses already count from the start of memory.

## Java API

Use `machine.Compiler` explicitly to distinguish it from Java's own `Compiler`
class. Compilation accepts a source path or a `List<String>` of source lines and
returns `List<Integer>` bytecode. The following checks use the latter API, so no
source files beyond the tracked Java classes are needed.

| API | Behavior |
| --- | --- |
| `Compiler.compile(String)` / `compile(List<String>)` | Compile source; declare `IOException` and `ParseException`. Imports can perform file I/O in either form. |
| `Machine.load(List<Integer>)` | Load an independent, mutable copy and reset the step counter. |
| `Machine.load(String)` / `reload()` | Read a memory file / reread its remembered path. File-read errors are printed to standard error. |
| `get(int)` / `set(int, int)` | Read or write a memory cell. |
| `step()` / `step(int)` / `run()` | Execute one instruction / a bounded batch / until `HALT`. |
| `isHalted()` / `getInstructionPointer()` | Inspect execution state. |
| `getStepCounter()` / `getMemorySize()` | Inspect executed steps / allocated cells. |
| `reset()` / `print()` / `setVerbosity(int)` | Clear memory and the counter / print memory / enable tracing with a positive verbosity. |

`reset()` and list-based loading do not clear the remembered memory-file path.
`reload()` therefore reloads that file, rather than restoring the last list or
recompiling source. Some malformed inputs raise unchecked lexer or preprocessor
errors in addition to the compiler's declared exceptions.

## Inline smoke checks

These checks use only the compiled classes and inline source. They cover macro
globals, copied initialization, nonzero comparisons, list-copy isolation, and
decrementing unallocated memory. The expected final message is
`Smoke checks passed.`; any exception means a check failed.

```sh
jshell --class-path "$machine_build" <<'EOF'
{
    var source = java.util.List.of(
        "void bump() {", "INC count;", "}",
        "var count = 2;", "var copy = count;", "begin:",
        "bump();", "if(count == 3) {", "DEC copy;", "}", "HALT;"
    );
    var image = machine.Compiler.compile(source);
    var vm = new machine.Machine();
    vm.load(image);
    vm.step(2000);
    if (!vm.isHalted() || vm.get(1) != 3 || vm.get(2) != 1
            || image.get(1) != 2) {
        throw new AssertionError("Compilation or execution failed");
    }
    vm.load(java.util.List.of(1, 3, 100, 0));
    vm.step();
    if (!vm.isHalted() || vm.get(100) != 0 || vm.getStepCounter() != 1) {
        throw new AssertionError("Unallocated-memory decrement failed");
    }
    System.out.println("Smoke checks passed.");
}
/exit
EOF
```

## Regenerate the parser

Edit `machine/CompilerParser.jj` and regenerate its Java outputs. The following
workflow has been verified with JavaCC 7.0.13; normal builds do not need JavaCC.
Generate into a temporary directory and review the outputs before copying:

```sh
javacc -version
parser_stage=$(mktemp -d)
javacc -OUTPUT_DIRECTORY="$parser_stage" machine/CompilerParser.jj
```

After review, update the three parser/lexer files together, then rebuild:

```sh
cp "$parser_stage/CompilerParser.java" \
  "$parser_stage/CompilerParserConstants.java" \
  "$parser_stage/CompilerParserTokenManager.java" machine/
git diff -- machine/CompilerParser.jj machine/CompilerParser.java \
  machine/CompilerParserConstants.java machine/CompilerParserTokenManager.java
```

JavaCC also emits support files corresponding to tracked `machine/Token.java`,
`machine/TokenMgrError.java`, `machine/ParseException.java`, and
`machine/SimpleCharStream.java`. Compare those outputs when changing lexer options
or the generator version, and include required changes deliberately.

## Implementation limits

Imports are relative to the working directory and are not recursively expanded.
Macro expansion is textual and ordered; define helpers before their callers and
put closing braces on separate lines. Diagnostics may identify preprocessed or
expanded lines instead of original file lines. Array accesses generally do not
check declared lengths. Large local literal initializers emit many increments;
runtime variable copying temporarily increments its source and can overflow at
`Integer.MAX_VALUE`. Arithmetic and step counters use unchecked Java `int` values.
Raw bytecode is not validated; an unknown opcode can make unlimited execution
loop forever. Prefer bounded `step(n)` calls when checking emitted programs.

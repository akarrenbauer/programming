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
| [SourceWorkspace.java](../machine/SourceWorkspace.java) | Editable main/import buffers, unsaved-change tracking, and explicit source saving. |
| [SourceEditor.java](../machine/SourceEditor.java), [SourceSyntax.java](../machine/SourceSyntax.java) | JavaFX source highlighting and its tolerant, text-preserving token classifier. |
| [CompilationResult.java](../machine/CompilationResult.java), [SourceDocument.java](../machine/SourceDocument.java), [SourceLocation.java](../machine/SourceLocation.java), [SourceTrace.java](../machine/SourceTrace.java) | Compiled image, source snapshots, original statement ranges, and macro call chains. |
| [package.bluej](../package.bluej), [machine/package.bluej](../machine/package.bluej) | BlueJ project metadata. |

`Compiler.compile(...)` first inserts imports and strips comments. A fresh parser
runs `Program(true)` to check the source before macro expansion. Macro-body
global references are validated after the global declarations. Expansion then
substitutes arguments and renames macro-local variables and labels. Another fresh
parser runs `Program(false)` on the expanded source and rejects unresolved labels.

The parser's `Statement` rule handles optional labels followed by a block,
control-flow statement, or `SimpleStatement`. The latter consumes the semicolon
for declarations, commands, macro calls, and empty statements. Macro definitions
and all control-flow bodies reuse `Block`. A shared `Condition(branchOnTrue)`
rule emits a branch test for a false condition in `if`/`while`, and a true condition
in `do`/`while`. `Lhs` reuses `Variable` for variable references. Macro parameter
and argument items have separate rules: arguments preserve literal/index text
for substitution and do not allocate cells for literal values.

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
| `Compiler.compile(List<String>, SourceLoader)` | Compile supplied main lines and obtain each direct import through a callback, allowing compilation from edited buffers. |
| `Compiler.compileWithSourceMap(SourceDocument, SourceResolver)` | Compile a named source document and imports into an immutable image with instruction-to-source mappings. |
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

`Compiler.SourceLoader.load(Path)` returns an imported file's lines and may throw
`IOException`. The callback runs once per direct import occurrence, in source
order, with the same relative or absolute path used by disk-based compilation.
The `preProcess` and `includeImports` methods also accept this loader. Imported
lines are not scanned recursively for imports.

The GUI's `SourceWorkspace` holds all opened documents and an optional active main.
Imports are discovered as compilation requests them. Documents identify files by resolved paths, so
repeated references and symbolic links share an editor buffer while their contents
are expanded at every import occurrence. Compilation reads the current buffers;
`save`, `saveAs`, and `saveAll` write files. Open buffers remain available after compilation failures
or removal of an import. The workspace is independent of JavaFX and can be used
without a GUI.

`SourceWorkspace.create()` starts with an empty unnamed draft and no main;
`newDocument()` adds another draft. `open(path)` initially opens one document and
activates it if eligible. `openDocument(path)` opens or reuses a buffer without
changing the main. `setMainDocument(document)` activates an owned document with a
top-level `begin:` marker. `getMainDocument()` returns null if none is active and
clears a main that has lost its marker; compilation without a main raises a
`ParseException`. `SourceSyntax.hasBeginMarker` checks eligibility while ignoring
comments, quoted paths, and nested braces. A marker does not establish that the
rest of the program is valid.

A document's
`getPath()` is null until its first save, while `getDisplayName()` always supplies
a tab name. `saveAs(document, path)` names an unnamed document, preserves its
identity, and rejects a target already open in another buffer. A failed save
leaves the document's name and dirty baseline unchanged. Empty untouched drafts
are clean but can still be saved. The GUI requests paths when saving edited
unnamed documents, and cancels a pending close if any chooser is canceled. Open
preserves existing buffers. Eligible files are activated and compiled on Open or
on a primary-button double-click of their tab header; ordinary selection and
editor double-clicks do not change the main. The first eligible edit activates a
document only when there is no main and execution is stopped, without compiling it.

`SourceWorkspace.compileWithSourceMap()` returns a `CompilationResult` containing
`bytecode`, `codeStart`, a `sourceMap` keyed by absolute opcode addresses, and the
`sources` used for compilation. `compile()` returns just its bytecode. A
`Compiler.SourceResolver` supplies each imported `SourceDocument`, including its
file identity; the workspace resolves these to canonical paths. Source snapshots
normalize CRLF and CR to LF to match the editor. Location offsets are UTF-16,
start-inclusive and end-exclusive; line and column numbers start at one.
For an unnamed main, source locations have a null file and the `sources` map
contains only named imports. The GUI resolves those null locations to the main
document and clears the mapping when its first save assigns a path.

Preprocessing retains original ranges through imports, comment removal, statement
splitting, and macro substitution. Each `SourceTrace` contains the statement's
`location` and its macro `callers` from outermost to innermost. Synthetic literal
argument initialization maps to the call site. The parser records expanded source
lines at opcode emission, then relocation converts them to absolute memory
addresses; operands and data cells are never map keys. Generated control-flow
instructions belong to their controlling statement, including the trailing
`while` of a `do` loop. This metadata does not change emitted bytecode.

`SourceEditor` extends JavaFX `TextArea` and adds a colored text layer through its
skin. Native editing, selection, caret movement, scrolling, and undo remain with
`TextArea`; highlighting does not rewrite the buffer. `SourceSyntax` classifies
text independently of the parser so incomplete programs can be highlighted while
typing. Both use the existing Java/JavaFX dependencies.

Execution backgrounds use the native text's range geometry beneath syntax colors
and selection. They do not modify the document. The GUI uses the exact instruction
address to select a trace, validates the compiled code region against memory on
refresh, and discards stale mappings after source or code changes. Following a
location scrolls through the skin without moving the caret. Source maps describe
execution; compiler diagnostics can still refer to preprocessed source lines.

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

## Check source mapping

Run these checks from the repository root in the fresh build directory created
above. They cover opcode addresses, original source ranges, imports, macro call
chains, unchanged bytecode, and compilation from editable buffers.

```sh
javac --release 17 -d "$machine_build" \
  -classpath "$machine_build" -sourcepath . \
  tests/CompilerInstructionMappingTest.java tests/CompilerSourceMappingTest.java \
  tests/SourceWorkspaceTest.java
java -cp "$machine_build" machine.CompilerInstructionMappingTest
java -cp "$machine_build" CompilerSourceMappingTest
java -cp "$machine_build" SourceWorkspaceTest
```

The JavaFX checks in [SourceEditorTest](../tests/SourceEditorTest.java) and
[MachineGUISourceMappingTest](../tests/MachineGUISourceMappingTest.java) cover marker
geometry, scrolling, macro navigation, editing behavior, and stale-map removal.
Their file headers describe how to compile and run them with JavaFX. They require
working JavaFX native libraries and a desktop or supported headless display.

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

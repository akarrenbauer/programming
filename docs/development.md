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
| [LanguageGame.java](../machine/LanguageGame.java) | Standalone JavaFX game, background checks, feedback, and saved drafts. |
| [StructuredDiagram.java](../machine/StructuredDiagram.java), [StructuredDiagramView.java](../machine/StructuredDiagramView.java) | Source-preserving diagram model, keyboard editing, and nested insertion targets. |
| [Flowchart.java](../machine/Flowchart.java), [FlowchartView.java](../machine/FlowchartView.java) | World 1 control-flow graph and editable flowchart with explicit jump edges and lesson-specific language profiles. |
| [DiagramToolbox.java](../machine/DiagramToolbox.java), [DiagramValidation.java](../machine/DiagramValidation.java) | Draggable control-structure symbols, bounded compilation checks, and damage marks on the last valid diagram. |
| [GameMessages.java](../machine/GameMessages.java), [GameDiagnostics.java](../machine/GameDiagnostics.java) | Task-language status messages and diagnostic presentation, preserving code, identifiers, and checker results. |
| [LanguageGameLauncher.java](../machine/LanguageGameLauncher.java), [pom.xml](../pom.xml) | Executable Linux JAR entry point and Maven build bundling JavaFX. |
| [GameCatalog.java](../machine/GameCatalog.java), [GameBookLevels.java](../machine/GameBookLevels.java), [GameLevel.java](../machine/GameLevel.java) | Ordered challenges, test contracts, hints, and earned helper definitions. |
| [GameTaskLanguage.java](../machine/GameTaskLanguage.java), [GameTranslations.java](../machine/GameTranslations.java) | System-locale defaults and translated teaching text, separate from executable levels. |
| [GameTaskSummaries.java](../machine/GameTaskSummaries.java) | Curated short English/German objectives for the compact header, preserving complete teaching text in Task details. |
| [GameGermanFoundations.java](../machine/GameGermanFoundations.java), [GameGermanArithmetic.java](../machine/GameGermanArithmetic.java) | German task titles, goals, explanations, and hints for all 46 challenges. |
| [GameEngine.java](../machine/GameEngine.java), [GameSyntax.java](../machine/GameSyntax.java), [GameResult.java](../machine/GameResult.java) | Feature gates, resource checks, isolated executions, and results. |
| [LanguageVersion.java](../machine/LanguageVersion.java), [GameLanguage.java](../machine/GameLanguage.java) | Per-compilation keyword profiles and game-level selection, including the rewrite debugger. |
| [GameGrammarView.java](../machine/GameGrammarView.java) | Styled JLS grammar with shaded terminals and highlights for new fragments. |
| [GameDebugSession.java](../machine/GameDebugSession.java), [GameDebugger.java](../machine/GameDebugger.java) | Bounded execution snapshots and a source-mapped debugging window using the machine and source editor. |
| [GameFeature.java](../machine/GameFeature.java), [GameGrammar.java](../machine/GameGrammar.java), [GameProgress.java](../machine/GameProgress.java) | Cumulative syntax, displayed grammar, and local persistence. |
| [GameWorld.java](../machine/GameWorld.java) | Six curriculum worlds, selection of flowcharts or structured diagrams, and the control-structure prerequisites. |
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
| `Compiler.compileWithSourceMap(SourceDocument, SourceResolver, PrintStream)` | Choose the preprocessing listing destination; pass `null` for quiet compilation. Existing overloads retain their standard-output listing. |
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
and Save source offer `.a26` for the current academic year; Open also accepts
legacy `.ad04` files and other extensions. Existing names are preserved on save.
Open preserves existing buffers. Eligible files are activated and compiled on Open or
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

Plain Enter copies the current line's leading spaces and tabs into the new line,
limited to the prefix before the insertion point when splitting indentation.
The newline and indentation are one native undo step. Like other source edits, this
updates the workspace buffer and dirty state and invalidates execution mapping
without compiling, saving, or changing machine memory. This behavior is shared by
the simulator and Language Game source editors.

`MachineGUI` keeps the source `TabPane` in a `SplitPane` with an optional flowchart
or Nassi–Shneiderman editor. The selected source document determines the diagram;
the active main still determines compilation and execution. Each document caches
both diagram views so pending fields survive tab and display-mode changes. The
views edit the existing `SourceEditor` through a single replacement, preserving
native undo and normal workspace change notifications.

Standalone diagram validation uses the current workspace buffers and the final
grammar. Imported helpers are checked in their main program's context without
changing documents or saving files. Helper-only diagrams render their real macro
bodies without adding a visible or editable `begin:` section. This path has no
game unlock restrictions. Explicit jumps are represented by flowcharts; a
Nassi–Shneiderman diagram requires structured source. Invalid drafts retain a
marked read-only diagram and clear stale source links.
`DiagramCompileGuard` bounds source size and estimated macro expansion for
automatic previews. Exceeding these preview limits leaves the source editable
and does not restrict the simulator's explicit **Compile** action.

[MachineGUIDiagramsTest](../tests/MachineGUIDiagramsTest.java) checks both diagram
modes, source synchronization and undo, imported buffers, focus links, retained
drafts, and the final grammar without changing machine execution or source files.

Execution diagrams track actual instruction transitions, including the last
transition in a multi-step batch or Run. The initial compiled state marks the
main entry and its outgoing edge. Manual data edits refresh values without
inventing a transition; manual instruction-pointer changes clear the old edge.
Current-value annotations are rendered only in the flowchart,
without changing source text, editable fields, or native undo history. These decorations
coexist with source/diagram focus linking and source execution markers.

`EditorZoom` handles the application shortcuts and shares one zoom factor among
the source and diagram views. `DiagramZoomPane` scales existing diagram nodes
and their scroll extents, preserving pending edits and pointer targets. Source
editors resize their native font so syntax and execution markers stay aligned.
[DiagramZoomPaneTest](../tests/DiagramZoomPaneTest.java) checks scaled geometry,
scroll anchors, focus, and revealing offscreen elements.
[EditorZoomTest](../tests/EditorZoomTest.java) exercises shortcuts in both
applications, including new tabs, mode changes, and unchanged editor contents.

[MachineGUIExecutionDiagramTest](../tests/MachineGUIExecutionDiagramTest.java)
checks the simulator's actual Step, Step n, and Run controls, loop branches,
HALT, current values, and stale-state invalidation.

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

## Run the Language Game

[BlueJ](../README.md#play-the-language-game) is the primary launch route. For a
command-line launch, set `fx_modules` to the module path of a JavaFX installation
compatible with your JDK and native libraries. For example, with separate jars:

```sh
fx_modules=/usr/share/java/openjfx/javafx.base.jar:/usr/share/java/openjfx/javafx.graphics.jar:/usr/share/java/openjfx/javafx.controls.jar
game_build=$(mktemp -d)
javac --release 17 --module-path "$fx_modules" --add-modules javafx.controls \
  -d "$game_build" -classpath "$game_build" -sourcepath . machine/LanguageGame.java
java --module-path "$fx_modules" --add-modules javafx.controls \
  -cp "$game_build" machine.LanguageGame
```

Use a graphical desktop. Native-library lookup may also require
`-Djava.library.path=...`, according to the JavaFX installation. Supply
`-Dlanguage.game.progress=...` before the class name for an isolated profile.

To build a JAR for Linux testers, run `mvn clean package`; the build and launch
commands are in [Linux distribution instructions](linux-testing.md). Maven
packages the application; the standalone regression suites below are run
separately.

The [packaged-app smoke check](../tests/packaging/LanguageGameJarTest.java) uses
only the resulting JAR and a fresh test-class directory. Run it on a private
graphical display (or under `xvfb-run -a` if available):

```sh
game_jar="$(pwd)/target/language-game-linux-x86_64.jar"
jar_test_classes=$(mktemp -d)
javac --release 17 -cp "$game_jar" -sourcepath "$jar_test_classes" \
  -d "$jar_test_classes" tests/packaging/LanguageGameJarTest.java
java -Duser.language=de -Duser.country=DE \
  -cp "$game_jar:$jar_test_classes" machine.LanguageGameJarTest "$game_jar"
```

It invokes the manifest launcher, checks German teaching text and the unchanged
English interface, compiles and runs a solution, then steps through it in the
debugger. Its progress is temporary; it closes the windows when finished.

The game reuses the real compiler and machine. `GameSyntax` parses the currently
earned subset and checks resource bounds before compilation; it is a gate, not
a replacement compiler. Keep its rules and `GameGrammar` consistent with the
JavaCC grammar when changing the language. Only virtual, earned toolbox imports
(`toolbox` and its book alias `macros.a26`) are resolved. Scalar input literals are substituted before each compilation,
so global copies receive the test input too. Runtime failures map back through
those substitutions to the student's original text.

World 1 pairs the source editor with `FlowchartView`; later worlds use
`StructuredDiagramView`. Both project the same persisted source draft, and
diagram edits patch the affected source range so native undo also restores the
diagram. The flowchart palette contains only rectangle and diamond shapes;
instruction placeholders still follow the earned feature set, and jump-head
placement determines the control-flow path. Post-test loops render their body
and trailing decision directly; their source-owned entry is an invisible
junction anchor rather than a `do` action box. Moving a direct nonzero loop's
return head atomically lowers its source to a block and explicit `JNZ`; a drop
on the original entry keeps the structured source intact.
The flowchart follows `GameLanguage.editingVersionFor`: early labels
and explicit `JNZ` are legal, and the conversion lesson can show either its old
labeled loop or the rewritten structured loop. A flowchart does not grant
language features; validation still enforces the earned feature set. Invalid
drafts retain the last valid diagram with damaged nodes marked read-only.
Opening another level clears both diagram caches before loading its draft.
Flowchart focus reports the corresponding source range to `SourceEditor`
without changing its caret or selection. Source-cursor movement identifies the
matching flowchart element in return. Invalid drafts clear these linked marks
instead of applying offsets from the retained diagram to changed code.

Task translation is a presentation layer. `GameTranslations` looks up teaching
text by stable level ID; it never replaces `GameLevel`, its source, or its test
contract. Java source tables keep the translations available in BlueJ and in
plain `javac` builds without a resource-copy step. When adding a level, add its
German text and run the translation coverage test. Custom levels without a
translation fall back to their supplied English text. `GameProgress` stores the
optional `task.language` preference as `en` or `de`; missing or unknown values
use `Locale.getDefault(Locale.Category.DISPLAY)`. Older saves remain compatible.
`GameTaskSummaries` supplies a separately authored objective of at most 150
characters for each catalog task and language. The fourth workspace tab,
**Task details**, retains the full translated title, goal, lesson, and contract.
Custom tasks keep their complete supplied goal as a fallback; no substring
truncation discards instructions. Add a short objective when adding a catalog
level and run [GameTaskSummariesTest](../tests/GameTaskSummariesTest.java).

Each check runs in a JavaFX `Task` on a single worker. Fresh machines enforce
instruction boundaries, data-only writes, nonnegative arithmetic, cancellation,
and step budgets. Code generation is also bounded, including expanded macro
depth and runtime literal initialization. Macro dependency analysis distinguishes
name and arity, with at most 32 parameters or arguments; literal specializations
share a conservative bound for that signature. Code estimates follow calls from
the main program and propagate literal arguments through parameter bindings;
unused helpers do not generate code. All macro signatures are still checked
for cycles. This permits the book's
24-argument byte operations while retaining recursion and expansion limits.
`GameProgress` derives unlocks from
known completed level IDs and replaces UTF-8 save files atomically when supported.
Completed levels and the earliest unfinished level remain available. Version 1
saves are validated against the original foundation order before migration;
version 2 preserves that credit and ID-based drafts when lessons move, without
awarding skipped lessons. Legacy completion of `mandatory-lap` retains its
do/while reward after that unlock moves to `clear-block`. Newly inserted levels
remain available to solve. A completed 45-level profile retains every completion,
draft, and selected completed lesson; the new `while-practice` is its only
unfinished level. Completing that rewrite fills the gap without changing old
answers or rewards. Unread grammar features and toolbox notices are saved
separately from unlocks; viewing a tab acknowledges its notice without affecting
earned tools. Old profiles start without unread notices. Whole-game reset clears all progress and drafts only
if the reset can be saved. This is local learning progress, not a secure
assessment record.

The debugger prepares the same substituted inputs and virtual imports as the
checker, and shares its memory and arithmetic guards. It skips exercise-specific
completion requirements so an unfinished draft can be inspected. Debug sessions
are immutable source snapshots, do not award progress, and keep the same step
budget as the selected level.

Core game checks require only the JDK:

```sh
game_tests=$(mktemp -d)
javac --release 17 -d "$game_tests" -classpath "$game_tests" -sourcepath . \
  tests/GameEngineTest.java tests/GameGrammarTest.java \
  tests/GameProgressTest.java tests/GameCurriculumTest.java tests/GameBookLevelsTest.java \
  tests/GameInventoryTest.java tests/GameDebugSessionTest.java tests/GameTranslationsTest.java tests/GameWorldTest.java
java -cp "$game_tests" GameEngineTest
java -cp "$game_tests" GameGrammarTest
java -cp "$game_tests" GameProgressTest
java -cp "$game_tests" machine.GameCurriculumTest
java -cp "$game_tests" machine.GameBookLevelsTest
java -cp "$game_tests" GameInventoryTest
java -cp "$game_tests" GameDebugSessionTest
java -cp "$game_tests" GameTranslationsTest
java -cp "$game_tests" machine.GameWorldTest
```

The curriculum suite checks every reference solution using exactly the previous
levels' unlocks, verifies that every grammar unlock is required in the immediately
following level, rejects every unfinished starter, and exercises the documented
in-place helper operations. The book-level suite also checks every full-adder
input, byte conversion boundaries, and overlapping input/output bytes. Their reference programs are test fixtures, not a
solution button in the game. The independent engine suite checks locked syntax,
input substitution, cancellation, invalid memory access, and compilation bounds.
[LanguageGameTest](../tests/LanguageGameTest.java) also walks the entire journey
through the GUI and checks failed submissions, cancellation, persistence, and
recovery. Its header gives JavaFX build/run instructions; tests use temporary
profiles and a private display.
[LanguageGameButtonHitTest](../tests/LanguageGameButtonHitTest.java) clicks the
solution button's text, centre, and edges with a real pointer, including after
hints, task changes, and reset, to catch invisible labels intercepting input.

[GameMessagesTest](../tests/GameMessagesTest.java) and
[GameDiagnosticsTest](../tests/GameDiagnosticsTest.java) check English/German
status text and real checker diagnostics. The GUI journey also switches the task
language after successes and failures while preserving drafts, selections, and
results. [DiagramValidationTest](../tests/DiagramValidationTest.java) checks
compilation and damage attribution; [StructuredDiagramViewTest](../tests/StructuredDiagramViewTest.java),
[DiagramToolboxTest](../tests/DiagramToolboxTest.java), and
[GameWorldEditorTest](../tests/GameWorldEditorTest.java) cover positional Enter,
trash icons, pointer and keyboard insertion, cached diagrams, and source undo.
[GameFlowchartEditorTest](../tests/GameFlowchartEditorTest.java) covers the first
world's jump paths, source/flowchart synchronization, undo, invalid-draft
recovery, and the transition between legacy `do:` labels and structured loops.
[FlowchartDragTest](../tests/FlowchartDragTest.java) uses real pointer gestures
to insert structures and move jump heads, including shared destinations,
pending edits, and automatic forward/backward comparison notation.
[FlowchartRoutingTest](../tests/FlowchartRoutingTest.java) verifies YES/NO at
decision tails, right-side jump exits, and labeled junctions on incoming arrows.
[FlowchartTest](../tests/FlowchartTest.java) compares graph paths with compiled
execution for all eight foundational lessons; [FlowchartViewTest](../tests/FlowchartViewTest.java)
checks node editing, label transactions, insertion, and retained error diagrams.

## Compiler language profiles

Existing compiler entry points default to `LanguageVersion.STRUCTURED`.
`Compiler.compileWithSourceMap(main, resolver, listing, version)` accepts an
explicit profile; `SourceWorkspace.compileWithSourceMap(version)` applies it to
open buffers. `LanguageVersion.BEFORE_DO_WHILE` makes `do` an ordinary identifier
and disables the post-tested loop. Structured makes `do` a reserved keyword.
Neither profile has a special keyword-label production.

`MachineGUI` always passes `LanguageVersion.STRUCTURED` when compiling its source
workspace and when configuring source editors. Its Open, Compile, and main-tab
activation paths share that fixed profile; the simulator exposes no version
selector. The earlier profile remains available to the game and compiler API.

The profile belongs to each parser/token manager and is applied to both parser
passes, including imported text and macro expansion. Configure a direct parser
before reading tokens. The game derives its profile from the exercise and earned
syntax; the rewrite debugger selects the earlier profile for actual identifier
uses of `do` without a structured `do` block, ignoring comments and quoted paths. It does not accept mixed
versions. Source highlighting uses the same selected profile without changing
text, undo history, or execution ranges.

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

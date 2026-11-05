# Language Game

[README](../README.md) · [Full grammar](grammar.md) · [Language reference](language.md)

Build a programming language by implementing its ideas in a smaller language.
The game starts with five machine instructions and grows into the language in
[grammar.md](grammar.md), then a library for arithmetic, gcd, and fixed-width
two's complement. Its worlds, short challenges, hints, and cumulative unlocks
are inspired by the [Natural Number Game](https://adam.math.hhu.de/).
This is a programming game for this project's machine: solutions are checked by
execution on finite examples, rather than proved in Lean.

## Start in BlueJ

Open the repository in BlueJ, enter the `machine` package, and compile it, as
described in the [README](../README.md#run-the-gui-with-bluej). Right-click
[LanguageGame](../machine/LanguageGame.java) and choose **Run as JavaFX
application**. It is a separate application from the machine simulator and also
has its own `public static void main(String[] args)`.

The [development guide](development.md#run-the-language-game) provides the
command-line build and launch commands.
For a single-file Linux testing distribution, see the
[JAR instructions](linux-testing.md).

## Task language

Choose **English** or **Deutsch** in **Task language** at the top of the window.
On first use, the game selects German for a German system display language,
including Austrian and Swiss locales, and English for other languages. Your
selection is saved with the profile and takes precedence on later launches.
Resetting the game keeps this language preference.

The selection translates the world and lesson titles, task goals, explanations,
hints, and status messages for all 46 levels. Completion messages, next-task
announcements, reward names, test feedback, and diagram status also follow this
language. Switching languages updates existing messages while retaining the
current draft, editor selection, revealed hint number, test results, and unlocks.
Control labels, grammar, source, starter comments, and earned helper code remain
in English. Task titles and execution status in the debugger use the language
selected when it opens.

## Play a level

The top of the workspace shows the task title and a concise objective.
Open **Task details**, next to **Earned toolbox**, for the full goal,
explanation, and execution contract. Both the summary and the complete text
follow **Task language**; switching languages keeps your draft and selected tab.

The journey is grouped into six worlds. **Machine foundations** teaches the
machine instructions and derives `do/while`, `if/else`, and `while`. Completing
level 8, **Build while from if and do/while**, earns the final control structure
and opens **Structured programming** at level 9, **Replace if and do/while with
while**. This level supplies the working guarded `do/while` solution and asks
you to replace it with one `while` loop, preserving its zero-iteration behavior
and its results. Later worlds retain this structured way of working. Earlier
completed levels keep their original rules.

**World 1: Machine foundations** displays an editable **flowchart** beside the
source. Commands appear as action boxes, decisions as diamonds, and arrows show
the next instruction or a jump to a label. The toolbox contains only a
**rectangle** for an instruction and a **diamond** for a decision. Drag either
shape into a gap; the same two shapes remain available as syntax is unlocked.
A rectangle starts as `NOP;`, or `;` once empty statements are earned. A diamond
starts as an editable jump decision. Its arrow destination determines whether
execution repeats earlier code or skips ahead; there are no separate loop or
conditional tools in this flowchart toolbox.
`JNZ` remains available here, so a
backward jump is visible as an edge back to its target. As control structures
are earned, their decisions and loop paths can also be shown in the flowchart.
For a backward jump, `JNZ x do;` appears as **x ≠ 0?** in a diamond; a forward
jump appears as **x = 0?**. The outgoing arrows carry **YES** and **NO** at
their tails. The comparison sign follows the destination's position and is
not clickable. Moving a jump arrow's head changes its destination in the
source and automatically updates the comparison and branch answers. The
machine still jumps when the variable is nonzero: the jump path is **YES**
for a backward jump and **NO** for a forward jump.
Jump arrows leave the diamond on the right and meet the incoming arrow above
their destination at a junction dot. The destination label sits to the left
of that dot in a single-line field. Renaming it preserves comments and line
breaks around the original source label.
Drag the arrowhead onto another statement's incoming arrow to retarget it;
a label is added automatically when needed. If several heads share a dot,
select the desired jump's diamond first to highlight its arrow and bring its
head forward. You can also edit the destination below the diamond. The pencil
opens the complete source instruction for rewrites such as replacing a jump
with a structured loop.
Changes to source update the flowchart; edits in its nodes update the same
source draft. Clicking or tabbing into a flowchart element highlights its
corresponding source lines without moving the source cursor. Moving the cursor
in the code highlights the matching flowchart element. These links clear while
the diagram shows an invalid draft, so old source positions are never highlighted.
The diagram follows the selected lesson's language version:
the early labeled-loop exercises accept `do:` as a label, and the `do/while`
rewrite switches to the structured keyword form when you replace that loop.
A `do/while` loop is drawn as its body followed by a condition diamond and an
arrow back to the body's entry, like the corresponding `JNZ` translation;
there is no extra `do` action box. For a direct `variable != 0` loop, moving
that return head to a different destination replaces the structured loop with
its block-and-`JNZ` form in the source. Dropping it back on its existing entry
leaves the source unchanged. Other loop comparisons retain their condition and
fixed return destination. Every diamond displays its condition as a question.

From Structured programming onward, explicit `JNZ` is suspended in submitted
code, including macro bodies and imports. Use the earned control structures;
the compiler still emits machine jumps internally. Both **Check solution** and
**Debug** enforce the selected world's rule.

**World 2 and later** offer **Nassi–Shneiderman** and **Flowchart** in the diagram
selector at the start of the diagram toolbox, in place of the diagram type label.
Nassi–Shneiderman is the initial choice: stacked
boxes show the sequence, split boxes show an `if/else`, and enclosing boxes show
loops. The flowchart shows the same structured program as decisions and arrows.
Its toolbox inserts earned control structures; loop destinations stay fixed,
and explicit `JNZ` remains prohibited. Both views edit the same source draft.
Switching views preserves undo history and pending diagram fields while the
source is unchanged. Your choice for later worlds is remembered for the current
session, including after replaying World 1; restarting the application selects
Nassi–Shneiderman again. The grammar reflects the current world's available
instructions. Returning to World 1 restores its flowchart and explicit jumps;
each level keeps its own saved source, with no separate diagram file.

Choose a world in **Your journey**; locked worlds open as you finish the earlier
levels. Use **Enter world** at a boundary to continue. In a diagram statement,
**Enter** at the beginning inserts a statement before it, **Enter** at the end
inserts one after it, and **Enter** in the middle applies the edit. The trash
can deletes the selected block. From World 2 onward, drag an **if**,
**while**, or **do/while** symbol from either diagram's toolbox into a gap or an empty branch.
You can also select a symbol
and activate the insertion point using the keyboard. In a Nassi–Shneiderman
diagram, an empty else branch is crossed out; inserting into it creates the
corresponding source branch.
Expand **Declarations and helpers** to edit program setup.

Click **Format** above the source, beside **Undo** and **Redo**, to apply a
Java-style layout to the whole draft: two spaces per nesting level, opening
braces beside their headers, and line breaks after opening braces and
statements. Code tokens and comments are preserved. Both diagrams follow the
formatted source, and one **Undo** restores its previous layout.

Use **Undo** and **Redo** above the source to reverse or restore a source edit,
including an applied edit from either diagram. Alternatively, click in the
source and press **Ctrl+Z** to undo (**Cmd+Z** on macOS). Redo is
**Ctrl+Shift+Z** on Linux, **Ctrl+Y** on Windows, or **Cmd+Shift+Z** on macOS;
the source's right-click menu also offers both actions. While a diagram text
field has focus, its keyboard undo affects that field's pending text. Apply the
edit with **Enter** in the middle of the field to update the shared source;
unapplied field text is not part of the saved draft.

In either diagram editor, only earned syntax is accepted. If source edits no longer compile, the last valid diagram stays visible and
damaged structures turn red. The retained diagram is read-only until the source
is valid again, so its old controls cannot overwrite your current draft. Fixing
the code restores editing and removes the red marks. Tab stops, new diagram
blocks, **Format**, and earned helpers all use two-space indentation. Diagram
edits preserve existing source formatting. Changing levels clears the previous level's
cached diagram, so an unfinished draft never displays another level's program.

1. Read the short objective and input/output examples; open **Task details**
   for the full instructions. Edit the supplied program in
   **Your program**. Add temporary variables as needed. The **Complete this level
   to unlock** panel names the features, helpers, and next challenge you can earn.
2. Use **Unlocked grammar** to see the syntax currently available. Terminals have
   a light-gray background and fixed-width font; nonterminals are italic, as in
   the Java Language Specification. Newly unlocked parts use bold green text. A
   **New** badge stays on the tab until you open it, including after a restart.
   The earned toolbox has its own unread badge. Only earned syntax is shown;
   `while` appears after you build it from `if` and `do/while`.
3. Click **Check solution**. Each case gets freshly initialized memory. The
   checker substitutes its input values into the named global declarations;
   changing those starting numbers does not change the test inputs.
4. Read the result table. A failed case shows inputs, expected values, actual
   values, and the number of instructions executed. Hover over a table cell to
   read long values. When a diagnostic has a source location, that statement is
   highlighted in the editor. Correct the program and try again.
5. Passing every case completes the level and awards its features or helpers.
   A persistent green **New tools unlocked** banner shows your rewards. Click
   **Next level** to continue. Completed levels remain available to revisit.

Each level has three progressively more specific hints. Hints carry no penalty.
**Restore starter** replaces only the current draft, after confirmation; earned
features remain unlocked. Checking runs in the background and **Cancel check**
keeps your draft. An infinite loop fails at the displayed instruction limit.

To start over, use **Reset game** in the header and confirm. This clears all
completed levels, syntax unlocks, earned helpers, and drafts, then returns to the
first level. The reset is saved before the game restarts; if saving fails, your previous game
remains intact. **Restore starter** affects only the current draft.

Declare every input as a global scalar with a numeric initializer. Keep the
input and output names specified by the starter; outputs may be array elements
where the goal says so. A goal that says “preserve” includes those inputs among
the checked outputs. Finish execution with `HALT;`. Syntax practice levels also
require the relevant construct in your own source; importing a macro definition
does not satisfy a request to write one yourself. The book macro exercises also
require the requested name and parameter count, with a call from the main program.

Tests include zero cases and boundaries, but passing them does not establish
correctness for every possible input. Aim for an algorithm that meets the stated
contract, rather than a list of answers for the examples.

## Debug a draft

Choose a test case beside **Debug**, then click **Debug** to open a separate
execution window. Selecting a row in the result table also selects its inputs
for debugging, so a failing case is easy to reproduce. The debugger compiles a
snapshot of the current draft with those exact inputs and the earned library.
It accepts unfinished exercises; syntax still has to compile and use unlocked
features. In the `do/while` rewrite exercise, Debug automatically uses the earlier
version for the old labeled loop and Structured for the rewritten loop. The
debugger identifies its selected version. Check solution always requires the
Structured rewrite. Neither version accepts a mixture of both forms. Imports
must use the same version as their main source, so later helpers using
`do/while` cannot be imported into an earlier-version exercise.

For the `while` rewrite, Debug accepts both the working `if` plus `do/while`
starter and the rewritten `while` loop. **Check solution** requires one
`while (n != 0)` containing `DEC n;` then `INC count;`, followed by `HALT;`.
Adding an unused `while` while retaining the original guarded loop does not
complete the rewrite.

Use **Step** for one machine instruction or **Step statement** to advance to the
next source range, including ranges inside helpers. **Step over** finishes the
current outermost macro invocation, including nested helper calls, then
stops before the next statement in the main program. Outside a macro, it advances
through the current source range. **Run** continues execution; during Run or a
long Step over, the Run button becomes **Pause** so you can interrupt and inspect
memory, the instruction pointer `i`, and the highlighted source. Pausing cancels
the pending step; Restart or closing the window cancels it too. Step over obeys
the same instruction limit and runtime checks as other execution controls.
The **Variables** table includes declared globals, each array element, and
allocated local variables, including those in expanded macros. Local names
include their memory address so shadowed declarations and separate expansions
of the same macro remain distinguishable, for example `scratch[0] @8`. Hover
over a row to see its declaration location and macro-call chain.
**Now** updates as execution advances;
**Input** and **Expected** show the selected test case's contract values where
applicable. Other variables have no contract values, and an undeclared required
output remains visible without an address or current value.
Source tabs include imported helpers, with macro-call provenance. Compiler
scratch cells remain available in the raw memory table.
**Restart** restores the initial memory and variable values for the same case.
The debugger reuses the machine and source-editor components of the simulator;
its source is read-only, so editing the game draft cannot invalidate a running
snapshot. Debugging never awards completion. Return to **Check solution** when
you are ready to test all cases.

## The starting language

Separate lines under an italic name are alternatives; { *X* } means zero or more
copies of *X*, and [ *X* ] makes *X* optional. Fixed-width text marks literal
source tokens, including literal braces. These are the conventions used in the
[Java Language Specification](https://docs.oracle.com/javase/specs/jls/se25/html/jls-2.html#jls-2.4).

*Program*:<br>
&emsp;{ *VariableDeclaration* `;` } `begin:` { *Statement* }

*VariableDeclaration*:<br>
&emsp;`var` *Identifier* `=` *Number*

*Statement*:<br>
&emsp;*Command* `;`<br>
&emsp;*Label* `:` *Statement*

*Command*:<br>
&emsp;`HALT`<br>
&emsp;`NOP`<br>
&emsp;`INC` *Variable*<br>
&emsp;`DEC` *Variable*<br>
&emsp;`JNZ` *Variable* *Label*

*Value*:<br>
&emsp;*Number*

*Variable*:<br>
&emsp;*Identifier*

*Label*:<br>
&emsp;*Identifier*

`INC` adds one; `DEC` subtracts one and leaves zero unchanged. `JNZ x label`
jumps if `x` is nonzero. `NOP` does nothing and `HALT` stops. Names and comments
follow the [language's lexical rules](grammar.md#names-and-numbers).

The compiler has two language versions. **Before do/while** treats `do` as an
ordinary identifier, so `do:` is a normal label and `JNZ x do;` a normal jump.
After the unlock, **Structured** reserves `do` for the loop keyword. *Label*
therefore means *Identifier* throughout; there is no keyword-label exception.
The early exercises retain their version when revisited, even after later tools
have been earned. The editor identifies and highlights the applicable version.

Labels and jumps provide the construction tools for the first world. Once
all structured control flow is earned, the next world suspends explicit `JNZ`;
labels remain accepted, but cannot be used for explicit jumps there. Zero tests
unlock before general comparisons. The **General comparisons** unlock allows
`==` and `!=` between any two values: variables, numeric literals, or array
elements once arrays are earned. For example, `left == right`, `cells[1] != limit`,
and `3 == count` preserve both operands. Comparing the same cell is valid, and
loops read both values again on every test. Array indices remain numeric
literals. Copying a value into a declaration, such as `var copy = original;`,
still requires the separate **Variable initializers** unlock; general comparisons do
not enable reference initializers.

## The journey

Each challenge introduces one main idea and reuses earlier work. Features are
awarded **after** the equivalent behavior has been implemented with already
available constructs. **Every grammar unlock must be used in the very next
level.** Some following challenges introduce a new problem; others ask you to
rewrite a working program with the newly earned syntax. The checker requires
that syntax in your own source, so matching the outputs alone is not enough.
The `else` and comparison rewrites ask for one conditional followed by `HALT`;
adding an unrelated condition alongside the old algorithm does not complete them.

The second level supplies a working clearing loop. Keep its instructions and
order, add an empty statement after setup, and put braces around only `DEC x;`
after the `do:` label:

```text
INC dummy;
;
do: {
    DEC x;
}
JNZ x do;
HALT;
```

The setup increments `dummy` once, even when `x` starts at zero. The standalone
`;` is an empty statement: it changes nothing and remains outside the repeated
block. This practices both features earned in the first level. The jump stays
**after the closing brace**, where the condition belongs in the structured form:

```text
INC dummy;
;
do {
    DEC x;
} while (x != 0);
HALT;
```

Correct output alone does not pass this exercise: the checker requires the
labeled block with its jump outside. Completing it unlocks `do/while`, which is
practiced immediately: the next level supplies the old labeled loop and asks
you to replace it with the structured form, keeping the setup and results.
The existing “Run at least once” challenge follows it. The following lessons
introduce `if` and `else`, then
derive `while` from `if (n != 0) { do { ... } while (n != 0); }`. The next lesson
immediately rewrites that working program as `while (n != 0) { ... }`, before
using loops to build an equality test. This follows the control-flow derivation
in the Moodle book. The
[alignment notes](moodle-alignment.md) explain the correspondence and differences.

| Level | Challenge | Reward or purpose |
| --- | --- | --- |
| **Machine foundations — code and flowcharts** | | |
| 1 | Increment an input and halt | Blocks and empty statements |
| 2 | Add an empty statement and a block around the clearing body | `do` / `while` |
| 3 | Rewrite the labeled clearing loop as `do/while` | Direct syntax practice |
| 4 | Execute at least one iteration | Required `do` / `while` practice |
| 5 | Recognize zero while preserving the input | `if`, with zero tests |
| 6 | Use `if` to produce one of two results | `else` |
| 7 | Use `if` / `else`: result is 1 for zero, 2 otherwise | Direct `else` practice |
| 8 | Guard a `do/while` loop with `if` | `while` |
| **Structured programming — code and a choice of diagrams** | | |
| 9 | Rewrite the guarded `do/while` as one `while` loop | Direct `while` practice; Nassi–Shneiderman is the default, with flowcharts also available |
| 10 | Compare with three using `while` and counters | General `==` / `!=` comparisons between values |
| 11 | Replace the counters with a comparison to three | Direct comparison practice |
| 12 | Copy while restoring the source | Variable initializers; `clear`, `copyTo`, `copy` |
| 13 | Use a variable initializer, then clear scratch storage | Runtime declarations |
| **Growing the language** | | |
| 14 | Use runtime declarations to rotate copies through three separate cells | Arrays and literal indices |
| 15 | Fill three array elements | Array practice |
| 16 | Repeat an operation on different cells | Macros; `bump`, `twiceTo` |
| 17 | Define and call a helper | Macro practice |
| 18 | Make a macro choose its behavior | Literal macro parameters; `step` |
| 19 | Dispatch on written literal arguments | Literal-parameter practice |
| 20 | Compose a small collection of helpers | Imports; `advanceThree`; full grammar unlocked |
| 21 | Import and compose earned helpers | Import practice; full grammar in use |
| **Natural-number toolbox** | | |
| 22 | Transfer into an occupied cell | `shift`, including literal specializations |
| 23 | Replace a destination by transferring | `move` |
| 24 | Add while preserving both inputs | `addTo`, `add` |
| 25 | Subtract with saturation at zero | `subtractTo`, `sub` |
| 26 | Multiply by repeated addition | `multiplyTo`, `mul` |
| 27 | Count full groups and leftovers | `divideTo`, `div`, `mod` |
| 28 | Apply Euclid's remainder algorithm | `gcdTo`, `gcd` |
| **Signed words** | | |
| 29 | Wrap a natural number to four bits | `wrap4` |
| 30 | Extract four individual bits | `bits4`, least significant bit first |
| 31 | Decode a signed word | `decode4`, sign flag and magnitude |
| 32 | Encode sign and magnitude | `encode4` |
| 33 | Invert four bits | `not4` |
| 34 | Negate at fixed width | `negate4` |
| 35 | Add and detect signed overflow | `add4` |
| 36 | Subtract and detect signed overflow | `subtract4` |
| 37 | Sign-extend from four to eight bits | `extend4` |
| 38 | Transfer addition and decoding to eight bits | `wrap8`, `decode8`, `add8` |
| **Bits and bytes** | | |
| 39 | Separate the lowest bit from the remaining number | `splitLowestBit` |
| 40 | Unpack the lowest eight bits | `lowestByte` |
| 41 | Reconstruct a number from eight bit cells | `joinBit`, `valueOfByte` |
| 42 | Add two bits and an incoming carry | Four-parameter `add` |
| 43 | Carry through eight bit positions | 24-parameter `add` |
| 44 | Flip a bit | One- and eight-parameter `flip` |
| 45 | Negate eight bit cells in place | Eight-parameter `negate` |
| 46 | Subtract two bytes | 24-parameter `sub`; journey complete |

Division exercises use a strictly positive divisor. The gcd exercise includes
zero inputs and defines `gcd(0, 0) = 0`. Later levels build on the earned library,
so students need not repeatedly rewrite copying, division, or sign decoding.

## Earned toolbox

**Earned toolbox** shows read-only, syntax-highlighted macro definitions with
two-space indentation and Java-style brace placement. Each helper
has a usage comment describing its inputs, outputs, ranges, and whether the
output may reuse an input cell. Unless explicitly allowed, keep outputs separate
from inputs and from each other. For example, `copyTo(n, n)` and
`addTo(a, total, total)` are supported; the quotient and remainder of `divideTo`
must use separate cells. Inputs remain unchanged when outputs are distinct.

Before macros unlock, earned definitions can be read but cannot be used. After
macros unlock, **Insert earned helpers at the top** copies them into the editor
with the same formatting. This insertion can be undone with **Undo**.
Once imports unlock, this directive includes the current earned collection:

```text
import "toolbox";
```

The book spelling `import "macros.a26";` resolves to the same earned collection.
Use either name. Both resolve in memory; the game does not read files or accept
arbitrary disk imports. Definitions are awarded only after their behavior has been solved.
They are supplied reference implementations, rather than automatic conversion
of a student's complete program into a macro. Students can still write and use
their own macros.

The book names `copy`, `add`, `sub`, `mul`, `div`, `mod`, and `gcd` are also
awarded alongside the original helpers. `div(a,b,c)` returns the quotient;
`mod(a,b,c)` returns the remainder, with `b > 0` in both cases. Their output may
reuse an input cell. By contrast, `shift` and `move` consume their source and
require two different cells. Read the helper comments before reusing storage.

The usual [macro rules](language.md#macros) apply: definitions precede globals,
helpers precede their callers, the signature and opening brace share a line,
and closing braces occupy separate lines. Keep each statement on its own line.
Literal parameters dispatch by the argument's written spelling, not a cell's
runtime value. Macro arguments and indexing retain the compiler's textual
semantics; the game does not add an array type system.

## Fixed-width signed numbers

Machine cells still contain nonnegative integers. A four-bit word is encoded as
an integer from 0 through 15; the student implements wrapping and interprets its
sign. Encodings 0 through 7 represent themselves; encodings 8 through 15 represent
the signed value `word - 16`. For example, 8 represents −8 and 15 represents −1.
The decoder reports `negative` as 0 or 1 and a nonnegative `magnitude`.

Addition and subtraction wrap modulo 16. Their `overflow` output indicates that
the mathematical **signed** result lies outside −8 through 7. This differs from
an unsigned carry: 15 + 1 wraps to zero but represents −1 + 1 and has no signed
overflow. Negating the minimum word, 8, returns 8 at this width. Sign extension
maps 15 to 255, keeping its signed interpretation of −1.

The eight-bit scalar challenge changes the modulus to 256 and the sign threshold to 128,
representing −128 through 127. It combines wrapping, decoding, and signed
overflow detection. Four-bit unary challenges test every encoding; addition,
subtraction, and eight-bit transfer use boundary and representative cases.

## From encoded words to bit cells

The final world follows the book's individual-bit representation. Each bit is a
separate machine cell containing 0 or 1; `b0` is the least significant bit.
Splitting a number by two extracts its lowest bit. Repeating that operation
extracts a byte, and repeated doubling plus a bit reconstructs its value.

A full adder uses `add(a,b,u,c)`: `a` and `b` are input bits, `u` is the incoming
carry, and the outputs are the sum bit `c` and updated carry `u`. All eight input
combinations are checked. Eight such steps form the 24-parameter byte `add`;
the last carry is discarded, so the result wraps modulo 256. The three-, four-,
and 24-parameter `add` macros coexist because calls select a signature by name
and number of arguments. Repetitive signatures and call sites are supplied in
the starters, leaving the algorithm as the task.

Bitwise inversion followed by adding one implements two's-complement negation.
Byte subtraction uses the same idea. The earned byte addition and subtraction
helpers copy inputs before replacing output cells, permitting an output byte to
reuse either input byte; output cells must still be distinct. This differs from
the earlier scalar levels, which also compute a signed-overflow flag.

## Saved work and execution limits

Drafts, completed levels, and the selected level save automatically on this
computer in `~/.language-game/progress.properties`. Closing also saves. A failed
save is shown in the status line and does not discard in-memory progress. An
unreadable or incompatible save is preserved while a separate recovery file is
used. To start a separate classroom profile, launch with the Java system
property `-Dlanguage.game.progress=/path/to/profile.properties`.

Every current level permits at most 500,000 executed instructions per case.
The checker also bounds source size, nesting, macro expansion, data storage,
and code size before running. Ordinary cells must stay within Java's nonnegative
`int` range; overflowing arithmetic and attempts to write code or the instruction
pointer fail the check. These bounds keep puzzle submissions manageable. They
do not change the standalone simulator's machine model.

Existing saves keep all completed levels and drafts when this curriculum grows.
Newly inserted levels remain unfinished; complete the earliest one to continue.

# Language Game and the Moodle machine-model book

[Game guide](language-game.md) · [Machine model](machine-model.md) · [Language](language.md)

The game follows the Moodle book *Maschinenmodell* while retaining every one of
its earlier 32 challenges. Ten additional challenges cover destructive transfer
and arithmetic on separate bit cells. A further rewrite exercise practices the newly earned `do/while` form directly.
Three short rewrites practice `else`, `while`, and general comparisons, bringing
the journey to 46 levels in six worlds. Every grammar unlock is required in the
next level.
The book remains useful alongside the game: it develops the reasoning in more
detail than an individual puzzle can.

## Correspondence

| Book topic | Game treatment |
| --- | --- |
| Memory, instructions, symbolic variables and labels | The first lesson introduces `i = S[0]`, compiler-assigned variable addresses, and the five instructions. The simulator and machine-model guide provide the raw-memory view. |
| Clearing with a backward jump; post-tested repetition | Level 2 groups `DEC x;` under `do:`. `JNZ x do;` follows the closing brace. Completing the level earns `do/while`; level 3 rewrites that same clearing loop as `do/while`; level 4 practices the at-least-once behavior. |
| Forward jumps, zero tests, alternatives | Levels 5–7 explain the zero-test derivation, flag inversion for a nonzero test, and why two independent tests can both execute when the first changes the tested value. Each unlock of `if` or `else` is followed immediately by its use. |
| Pre-tested repetition | Level 8 requires an `if` around a `do/while` loop before awarding `while`. Level 9 supplies that working solution and asks for the direct rewrite as `while (n != 0) { DEC n; INC count; }`, preserving zero iterations for zero input. Level 10 then uses loops to build an equality test. |
| Structured programming and Nassi–Shneiderman diagrams | Level 9 opens a new world after all control structures are earned, with the immediate `while` rewrite as its first task. Code and editable diagrams appear together; explicit `JNZ` is suspended while generated machine jumps remain internal to the compiler. |
| Extension mechanisms | Copying, local declarations, arrays, macros, literal specializations, and imports each have construction and immediate practice levels. |
| Natural-number macros | The toolbox includes `clear`, `shift`, `move`, `copy`, `add`, `sub`, `mul`, `div`, `mod`, and `gcd`. The earlier helper names remain available. |
| Binary representation | The four-bit exercises introduce wrapping, decomposition, signed interpretation, and arithmetic before an eight-bit scalar challenge. |
| Arithmetic on separate bits | The added final world derives `splitLowestBit`, `lowestByte`, `joinBit`, `valueOfByte`, a full adder, byte addition, `flip`, `negate`, and byte subtraction. |

The clearing exercise deliberately has this shape:

```text
INC dummy;
;
do: {
    DEC x;
}
JNZ x do;
HALT;
```

The standalone `;` practices the empty statement earned alongside blocks. It
has no effect and stays after setup, outside the repeated body. Only the loop
body is inside the block. The jump corresponds to the trailing
condition of `do { DEC x; } while (x != 0);`. Placing the jump inside the block
would execute equivalently, but obscure this correspondence. The exercise now
rejects that grouping, as well as grouping the unrelated setup instruction.

The compiler handles the transition through two profiles rather than allowing a
keyword as a label. In **Before do/while**, `do` is an ordinary identifier; in
**Structured**, it is a reserved loop keyword. The label production remains just
*Identifier*. The game keeps earlier exercises on their original profile and
selects the appropriate profile when debugging either side of the rewrite.

The book's overloads coexist: natural-number `add` has three parameters, the
full adder has four, and byte addition has 24. `flip` has one- and eight-parameter
forms. Literal specializations retain the language's existing dispatch rules.
The checker distinguishes macro names and arities and accepts up to 32 arguments.

Helpers state their storage contracts. `shift` and `move` consume their source
and require distinct cells. Preserving arithmetic permits an output to replace
an input by computing from the original values. Byte addition and subtraction
snapshot inputs before writing outputs; their output cells must be distinct.
Division requires a positive divisor, and `gcd(0, 0)` is defined as zero.

## Deliberate differences and limits

There is no unresolved syntax conflict for the covered book algorithms. Some
differences are consequences of a runnable, bounded teaching environment:

- The book's mathematical machine has unbounded natural numbers and memory.
  This implementation uses Java `int` cells and finite storage. Game checks also
  impose compilation and execution limits. Removing that difference requires
  a separate machine-representation change; it is not a curriculum edit.
- Raw instruction listings and manipulation of the instruction pointer belong
  to the machine-model explanation and simulator. Game submissions use symbolic
  source and cannot write instructions or the instruction-pointer cell.
- `import "macros.a26";` and `import "toolbox";` both select the earned virtual
  library in the game. General file imports remain available in the compiler
  and simulator, but game checks do not load arbitrary disk files.
- The current simulator uses **Open** to choose a source and **Compile** to
  recompile the active main file. The book's older instruction to choose a file
  through **Compile** does not describe the current interface.
- The game awards both zero comparisons with `if`. The book derives them
  separately; the lesson explains that derivation without adding another lock.

Existing saved completions and drafts are retained by level ID. Newly added
lessons do not receive credit automatically. The earliest unfinished lesson is
available alongside previously completed lessons. A saved 45-level completion
keeps all of its credit and drafts; only the new `while` rewrite remains to solve.

## Teaching tradeoffs

The game's advantages are immediate feedback on concrete counterexamples,
short cumulative tasks, explicit unlocks, and repeatable checks of zero and
boundary cases. The four-bit stage keeps examples small enough to inspect all
unary encodings before moving to eight bits. Its signed-overflow flag, minimum
signed-value negation, and sign extension make distinctions that are easy to
miss when focusing only on unsigned carry.

The book gives more space to continuous derivations, invariants, contracts,
and arguments for correctness. The added transfer lessons make the invariant
`a + b` explicit, and helper comments describe aliasing, but finite execution
checks still do not prove a student's program correct for every input. Using
the book for explanation and the game for practice preserves both strengths.

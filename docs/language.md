# Language reference

[README](../README.md) · [Machine model](machine-model.md) · [Development](development.md)

The syntax is defined in [CompilerParser.jj](../machine/CompilerParser.jj); imports and macro expansion
are implemented in [Compiler.java](../machine/Compiler.java). Ordinary programs use structured control
flow. Macros expand into machine instructions at compile time.

The [context-free grammar](grammar.md) gives the formal lexical and syntactic
productions using Java Language Specification notation.

## Program layout and syntax

After import expansion, source consists of macro definitions, then global variable declarations,
then exactly one `begin:` and executable statements. Define helper macros before macros that call
them. End programs with `HALT;`.

Names and keywords are case-sensitive. Use lowercase `var`, `void`, `if`, `else`, `while`, and `do`,
and uppercase instructions. Write `begin:` without an intervening space. Identifiers start with an
ASCII letter or `_`, followed by letters, underscores, or digits. Avoid the `___` prefix used for
generated names.

`//` comments run to the end of the line; block comments are unsupported. Declarations,
instructions, macro calls, and `do`/`while` statements end with `;`. `if` and `while` blocks need no
trailing semicolon. An isolated `;` does nothing.

## Values, variables, and arrays

Numbers are nonnegative decimal literals from `0` through `2147483647`. There are no general
arithmetic expressions or standalone assignments. The machine stores Java `int` values, and
arithmetic overflow is not checked.

| Declaration | Meaning |
| --- | --- |
| `var count = 7;` | Allocate one cell initialized to seven. |
| `var copy = count;` | Allocate an independent cell with the source value. |
| `var cells[3] = 2;` | Allocate three cells, each initialized to two. |
| `var copy = cells[1];` | Copy the second cell's value. |
| `var copies[3] = cells[1];` | Fill three cells with that one value. |

Array lengths and indices must be numeric literals. Use positive lengths and zero-based indices
below the declared length. An unindexed array name refers to its first element. Individual array
bounds are not enforced; an invalid index can access other data or code. Global reference
initializers reject addresses outside allocated data, but this is not full array-bounds checking.

Before `begin:`, declarations initialize memory during compilation; reference initializers can use
only previously declared globals. After `begin:`, a `var` declaration emits instructions that
initialize its storage when reached. Thus `var snapshot = count;` copies the value at that point in
execution. Reentering that declaration initializes the same storage again.

Braces group statements but do not introduce independent lexical variable scopes. Use distinct names
for ordinary program declarations. Macro-local names are renamed separately for each expansion.

Large runtime literal initializers generate substantial code, and runtime value copies can take many
machine steps. Runtime copying temporarily increments its source, overflowing at `2147483647`; avoid
that boundary for runtime copies. Global initialization can copy the maximum value without that
increment.

## Instructions and control flow

| Instruction | Effect |
| --- | --- |
| `INC count;` | Increment a variable or array element. |
| `DEC count;` | Decrement it, stopping at zero. |
| `NOP;` | Advance without changing ordinary data. |
| `HALT;` | Stop execution. |

Conditions support `==` and `!=`. The left operand is a variable, an array element, or a numeric
literal; the right operand must be a numeric literal. Comparisons preserve their operands. There are
no variable-to-variable comparisons, ordering operators, or Boolean combinations.

`if` has an optional `else`. `while` checks before its body, whereas `do`/`while` checks afterward
and executes at least once. All bodies require braces and may be nested. There is no `break`,
`continue`, or `return`.

This complete program finishes with `count = 1` and `matches = 1`:

```text
var count = 0;
var matches = 0;
begin:
while(count != 3) {
    INC count;
}
if(count == 3) {
    INC matches;
} else {
    NOP;
}
do {
    DEC count;
} while(count != 1);
HALT;
```

## Macros

A `void` definition has a name, a parameter list, and a body. Calls use parentheses and a trailing
semicolon; parameterless definitions and calls use `name()`. Arguments can be variables,
literal-index array elements, or numeric literals. Expansion substitutes arguments into the body.
Changing a variable argument changes the caller's cell; passing the same cell twice can alias it.

Parameter and argument lists may be empty. Nonempty lists contain entries separated by single
commas; leading, trailing, and consecutive commas are rejected, even in unused definitions.

Scalar and array declarations inside a macro receive fresh names for each expansion, including
references passed to nested macros. Declare locals before use and do not redeclare parameters or
locals within a macro.

Macros may access globals declared after the definitions. Instruction operands, conditions, and
nested-call arguments that are neither parameters nor prior locals are checked against globals even
in unused definitions. A variable declared only in the executable section cannot satisfy such a
global reference.

This complete program leaves `source = 3`, `total = 9`, both `cells` elements at `3`, and `calls =
1`:

```text
void add(amount,destination) {
    var remaining = amount;
    while(remaining != 0) {
        DEC remaining;
        INC destination;
    }
}
void add(0,destination) {
    NOP;
}
void tick() {
    INC calls;
}
var source = 3;
var total = 4;
var cells[2] = source;
var calls = 0;
begin:
add(cells[1],total);
add(2,total);
add(0,total);
tick();
HALT;
```

Definitions may share a name. Argument counts must match. Identifier parameters match any allowed
argument; numeric parameters match the exact literal spelling, so `1` differs from `01` and from a
variable holding one. Surrounding whitespace is ignored. For calls after their candidate
definitions, the last matching definition wins, without automatic preference for specializations.
Put general versions first and literal specializations later. Helpers must precede callers; general
forward macro references and recursion are unsupported.

A condition's right operand must already be a numeric literal in the definition; a named macro
parameter cannot stand there even if a call would supply a literal.

Macro expansion is line-oriented: keep signatures and calls on individual lines, put the opening `{`
on the signature line, and put each closing brace on its own line. Use one statement per line. When
a parameter may receive a literal, write one space between `INC`/`DEC` and that parameter; detection
of required temporary storage is textual.

## Imports and internal branches

An import consists of `import`, a quoted path to a library supplied by the user, and `;`. Put it on
its own line without a trailing comment. Paths are relative to the Java process's working directory,
not the source file's directory; absolute paths also work. Imports expand once before comment removal, so nested imports
are unsupported. Imported text must preserve the macro/global/`begin:` order. Libraries normally
contain macro definitions without `begin:`.

Public programs should use `if`, `while`, and `do`/`while`. Labels and `JNZ` are mechanisms for the
compiler and library macro implementations; labels are intentionally not macro arguments. Raw label
and `JNZ` syntax remains accepted by the parser, so this public/internal boundary is currently a
project convention, not an enforced restriction.

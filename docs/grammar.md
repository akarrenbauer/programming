# Language grammar

[README](../README.md) · [Language reference](language.md) · [Machine model](machine-model.md)

This grammar describes structured programs after imports and comments have been
processed. It follows the language accepted by
[CompilerParser.jj](../machine/CompilerParser.jj). Internal labels and jumps are
listed separately at the end.

## How to read the grammar

The notation follows the [Java Language Specification](https://docs.oracle.com/javase/specs/jls/se25/html/jls-2.html#jls-2.4):

| Notation | Meaning |
| --- | --- |
| *Name* | A named part of the grammar, defined below. |
| `text` | Characters written literally in the program. |
| [ *X* ] | *X* is optional. |
| { *X* } | *X* may occur zero or more times. |
| Separate lines under a name | Alternatives: choose one. |

Brackets and braces in fixed-width text, such as `[` and `{`, are literal source
characters. For example, the *Variable* rule allows both `count` and `cells[2]`.

## Names and numbers

The grammar uses these lexical tokens:

*Identifier*:<br>
&emsp;an ASCII letter or `_`, followed by zero or more ASCII letters, digits, or `_`

*Number*:<br>
&emsp;one or more decimal digits (`0` through `9`)

Names are case-sensitive. The reserved words `var`, `void`, `if`, `else`, `while`,
`do`, `HALT`, `NOP`, `INC`, `DEC`, and `JNZ` cannot be identifiers. Leading zeros
are allowed in numbers. The entry marker `begin:` is a single token: do not put
whitespace before its colon. Bare `begin` is an identifier.

Spaces, tabs, and line breaks separate tokens. Comments start with `//` and
continue to the end of the line.

## Programs and macros

*Program*:<br>
&emsp;{ *MacroDefinition* } { *VariableDeclaration* `;` } `begin:` { *Statement* }

*MacroDefinition*:<br>
&emsp;`void` *Identifier* `(` [ *Parameter* { `,` *Parameter* } ] `)` *Block*

*Parameter*:<br>
&emsp;*Identifier*<br>
&emsp;*Number*

A program has macro definitions first, then global declarations, then `begin:`
and executable statements. The entire input must match *Program*. Ending with
`HALT;` is recommended, but not required by the grammar.

A named macro parameter stands for an argument. A numeric parameter matches that
literal spelling, as explained in the [macro reference](language.md#macros).

## Statements

*Statement*:<br>
&emsp;*VariableDeclaration* `;`<br>
&emsp;*Command* `;`<br>
&emsp;*MacroCall* `;`<br>
&emsp;*Block*<br>
&emsp;`if` `(` *Condition* `)` *Block* [ `else` *Block* ]<br>
&emsp;`while` `(` *Condition* `)` *Block*<br>
&emsp;`do` *Block* `while` `(` *Condition* `)` `;`<br>
&emsp;`;`

*Block*:<br>
&emsp;`{` { *Statement* } `}`

*VariableDeclaration*:<br>
&emsp;`var` *Identifier* [ `[` *Number* `]` ] `=` *Value*

*Command*:<br>
&emsp;`HALT`<br>
&emsp;`NOP`<br>
&emsp;`INC` *Variable*<br>
&emsp;`DEC` *Variable*

*MacroCall*:<br>
&emsp;*Identifier* `(` [ *Value* { `,` *Value* } ] `)`

Blocks include their braces and may be empty. All loop and conditional bodies
are blocks. A lone `;` is an empty statement. Macro parameter and argument lists
may be empty; otherwise commas separate entries, with no trailing comma.

Declarations have the same syntax before and after `begin:`. Global declarations
initialize memory during compilation; declarations in statements initialize it
when execution reaches them.

## Values and conditions

*Value*:<br>
&emsp;*Number*<br>
&emsp;*Variable*

*Variable*:<br>
&emsp;*Identifier* [ `[` *Number* `]` ]

*Condition*:<br>
&emsp;*Value* `==` *Number*<br>
&emsp;*Value* `!=` *Number*

Array sizes, array indices, and the right-hand side of a comparison are numeric
literals. For example, `cells[2] != 0` is a condition.

## Imports and other compiler rules

Before parsing, the compiler replaces each import directive with the contents of
the named file:

*ImportDirective*:<br>
&emsp;`import` `"` *ImportPath* `"` `;`

*ImportPath*:<br>
&emsp;a nonempty path without double quotes or line breaks

Put an import on its own line, with no trailing comment or space between the
closing quote and `;`. Relative paths start at the working directory. Imported
files are not scanned for further imports. After replacement, the combined text
must follow *Program*, including its declaration order.

The grammar describes syntax. Name resolution, numeric limits, macro matching,
and the current macro-formatting restrictions are additional compiler rules;
see the [language reference](language.md).

## Internal labels and jumps

The compiler also accepts labels and `JNZ`. By project convention, these are for
compiler and library macro implementations; student programs use structured
control flow. To describe internal code, add these alternatives:

*Statement*:<br>
&emsp;*Identifier* `:` *Statement*

*Command*:<br>
&emsp;`JNZ` *Variable* *Identifier*

The final identifier in `JNZ` names a label. The recursive *Statement* rule allows
several labels before one statement, but a label must be followed by a statement.
Labels are not macro arguments.

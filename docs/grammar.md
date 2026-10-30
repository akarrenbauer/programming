# Context-free grammar

[README](../README.md) · [Language reference](language.md) · [Machine model](machine-model.md) · [Development](development.md)

This reference describes the language's lexical and syntactic structure, derived
from [CompilerParser.jj](../machine/CompilerParser.jj). The main syntactic grammar
describes structured source programs after imports and comments have been
processed, but before macro expansion. Internal branches and preprocessing are
specified separately below.

## Grammar notation

The notation follows [JLS §2.4, Grammar Notation](https://docs.oracle.com/javase/specs/jls/se25/html/jls-2.html#jls-2.4).
*ItalicNames* denote nonterminals; `fixed-width text` denotes literal terminals.
A production begins with its nonterminal and a colon. Each following line gives
an alternative. The annotation “(one of)” lists alternatives on one line.

[ *X* ] means that *X* is optional, and { *X* } means zero or more repetitions.
These are grammar notation: literal array brackets and block braces are written
as `[` `]` and `{` `}`. These abbreviations can be expanded into ordinary
context-free productions. Narrative character descriptions define lexical
character sets.

## Lexical grammar

The syntactic grammar consumes tokens. Their character-level forms are defined
below. Tokens are case-sensitive; the lexer selects the longest matching token,
with keywords taking precedence over an equally long identifier.

*Token*:<br>
&emsp;*Identifier*<br>
&emsp;*Number*<br>
&emsp;*Keyword*<br>
&emsp;*Separator*<br>
&emsp;*Operator*<br>
&emsp;*EntryMarker*

*Identifier*:<br>
&emsp;*IdentifierStart* { *IdentifierPart* } but not a *Keyword*

*IdentifierStart*:<br>
&emsp;an ASCII letter from `A` through `Z` or `a` through `z`<br>
&emsp;`_`

*IdentifierPart*:<br>
&emsp;*IdentifierStart*<br>
&emsp;*Digit*

*Number*:<br>
&emsp;*Digit* { *Digit* }

*Digit*:<br>
&emsp;(one of)<br>
&emsp;`0` `1` `2` `3` `4` `5` `6` `7` `8` `9`

*Keyword*:<br>
&emsp;(one of)<br>
&emsp;`HALT` `NOP` `INC` `DEC` `JNZ` `var` `do` `while` `if` `else` `void`

*Separator*:<br>
&emsp;(one of)<br>
&emsp;`(` `)` `{` `}` `[` `]` `,` `;` `:`

*Operator*:<br>
&emsp;(one of)<br>
&emsp;`=` `==` `!=`

*EntryMarker*:<br>
&emsp;`begin:`

`begin:` is one token and must contain no intervening whitespace. Bare `begin`
is an identifier. `import` is handled before lexing, rather than being a keyword
in this token grammar. Leading zeros are allowed in *Number*; numeric range
limits are separate from its lexical form.

The lexer skips spaces, horizontal tabs, carriage returns, and line feeds.
`//` comments are removed by the compiler before lexing; block comments are not
supported. Whitespace may separate tokens but cannot split a token.

## Syntactic grammar

The goal symbol is *Program*. The complete token stream must match this
production; reaching the end of input is required but is not a source token.

### Programs and declarations

*Program*:<br>
&emsp;{ *MacroDefinition* } { *GlobalVariableDeclaration* } `begin:` { *StatementOrBlock* }

*GlobalVariableDeclaration*:<br>
&emsp;*VariableDeclaration* `;`

*VariableDeclaration*:<br>
&emsp;`var` *Identifier* [ *ArraySize* ] `=` *Value*

*ArraySize*:<br>
&emsp;`[` *Number* `]`

*Value*:<br>
&emsp;*Number*<br>
&emsp;*Variable*

*Variable*:<br>
&emsp;*Identifier* [ *ArrayIndex* ]

*ArrayIndex*:<br>
&emsp;`[` *Number* `]`

Global and runtime variable declarations have the same syntax. Their placement
determines when initialization occurs. Array sizes and indices are literal
numbers, rather than expressions.

### Macro definitions and calls

*MacroDefinition*:<br>
&emsp;`void` *MacroSignature* *Block*

*MacroSignature*:<br>
&emsp;*Identifier* `(` [ *ParameterList* ] `)`

*ParameterList*:<br>
&emsp;*Parameter* { `,` *Parameter* }

*Parameter*:<br>
&emsp;*Identifier*<br>
&emsp;*Number*

*MacroCall*:<br>
&emsp;*Identifier* `(` [ *ArgumentList* ] `)`

*ArgumentList*:<br>
&emsp;*Argument* { `,` *Argument* }

*Argument*:<br>
&emsp;*Value*

A numeric parameter denotes a literal specialization. Formal parameters cannot
include array indices, but actual arguments may name array elements. Empty
parameter and argument lists are allowed. Calls receive their terminating `;`
from *Statement* below; definitions do not require one.

Each nonempty list requires an entry before and after every comma. Leading,
trailing, and consecutive commas are syntax errors. These rules are checked
before macro expansion, including in definitions that are never called.

### Statements and blocks

*StatementOrBlock*:<br>
&emsp;*UnlabeledStatementOrBlock*

*UnlabeledStatementOrBlock*:<br>
&emsp;*Block*<br>
&emsp;*WhileBlock*<br>
&emsp;*DoWhileBlock*<br>
&emsp;*IfElseBlock*<br>
&emsp;*Statement*

*Block*:<br>
&emsp;`{` { *StatementOrBlock* } `}`

*Statement*:<br>
&emsp;*VariableDeclaration* `;`<br>
&emsp;*Command* `;`<br>
&emsp;*MacroCall* `;`<br>
&emsp;`;`

*Command*:<br>
&emsp;`HALT`<br>
&emsp;`NOP`<br>
&emsp;`INC` *Variable*<br>
&emsp;`DEC` *Variable*

*WhileBlock*:<br>
&emsp;`while` `(` *Condition* `)` *Block*

*DoWhileBlock*:<br>
&emsp;`do` *Block* `while` `(` *Condition* `)` `;`

*IfElseBlock*:<br>
&emsp;`if` `(` *Condition* `)` *Block* [ *ElsePart* ]

*ElsePart*:<br>
&emsp;`else` *Block*

*Condition*:<br>
&emsp;*Value* *ComparisonOperator* *Number*

*ComparisonOperator*:<br>
&emsp;(one of)<br>
&emsp;`==` `!=`

Blocks may be empty or nested, including standalone blocks. Every conditional
and loop body requires braces; `else if` without an intervening block is not a
production. The right operand of a comparison must be a numeric literal, even
inside a macro definition. `HALT;` is recommended at the end of a program but is
not required by the grammar: `begin:` alone is syntactically valid.

## Internal branch syntax

Labels and `JNZ` are reserved by project convention for compiler and library
macro implementations. Public programs should use the structured statements
above. The parser currently also accepts the following extension.

Replace the *StatementOrBlock* production with:

*StatementOrBlock*:<br>
&emsp;{ *LabelDeclaration* } *UnlabeledStatementOrBlock*

*LabelDeclaration*:<br>
&emsp;*Identifier* `:`

Add this alternative to *Command*:

*Command*:<br>
&emsp;`JNZ` *Variable* *Identifier*

The final identifier is a label name. There is no comma between the operands.
Multiple labels may precede one statement or block, including an empty statement.
A label cannot stand alone at the end of a block or program. Label arguments to
macros are intentionally unsupported: a name in *Argument* denotes a variable,
not a code label. This distinction requires name resolution beyond the grammar.

## Imports and preprocessing

[Compiler.java](../machine/Compiler.java) expands imports before invoking the
parser. The supported directive form is:

*ImportDirective*:<br>
&emsp;`import` `"` *ImportPath* `"` `;`

*ImportPath*:<br>
&emsp;a nonempty path without double quotes or line terminators

Put each directive on its own line, with no trailing comment and no space
between the closing quote and `;`. Paths do not use string escape sequences.
Relative paths resolve from the process's working directory. Imported contents
replace the directive once; nested imports are not expanded. After replacement,
the combined text must match *Program*, including its declaration order. Imports
therefore do not appear as alternatives within the syntactic grammar.

Macro expansion also imposes formatting rules beyond the token grammar. Keep
signatures and calls on individual lines, with opening braces on signature lines
and closing braces on separate lines. See the [language reference](language.md#macros)
for supported formatting and matching behavior.

## Correspondence with the implementation

The two parser productions `GlobalVariableDeclaration` and
`LocalVariableDeclaration` share the syntax represented by *VariableDeclaration*.
Similarly, `IfCondition` and `DoWhileCondition` share *Condition* syntax but emit
different control-flow code. JavaCC lookahead directives select alternatives;
they are not source tokens or additional productions.

A syntactic derivation does not establish that names resolve, numeric values fit
the implementation's integer range, or a matching macro definition exists.
Those properties require further checks. Imports must also be readable, and
macro expansion must succeed. The final compilation pass rejects remaining macro
definitions or calls and unresolved labels. See the [development guide](development.md)
for the compilation stages.

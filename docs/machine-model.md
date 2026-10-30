# Machine model

[README](../README.md) · [Language](language.md) · [Development](development.md)

[Machine.java](../machine/Machine.java) executes an expandable list of Java `int`
values. Let `i = S[0]` be the instruction pointer before a step. The opcode is
`S[i]`; operands follow it in memory. Addresses and jump destinations are absolute.

| Opcode | Encoding | Operation | Next instruction pointer |
| --- | --- | --- | --- |
| 0 | `0` | `HALT`: leave state unchanged. | `i` |
| 1 | `1` | `NOP`: no ordinary data operation. | `i + 1` |
| 2 | `2, j` | `INC`: increment `S[j]`. | `i + 2` |
| 3 | `3, j` | `DEC`: set `S[j]` to `max(0, S[j] - 1)`. | `i + 2` |
| 4 | `4, j, k` | `JNZ`: test `S[j]`. | `k` if nonzero; otherwise `i + 3`. |

Unallocated nonnegative addresses read as zero. Writes grow memory and fill
intervening cells with zero. Negative addresses are invalid. The step counter
excludes `HALT`. `step(n)` stops early at `HALT`, while `run()` has no execution
limit. `INC` or `DEC` targeting cell zero is followed by the ordinary
instruction-pointer update, which overwrites that write.

Compiled memory contains data followed by code. The first global normally starts
at address `1`; compiler-generated scratch cells and runtime locals also occupy
data memory. Do not assume a fixed starting address for the instructions.

For example, the memory image `[2, 7, 2, 1, 0]` starts at address `2`, increments
cell `1` from seven to eight, and then halts at address `4`. Labels and jumps
remain internal tools; source programs should use structured control flow.

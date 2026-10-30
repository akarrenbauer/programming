# Programming: machine simulator and compiler

A teaching project for exploring how structured programs become instructions on a
small random-access machine. It includes a compiler for variables, arrays,
conditions, loops, and macros; a Java execution model; and a JavaFX source editor,
memory viewer, and debugger.

**BlueJ is the recommended way to run the GUI.** The documentation also explains
the source language, the machine model, and a command-line development workflow.
All repository file links and build inputs refer to tracked project files;
examples are included directly in the documentation.

- [Run the GUI with BlueJ](#run-the-gui-with-bluej)
- [Use the simulator](#use-the-simulator)
- [Language](docs/language.md)
- [Context-free grammar](docs/grammar.md)
- [Machine model](docs/machine-model.md)
- [Development](docs/development.md)

## Run the GUI with BlueJ

Use a BlueJ installation with JavaFX support and a suitable Java runtime. The
[command-line build](docs/development.md#quick-start-without-a-gui) targets Java 17. JavaCC is needed only when
editing the grammar, because generated parser sources are already included.

1. In BlueJ, choose **Project → Open** and select the repository root, containing
   the tracked [package.bluej](package.bluej).
2. Open the `machine` package. Its
   [project metadata](machine/package.bluej) contains the class diagram.
3. Click BlueJ's **Compile** button in that package.
4. Right-click [MachineGUI](machine/MachineGUI.java) and choose
   **Run as JavaFX application**, BlueJ's
   [documented JavaFX launch action](https://www.bluej.org/faq.html).
5. If BlueJ asks whether to run the project's code on the JavaFX thread, enable
   that option when interacting with GUI objects through BlueJ.

The **Machine Simulator** window opens with halted, empty memory. BlueJ's Compile
button compiles Java classes; the simulator's separate **Compile** button compiles
programs in the teaching language.

If compilation reports missing `javafx` packages, check the BlueJ installation's
JavaFX configuration. If Java source features are rejected, check the Java version
used by BlueJ. A graphical desktop and compatible JavaFX native libraries are
required to display the window.

### First program

Click **New** in the source toolbar, enter this complete program, then click
**Compile**. Use **Save source** to choose a filename when ready. No example
file from the working tree is needed.

```text
var left = 3;
var right = 4;

begin:
while (left != 0) {
    DEC left;
    INC right;
}
HALT;
```

Click **Step** to execute individual machine instructions, or **Run** to finish.
The loop transfers three units from `left` into `right`: address `1` ends at `0`
and address `2` ends at `7`. Use **Compile** to restore the initial program state.
A source statement can compile to several machine instructions.

## Use the simulator

| Control | Behavior |
| --- | --- |
| **New** | Open an empty, unnamed source tab without changing machine memory or closing other tabs. |
| **Open** | Open a source file in a tab. If it contains a top-level `begin:` marker, activate it as main and attempt compilation. |
| **Compile** | Compile the active main file and its imports from the current editor contents, including unsaved edits. Successful compilation replaces memory and resets the step counter. |
| **Save source** | Save the selected source tab, choosing a filename for an unnamed tab. Ctrl+S / Command+S does the same. |
| **Follow execution** | Automatically show the source statement for the next instruction as execution advances. |
| **Load** | Load a raw integer memory image, replacing memory and resetting the counter. |
| **Reload** | Reread the last memory file chosen with **Load**, independently of the source chosen with **Open**. |
| **Save** | Save the current memory snapshot, including the instruction pointer. It does not save source or change the **Reload** target. |
| **Reset** | Clear memory and the step counter. Use **Compile** or **Reload** to restart the original program. |
| **Run** / **Stop** | Execute until `HALT` or pause while preserving the current state. |
| **Step** | Execute one instruction; do nothing at `HALT`. |
| **Step n** | Execute at most a nonnegative number of instructions, stopping earlier at `HALT`. |
| **T** / **Home** | Scroll to address `0`. |

### Edit source files

The active main tab is marked **(main)**; it need not be the first tab. For named
files, the full path appears below the editor and in the tab's
tooltip. A `*` marks unsaved changes. **Save source** saves that tab;
**Save** in the main toolbar saves machine memory.

**New** adds a blank **Untitled** tab; further new tabs receive numbered names.
No file is created until **Save source** succeeds. Canceling the filename dialog
keeps the tab and its contents. A blank draft is not a main file. When no main is
active, adding a top-level `begin:` marker activates that draft and enables
**Compile**. An unnamed main can be compiled and stepped through before saving;
its first save gives it a file identity, so compile again to restore source
tracking afterward.

**Open** retains all existing tabs and unsaved edits. An opened file with a
top-level `begin:` marker becomes main and is compiled automatically. A file
without that marker opens for editing and leaves the active main and machine
state unchanged. Markers in comments, quoted paths, or nested blocks do not count.

Single-click a tab to browse its source. **Double-click its header** to activate
and compile it as main; it must contain a top-level `begin:` marker. **Compile**
always uses the active main, even while another tab is selected. Removing the
main's marker clears its active status and disables **Compile**. Stop execution
before opening or activating another main.

Source tabs highlight keywords, machine instructions, numbers, quoted paths,
comments, labels, and macro names as you type. Highlighting also works while
statements are incomplete; compilation still checks whether the program is valid.

**Compile** uses the current editor contents without saving them. New imports
open when compilation reaches them, using the compiler's existing paths relative
to the working directory. Already opened tabs keep their current contents,
including when opened again; reopening does not discard edits or reread the file.
Removing an import leaves its tab open so edits can still be saved, but that file
is included in compilation only while the main program imports it.

Compilation errors leave the source tabs available for correction and preserve
the previous machine memory. Before closing the window, unsaved changes offer
**Save all**, **Discard**, or **Cancel**. **Save all** asks for filenames for edited
unnamed tabs; canceling a filename dialog cancels the close. Untouched empty drafts
need no save prompt. **Home** works
normally inside the editor. Stop execution before recompiling or replacing
memory; those controls are disabled while the machine runs.

### Follow execution in the source

After compilation, a green background marks the statement for the instruction at
`i`, which is the **next** instruction to execute. **Step**, **Step n**, **Run**, and
**Stop** update this marker together with memory. At an explicit `HALT`, the
statement turns amber and the status reads **Halted**. Several machine instructions
may belong to one statement, so a step does not always move the marker.

With **Follow execution** enabled, the editor selects the relevant file tab and
scrolls to the statement. This preserves the caret, text selection, and undo
history. Turn it off to browse other files while execution continues; the markers
and location status still update.

For an expanded macro, the marker appears on the statement in the macro body,
including bodies in imported files. Its call sites have a pale blue background;
**Called from** links let you visit the calls from outermost to innermost. Literal
argument preparation points to the call that caused it.

Editing source clears execution tracking until the next successful **Compile**.
Saving alone does not restore it. Changing compiled instruction or operand cells,
including changes made by the running program, also clears tracking. Data edits
and changes to `i` keep tracking while the compiled code remains intact. **Load**,
**Reload**, and **Reset** clear it. An address without a mapped instruction, such
as an operand cell or an implicit halt beyond the program, has no source marker.

### Inspect and edit memory

Address `0` contains the instruction pointer and is highlighted orange. The next
instruction is green, and its argument cells are pale green. The CPU area shows
the decoded instruction and executed-step counter.

Double-click a value to edit it and press Enter to commit. Clicking an empty
value cell adds the next address, initially zero, and starts editing. Data and
code share memory, so changing instruction cells or address `0` changes execution.
Stop before editing, loading, or saving. Large **Step n** batches run synchronously
and can delay the interface.

**Open** accepts source files. **Load** accepts one nonnegative decimal integer
per memory cell, starting with address `0`. Write these integers without
indentation: the current loader reads leading integers and skips lines that do
not start with a parseable integer. Skipped lines do not reserve addresses. A
snapshot saved at `HALT` remains halted when loaded again.

## License

[MIT](LICENSE), copyright Andreas Karrenbauer.

# Programming: machine simulator and compiler

A teaching project for exploring how structured programs become instructions on a
small random-access machine. It includes a compiler for variables, arrays,
conditions, loops, and macros; a Java execution model; and a JavaFX memory viewer
and debugger.

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

Save this complete program as a source file of your own, then click **Compile** in
the simulator and select that file. No example file from the working tree is
needed.

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
and address `2` ends at `7`. Use **Recompile** to restore the initial program state.
A source statement can compile to several machine instructions.

## Use the simulator

| Control | Behavior |
| --- | --- |
| **Compile** | Choose a source program, compile it, replace memory, and reset the step counter. |
| **Recompile** | Compile the last chosen source again. Use **Compile** first. |
| **Load** | Load a raw integer memory image, replacing memory and resetting the counter. |
| **Reload** | Reread the last memory file chosen with **Load**, independently of the source chosen with **Compile**. |
| **Save** | Save the current memory snapshot, including the instruction pointer. It does not save source or change the **Reload** target. |
| **Reset** | Clear memory and the step counter. Use **Recompile** or **Reload** to restart the original program. |
| **Run** / **Stop** | Execute until `HALT` or pause while preserving the current state. |
| **Step** | Execute one instruction; do nothing at `HALT`. |
| **Step n** | Execute at most a nonnegative number of instructions, stopping earlier at `HALT`. |
| **T** / **Home** | Scroll to address `0`. |

Address `0` contains the instruction pointer and is highlighted orange. The next
instruction is green, and its argument cells are pale green. The CPU area shows
the decoded instruction and executed-step counter.

Double-click a value to edit it and press Enter to commit. Clicking an empty
value cell adds the next address, initially zero, and starts editing. Data and
code share memory, so changing instruction cells or address `0` changes execution.
Stop before editing, loading, or saving. Large **Step n** batches run synchronously
and can delay the interface.

**Compile** accepts source text. **Load** accepts one nonnegative decimal integer
per memory cell, starting with address `0`. Write these integers without
indentation: the current loader reads leading integers and skips lines that do
not start with a parseable integer. Skipped lines do not reserve addresses. A
snapshot saved at `HALT` remains halted when loaded again.

## License

[MIT](LICENSE), copyright Andreas Karrenbauer.

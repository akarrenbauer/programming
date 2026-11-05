# Distributing the Language Game for Linux testing

[README](../README.md) · [Game guide](language-game.md) · [Development](development.md)

The testing distribution is one JAR containing the game, compiler, debugger,
English and German task text, and the JavaFX classes and native libraries.
Testers need **Java 17 or newer** and a **Linux x86-64 graphical desktop with
GTK 3**. They do not need BlueJ, Maven, a separate JavaFX SDK, or the source tree.

## Run a received JAR

Save `language-game-linux-x86_64.jar` in a directory of your choice. Open a
terminal in that directory and run:

```sh
java -version
java -jar language-game-linux-x86_64.jar
```

The Java version must be at least 17. Use **Task language** to select
**English** or **Deutsch**; the first selection follows the system display
language. Tasks, hints, and status messages follow this selection; control
labels, grammar, and code remain in English.

In World 1, an editable flowchart appears next to the source, including arrows
for explicit `JNZ` jumps. Its toolbox has only a rectangle and a diamond;
move jump heads to choose a backward loop or a forward branch. A `do/while`
loop displays its body and trailing decision as in its jump translation.
Once `do/while`, `if/else`, and `while` have been earned,
World 2 starts with Nassi–Shneiderman diagrams and offers **Nassi–Shneiderman**
or **Flowchart** in the diagram selector. Explicit `JNZ` remains suspended in
both views; the later-world flowchart uses structured insertion tools and fixed
loop destinations. Both diagram types edit the same source draft and follow the
tools available in the selected lesson. **Undo** and **Redo** above the source
also reverse and restore applied diagram edits. With the source focused,
**Ctrl+Z** undoes and **Ctrl+Shift+Z** redoes on Linux.
If a draft becomes invalid, its last valid diagram stays
visible with damaged structures marked red until the code is corrected.

Progress and drafts save in `~/.language-game/progress.properties`, independently
of the JAR's location. Replacing the JAR keeps saved progress. For a separate
testing profile, run:

```sh
java -Dlanguage.game.progress="$PWD/testing-progress.properties" \
  -jar language-game-linux-x86_64.jar
```

Use the [game guide](language-game.md) for checking solutions, revealing hints,
and stepping through a draft with **Debug**. When reporting a problem, include
the level name, the source program, test inputs, the Java version, and any
terminal error text.

## Build the distribution

The build machine needs a JDK 17 or newer, Maven, and internet access for the
first dependency download. From the repository root:

```sh
mvn clean package
```

Share **`target/language-game-linux-x86_64.jar`**. The file whose name starts
with `original-` is Maven's intermediate JAR and does not include JavaFX.
Generated files under `target/` are ignored by Git.

[pom.xml](../pom.xml) compiles the Java sources into a fresh build directory,
then bundles JavaFX 17.0.21 for Linux x86-64. Adjacent BlueJ `.class` files,
test classes, local progress, and example files are not distribution inputs.
The JAR includes the project's MIT license and JavaFX's license notices.

For Linux ARM64, build a separate artifact:

```sh
mvn -Djavafx.platform=linux-aarch64 \
  -Ddist.name=language-game-linux-aarch64 clean package
```

That produces `target/language-game-linux-aarch64.jar`. It must run on an ARM64
machine; the default artifact targets x86-64. The automated graphical checks
for this distribution run on Linux x86-64.

The packaging follows the OpenJFX documentation for a
[non-modular executable JAR](https://openjfx.io/openjfx-docs/modular.html).
[LanguageGameLauncher](../machine/LanguageGameLauncher.java) supplies the plain
Java entry point needed by that packaging method; BlueJ can still launch
`LanguageGame` directly.

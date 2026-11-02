package machine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.IntFunction;

import static machine.GameFeature.*;

/** The fixed Language Game curriculum and the helpers earned by solving it. */
public final class GameCatalog {
    private static final String FOUNDATIONS = GameWorld.MACHINE_FOUNDATIONS.title();
    private static final String STRUCTURED = GameWorld.STRUCTURED_PROGRAMMING.title();
    private static final String LANGUAGE = GameWorld.GROWING_LANGUAGE.title();
    private static final String NATURALS = GameWorld.NATURAL_NUMBERS.title();
    private static final String WORDS = GameWorld.SIGNED_WORDS.title();
    private static final List<GameLevel> LEVELS = buildLevels();

    private GameCatalog() { }

    public static List<GameLevel> levels() {
        return LEVELS;
    }

    /** A virtual import: opening it never reads or writes the filesystem. */
    public static Map<String, String> library(Set<String> completed) {
        StringBuilder source = new StringBuilder();
        for (GameLevel level : LEVELS) {
            if (completed.contains(level.id())) {
                level.rewards().values().forEach(helper -> source.append(helper).append('\n'));
            }
        }
        String earned = source.toString();
        return Map.of("toolbox", earned, "macros.a26", earned);
    }

    private static List<GameLevel> buildLevels() {
        List<GameLevel> levels = new ArrayList<>();
        levels.add(level("first-steps", FOUNDATIONS, "One step forward",
                "Increase x by one, then halt. The checker tries several starting values.",
                "The machine stores numbers in cells S[0], S[1], and so on. S[0] = i means that the next instruction starts at address i. Named variables let the compiler choose the addresses; write A26 source here, not a raw memory listing. INC adds one; DEC stops at zero. HALT ends execution, and NOP does nothing. Your first tools are commands, labels, and JNZ. The flowchart beside your code shows commands as boxes and decisions as diamonds, connected by execution arrows. Edit either view to update the other. Finishing earns blocks and empty statements.",
                starter("var x = 0;\n", "Change x using a machine command."),
                hints("Read x as a changing input, not a fixed answer.", "INC changes its named cell by one.", "Put the increment before HALT; instructions after HALT never run."),
                Set.of(BLOCKS, EMPTY_STATEMENTS), Set.of(), Map.of(),
                unary("x", new int[] {0, 1, 8}, x -> values("x", x + 1))));

        levels.add(level("clear-block", FOUNDATIONS, "Put the clearing loop in a block",
                "Keep the starter's instructions and their order. Add one empty statement ; after INC dummy, then braces around only DEC x, immediately after do:. Leave JNZ x do after the closing brace. Finish with dummy increased once and x = 0.",
                "The starter already clears x. Group the loop body as do: { DEC x; }, then test whether to repeat with JNZ x do; after the closing brace. This separates the body from its trailing condition. Keep INC dummy before the block, followed by a standalone ;. This empty statement has no effect and is separate from the semicolon ending INC dummy. Here do is an ordinary identifier; the next language version reserves it as a loop keyword. After this level, do { DEC x; } while (x != 0); expresses the same loop without an explicit label or jump.",
                "var dummy = 0;\nvar x = 0;\nbegin:\nINC dummy;\ndo: DEC x;\nJNZ x do;\nHALT;\n",
                hints("Keep INC dummy first, then add ; on its own line. Both setup and the empty statement stay outside the block.",
                        "Put the opening brace immediately after do:, before DEC x. Put the closing brace before JNZ x do;.",
                        "The block contains only DEC x;. JNZ x do; and HALT; follow the closing brace. Starting with x = 0 is safe because DEC leaves zero unchanged."),
                Set.of(DO_WHILE), Set.of(BLOCKS, EMPTY_STATEMENTS), Map.of(),
                pairs("dummy", "x", new int[][] {{0, 0}, {4, 0}, {0, 1}, {4, 1}, {0, 7}, {4, 7}},
                        (dummy, x) -> values("dummy", dummy + 1, "x", 0))));

        levels.add(level("clear-do-while", FOUNDATIONS, "Replace the jump with do/while",
                "Rewrite the labeled clearing block and its trailing jump as do { DEC x; } while (x != 0);. Keep INC dummy and its following empty statement before the loop, and HALT after it.",
                "The starter is your working block solution in the earlier language version, where do is an identifier. The new version reserves do as a loop keyword. Debug can run either form. Replace do: with do, and replace JNZ x do; with while (x != 0);. The body still runs before its condition is tested, so it clears x even when x starts at zero. This is a change in notation; dummy must still increase exactly once.",
                "var dummy = 0;\nvar x = 0;\nbegin:\nINC dummy;\n;\ndo: {\nDEC x;\n}\nJNZ x do;\nHALT;\n",
                hints("Keep the block body unchanged: it contains only DEC x;.",
                        "Remove the colon after do. The structured loop no longer needs an explicit label or jump.",
                        "After the closing brace, write while (x != 0);. INC dummy; and the following standalone ; stay before do, and HALT; stays after the trailing semicolon."),
                Set.of(), Set.of(DO_WHILE), Map.of(),
                pairs("dummy", "x", new int[][] {{0, 0}, {4, 0}, {0, 1}, {4, 1}, {0, 7}, {4, 7}},
                        (dummy, x) -> values("dummy", dummy + 1, "x", 0))));

        levels.add(level("mandatory-lap", FOUNDATIONS, "Run at least once",
                "Use a do/while loop to consume n and set rounds to max(1, original n). Even n = 0 must do one round.",
                "You earned do/while by grouping a jump-based clearing loop. Its body runs before its condition is tested. Clearing zero is safe, but also incrementing another cell still changes that cell once: a do/while transfer does not add zero correctly unless its first iteration is guarded.",
                starter("var n = 0;\nvar rounds = 0;\n", "Put one round in a do/while body."),
                hints("An initially zero input still needs one increment of rounds.", "Put INC rounds and DEC n in the do body. DEC on zero is safe because subtraction saturates at zero.", "After the body's closing brace, test while (n != 0); so that another round runs only when n is still nonzero."),
                Set.of(), Set.of(DO_WHILE), Map.of(),
                unary("n", new int[] {0, 1, 2, 6}, n -> values("n", 0, "rounds", Math.max(1, n)))));

        levels.add(level("zero-test", FOUNDATIONS, "Recognize zero",
                "Preserve n. Set zero to 1 exactly when n is zero; otherwise leave zero at 0.",
                "A forward JNZ n beyond skips a block when n is nonzero, so that block runs exactly when n == 0. This is the blueprint for if (n == 0). To invert the test, start a flag at zero, increment it when n == 0, then run the other work only when the flag == 0. Solve this decision using JNZ to earn both zero-test forms of if.",
                starter("var n = 0;\nvar zero = 0;\n", "Use a jump to skip work on the nonzero path."),
                hints("You do not need to count down n.", "JNZ can jump straight to a labeled HALT.", "Only the path that does not jump should increment zero."),
                Set.of(IF), Set.of(), Map.of(),
                unary("n", new int[] {0, 1, 7}, n -> values("n", n, "zero", n == 0 ? 1 : 0))));

        levels.add(level("two-paths", FOUNDATIONS, "Choose between two results",
                "Use an if statement. Preserve n and produce result = 2 for n = 0, and result = 1 for nonzero n.",
                "An if block can add work on one path. Combine common work and conditional work to express both outcomes before earning else. Two independent if tests are not always alternatives: if (f == 0) { INC f; } followed by if (f != 0) { DEC f; } executes both bodies when f starts at zero. An if/else chooses one branch from a single test.",
                starter("var n = 0;\nvar result = 0;\n", "Find the work shared by both outcomes."),
                hints("Both results include one increment.", "The zero case needs one extra increment.", "Perform the shared increment and use if (n == 0) for the extra one."),
                Set.of(ELSE), Set.of(IF), Map.of(),
                unary("n", new int[] {0, 1, 8}, n -> values("n", n, "result", n == 0 ? 2 : 1))));

        levels.add(level("else-practice", FOUNDATIONS, "Make the alternatives explicit",
                "Rewrite the working starter as one if/else testing n against zero, followed by HALT. Preserve n; result must be 1 when n is zero and 2 otherwise.",
                "The starter shares one increment between both outcomes. Your new else branch lets each path state its complete result. Move the increments into the two branches, keeping the same behavior for zero and nonzero inputs. The main program should contain just that if/else and HALT.",
                "var n = 0;\nvar result = 0;\nbegin:\nINC result;\nif (n != 0) {\nINC result;\n}\nHALT;\n",
                hints("The starter already gives the right answers, but it does not use else.",
                        "Test n once with if (n == 0), followed by an else block.",
                        "Put one increment in the zero branch and two in the else branch; remove the shared increment before the if."),
                Set.of(), Set.of(IF, ELSE), Map.of(),
                unary("n", new int[] {0, 1, 8}, n -> values("n", n, "result", n == 0 ? 1 : 2))));

        levels.add(level("countdown", FOUNDATIONS, "Build while from if and do/while",
                "Consume n down to zero and leave its original value in count.",
                "A do/while body runs at least once. Guard it with if (n != 0), then repeat while (n != 0), to allow zero iterations too. When the outer if and the trailing while use the same condition, they form the blueprint for a while loop. Use both constructs here to earn that shorter form.",
                starter("var n = 0;\nvar count = 0;\n", "Check for zero before entering your repeated work."),
                hints("Each removed unit of n must become one unit of count.", "Use if (n != 0) to guard the first iteration: n may start at zero.", "Inside the if block, use do { DEC n; INC count; } while (n != 0);. The guard and loop test must agree."),
                Set.of(WHILE), Set.of(IF, DO_WHILE), Map.of(),
                unary("n", new int[] {0, 1, 2, 9}, n -> values("n", 0, "count", n))));

        levels.add(level("while-practice", STRUCTURED, "Replace if and do/while with while",
                "Rewrite the working starter as while (n != 0) { DEC n; INC count; }, followed by HALT. Keep DEC n before INC count inside the loop. Remove the outer if and the do/while form; consume n to zero and leave its original value in count.",
                "Welcome to Structured programming. You have earned do/while, if/else, and while. Explicit JNZ is now suspended: use these structures and let the compiler produce the jumps. The Nassi–Shneiderman diagram beside your code shows sequences as stacked boxes, decisions as branches, and loops enclosing their bodies. Edit either view to update the other. The starter is your working if-plus-do/while countdown. Its guard and trailing test are identical, so replace both with one while (n != 0) before the unchanged body. A while loop checks before every iteration, including the first: n = 0 must leave count = 0.",
                "var n = 0;\nvar count = 0;\nbegin:\nif (n != 0) {\n  do {\n    DEC n;\n    INC count;\n  } while (n != 0);\n}\nHALT;\n",
                hints("The starter already produces the correct results. This exercise asks you to express the same algorithm using your new while loop.",
                        "Replace if (n != 0) with while (n != 0), then remove do { and the matching } while (n != 0);.",
                        "After begin:, keep exactly while (n != 0) { DEC n; INC count; } and then HALT;. The decrement and increment remain in that order inside the loop."),
                Set.of(), Set.of(WHILE), Map.of(),
                unary("n", new int[] {0, 1, 2, 9}, n -> values("n", 0, "count", n))));

        levels.add(level("equals-three", STRUCTURED, "Build an equality test",
                "Use a while loop to count and restore n. Set equal to 1 exactly when n is 3; all other inputs need equal = 0.",
                "Only comparisons with zero are available so far. Count against a three-unit budget and remember whether the input exceeded it. This earns comparisons with any natural-number literal.",
                starter("var n = 0;\nvar equal = 0;\nvar saved = 0;\nvar work = 0;\nvar budget = 3;\nvar excess = 0;\n", "Compare by counting, then restore n."),
                hints("Move n into both saved and work, then move saved back into n.", "For each unit of work, decrease budget if possible; otherwise count excess.", "Equality requires both budget and excess to be zero. Testing only budget would also accept inputs above three."),
                Set.of(COMPARISONS), Set.of(WHILE), Map.of(),
                unary("n", new int[] {0, 1, 2, 3, 4, 7}, n -> values("n", n, "equal", n == 3 ? 1 : 0))));

        levels.add(level("comparison-practice", STRUCTURED, "Compare with three directly",
                "Replace the counting algorithm with one if comparing n to the literal 3, followed by HALT. Preserve n and set equal to 1 exactly when n is 3; otherwise equal must be 0.",
                "You constructed equality from zero tests and counting. The newly earned comparisons express that work directly. Keep the same input/output contract while replacing the old implementation with a condition such as n == 3. The main program should contain just that if (with an optional else) and HALT; unused temporary declarations may be removed.",
                "var n = 0;\nvar equal = 0;\nvar saved = 0;\nvar work = 0;\nvar budget = 3;\nvar excess = 0;\nbegin:\n"
                        + "while (n != 0) {\nDEC n;\nINC saved;\nINC work;\n}\n"
                        + "while (saved != 0) {\nDEC saved;\nINC n;\n}\n"
                        + "while (work != 0) {\nif (budget == 0) {\nINC excess;\n} else {\nDEC budget;\n}\nDEC work;\n}\n"
                        + "if (budget == 0) {\nif (excess == 0) {\nINC equal;\n}\n}\nHALT;\n",
                hints("The starter already works; it fails this challenge because every comparison still uses zero.",
                        "Replace the loops and nested zero tests with one if statement comparing n with 3.",
                        "Start equal at zero and increment it inside if (n == 3). The comparison leaves n unchanged."),
                Set.of(), Set.of(COMPARISONS), Map.of(),
                unary("n", new int[] {0, 1, 2, 3, 4, 7}, n -> values("n", n, "equal", n == 3 ? 1 : 0))));

        levels.add(level("preserve-copy", STRUCTURED, "Copy without losing the original",
                "Leave n unchanged and put the same value in copy.",
                "A transfer loop destroys its source. Count each removed unit into a second temporary cell, then restore the source. This earns variable initializers such as var temporary = n;.",
                starter("var n = 0;\nvar copy = 0;\nvar saved = 0;\n", "Use saved to restore every unit removed from n."),
                hints("Each unit removed from n can increment both copy and saved.", "After that transfer, copy is correct but n is zero.", "Transfer saved back into n, leaving copy untouched."),
                Set.of(COPYING), Set.of(), rewards("clear", clearHelper(), "copyTo", copyHelper(),
                        "copy", "// copy(a, out) preserves a and writes its original value to out; copy(a, a) is safe.\nvoid copy(a, out) {\ncopyTo(a, out);\n}\n"),
                unary("n", new int[] {0, 1, 7, 13}, n -> values("n", n, "copy", n))));

        levels.add(level("reset-scratch", STRUCTURED, "Reset reusable scratch space",
                "Use a variable initializer to copy n into result. Finish with n = 0 and scratch = 0, regardless of scratch's starting value.",
                "Your new variable initializers can copy a value: declare result = n before begin, then clear n and scratch with loops. The copied result survives changes to n. Runtime var declarations, earned here, will let you initialize temporary cells where their work begins.",
                starter("var n = 0;\nvar scratch = 0;\nvar result = 0;\n", "Copy n in result's global declaration, then clear n and scratch."),
                hints("Include the case where n is zero but scratch is not.", "A while loop of DEC scratch clears its old value.", "Change the global declaration to var result = n; before begin. Then clear n without incrementing result again."),
                Set.of(LOCAL_VARIABLES), Set.of(COPYING), Map.of(),
                pairs("n", "scratch", new int[][] {{0, 0}, {0, 8}, {1, 3}, {5, 0}, {4, 7}},
                        (n, scratch) -> values("n", 0, "scratch", 0, "result", n))));

        levels.add(level("three-cells", LANGUAGE, "Arrange three separate cells",
                "Use runtime variable declarations. Preserve a, b, and c; put b in first, c in second, and a in third.",
                "Several named cells can represent a small collection. Copy through runtime temporaries to rotate their values. The reward is array declarations and literal indices.",
                starter("var a = 0;\nvar b = 0;\nvar c = 0;\nvar first = 0;\nvar second = 0;\nvar third = 0;\n", "Transfer copies of b, c, and a into the three outputs."),
                hints("Do not consume the input cells directly.", "A runtime declaration can initialize a temporary from an input.", "Use one temporary per transfer, counting into first from b, second from c, and third from a."),
                Set.of(ARRAYS), Set.of(LOCAL_VARIABLES), Map.of(),
                List.of(threeCells(0, 1, 2), threeCells(4, 0, 3), threeCells(2, 2, 0))));

        levels.add(level("array-practice", LANGUAGE, "Three array slots",
                "Preserve n. Declare cells with three elements and fill them with n, n+1, and n+2.",
                "An array declaration initializes every element. Indices are literal numbers: cells[0], cells[1], cells[2]. Initialize all three cells from n, then use indexed INC commands to adjust the last two.",
                starter("var n = 0;\nvar cells[3] = 0;\n", "Fill all three indexed output cells."),
                hints("Use the indices 0, 1, and 2.", "Change the array declaration to var cells[3] = n; so every element starts with a copy of n.", "Increment element 1 once and element 2 twice after copying."),
                Set.of(), Set.of(ARRAYS), Map.of(),
                unary("n", new int[] {0, 1, 5, 9}, n -> values("n", n, "cells[0]", n, "cells[1]", n + 1, "cells[2]", n + 2))));

        levels.add(level("repeated-work", LANGUAGE, "Spot a reusable operation",
                "Preserve a and b. Leave left = 2*a and right = 2*b.",
                "The same algorithm can run on different cells. Write the two transfers now; completing them earns macros so that later programs can name and reuse that work.",
                starter("var a = 0;\nvar b = 0;\nvar left = 0;\nvar right = 0;\n", "Each unit from a copied input contributes two output increments."),
                hints("Use copies of a and b so the inputs survive.", "Increment an output twice for each unit removed from its temporary.", "Repeat the same loop shape once for left and once for right."),
                Set.of(MACROS), Set.of(), rewards("bump", "void bump(out) {\nINC out;\n}\n", "twiceTo", twiceHelper()),
                pairs("a", "b", new int[][] {{0, 0}, {1, 2}, {3, 0}, {0, 4}, {5, 2}},
                        (a, b) -> values("a", a, "b", b, "left", 2 * a, "right", 2 * b))));

        levels.add(level("macro-practice", LANGUAGE, "Write and call your own macro",
                "Preserve n and produce result = n + 2. Include a macro definition in your source.",
                "A macro expands into instructions at each call. Its parameters name the cells it works on. Define a short two-increment helper and call it after copying n.",
                starter("var n = 0;\nvar result = 0;\n", "Add your own macro above the global declarations."),
                hints("Your helper needs one named output parameter.", "Its body can contain two INC commands.", "Use var result = n; in the globals, then call your own helper with result. Imports are still locked."),
                Set.of(), Set.of(MACROS), Map.of(),
                unary("n", new int[] {0, 2, 7}, n -> values("n", n, "result", n + 2))));

        levels.add(level("literal-dispatch", LANGUAGE, "Choose a macro's behavior",
                "Preserve mode, which is 0 or 1. Use a macro to produce result = mode + 1.",
                "A named macro parameter accepts a cell or a literal. Start with a normal macro and a decision inside it. The reward is literal parameters, such as void step(0, out), that select a definition by the written argument.",
                starter("void choose(mode, out) {\n// Decide which increments belong here.\n}\nvar mode = 0;\nvar result = 0;\nbegin:\nchoose(mode, result);\nHALT;\n", ""),
                hints("Use a named parameter for mode in this level.", "The zero path needs one increment and the other path needs two.", "An if/else inside the macro can choose those paths without changing mode."),
                Set.of(LITERAL_PARAMETERS), Set.of(MACROS), rewards("step", stepHelpers()),
                unary("mode", new int[] {0, 1}, mode -> values("mode", mode, "result", mode + 1))));

        levels.add(level("specialization-practice", LANGUAGE, "Match literal arguments",
                "Preserve n. Produce first = n+1 and second = n+2 using literal-parameter macro definitions.",
                "A literal parameter matches the spelling at a call site. It does not inspect a cell at runtime. Define two specializations and call them with the written literals 0 and 1.",
                starter("var n = 0;\nvar first = 0;\nvar second = 0;\n", "Define your two literal specializations above the globals."),
                hints("Use different literal parameters in the two definitions.", "Initialize first and second from n in their global declarations. Define both specializations in this source; imports are still locked.", "Call the one-increment specialization with literal 0 and the two-increment specialization with literal 1."),
                Set.of(), Set.of(LITERAL_PARAMETERS), Map.of(),
                unary("n", new int[] {0, 1, 6}, n -> values("n", n, "first", n + 1, "second", n + 2))));

        levels.add(level("compose-helpers", LANGUAGE, "Compose a small library",
                "Preserve n. Use macros to produce result = n + 3.",
                "Helpers can call earlier helpers. Copy earned clear, copyTo, and bump definitions from your toolbox into the top of this source, then compose them. Completion unlocks virtual imports, including import \"toolbox\";.",
                starter("var n = 0;\nvar result = 0;\n", "Define or paste helpers above the globals, then compose them here."),
                hints("The result starts as a copy of n.", "One helper can call bump three times; put each call on its own line.", "Define helpers before their callers, and put macro braces on separate lines from their bodies."),
                Set.of(IMPORTS), Set.of(MACROS), rewards("advanceThree", "void advanceThree(out) {\nbump(out);\nbump(out);\nbump(out);\n}\n"),
                unary("n", new int[] {0, 1, 6, 11}, n -> values("n", n, "result", n + 3))));

        levels.add(level("import-practice", LANGUAGE, "Import the earned toolbox",
                "Preserve n and produce result = 2*n + 1 using an import.",
                "The game provides one virtual library, toolbox, containing only earned helpers. It reads no disk files. Put its import before declarations and combine twiceTo with bump.",
                toolboxStarter("var n = 0;\nvar result = 0;\n", "Compose helpers from the virtual toolbox."),
                hints("Keep import \"toolbox\"; on its own line.", "twiceTo(n, result) produces the doubled value without changing n.", "Call bump(result) after the doubling helper, then halt."),
                Set.of(), Set.of(IMPORTS), Map.of(),
                unary("n", new int[] {0, 1, 4, 9}, n -> values("n", n, "result", 2 * n + 1))));

        levels.addAll(GameBookLevels.transferLevels());

        levels.add(level("preserving-add", NATURALS, "Add and preserve both inputs",
                "Produce result = a+b while preserving a and b.",
                "Copy one input into the result and transfer a temporary copy of the other. Your verified solution earns addTo(a, b, out). Helpers clear their output before filling it.",
                toolboxStarter("var a = 0;\nvar b = 0;\nvar result = 0;\n", "Add without consuming either input."),
                hints("Start result with a copy of a.", "Initialize a temporary from b so the loop can consume that copy.", "For each unit of the temporary, increment result and decrement the temporary."),
                Set.of(), Set.of(), rewards("addTo", addHelper(),
                        "add", binaryAlias("add", "addTo", "a+b")),
                pairs("a", "b", new int[][] {{0, 0}, {0, 7}, {5, 0}, {3, 4}, {8, 9}},
                        (a, b) -> values("a", a, "b", b, "result", a + b))));

        levels.add(level("saturating-subtract", NATURALS, "Subtract in natural numbers",
                "Preserve a and b. Produce result = max(0, a-b).",
                "Machine cells never become negative. Starting from a copy of a, one DEC per copied unit of b gives saturating subtraction. Completion earns subtractTo(a, b, out).",
                toolboxStarter("var a = 0;\nvar b = 0;\nvar result = 0;\n", "Use DEC's behavior at zero."),
                hints("Begin with result equal to a.", "Consume a temporary initialized from b.", "Decrement result once per temporary unit, even if result is already zero."),
                Set.of(), Set.of(), rewards("subtractTo", subtractHelper(),
                        "sub", binaryAlias("sub", "subtractTo", "max(0, a-b)")),
                pairs("a", "b", new int[][] {{0, 0}, {0, 5}, {7, 0}, {5, 5}, {3, 7}, {9, 4}},
                        (a, b) -> values("a", a, "b", b, "result", Math.max(0, a - b)))));

        levels.add(level("multiply", NATURALS, "Multiply by repeated addition",
                "Preserve a and b. Produce result = a*b.",
                "Each of b repetitions contributes a units. Keep loop counters separate from the inputs, and include zero repetitions. Completion earns multiplyTo(a, b, out).",
                toolboxStarter("var a = 0;\nvar b = 0;\nvar result = 0;\n", "Repeat an addition with a copied counter."),
                hints("A zero input must lead to a zero result.", "Use a temporary initialized from b as the outer loop counter.", "Add a to result in each iteration; earned addTo accepts result as its output even when it is also an input."),
                Set.of(), Set.of(), rewards("multiplyTo", multiplyHelper(),
                        "mul", binaryAlias("mul", "multiplyTo", "a*b")),
                pairs("a", "b", new int[][] {{0, 0}, {0, 6}, {7, 0}, {1, 8}, {3, 4}, {7, 3}},
                        (a, b) -> values("a", a, "b", b, "result", a * b))));

        levels.add(level("divmod", NATURALS, "Count complete groups and leftovers",
                "Preserve numerator and divisor. For divisor > 0, produce quotient and remainder with numerator = quotient*divisor + remainder and remainder < divisor.",
                "Consume a copied numerator one unit at a time. Complete a group whenever a divisor-sized countdown reaches zero. The test contract excludes a zero divisor. Completion earns divideTo(n, d, quotient, remainder).",
                toolboxStarter("var numerator = 0;\nvar divisor = 1;\nvar quotient = 0;\nvar remainder = 0;\n", "Count full groups and reset their remaining capacity."),
                hints("A numerator below divisor has quotient zero and keeps all units as remainder.", "Keep a countdown initialized from divisor; each unit also increments remainder.", "When the countdown reaches zero, increment quotient, clear remainder, and refill the countdown from divisor."),
                Set.of(), Set.of(), rewards("divideTo", divideHelper(),
                        "div", divisionAlias("div", "quotient"), "mod", divisionAlias("mod", "remainder")),
                pairs("numerator", "divisor", new int[][] {{0, 1}, {1, 1}, {1, 3}, {7, 2}, {8, 4}, {23, 5}, {31, 7}},
                        (n, d) -> values("numerator", n, "divisor", d, "quotient", n / d, "remainder", n % d))));

        levels.add(level("gcd", NATURALS, "Euclid's algorithm",
                "Preserve a and b. Produce their greatest common divisor; define gcd(0,0) = 0.",
                "For nonzero y, gcd(x,y) equals gcd(y,x mod y). Earned divideTo supplies the remainder. Always test y before division. Completion earns gcdTo(a, b, out).",
                toolboxStarter("var a = 0;\nvar b = 0;\nvar result = 0;\n", "Work on copies and repeat the remainder step."),
                hints("Start with copies x = a and y = b.", "While y is nonzero, divide x by y into temporary quotient and remainder cells.", "Replace x with the old y and y with the remainder. When y becomes zero, x is the answer."),
                Set.of(), Set.of(), rewards("gcdTo", gcdHelper(),
                        "gcd", binaryAlias("gcd", "gcdTo", "gcd(a,b), with gcd(0,0)=0")),
                pairs("a", "b", new int[][] {{0, 0}, {0, 9}, {8, 0}, {6, 9}, {12, 18}, {7, 5}, {21, 14}},
                        (a, b) -> values("a", a, "b", b, "result", gcd(a, b)))));

        levels.add(level("normalize-four", WORDS, "Keep only four bits",
                "Preserve n and produce word = n mod 16, an encoded four-bit word from 0 through 15.",
                "The machine still stores natural numbers. Fixed width is a rule your program implements: after 15, the next increment wraps to 0. Completion earns wrap4(value, out).",
                toolboxStarter("var n = 0;\nvar word = 0;\n", "Wrap the output after each group of sixteen units."),
                hints("The outputs for 0 and 16 are both zero.", "You may use divideTo and keep its remainder when dividing by 16.", "Alternatively, count into word and clear it whenever it reaches 16."),
                Set.of(), Set.of(), rewards("wrap4", wrapHelper("wrap4", 16)),
                unary("n", new int[] {0, 1, 15, 16, 17, 31, 32, 47, 255}, n -> values("n", n, "word", n % 16))));

        levels.add(level("decompose-four", WORDS, "Read the four bits",
                "Preserve word (0..15). Store its bits in bits[0] through bits[3], least significant bit first.",
                "Dividing by two separates a low bit (remainder) from the remaining higher bits (quotient). Four repetitions recover the whole word. Completion earns bits4(word, bits).",
                toolboxStarter("var word = 0;\nvar bits[4] = 0;\n", "Store one remainder per literal array index."),
                hints("For word 10, the output is [0,1,0,1].", "The first bit is word mod 2.", "Divide a working copy by 2 four times, storing each remainder and keeping the quotient for the next step."),
                Set.of(), Set.of(ARRAYS), rewards("bits4", bitsHelper()),
                unary("word", range(16), n -> values("word", n, "bits[0]", n & 1, "bits[1]", (n >> 1) & 1,
                        "bits[2]", (n >> 2) & 1, "bits[3]", (n >> 3) & 1))));

        levels.add(level("decode-four", WORDS, "Decode a signed word",
                "Preserve word (0..15). Produce negative = 1 for words 8..15, otherwise 0, and the signed value's nonnegative magnitude.",
                "Four-bit two's complement represents -8..7: 0..7 keep their value, while 8..15 represent word-16. Negative results are reported as a flag and a natural-number magnitude; the machine never stores a negative integer.",
                toolboxStarter("var word = 0;\nvar negative = 0;\nvar magnitude = 0;\n", "Distinguish the upper half of the encoded range."),
                hints("Word 8 means negative = 1 and magnitude = 8; word 15 means negative = 1 and magnitude = 1.", "A countdown from 8 reaches zero exactly when the input is at least 8.", "In the negative half, magnitude is 16-word; otherwise it is word."),
                Set.of(), Set.of(), rewards("decode4", decodeHelper("decode4", 16, 8)),
                unary("word", range(16), n -> values("word", n, "negative", n >= 8 ? 1 : 0, "magnitude", Math.abs(signed(n, 16))))));

        levels.add(level("encode-four", WORDS, "Encode sign and magnitude",
                "Preserve negative and magnitude. Encode them in word: positive magnitudes are 0..7, negative magnitudes are 0..8. Either sign with magnitude zero encodes as 0.",
                "Two's complement has one zero encoding. A negative value of magnitude m uses (16-m) mod 16. Completion earns encode4(negative, magnitude, out).",
                toolboxStarter("var negative = 0;\nvar magnitude = 0;\nvar word = 0;\n", "Handle zero and the most negative value as well as ordinary values."),
                hints("The encoding of negative magnitude 1 is 15.", "For a nonnegative value, just copy its magnitude.", "For a negative value, subtract its magnitude from 16 and normalize with wrap4."),
                Set.of(), Set.of(), rewards("encode4", encodeHelper()),
                pairs("negative", "magnitude", new int[][] {{0, 0}, {0, 1}, {0, 7}, {1, 0}, {1, 1}, {1, 7}, {1, 8}},
                        (sign, mag) -> values("negative", sign, "magnitude", mag, "word", sign == 0 ? mag : (16 - mag) % 16))));

        levels.add(level("not-four", WORDS, "Invert exactly four bits",
                "Preserve word (0..15) and produce result with all four bits inverted.",
                "Within four bits, bitwise NOT pairs 0000 with 1111 and 0101 with 1010. This is the natural-number operation 15-word, not machine integer negation. Completion earns not4(word, out).",
                toolboxStarter("var word = 0;\nvar result = 0;\n", "Use the all-ones four-bit mask."),
                hints("The outputs for 0 and 15 are 15 and 0.", "Every input/output pair adds to 15.", "Use earned subtractTo with 15 as the left input."),
                Set.of(), Set.of(), rewards("not4", "void not4(value, out) {\nsubtractTo(15, value, out);\n}\n"),
                unary("word", range(16), n -> values("word", n, "result", 15 - n))));

        levels.add(level("negate-four", WORDS, "Negate with fixed width",
                "Preserve word (0..15) and produce result = (-word) mod 16.",
                "Invert the bits and add one, then keep four bits. Zero remains zero. The word 8 (-8) maps to itself because +8 cannot be represented in signed four-bit form. Completion earns negate4(word, out).",
                toolboxStarter("var word = 0;\nvar result = 0;\n", "Normalize after complementing and incrementing."),
                hints("The pairs 1/15 and 7/9 are each other's negations.", "not4 gives the complement, but one increment is still needed.", "Apply wrap4 after that increment so that zero negates to zero."),
                Set.of(), Set.of(), rewards("negate4", "void negate4(value, out) {\nsubtractTo(16, value, out);\nwrap4(out, out);\n}\n"),
                unary("word", range(16), n -> values("word", n, "result", (16 - n) % 16))));

        levels.add(level("add-four", WORDS, "Add and detect signed overflow",
                "Preserve a and b (0..15). Produce word = (a+b) mod 16 and overflow = 1 exactly when their signed sum is outside -8..7.",
                "A carry beyond bit 3 is different from signed overflow. Signed overflow requires equal input signs and a different result sign. Completion earns add4(a, b, out, overflow).",
                toolboxStarter("var a = 0;\nvar b = 0;\nvar word = 0;\nvar overflow = 0;\n", "Compute the wrapped word, then compare sign flags."),
                hints("7+1 overflows; encoded -1 plus 1 wraps to zero without signed overflow.", "Use decode4 for the two input signs and the wrapped result sign.", "Flag overflow only for two nonnegative inputs producing a negative result, or two negative inputs producing a nonnegative result."),
                Set.of(), Set.of(), rewards("add4", signedAddHelper("add4", "wrap4", "decode4")),
                wordPairs(16, false, new int[][] {{0, 0}, {7, 0}, {7, 1}, {7, 7}, {8, 8}, {8, 15}, {15, 1}, {15, 15}, {8, 7}, {9, 14}, {3, 4}, {4, 4}, {1, 15}})));

        levels.add(level("subtract-four", WORDS, "Subtract signed words",
                "Preserve a and b (0..15). Produce word = (a-b) mod 16 and flag signed overflow outside -8..7.",
                "Subtract by adding a fixed-width negation, but determine overflow from the original inputs. The most negative input cannot be negated into a positive representable value. Completion earns subtract4(a, b, out, overflow).",
                toolboxStarter("var a = 0;\nvar b = 0;\nvar word = 0;\nvar overflow = 0;\n", "Keep the original signs while forming the wrapped difference."),
                hints("Encoded 0-8 represents an unrepresentable +8, while 8-8 is zero without overflow.", "Use negate4 for b and add4 for the word, but do not simply reuse that addition's overflow flag.", "Subtraction overflows when the original input signs differ and the result sign differs from a's sign."),
                Set.of(), Set.of(), rewards("subtract4", signedSubtractHelper()),
                wordPairs(16, true, new int[][] {{0, 0}, {0, 1}, {0, 8}, {8, 8}, {8, 1}, {7, 15}, {7, 8}, {15, 7}, {8, 15}, {3, 2}, {2, 3}, {15, 15}, {7, 7}})));

        levels.add(level("extend-four", WORDS, "Sign-extend from four to eight bits",
                "Preserve word (0..15). Produce wide (0..255) representing the same signed value in eight bits.",
                "Sign extension repeats the sign bit in the new high positions. Positive words stay unchanged. Negative words gain four leading ones, adding 240 to their natural encoding. Completion earns extend4(word, out).",
                toolboxStarter("var word = 0;\nvar wide = 0;\n", "Preserve the signed value, not just the unsigned pattern."),
                hints("Four-bit 15 (-1) must become eight-bit 255 (-1).", "Use decode4 to determine whether the sign bit is set.", "Copy the original word, adding 240 only on the negative path."),
                Set.of(), Set.of(), rewards("extend4", extendHelper()),
                unary("word", range(16), n -> values("word", n, "wide", n >= 8 ? n + 240 : n))));

        levels.add(level("transfer-eight", WORDS, "Transfer the method to eight bits",
                "Preserve a and b (0..255). Add modulo 256 into word, decode word into negative and magnitude, and flag signed overflow outside -128..127.",
                "Transfer the four-bit algorithms by changing the modulus to 256 and the sign threshold to 128. The same sign rule detects overflow. No negative machine values or bitwise instructions are needed.",
                toolboxStarter("var a = 0;\nvar b = 0;\nvar word = 0;\nvar negative = 0;\nvar magnitude = 0;\nvar overflow = 0;\n", "Adapt the fixed-width helpers above the global declarations."),
                hints("Eight-bit -128 is encoded as 128; -1 is encoded as 255.", "Adapt wrap4 and decode4 to modulus 256 and threshold 128.", "Wrap the sum, decode all three sign flags, and apply the same equal-input-sign/different-result-sign overflow rule."),
                Set.of(), Set.of(), rewards("wrap8", wrapHelper("wrap8", 256), "decode8", decodeHelper("decode8", 256, 128),
                        "add8", signedAddHelper("add8", "wrap8", "decode8")),
                wordPairs(256, false, new int[][] {{0, 0}, {127, 1}, {128, 255}, {255, 1}, {128, 128}, {127, 127},
                        {255, 255}, {200, 100}, {0, 128}, {1, 254}, {42, 17}, {240, 240}})));
        levels.addAll(GameBookLevels.bitLevels());
        return List.copyOf(levels);
    }

    private static GameLevel level(String id, String world, String title, String goal, String lesson,
            String starter, List<String> hints, Set<GameFeature> unlocks, Set<GameFeature> required,
            Map<String, String> rewards, List<GameLevel.TestCase> cases) {
        return new GameLevel(id, world, title, goal, lesson, starter, hints, unlocks, required, rewards, cases, 500_000);
    }

    private static String starter(String globals, String comment) {
        if (comment.isEmpty()) {
            return globals;
        }
        return globals + "begin:\n// " + comment + "\nHALT;\n";
    }

    private static String toolboxStarter(String globals, String comment) {
        return "import \"toolbox\";\n" + starter(globals, comment);
    }

    private static List<String> hints(String first, String second, String third) {
        return List.of(first, second, third);
    }

    private static Map<String, String> rewards(String... entries) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            String contract = helperContract(entries[index]);
            result.put(entries[index], "// Natural-number inputs are preserved unless reused as an allowed in-place output.\n"
                    + "// Cells: keep outputs separate from inputs and from each other, unless allowed below.\n"
                    + "// " + contract.replace("\n", "\n// ") + "\n" + entries[index + 1]);
        }
        return Collections.unmodifiableMap(result);
    }

    private static String helperContract(String name) {
        return switch (name) {
            case "clear" -> "clear(out): replace the natural-number output with zero.";
            case "copyTo" -> "copyTo(value, out): replace out with value.\nIn-place allowed: out may be value; its old value is copied first.";
            case "copy" -> "copy(a, out): book name for copyTo; replace out with the original value of a.\nIn-place allowed: copy(a, a) preserves a.";
            case "bump" -> "bump(out): add one to out.";
            case "twiceTo" -> "twiceTo(value, out): replace out with 2*value.\nIn-place allowed: out may be value.";
            case "step" -> "step(0, out) adds one; step(1, out) adds two. The selector must be a written literal, not a cell's value.";
            case "advanceThree" -> "advanceThree(out): add three to out.";
            case "addTo" -> "addTo(a, b, out): replace out with the natural-number sum a+b.\nIn-place allowed: out may be a or b; both input values are copied first.";
            case "add" -> "add(a, b, out): book name for addTo; replace out with the original a+b.\nIn-place allowed: out may be a or b.";
            case "subtractTo" -> "subtractTo(a, b, out): replace out with max(0, a-b).\nIn-place allowed: out may be a or b.";
            case "sub" -> "sub(a, b, out): book name for subtractTo; replace out with max(0, original a-b).\nIn-place allowed: out may be a or b.";
            case "multiplyTo" -> "multiplyTo(a, b, out): replace out with the natural-number product a*b.\nIn-place allowed: out may be a or b.";
            case "mul" -> "mul(a, b, out): book name for multiplyTo; replace out with the original a*b.\nIn-place allowed: out may be a or b.";
            case "divideTo" -> "divideTo(n, d, quotient, remainder): requires d > 0; returns floor(n/d) and n mod d.\nKeep both outputs distinct from each other and from inputs; the divisor is read repeatedly.";
            case "div" -> "div(a, b, out): requires b > 0; replace out with floor(original a/b).\nIn-place allowed: out may be a or b; both inputs are copied before division.";
            case "mod" -> "mod(a, b, out): requires b > 0; replace out with original a mod b.\nIn-place allowed: out may be a or b; both inputs are copied before division.";
            case "gcdTo" -> "gcdTo(a, b, out): replace out with gcd(a,b), including gcd(0,0)=0.\nIn-place allowed: out may be a or b.";
            case "gcd" -> "gcd(a, b, out): book name for gcdTo; replace out with gcd of the original inputs, including gcd(0,0)=0.\nIn-place allowed: out may be a or b.";
            case "wrap4" -> "wrap4(value, out): replace out with value mod 16 (0..15).\nIn-place allowed: out may be value.";
            case "bits4" -> "bits4(value, bits): requires value in 0..15 and an array of at least four cells.\nReplaces bits[0..3] with low-to-high bits, each 0 or 1; keep value separate from those output cells.";
            case "decode4" -> "decode4(value, negative, magnitude): requires value in 0..15; decodes signed -8..7.\nOutputs are a 0/1 negative flag and a natural magnitude (0..8); zero has flag 0. Use three distinct cells.";
            case "encode4" -> "encode4(negative, magnitude, out): negative must be 0 or 1; magnitude is 0..7 when positive, 0..8 when negative.\nReturns the encoding 0..15; either sign with magnitude 0 gives 0. Keep out separate from both inputs.";
            case "not4" -> "not4(value, out): requires value in 0..15; replace out with the four-bit complement 15-value.\nIn-place allowed: out may be value.";
            case "negate4" -> "negate4(value, out): requires value in 0..15; replace out with (-value) mod 16.\nZero stays 0 and the most negative encoding 8 stays 8. In-place allowed: out may be value.";
            case "add4" -> "add4(a, b, out, overflow): requires a,b in 0..15; out is (a+b) mod 16.\nOverflow is 1 exactly when the signed sum lies outside -8..7, otherwise 0. Keep outputs distinct from inputs and each other.";
            case "subtract4" -> "subtract4(a, b, out, overflow): requires a,b in 0..15; out is (a-b) mod 16.\nOverflow is 1 exactly when the signed difference lies outside -8..7, otherwise 0. Keep outputs distinct from inputs and each other.";
            case "extend4" -> "extend4(value, out): requires value in 0..15; out is its sign-extended eight-bit encoding (0..255).\nIn-place allowed: out may be value; the represented signed value stays unchanged.";
            case "wrap8" -> "wrap8(value, out): replace out with value mod 256 (0..255).\nIn-place allowed: out may be value.";
            case "decode8" -> "decode8(value, negative, magnitude): requires value in 0..255; decodes signed -128..127.\nOutputs are a 0/1 negative flag and a natural magnitude (0..128); zero has flag 0. Use three distinct cells.";
            case "add8" -> "add8(a, b, out, overflow): requires a,b in 0..255; out is (a+b) mod 256.\nOverflow is 1 exactly when the signed sum lies outside -128..127, otherwise 0. Keep outputs distinct from inputs and each other.";
            default -> throw new IllegalArgumentException("Missing toolbox contract for " + name);
        };
    }

    private static Map<String, Integer> values(Object... entries) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            result.put((String) entries[index], (Integer) entries[index + 1]);
        }
        return result;
    }

    private static List<GameLevel.TestCase> unary(String name, int[] inputs,
            IntFunction<Map<String, Integer>> expected) {
        List<GameLevel.TestCase> cases = new ArrayList<>();
        for (int input : inputs) {
            cases.add(new GameLevel.TestCase(values(name, input), expected.apply(input)));
        }
        return cases;
    }

    private static List<GameLevel.TestCase> pairs(String first, String second, int[][] inputs,
            BiFunction<Integer, Integer, Map<String, Integer>> expected) {
        List<GameLevel.TestCase> cases = new ArrayList<>();
        for (int[] input : inputs) {
            cases.add(new GameLevel.TestCase(values(first, input[0], second, input[1]), expected.apply(input[0], input[1])));
        }
        return cases;
    }

    private static GameLevel.TestCase threeCells(int a, int b, int c) {
        return new GameLevel.TestCase(values("a", a, "b", b, "c", c),
                values("a", a, "b", b, "c", c, "first", b, "second", c, "third", a));
    }

    private static int[] range(int length) {
        int[] range = new int[length];
        for (int index = 0; index < length; index++) {
            range[index] = index;
        }
        return range;
    }

    private static int signed(int word, int modulus) {
        return word < modulus / 2 ? word : word - modulus;
    }

    private static int gcd(int a, int b) {
        while (b != 0) {
            int remainder = a % b;
            a = b;
            b = remainder;
        }
        return a;
    }

    private static List<GameLevel.TestCase> wordPairs(int modulus, boolean subtract, int[][] inputs) {
        return pairs("a", "b", inputs, (a, b) -> {
            int mathematical = signed(a, modulus) + (subtract ? -signed(b, modulus) : signed(b, modulus));
            int word = Math.floorMod(mathematical, modulus);
            Map<String, Integer> expected = values("a", a, "b", b, "word", word,
                    "overflow", mathematical < -modulus / 2 || mathematical >= modulus / 2 ? 1 : 0);
            if (modulus == 256) {
                expected.put("negative", word >= 128 ? 1 : 0);
                expected.put("magnitude", Math.abs(signed(word, modulus)));
            }
            return expected;
        });
    }

    // These definitions are displayed only as rewards after their level succeeds.
    private static String clearHelper() {
        return "void clear(out) {\ndo {\nDEC out;\n} while (out != 0);\n}\n";
    }

    private static String copyHelper() {
        return "void copyTo(value, out) {\nvar work = value;\nclear(out);\nwhile (work != 0) {\nINC out;\nDEC work;\n}\n}\n";
    }

    private static String twiceHelper() {
        return "void twiceTo(value, out) {\nvar work = value;\nclear(out);\nwhile (work != 0) {\nINC out;\nINC out;\nDEC work;\n}\n}\n";
    }

    private static String stepHelpers() {
        return "void step(0, out) {\nINC out;\n}\nvoid step(1, out) {\nINC out;\nINC out;\n}\n";
    }

    private static String addHelper() {
        return "void addTo(a, b, out) {\nvar left = a;\nvar right = b;\nclear(out);\nwhile (left != 0) {\nINC out;\nDEC left;\n}\nwhile (right != 0) {\nINC out;\nDEC right;\n}\n}\n";
    }

    private static String subtractHelper() {
        return "void subtractTo(a, b, out) {\nvar count = b;\ncopyTo(a, out);\nwhile (count != 0) {\nDEC out;\nDEC count;\n}\n}\n";
    }

    private static String multiplyHelper() {
        return "void multiplyTo(a, b, out) {\nvar times = b;\nvar value = a;\nclear(out);\nwhile (times != 0) {\naddTo(out, value, out);\nDEC times;\n}\n}\n";
    }

    private static String divideHelper() {
        return "void divideTo(n, d, quotient, remainder) {\nvar left = n;\nvar capacity = d;\nclear(quotient);\nclear(remainder);\nwhile (left != 0) {\nDEC left;\nDEC capacity;\nINC remainder;\nif (capacity == 0) {\nINC quotient;\nclear(remainder);\ncopyTo(d, capacity);\n}\n}\n}\n";
    }

    private static String gcdHelper() {
        return "void gcdTo(a, b, out) {\nvar x = a;\nvar y = b;\nwhile (y != 0) {\nvar quotient = 0;\nvar remainder = 0;\ndivideTo(x, y, quotient, remainder);\ncopyTo(y, x);\ncopyTo(remainder, y);\n}\ncopyTo(x, out);\n}\n";
    }

    private static String binaryAlias(String name, String implementation, String result) {
        return "// " + name + " writes " + result + " using the values before the call.\n"
                + "void " + name + "(a, b, out) {\n" + implementation + "(a, b, out);\n}\n";
    }

    private static String divisionAlias(String name, String result) {
        return "void " + name + "(a, b, out) {\nvar numerator = a;\nvar divisor = b;\n"
                + "var quotient = 0;\nvar remainder = 0;\ndivideTo(numerator, divisor, quotient, remainder);\n"
                + "copyTo(" + result + ", out);\n}\n";
    }

    private static String wrapHelper(String name, int modulus) {
        return "void " + name + "(value, out) {\nvar count = value;\nvar capacity = " + modulus
                + ";\nclear(out);\nwhile (count != 0) {\nINC out;\nDEC capacity;\nif (capacity == 0) {\nclear(out);\ncopyTo("
                + modulus + ", capacity);\n}\nDEC count;\n}\n}\n";
    }

    private static String bitsHelper() {
        StringBuilder body = new StringBuilder("void bits4(value, bits) {\nvar work = value;\nvar quotient = 0;\nvar remainder = 0;\n");
        for (int index = 0; index < 4; index++) {
            body.append("divideTo(work, 2, quotient, remainder);\ncopyTo(remainder, bits[")
                    .append(index).append("]);\ncopyTo(quotient, work);\n");
        }
        return body.append("}\n").toString();
    }

    private static String decodeHelper(String name, int modulus, int threshold) {
        return "void " + name + "(value, negative, magnitude) {\nvar count = value;\nvar threshold = " + threshold
                + ";\nwhile (count != 0) {\nDEC count;\nDEC threshold;\n}\nclear(negative);\nif (threshold == 0) {\nINC negative;\nsubtractTo("
                + modulus + ", value, magnitude);\n} else {\ncopyTo(value, magnitude);\n}\n}\n";
    }

    private static String encodeHelper() {
        return "void encode4(negative, magnitude, out) {\ncopyTo(magnitude, out);\nif (negative != 0) {\nsubtractTo(16, magnitude, out);\nwrap4(out, out);\n}\n}\n";
    }

    private static String signedAddHelper(String name, String wrap, String decode) {
        return "void " + name + "(a, b, out, overflow) {\nvar leftSign = 0;\nvar rightSign = 0;\nvar resultSign = 0;\nvar magnitude = 0;\n"
                + decode + "(a, leftSign, magnitude);\n" + decode + "(b, rightSign, magnitude);\naddTo(a, b, out);\n"
                + wrap + "(out, out);\n" + decode + "(out, resultSign, magnitude);\nclear(overflow);\n"
                + "if (leftSign == 0) {\nif (rightSign == 0) {\nif (resultSign != 0) {\nINC overflow;\n}\n}\n} else {\nif (rightSign != 0) {\nif (resultSign == 0) {\nINC overflow;\n}\n}\n}\n}\n";
    }

    private static String signedSubtractHelper() {
        return "void subtract4(a, b, out, overflow) {\nvar leftSign = 0;\nvar rightSign = 0;\nvar resultSign = 0;\nvar magnitude = 0;\nvar negated = 0;\nvar ignored = 0;\ndecode4(a, leftSign, magnitude);\ndecode4(b, rightSign, magnitude);\nnegate4(b, negated);\nadd4(a, negated, out, ignored);\ndecode4(out, resultSign, magnitude);\nclear(overflow);\nif (leftSign == 0) {\nif (rightSign != 0) {\nif (resultSign != 0) {\nINC overflow;\n}\n}\n} else {\nif (rightSign == 0) {\nif (resultSign == 0) {\nINC overflow;\n}\n}\n}\n}\n";
    }

    private static String extendHelper() {
        return "void extend4(value, out) {\nvar negative = 0;\nvar magnitude = 0;\ndecode4(value, negative, magnitude);\ncopyTo(value, out);\nif (negative != 0) {\naddTo(out, 240, out);\n}\n}\n";
    }
}

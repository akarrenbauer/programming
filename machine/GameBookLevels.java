package machine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Additional book-aligned exercises; the existing scalar-word journey stays intact. */
public final class GameBookLevels {
    private static final int MAX_STEPS = 500_000;
    private static final List<String> A_BITS = names("a", 8);
    private static final List<String> B_BITS = names("b", 8);
    private static final List<String> C_BITS = names("c", 8);
    private static final List<String> BYTE_ARGUMENTS = concat(A_BITS, B_BITS, C_BITS);
    private static final List<GameLevel> TRANSFERS = buildTransfers();
    private static final List<GameLevel> BITS = buildBits();

    private GameBookLevels() { }

    public static List<GameLevel> transferLevels() {
        return TRANSFERS;
    }

    public static List<GameLevel> bitLevels() {
        return BITS;
    }

    /** The book exercises ask for a named definition and a call from the main program. */
    record MacroRequirement(String name, int arity) { }

    static MacroRequirement requiredMacro(String levelId) {
        return switch (levelId) {
            case "shift-macro" -> new MacroRequirement("shift", 2);
            case "move-macro" -> new MacroRequirement("move", 2);
            case "split-lowest-bit" -> new MacroRequirement("splitLowestBit", 2);
            case "lowest-byte" -> new MacroRequirement("lowestByte", 9);
            case "value-of-byte" -> new MacroRequirement("valueOfByte", 9);
            case "full-adder" -> new MacroRequirement("add", 4);
            case "byte-add" -> new MacroRequirement("add", 24);
            case "flip-bit" -> new MacroRequirement("flip", 1);
            case "negate-byte" -> new MacroRequirement("negate", 8);
            case "subtract-byte" -> new MacroRequirement("sub", 24);
            default -> null;
        };
    }

    private static List<GameLevel> buildTransfers() {
        List<GameLevel.TestCase> shifts = new ArrayList<>();
        List<GameLevel.TestCase> moves = new ArrayList<>();
        for (int[] pair : new int[][] {{0, 0}, {0, 7}, {1, 0}, {3, 5}, {9, 2}}) {
            Map<String, Integer> inputs = values("a", pair[0], "b", pair[1]);
            shifts.add(test(inputs, values("a", 0, "b", pair[0] + pair[1])));
            moves.add(test(inputs, values("a", 0, "b", pair[0])));
        }
        String shift = documented("shift(a,b): add the original a to b and set a to zero. Variable arguments must be distinct cells.",
                function("shift", List.of("a", "b"), "while (a != 0) {\nDEC a;\nINC b;\n}\n")
                + function("shift", List.of("0", "b"), "")
                + function("shift", List.of("1", "b"), "INC b;\n")
                + function("shift", List.of("2", "b"), "INC b;\nINC b;\n"));
        String move = documented("move(a,b): replace b with the original a, then leave a at zero. Variable arguments must be distinct cells.",
                function("move", List.of("a", "b"), "clear(b);\nshift(a, b);\n"));
        return List.of(
                level("shift-macro", "Natural-number toolbox", "Transfer into an occupied cell",
                        "Define shift(a,b). Consume a into b: a must become zero and b must contain the sum of both original inputs.",
                        "The book's shift macro moves units into an existing total. Each DEC a; INC b; pair preserves a+b. When a reaches zero, b is that original sum. Distinct input cells are required; shift(a,a) would not make progress. Literal specializations are also awarded after the general definition.",
                        scaffold("shift", List.of("a", "b"), "var a = 0;\nvar b = 0;\n", "shift(a, b);"),
                        List.of("The destination may already be nonzero; do not clear it.",
                                "Repeat one decrement of a and one increment of b until a is zero.",
                                "Use a while loop so a = 0 leaves the existing b unchanged; the invariant is a+b = the original sum."),
                        rewards("shift", shift), shifts),
                level("move-macro", "Natural-number toolbox", "Replace the destination",
                        "Define move(a,b). Finish with a = 0 and b equal to the original a, regardless of b's previous value.",
                        "Composition changes a helper's contract. shift adds to a destination; move first clears it, then transfers. The two variable arguments must name different cells. This is a destructive move, unlike the preserving copy helper.",
                        scaffold("move", List.of("a", "b"), "var a = 0;\nvar b = 0;\n", "move(a, b);"),
                        List.of("Include the case a = 0 and b is nonzero: the destination must still be cleared.",
                                "You already have clear and shift in the earned toolbox.",
                                "Clear the destination before shifting the source into it."),
                        rewards("move", move), moves));
    }

    private static List<GameLevel> buildBits() {
        List<GameLevel> levels = new ArrayList<>();
        List<GameLevel.TestCase> splitCases = new ArrayList<>();
        for (int[] pair : new int[][] {{0, 9}, {1, 8}, {2, 7}, {3, 0}, {7, 1}, {128, 3}, {255, 4}, {256, 5}, {511, 6}}) {
            splitCases.add(test(values("n", pair[0], "b", pair[1]), values("n", pair[0] / 2, "b", pair[0] % 2)));
        }
        levels.add(level("split-lowest-bit", "Bits and bytes", "Split off the lowest bit",
                "Define splitLowestBit(n,b). Replace n with its original value divided by 2, rounded down, and replace b with its original remainder modulo 2.",
                "A scalar encoding can become individual bit cells. Dividing by two separates the lowest bit from the remaining higher bits. Obtain the remainder before overwriting n. Unlike the earlier preserving arithmetic, this helper intentionally updates n; n and b must be distinct.",
                scaffold("splitLowestBit", List.of("n", "b"), "var n = 0;\nvar b = 0;\n", "splitLowestBit(n, b);"),
                List.of("The output bit is always 0 or 1, even when b started with another value.",
                        "The book-named div and mod helpers preserve inputs unless the output is the same cell.",
                        "Compute mod(n,2,b) first, then div(n,2,n), so both results use the original n."),
                rewards("splitLowestBit", documented("splitLowestBit(n,b): n becomes floor(original n/2), b becomes original n mod 2. The two cells must be distinct.",
                        function("splitLowestBit", List.of("n", "b"), "mod(n, 2, b);\ndiv(n, 2, n);\n"))), splitCases));

        List<GameLevel.TestCase> lowestCases = new ArrayList<>();
        for (int value : new int[] {0, 1, 2, 127, 128, 170, 255, 256, 511, 1024}) {
            Map<String, Integer> expected = values("a", value);
            expected.putAll(bits("b", value));
            lowestCases.add(test(values("a", value), expected));
        }
        levels.add(level("lowest-byte", "Bits and bytes", "Unpack the lowest byte",
                "Define lowestByte(a,b0,...,b7). Preserve a and write the eight bits of a mod 256 into b0 through b7, lowest bit first.",
                "The book uses eight separate cells instead of one encoded scalar. Repeat the same split eight times on a working copy. Any remaining higher bits are discarded; each output cell must end as 0 or 1.",
                scaffold("lowestByte", concat(List.of("a"), B_BITS), "var a = 0;\n" + declarations(B_BITS),
                        call("lowestByte", concat(List.of("a"), B_BITS))),
                List.of("Work on a copy so the input a survives.",
                        "The first split writes b0, the next b1, and so on.",
                        "Call splitLowestBit on the same working cell eight times, changing only the destination bit each time."),
                rewards("lowestByte", documented("lowestByte(a,b0,...,b7): preserve a; output the low eight bits of a, b0 first. All eight output cells must be distinct and separate from a.",
                        lowestByteHelper())), lowestCases));

        List<GameLevel.TestCase> valueCases = new ArrayList<>();
        for (int value : new int[] {0, 1, 2, 3, 127, 128, 170, 254, 255}) {
            Map<String, Integer> inputs = bits("b", value);
            Map<String, Integer> expected = new LinkedHashMap<>(inputs);
            expected.put("a", value);
            valueCases.add(test(inputs, expected));
        }
        String join = documented("joinBit(b,a): replace a with 2*original a+b, preserving b. b must be 0 or 1 and distinct from a.",
                function("joinBit", List.of("b", "a"), "add(a, a, a);\nif (b != 0) {\nINC a;\n}\n"));
        levels.add(level("value-of-byte", "Bits and bytes", "Reassemble a byte",
                "Define valueOfByte(b0,...,b7,a). Preserve the eight input bits and write their unsigned value, 0..255, into a.",
                "Reverse the splitting process from highest bit to lowest. Start with b7; each next bit doubles the accumulated value and adds that bit. This is positional notation, not sign decoding. Completion also awards the helper joinBit(b,a).",
                scaffold("valueOfByte", concat(B_BITS, List.of("a")), declarations(B_BITS) + "var a = 0;\n",
                        call("valueOfByte", concat(B_BITS, List.of("a")))),
                List.of("Process b7 first and b0 last.",
                        "After the highest bit, double a and add the next bit at each step.",
                        "You may define your own joinBit helper before valueOfByte; add(a,a,a) safely doubles a."),
                rewards("joinBit", join, "valueOfByte", documented("valueOfByte(b0,...,b7,a): preserve bits b0..b7 (each 0 or 1) and replace a with their unsigned value. a must be separate from all input bits.",
                        valueOfByteHelper())), valueCases));

        List<GameLevel.TestCase> adderCases = new ArrayList<>();
        for (int a = 0; a <= 1; a++) {
            for (int b = 0; b <= 1; b++) {
                for (int u = 0; u <= 1; u++) {
                    int sum = a + b + u;
                    adderCases.add(test(values("a", a, "b", b, "u", u), values("a", a, "b", b, "u", sum / 2, "c", sum % 2)));
                }
            }
        }
        levels.add(level("full-adder", "Bits and bytes", "Add one bit and its carry",
                "Define add(a,b,u,c) with four parameters. Preserve input bits a and b, replace u with the outgoing carry, and write the sum bit into c. All inputs are 0 or 1.",
                "A full adder combines two bits and a carry-in. Their total is 0..3: its remainder modulo 2 is the output bit, and its quotient divided by 2 is the carry. Macro signatures distinguish this four-parameter add from the earned three-parameter natural addition.",
                scaffold("add", List.of("a", "b", "u", "c"), "var a = 0;\nvar b = 0;\nvar u = 0;\nvar c = 0;\n", "add(a, b, u, c);"),
                List.of("A total of 2 means sum bit 0 and carry 1; a total of 3 means both outputs are 1.",
                        "Use add(a,b,s) with a temporary s, then add the original carry into s.",
                        "shift(u,s) consumes the old carry. Afterwards mod(s,2,c) and div(s,2,u) produce the two outputs."),
                rewards("add / 4 parameters", documented("add(a,b,u,c): a,b,u are input bits. Preserve a,b; replace u with carry-out and c with the sum bit. Use four distinct cells.",
                        function("add", List.of("a", "b", "u", "c"), "var s = 0;\nadd(a, b, s);\nshift(u, s);\nmod(s, 2, c);\ndiv(s, 2, u);\n"))), adderCases));

        levels.add(level("byte-add", "Bits and bytes", "Carry across eight columns",
                "Define add with 24 parameters: eight a bits, eight b bits, then eight c output bits. Preserve both input bytes and write their sum modulo 256 into c0..c7.",
                "Use the four-parameter full adder in each column, beginning at bit 0 with carry 0. Each column passes its carry to the next. Discard the final carry beyond bit 7. This is unsigned carry propagation; the earlier signed-overflow flag is a separate concept.",
                scaffold("add", BYTE_ARGUMENTS, declarations(BYTE_ARGUMENTS), call("add", BYTE_ARGUMENTS)),
                List.of("Initialize one carry cell to zero and reuse it in all eight columns.",
                        "Call add(a0,b0,carry,c0), then continue through bit 7.",
                        "To support reusing input cells as outputs too, copy all input bits before writing any result bit."),
                rewards("add / 24 parameters", documented("add(a0,...,a7,b0,...,b7,c0,...,c7): input cells hold bits; output is the sum modulo 256. The eight output cells must be distinct. They may overlap input cells; all original inputs are snapshotted first. Other input cells are preserved.",
                        byteArithmeticHelper(false))), byteCases(false)));

        String flipBit = function("flip", List.of("b"), "if (b == 0) {\nINC b;\n} else {\nDEC b;\n}\n");
        StringBuilder flipBody = new StringBuilder();
        B_BITS.forEach(bit -> flipBody.append("flip(").append(bit).append(");\n"));
        levels.add(level("flip-bit", "Bits and bytes", "Complement a bit",
                "Define flip(b). Change 0 to 1 and 1 to 0 in the same cell.",
                "A bit has only two valid values. A short if/else implements its complement. The reward also includes an eight-parameter flip overload that applies your one-bit operation to a whole byte.",
                scaffold("flip", List.of("b"), "var b = 0;\n", "flip(b);"),
                List.of("Choose between the two cases by testing b against zero.",
                        "Increment the zero case and decrement the one case.",
                        "Use if/else so that a bit changed from 0 to 1 is not immediately changed back."),
                rewards("flip / 1 and 8 parameters", documented("flip(b): b must be 0 or 1; complement it in place. flip(b0,...,b7): complement eight distinct bit cells in place.",
                        flipBit + function("flip", B_BITS, flipBody.toString()))),
                List.of(test(values("b", 0), values("b", 1)), test(values("b", 1), values("b", 0)))));

        List<GameLevel.TestCase> negateCases = new ArrayList<>();
        for (int value : new int[] {0, 1, 2, 42, 127, 128, 129, 254, 255}) {
            negateCases.add(test(bits("b", value), bits("b", Math.floorMod(-value, 256))));
        }
        List<String> one = List.of("1", "0", "0", "0", "0", "0", "0", "0");
        levels.add(level("negate-byte", "Bits and bytes", "Negate a byte in place",
                "Define negate(b0,...,b7). Replace the eight input bits with their two's-complement negation modulo 256.",
                "Negation complements every bit and adds one. The byte adder supports its output cells overlapping an input byte, so the result can replace the original bits. Zero remains zero, and the minimum signed encoding 128 remains 128 at this width.",
                scaffold("negate", B_BITS, declarations(B_BITS), call("negate", B_BITS)),
                List.of("Use the earned eight-parameter flip first.",
                        "The low-to-high bits for the number one are 1,0,0,0,0,0,0,0.",
                        "Add that byte to the complemented bits with the same b cells as outputs; the discarded final carry handles zero."),
                rewards("negate / 8 parameters", documented("negate(b0,...,b7): eight distinct cells, each 0 or 1; replace the byte with its negation modulo 256. Encodings 0 and 128 are fixed points.",
                        function("negate", B_BITS, call("flip", B_BITS) + "\n" + call("add", concat(one, B_BITS, B_BITS)) + "\n"))), negateCases));

        levels.add(level("subtract-byte", "Bits and bytes", "Subtract with complement and carry",
                "Define sub with 24 parameters: eight a bits, eight b bits, then eight c outputs. Preserve the inputs and write (a-b) modulo 256 into c0..c7.",
                "Subtraction is a plus the bitwise complement of b plus one. Snapshot both input bytes, flip only the copied b bits, and start the carry at 1. Using copies also makes it safe for result cells to overlap original input cells.",
                scaffold("sub", BYTE_ARGUMENTS, declarations(BYTE_ARGUMENTS), call("sub", BYTE_ARGUMENTS)),
                List.of("Do not flip the original b cells when the task asks to preserve them.",
                        "Copy all sixteen input bits before writing outputs; then complement the copied b byte.",
                        "Start carry at 1 and call the four-parameter add once per bit from low to high; discard the final carry."),
                rewards("sub / 24 parameters", documented("sub(a0,...,a7,b0,...,b7,c0,...,c7): input cells hold bits; output is (a-b) modulo 256. The eight outputs must be distinct but may overlap input cells. Original inputs are snapshotted; other input cells remain unchanged.",
                        byteArithmeticHelper(true))), byteCases(true)));
        return List.copyOf(levels);
    }

    private static GameLevel level(String id, String world, String title, String goal, String lesson,
            String starter, List<String> hints, Map<String, String> rewards, List<GameLevel.TestCase> cases) {
        return new GameLevel(id, world, title, goal, lesson, starter, hints, Set.of(),
                Set.of(GameFeature.MACROS), rewards, cases, MAX_STEPS);
    }

    private static String scaffold(String name, List<String> parameters, String globals, String invocation) {
        return "import \"toolbox\";\n" + function(name, parameters, "// Complete this macro using earned helpers.\n")
                + globals + "begin:\n" + invocation + "\nHALT;\n";
    }

    private static String function(String name, List<String> parameters, String body) {
        return "void " + name + "(" + String.join(", ", parameters) + ") {\n" + body + "}\n";
    }

    private static String call(String name, List<String> arguments) {
        return name + "(" + String.join(", ", arguments) + ");";
    }

    private static String declarations(List<String> names) {
        StringBuilder declarations = new StringBuilder();
        names.forEach(name -> declarations.append("var ").append(name).append(" = 0;\n"));
        return declarations.toString();
    }

    private static List<String> names(String prefix, int length) {
        List<String> names = new ArrayList<>();
        for (int index = 0; index < length; index++) names.add(prefix + index);
        return List.copyOf(names);
    }

    @SafeVarargs
    private static List<String> concat(List<String>... lists) {
        List<String> combined = new ArrayList<>();
        for (List<String> list : lists) combined.addAll(list);
        return List.copyOf(combined);
    }

    private static Map<String, Integer> values(Object... entries) {
        Map<String, Integer> values = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) values.put((String) entries[index], (Integer) entries[index + 1]);
        return values;
    }

    private static Map<String, Integer> bits(String prefix, int value) {
        Map<String, Integer> bits = new LinkedHashMap<>();
        for (int index = 0; index < 8; index++) bits.put(prefix + index, (value >>> index) & 1);
        return bits;
    }

    private static GameLevel.TestCase test(Map<String, Integer> inputs, Map<String, Integer> expected) {
        return new GameLevel.TestCase(inputs, expected);
    }

    private static Map<String, String> rewards(String... entries) {
        Map<String, String> rewards = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) rewards.put(entries[index], entries[index + 1]);
        return Collections.unmodifiableMap(rewards);
    }

    private static String documented(String contract, String definition) {
        return "// " + contract + "\n" + definition;
    }

    private static String lowestByteHelper() {
        StringBuilder body = new StringBuilder("var work = a;\n");
        B_BITS.forEach(bit -> body.append("splitLowestBit(work, ").append(bit).append(");\n"));
        return function("lowestByte", concat(List.of("a"), B_BITS), body.toString());
    }

    private static String valueOfByteHelper() {
        StringBuilder body = new StringBuilder("copy(b7, a);\n");
        for (int index = 6; index >= 0; index--) body.append("joinBit(b").append(index).append(", a);\n");
        return function("valueOfByte", concat(B_BITS, List.of("a")), body.toString());
    }

    private static String byteArithmeticHelper(boolean subtract) {
        StringBuilder body = new StringBuilder();
        for (String prefix : List.of("a", "b")) {
            for (int index = 0; index < 8; index++) {
                body.append("var ").append(prefix).append(prefix).append(index).append(" = ")
                        .append(prefix).append(index).append(";\n");
            }
        }
        body.append("var carry = ").append(subtract ? 1 : 0).append(";\n");
        if (subtract) body.append(call("flip", names("bb", 8))).append('\n');
        for (int index = 0; index < 8; index++) {
            body.append("add(aa").append(index).append(", bb").append(index).append(", carry, c")
                    .append(index).append(");\n");
        }
        return function(subtract ? "sub" : "add", BYTE_ARGUMENTS, body.toString());
    }

    private static List<GameLevel.TestCase> byteCases(boolean subtract) {
        int[][] pairs = subtract
                ? new int[][] {{0, 0}, {0, 1}, {0, 128}, {0, 255}, {128, 128}, {128, 1}, {127, 255}, {255, 0}, {2, 3}, {170, 85}}
                : new int[][] {{0, 0}, {0, 255}, {1, 1}, {1, 255}, {127, 1}, {128, 128}, {255, 255}, {85, 170}, {170, 85}, {200, 100}};
        List<GameLevel.TestCase> cases = new ArrayList<>();
        for (int[] pair : pairs) {
            Map<String, Integer> inputs = bits("a", pair[0]);
            inputs.putAll(bits("b", pair[1]));
            Map<String, Integer> expected = new LinkedHashMap<>(inputs);
            expected.putAll(bits("c", Math.floorMod(pair[0] + (subtract ? -pair[1] : pair[1]), 256)));
            cases.add(test(inputs, expected));
        }
        return cases;
    }
}

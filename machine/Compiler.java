package machine;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Compiler {

    @FunctionalInterface
    public interface SourceLoader {
        List<String> load(Path path) throws IOException;
    }

    @FunctionalInterface
    public interface SourceResolver {
        SourceDocument resolve(Path path) throws IOException;
    }

    private static final int HALT = 0;
    private static final int NOP = 1;
    private static final int INC = 2;
    private static final int DEC = 3;
    private static final int JNZ = 4;
    private static final Pattern LOCAL_VARIABLE =
        Pattern.compile("^var\\s+([A-Za-z_][A-Za-z0-9_]*)\\b");

    public static void main(String[] args) throws ParseException {
        if (args.length < 1) {
            System.err.println("Please provide the path to the source file as an argument.");
            System.exit(1);
        }

        try
        {
            List<Integer> bytecode = compile(args[0]);
            if (bytecode == null) {
                System.err.println("Error: Could not read the source file.");
                System.exit(1);
            }
            writeBytecodeToFile(bytecode, "bytecode.txt");
        } catch (IOException e) {
            System.err.println("Error reading the source file: " + args[0]);
        }
    }

    private static void writeBytecodeToFile(List<Integer> bytecode, String outputPath) {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputPath))) {
            for (int code : bytecode) {
                writer.write(String.valueOf(code));
                writer.newLine();
            }
        } catch (IOException e) {
            System.err.println("Error writing the bytecode file: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static List<Integer> compile(String inputFilePath) throws ParseException, IOException {
        List<String> sourceCode = Files.readAllLines(Paths.get(inputFilePath));
        return compile(sourceCode);
    }

    public static boolean matchingSignature(String[] argumentList, String[] parameterList) {
        if (argumentList.length != parameterList.length) {
            return false;
        }
        for (int k = 0; k < argumentList.length; ++k) {
            String parameter = parameterList[k].trim();
            String argument = argumentList[k].trim();
            if (parameter.isEmpty() || argument.isEmpty()) {
                return false;
            }
            if (Character.isDigit(parameter.charAt(0)) && !parameter.equals(argument)) {
                return false;
            }
        }
        return true;
    }

    public static List<String> preProcess( List<String> sourceCode ) throws ParseException, IOException, IllegalArgumentException {
        return preProcess(sourceCode, Files::readAllLines);
    }

    public static List<String> preProcess( List<String> sourceCode, SourceLoader sourceLoader ) throws ParseException, IOException, IllegalArgumentException {
        return texts(preProcessMapped(anonymous(sourceCode), path -> anonymous(sourceLoader.load(path))));
    }

    private static List<MappedText> preProcessMapped(List<MappedText> sourceCode, MappedLoader loader)
            throws ParseException, IOException {
        List<MappedText> strippedSourceCode = stripMappedComments(includeMappedImports(sourceCode, loader));

        int lineNumber = 1;
        for( MappedText line : strippedSourceCode ) {
            System.out.printf("%4d\t%s%n", lineNumber++, line.text);
        }

        List<String> codeCopy = texts(strippedSourceCode);
        codeCopy.add("");

        String joinedString = codeCopy.stream()
            .collect(Collectors.joining("\n"))
            .replaceAll("#|//(~[\\n])*","");

        new CompilerParser(new StringReader(joinedString)).Program(true);

        Map<String, List<String>> macroMap = new HashMap<>();
        int labelCounter = 0;
        // Iterate through source code to find and replace macro definitions and calls
        for (int i = strippedSourceCode.size() - 1; i >= 0; --i) {
            String line = strippedSourceCode.get(i).text.trim();

            // Check if the line is a macro definition
            if (line.startsWith("void") ) {
                if( line.contains("(") && line.contains(")")) {
                    String macroName = line.substring(5, line.indexOf('(')).trim();
                    String parameters = line.substring(line.indexOf('(') + 1, line.indexOf(')')).trim();
                    String[] parameterList = parameters.isEmpty() ? new String[0] : parameters.split(",");
                    boolean[] nonconst = new boolean[parameterList.length];

                    Map<String, String> locals = new HashMap<>();

                    // Extract macro body
                    MappedBuilder macroBody = new MappedBuilder();
                    Stack<String> blockStack = new Stack<>();
                    if (line.contains("{")) {
                        blockStack.push("{");
                    }
                    strippedSourceCode.set(i, MappedText.plain(""));
                    i++; // Move to the next line, which should be the start of the macro body
                    while (i < strippedSourceCode.size()) {
                        MappedText bodyLine = strippedSourceCode.get(i).trim();
                        String macroLine = bodyLine.text;

                        for( var c : macroLine.toCharArray() ) {
                            if (c == '{') {
                                blockStack.push("{");
                            } else if (c == '}') {
                                if (blockStack.isEmpty()) {
                                    throw new IllegalArgumentException("Unmatched parenthesis: " + macroLine);
                                } else {
                                    blockStack.pop();
                                }
                            }
                        }

                        if (macroLine.contains(":")) {
                            String label = macroLine.substring(0, macroLine.indexOf(':')).trim();
                            locals.put(label, "___#" + labelCounter + "___");
                            ++labelCounter;
                        }
                        Matcher declaration = LOCAL_VARIABLE.matcher(macroLine);
                        if (declaration.find()) {
                            String label = declaration.group(1);
                            locals.put(label, "___#" + labelCounter + "___");
                            ++labelCounter;
                        }
                        for (var local : locals.entrySet()) {
                            macroLine = macroLine.replaceAll("\\b" + Pattern.quote(local.getKey()) + "\\b",
                                Matcher.quoteReplacement(local.getValue()));
                        }

                        for (int k = 0; k < parameterList.length; ++k) {
                            if (macroLine.contains("INC " + parameterList[k].trim()) ||
                            macroLine.contains("DEC " + parameterList[k].trim())) {
                                nonconst[k] = true;
                            }
                            macroLine = macroLine.replaceAll("\\b" + parameterList[k].trim() + "\\b", "%%" + k + "%%");
                        }
                        strippedSourceCode.set(i, MappedText.plain(""));
                        i++;
                        if( blockStack.empty() ) {
                            break; // end of macro definition
                        } else {
                            macroBody.append(bodyLine.withText(macroLine)).append(MappedText.plain("\n"));
                        }
                    }

                    // Store the macro definition for replacement later
                    MappedText macroDefinition = macroBody.build();

                    // Replace all macro calls in the source code
                    for (int j = i; j < strippedSourceCode.size(); j++) {
                        MappedBuilder replacement = new MappedBuilder();
                        for (MappedText subLine : strippedSourceCode.get(j).splitLines()) {
                            MappedText callLine = subLine.trim();
                            String currentLine = callLine.text;
                            if (currentLine.startsWith(macroName) && currentLine.substring(macroName.length()).matches("\\s*\\(.*\\);")) {
                                String arguments = currentLine.substring(currentLine.indexOf('(') + 1, currentLine.indexOf(')')).trim();
                                String[] argumentList = arguments.isEmpty() ? new String[0] : arguments.split(",");
                                if (matchingSignature(argumentList, parameterList)) {
                                    MappedText expandedMacro = macroDefinition.invokedBy(callLine.origin(0));
                                    for (int k = 0; k < argumentList.length; k++) {
                                        String arg = argumentList[k].trim();
                                        if (nonconst[k] && Character.isDigit(arg.charAt(0))) {
                                            MappedText temporary = MappedText.located(
                                                "var ___#" + labelCounter + "___ = " + arg + ";\n",
                                                callLine.origin(0));
                                            expandedMacro = new MappedBuilder().append(temporary)
                                                .append(expandedMacro.replaceAll("%%"+k+"%%", "___#" + labelCounter + "___"))
                                                .build();
                                            ++labelCounter;
                                        } else {
                                            expandedMacro = expandedMacro.replaceAll("%%" + k + "%%", argumentList[k].trim());
                                        }
                                    }
                                    Map<String, String> labelMap = new HashMap<>();
                                    for (var token : locals.values()) {
                                        if (expandedMacro.text.contains(token)) {
                                            if( !labelMap.containsKey(token) ) {
                                                labelMap.put(token, "___#" + labelCounter + "___");
                                                ++labelCounter;
                                            }
                                            expandedMacro = expandedMacro.replaceAll(token, labelMap.get(token));
                                        }
                                    }

                                    replacement.append(expandedMacro).append(MappedText.plain("\n"));
                                } else {
                                    replacement.append(callLine).append(MappedText.plain("\n"));
                                }
                            } else {
                                replacement.append(callLine).append(MappedText.plain("\n"));
                            }
                        }
                        strippedSourceCode.set(j, replacement.build());
                    }
                } else{
                    throw new IllegalArgumentException("void keyword must be followed by identifier and (...): " + line ); 
                }
            }
        }

        // Remove macro definitions from the source code
        strippedSourceCode.removeIf(line -> line.text.isEmpty() || line.text.trim().startsWith("void "));

        List<MappedText> output = new ArrayList<>();
        for (MappedText text : strippedSourceCode) {
            for (MappedText line : text.splitLines()) {
                if (!line.text.isEmpty()) {
                    output.add(line);
                }
            }
        }
        return output;
    }

    public static List<String> breakLines( List<String> sourceCode ) {
        List<String> output = new ArrayList<>();

        for (String line : sourceCode) {
            String[] lines = line.split("\n");
            for( String newLine : lines ) {
                if( !newLine.isEmpty() ) {
                    output.add(newLine);
                }
            }
        }
        return output;
    }

    public static List<String> includeImports( List<String> sourceCode ) throws IOException, IllegalArgumentException {
        return includeImports(sourceCode, Files::readAllLines);
    }

    /**
     * Expands direct imports in source order, calling the loader for every occurrence.
     * Relative paths retain their existing process-working-directory interpretation.
     * Imported lines are not scanned for further imports.
     */
    public static List<String> includeImports( List<String> sourceCode, SourceLoader sourceLoader ) throws IOException, IllegalArgumentException {
        return texts(includeMappedImports(anonymous(sourceCode), path -> anonymous(sourceLoader.load(path))));
    }

    private static List<MappedText> includeMappedImports(List<MappedText> sourceCode, MappedLoader loader)
            throws IOException {
        List<MappedText> output = new ArrayList<>();

        for( MappedText sourceLine : sourceCode ) {
            MappedText trimmed = sourceLine.trimOriginal();
            String line = trimmed.text;

            // Check if the line is an import statement
            if (line.startsWith("import")) {
                if( line.contains("\"") && line.endsWith("\";") ) {
                    String filename = line.substring(line.indexOf('"') + 1, line.lastIndexOf('"')).trim();
                    output.addAll(loader.load(Paths.get(filename)));
                } else {
                    throw new IllegalArgumentException("import keyword must be followed by \"/path/to/file\": " + line);
                }
            }else {
                output.add(trimmed);
            }
        }

        return output;
    }

    public static List<String> stripComments(List<String> sourceCode) {
        return texts(stripMappedComments(anonymous(sourceCode)));
    }

    private static List<MappedText> stripMappedComments(List<MappedText> sourceCode) {
        Pattern splitAfter = Pattern.compile("(?<=[;:{])");
        List<MappedText> output = new ArrayList<>();
        for (MappedText original : sourceCode) {
            int comment = original.text.indexOf("//");
            MappedText line = comment < 0 ? original : original.sliceOriginal(0, comment).trimOriginal();
            Matcher splits = splitAfter.matcher(line.text);
            int start = 0;
            while (splits.find()) {
                if (splits.end() > start) {
                    output.add(line.sliceOriginal(start, splits.end()).finishOriginal());
                }
                start = splits.end();
            }
            if (start < line.text.length()) {
                output.add(line.sliceOriginal(start, line.text.length()).finishOriginal());
            }
        }
        return output;
    }

    public static List<Integer> compile(List<String> sourceCode)  throws IOException, ParseException, IllegalArgumentException {
        return compile(sourceCode, Files::readAllLines);
    }

    /** Compiles the supplied main source and obtains direct imports from the loader. */
    public static List<Integer> compile(List<String> sourceCode, SourceLoader sourceLoader)  throws IOException, ParseException, IllegalArgumentException {
        List<MappedText> expanded = preProcessMapped(anonymous(sourceCode), path -> anonymous(sourceLoader.load(path)));
        return new ArrayList<>(compileExpanded(expanded, Map.of()).bytecode());
    }

    /** Compiles normalized source snapshots without re-reading any editor-owned buffers. */
    public static CompilationResult compileWithSourceMap(SourceDocument main, SourceResolver imports)
            throws IOException, ParseException {
        Map<Path, String> sources = new LinkedHashMap<>();
        List<MappedText> expanded = preProcessMapped(documentLines(snapshot(main, sources)),
            path -> documentLines(snapshot(imports.resolve(path), sources)));
        return compileExpanded(expanded, sources);
    }

    private static CompilationResult compileExpanded(List<MappedText> expanded, Map<Path, String> sources)
            throws ParseException {
        List<String> codeCopy = texts(expanded);
        codeCopy.add("");

        String joinedString = codeCopy.stream()
            .collect(Collectors.joining("\n"))
            .replaceAll("#|//(~[\\n])*","");

        CompilerParser parser = new CompilerParser(new ByteArrayInputStream(joinedString.getBytes(StandardCharsets.UTF_8)));
        List<Integer> bytecode = parser.Program(false).stream().map(e -> Integer.valueOf(e.getValue())).collect(Collectors.toList());
        Map<Integer, SourceTrace> sourceMap = new LinkedHashMap<>();
        for (var instruction : parser.getInstructionSourceLines().entrySet()) {
            int line = instruction.getValue() - 1;
            if (line >= 0 && line < expanded.size()) {
                SourceTrace trace = expanded.get(line).origin(0);
                if (trace != null) {
                    sourceMap.put(instruction.getKey(), trace);
                }
            }
        }
        return new CompilationResult(bytecode, sourceMap, parser.getCodeStart(), sources);
    }

    private static SourceDocument snapshot(SourceDocument document, Map<Path, String> sources) {
        Objects.requireNonNull(document, "source document");
        if (document.file() == null) {
            return document;
        }
        String previous = sources.putIfAbsent(document.file(), document.text());
        return previous == null ? document : new SourceDocument(document.file(), previous);
    }

    private static List<MappedText> documentLines(SourceDocument document) {
        List<MappedText> lines = new ArrayList<>();
        int start = 0;
        int line = 1;
        while (start < document.text().length()) {
            int end = document.text().indexOf('\n', start);
            if (end < 0) {
                end = document.text().length();
            }
            SourceLocation location = new SourceLocation(document.file(), start, end, line, 1);
            lines.add(MappedText.located(document.text().substring(start, end),
                new SourceTrace(location, List.of())));
            start = end + 1;
            ++line;
        }
        return lines;
    }

    private static List<MappedText> anonymous(List<String> source) {
        return source.stream().map(MappedText::plain).collect(Collectors.toList());
    }

    private static List<String> texts(List<MappedText> source) {
        return source.stream().map(line -> line.text).collect(Collectors.toList());
    }

    @FunctionalInterface
    private interface MappedLoader {
        List<MappedText> load(Path path) throws IOException;
    }

    /** Keeps one provenance entry per generated LF-delimited line, including blank lines. */
    private static final class MappedText {
        final String text;
        final List<SourceTrace> origins;

        MappedText(String text, List<SourceTrace> origins) {
            this.text = text;
            this.origins = new ArrayList<>(origins);
        }

        static MappedText plain(String text) {
            return located(text, null);
        }

        static MappedText located(String text, SourceTrace origin) {
            return new MappedText(text, Collections.nCopies(lineCount(text), origin));
        }

        SourceTrace origin(int line) {
            return line < origins.size() ? origins.get(line) : null;
        }

        MappedText withText(String replacement) {
            List<SourceTrace> replacementOrigins = new ArrayList<>();
            int count = lineCount(replacement);
            for (int line = 0; line < count; ++line) {
                replacementOrigins.add(origin(line));
            }
            return new MappedText(replacement, replacementOrigins);
        }

        MappedText replaceAll(String pattern, String replacement) {
            return withText(text.replaceAll(pattern, replacement));
        }

        MappedText trim() {
            int start = trimStart();
            int end = trimEnd(start);
            int firstLine = lineCount(text.substring(0, start)) - 1;
            String trimmed = text.substring(start, end);
            List<SourceTrace> trimmedOrigins = new ArrayList<>();
            int count = lineCount(trimmed);
            for (int line = 0; line < count; ++line) {
                trimmedOrigins.add(origin(firstLine + line));
            }
            return new MappedText(trimmed, trimmedOrigins);
        }

        MappedText trimOriginal() {
            int start = trimStart();
            return sliceOriginal(start, trimEnd(start));
        }

        MappedText sliceOriginal(int start, int end) {
            SourceTrace trace = origin(0);
            if (trace != null) {
                SourceLocation at = trace.location();
                SourceLocation slice = new SourceLocation(at.file(), at.startOffset() + start,
                    at.startOffset() + end, at.line(), at.column() + start);
                trace = new SourceTrace(slice, trace.callers());
            }
            return located(text.substring(start, end), trace);
        }

        MappedText finishOriginal() {
            // Keep the compiler's exact whitespace, but highlight just the original construct.
            int start = trimStart();
            int end = trimEnd(start);
            int following = start;
            while (following < end && text.charAt(following) == '}') {
                ++following;
                while (following < end && text.charAt(following) <= ' ') {
                    ++following;
                }
            }
            if (following < end) {
                start = following;
            }
            return located(text, sliceOriginal(start, end).origin(0));
        }

        List<MappedText> splitLines() {
            String[] lines = text.split("\n");
            List<MappedText> output = new ArrayList<>();
            for (int line = 0; line < lines.length; ++line) {
                output.add(located(lines[line], origin(line)));
            }
            return output;
        }

        MappedText invokedBy(SourceTrace call) {
            if (call == null) {
                return this;
            }
            List<SourceTrace> invokedOrigins = new ArrayList<>();
            for (SourceTrace body : origins) {
                if (body == null) {
                    invokedOrigins.add(null);
                } else {
                    List<SourceLocation> callers = new ArrayList<>(call.callers());
                    callers.add(call.location());
                    callers.addAll(body.callers());
                    invokedOrigins.add(new SourceTrace(body.location(), callers));
                }
            }
            return new MappedText(text, invokedOrigins);
        }

        private int trimStart() {
            int start = 0;
            while (start < text.length() && text.charAt(start) <= ' ') {
                ++start;
            }
            return start;
        }

        private int trimEnd(int start) {
            int end = text.length();
            while (end > start && text.charAt(end - 1) <= ' ') {
                --end;
            }
            return end;
        }

        private static int lineCount(String text) {
            int count = 1;
            for (int index = 0; index < text.length(); ++index) {
                if (text.charAt(index) == '\n') {
                    ++count;
                }
            }
            return count;
        }
    }

    private static final class MappedBuilder {
        private final StringBuilder text = new StringBuilder();
        private final List<SourceTrace> origins = new ArrayList<>(Collections.singletonList(null));
        private boolean hasContent;

        MappedBuilder append(MappedText part) {
            int sourceLine = 0;
            for (int index = 0; index < part.text.length(); ++index) {
                char character = part.text.charAt(index);
                text.append(character);
                if (character == '\n') {
                    origins.add(null);
                    hasContent = false;
                    ++sourceLine;
                } else if (!hasContent && character > ' ') {
                    origins.set(origins.size() - 1, part.origin(sourceLine));
                    hasContent = true;
                }
            }
            return this;
        }

        MappedText build() {
            return new MappedText(text.toString(), origins);
        }
    }
}

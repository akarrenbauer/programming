package machine;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;
import java.util.regex.Pattern;

public class Compiler {

    private static final int HALT = 0;
    private static final int NOP = 1;
    private static final int INC = 2;
    private static final int DEC = 3;
    private static final int JNZ = 4;

    public static void main(String[] args) throws ParseException {
        if (args.length < 1) {
            System.err.println("Please provide the path to the source file as an argument.");
            System.exit(1);
        }

        List<Integer> bytecode = compile(args[0]);
        if (bytecode == null) {
            System.err.println("Error: Could not read the source file.");
            System.exit(1);
        }

        writeBytecodeToFile(bytecode, "bytecode.txt");
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

    public static List<Integer> compile(String inputFilePath) throws ParseException {
        try {
            List<String> sourceCode = Files.readAllLines(Paths.get(inputFilePath));
            return compile(sourceCode);
        } catch (IOException e) {
            System.err.println("Error reading the source file: " + inputFilePath);
            e.printStackTrace();
        }
        return new ArrayList<>();
    }

    public static boolean matchingSignature(String[] argumentList, String[] parameterList) {
        if (argumentList.length != parameterList.length) {
            return false;
        }
        for (int k = 0; k < argumentList.length; ++k) {
            if (Character.isDigit(parameterList[k].charAt(0)) && !parameterList[k].equals(argumentList[k])) {
                return false;
            }
        }
        return true;
    }

    public static List<String> preProcess( List<String> sourceCode ) throws IllegalArgumentException {

        List<String> strippedSourceCode = stripComments(includeImports(sourceCode));

        Map<String, List<String>> macroMap = new HashMap<>();

        int labelCounter = 0;

        // Iterate through source code to find and replace macro definitions and calls
        for (int i = strippedSourceCode.size() - 1; i >= 0; --i) {
            String line = strippedSourceCode.get(i).trim();

            // Check if the line is a macro definition
            if (line.startsWith("void") ) {
                if( line.contains("(") && line.contains(")")) {
                    String macroName = line.substring(5, line.indexOf('(')).trim();
                    String parameters = line.substring(line.indexOf('(') + 1, line.indexOf(')')).trim();
                    String[] parameterList = parameters.split(",");
                    boolean[] nonconst = new boolean[parameterList.length];

                    Map<String, String> locals = new HashMap<>();

                    // Extract macro body
                    StringBuilder macroBody = new StringBuilder();
                    Stack<String> blockStack = new Stack<>();
                    if (line.contains("{")) {
                        blockStack.push("{");
                    }
                    strippedSourceCode.set(i, "");
                    i++; // Move to the next line, which should be the start of the macro body
                    while (i < strippedSourceCode.size()) {
                        String macroLine = strippedSourceCode.get(i).trim();

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
                        if (macroLine.startsWith("var ") ) {
                            String label = macroLine.substring(4, macroLine.indexOf('=')).trim();
                            locals.put(label, "___#" + labelCounter + "___");
                            ++labelCounter;
                        }
                        for (var local : locals.entrySet()) {
                            macroLine = macroLine.replaceAll("\\b" + local.getKey() + "\\b", 
                                local.getValue());                            
                        }

                        for (int k = 0; k < parameterList.length; ++k) {
                            if (macroLine.contains("INC " + parameterList[k].trim()) ||
                            macroLine.contains("DEC " + parameterList[k].trim())) {
                                nonconst[k] = true;
                            }
                            macroLine = macroLine.replaceAll("\\b" + parameterList[k].trim() + "\\b", "%%" + k + "%%");
                        }
                        strippedSourceCode.set(i, "");
                        i++;
                        if( blockStack.empty() ) {
                            break; // end of macro definition
                        } else {
                            macroBody.append(macroLine).append("\n");
                        }
                    }

                    // Store the macro definition for replacement later
                    String macroDefinition = macroBody.toString();

                    // Replace all macro calls in the source code
                    for (int j = i; j < strippedSourceCode.size(); j++) {
                        StringBuilder replacement = new StringBuilder();
                        String[] subLines = strippedSourceCode.get(j).split("\n");
                        for (String subLine : subLines) {
                            String currentLine = subLine.trim();
                            if (currentLine.startsWith(macroName) && currentLine.substring(macroName.length()).matches("\\s*\\(.*\\);")) {
                                String arguments = currentLine.substring(currentLine.indexOf('(') + 1, currentLine.indexOf(')')).trim();
                                String[] argumentList = arguments.split(",");
                                if (matchingSignature(argumentList, parameterList)) {
                                    String expandedMacro = macroDefinition;
                                    for (int k = 0; k < argumentList.length; k++) {
                                        String arg = argumentList[k].trim();
                                        if (nonconst[k] && Character.isDigit(arg.charAt(0))) {
                                            expandedMacro = "var ___#" + labelCounter + "___ = " + arg + ";\n" +
                                            expandedMacro.replaceAll("%%"+k+"%%", "___#" + labelCounter + "___");
                                            ++labelCounter;
                                        } else {
                                            expandedMacro = expandedMacro.replaceAll("%%" + k + "%%", argumentList[k].trim());
                                        }
                                    }
                                    Map<String, String> labelMap = new HashMap<>();
                                    for (var token : locals.values()) {
                                        if (expandedMacro.contains(token)) {
                                            if( !labelMap.containsKey(token) ) {
                                                labelMap.put(token, "___#" + labelCounter + "___");
                                                ++labelCounter;
                                            }
                                            expandedMacro = expandedMacro.replaceAll(token, labelMap.get(token));
                                        }
                                    }

                                    replacement.append(expandedMacro).append("\n");
                                } else {
                                    replacement.append(currentLine).append("\n");
                                }
                            } else {
                                replacement.append(currentLine).append("\n");
                            }
                        }
                        strippedSourceCode.set(j, replacement.toString() );
                    }
                } else{
                    throw new IllegalArgumentException("void keyword must be followed by identifier and (...): " + line ); 
                }
            }
        }

        // Remove macro definitions from the source code
        strippedSourceCode.removeIf(line -> line.isEmpty() || line.trim().startsWith("void "));

        return breakLines(strippedSourceCode);
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

    public static List<String> includeImports( List<String> sourceCode ) throws IllegalArgumentException {
        List<String> output = new ArrayList<>();

        for( String sourceLine : sourceCode ) {
            String line = sourceLine.trim();

            // Check if the line is an import statement
            if (line.startsWith("import")) {
                if( line.contains("\"") && line.endsWith("\";") ) {
                    String filename = line.substring(line.indexOf('"') + 1, line.lastIndexOf('"')).trim();
                    try {
                        List<String> importedLines = Files.readAllLines(Paths.get(filename));
                        output.addAll(importedLines);
                    } catch (IOException e) {
                        System.err.println("Error importing file: " + filename);
                        e.printStackTrace();
                    }
                } else {
                    throw new IllegalArgumentException("import keyword must be followed by \"/path/to/file\": " + line);
                }
            }else {
                output.add(line);
            }
        }

        return output;
    }

    public static List<String> stripComments(List<String> sourceCode) {
        Pattern splitAfter = Pattern.compile("(?<=[;:{])");
        return sourceCode.stream()
        .map(line -> line.contains("//") ? line.substring(0, line.indexOf("//")).trim() : line)
        .flatMap(s -> splitAfter.splitAsStream(s))          // keeps the delimiters at the end
        .filter(s -> !s.isEmpty())                          // drop empty chunks
        .collect(Collectors.toList());
    }

    public static List<Integer> compile(List<String> sourceCode)  throws ParseException, IllegalArgumentException {
        List<String> preprocessedCode = preProcess( sourceCode );

        List<String> codeCopy = new ArrayList<>( preprocessedCode );
        codeCopy.add("");

        String joinedString = codeCopy.stream()
            .collect(Collectors.joining("\n"))
            .replaceAll("#|//(~[\\n])*","");

        System.out.println(joinedString);                                      

        CompilerParser parser = new CompilerParser(new ByteArrayInputStream(joinedString.getBytes(StandardCharsets.UTF_8)));
        return parser.Program().stream().map(e -> Integer.valueOf(e.getValue())).collect(Collectors.toList());
    }
}

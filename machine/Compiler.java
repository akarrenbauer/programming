import java.io.BufferedReader;
import java.io.FileReader;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.io.IOException;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Stack;

public class Compiler {

    private static final int HALT = 0;
    private static final int NOP = 1;
    private static final int INC = 2;
    private static final int DEC = 3;
    private static final int JNZ = 4;

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Please provide the path to the source file as an argument.");
            System.exit(1);
        }

        List<Integer> bytecode = compile(args[0]);
        if (bytecode == null) {
            System.err.println("Error: Could not read the source file.");
            System.exit(1);
        }

        // Write bytecode to a file
        try (BufferedWriter writer = new BufferedWriter(new FileWriter("bytecode.txt"))) {
            for (int code : bytecode) {
                writer.write(String.valueOf(code));
                writer.newLine();
            }
        } catch (IOException e) {
            System.err.println("Error writing the bytecode file: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static List<Integer> compile(String inputFilePath) {
        List<String> sourceCode = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(inputFilePath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sourceCode.add(line);
            }
            return compile(sourceCode);
        } catch (IOException e) {
            System.err.println("Error reading the source file: " + inputFilePath);
            e.printStackTrace();
        }
        return new ArrayList<>(); // Return an empty list instead of null
    }

    public static List<String> preProcess( List<String> sourceCode ) throws IllegalArgumentException {

        List<String> strippedSourceCode = stripComments( includeImports( sourceCode ) );

        Map<String,List<String>> macroMap = new HashMap<>();
        // Iterate through source code to find and replace macro definitions and calls
        for (int i = 0; i < strippedSourceCode.size(); i++) {
            String line = strippedSourceCode.get(i).trim();

            // Check if the line is a macro definition
            if (line.startsWith("void") ) {
                if( line.contains("(") && line.contains(")")) {
                    String macroName = line.substring(5, line.indexOf('(')).trim();
                    String parameters = line.substring(line.indexOf('(') + 1, line.indexOf(')')).trim();
                    String[] parameterList = parameters.split(",");

                    // Extract macro body
                    StringBuilder macroBody = new StringBuilder();
                    Stack<String> blockStack = new Stack<>();
                    if( line.contains("{") ) {
                        blockStack.push("{");
                    }
                    i++; // Move to the next line, which should be the start of the macro body
                    while (i < strippedSourceCode.size()) {
                        String macroLine = strippedSourceCode.get(i).trim();

                        if (macroLine.contains("{")) {
                            blockStack.push("{");
                        } else if (macroLine.contains("}")) {
                            if (blockStack.isEmpty()) {
                                throw new IllegalArgumentException("Unmatched parenthesis: " + macroLine );
                            } else {
                                blockStack.pop();
                            }
                        }

                        for ( int k = 0; k < parameterList.length; ++k) {
                            macroLine = macroLine.replaceAll("\\b" + parameterList[k].trim() + "\\b", "%%"+k);
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
                        String currentLine = strippedSourceCode.get(j).trim();
                        if (currentLine.startsWith(macroName + "(") && currentLine.endsWith(")")) {
                            String arguments = currentLine.substring(currentLine.indexOf('(') + 1, currentLine.indexOf(')')).trim();
                            String[] argumentList = arguments.split(",");
                            if( argumentList.length == parameterList.length ) {
                                String expandedMacro = macroDefinition;
                                for (int k = 0; k < argumentList.length; k++) {
                                    expandedMacro = expandedMacro.replaceAll("%%"+k, argumentList[k].trim());
                                }

                                strippedSourceCode.set(j, expandedMacro);
                            }
                        }
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

        for( String line : sourceCode ) {
            String[] lines = line.split("\n");
            for( String newLine : lines ) {
                output.add(newLine);
            }
        }
        return output;
    }

    public static List<String> includeImports( List<String> sourceCode ) throws IllegalArgumentException {
        List<String> output = new ArrayList<>();

        for( String sourceLine : sourceCode ) {
            String line = sourceLine.trim();

            // Check if the line is an import statement
            if (line.startsWith("import") ) {
                if( line.contains("\"") && line.endsWith("\"") ) {
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
        List<String> output = new ArrayList<>();
        for (String line : sourceCode) {
            int commentIndex = line.indexOf("//");
            if (commentIndex != -1) {
                output.add(line.substring(0, commentIndex).trim());
            } else {       
                output.add(line);
            }
        }
        return output;
    }

    public static List<Integer> compile(List<String> sourceCode)  throws IllegalArgumentException {
        List<String> preprocessedCode = preProcess( sourceCode );
        // List to store the bytecode
        List<Integer> bytecode = new ArrayList<>();
        // Map to store variable names and their indices in the bytecode
        Map<String, Integer> identifiers = new HashMap<>();

        Map<Integer,String> forwardIdentifiers = new HashMap<>();

        Stack<StackEntry> blockStack = new Stack<>();

        boolean first = true;

        // Iterate through the high-level program and generate bytecode
        for (String line : preprocessedCode) {

            int commentIndex = line.indexOf("//");
            if (commentIndex != -1) {
                line = line.substring(0, commentIndex).trim();
            }
            // Collapse multiple consecutive whitespaces
            line = line.replaceAll("\\s+", " ").trim();

            String strippedLine = line;

            int colonIndex = line.indexOf(":");
            if(colonIndex != -1 ) {
                String[] supertokens = line.split(":");
                if( supertokens.length > 2 ) {
                    throw new IllegalArgumentException( "Only one label per line allowed: " + line );
                } else if( identifiers.containsKey(supertokens[0]) ) {
                    throw new IllegalArgumentException( "Label is already defined elsewhere: " + line );
                } else {
                    identifiers.put(supertokens[0], bytecode.size());
                }
                strippedLine = line.substring(colonIndex+1).trim();
            }

            String[] tokens = strippedLine.split(" ");
            String command = tokens[0];
            if( ("").equals(command) ) {
                // do nothing
            } else if( command.matches("\\d+") ) {
                bytecode.add( Integer.parseInt(command) );
                first = false;
            } else if( first ) {
                forwardIdentifiers.put(0,command);
                bytecode.add(null);
                first = false;
            } else {
                switch (command) {
                    case "HALT":
                        bytecode.add(HALT); // HALT command
                        break;
                    case "NOP":
                        bytecode.add(NOP); // NOP command
                        break;
                    case "INC":
                        if (tokens.length < 2) {
                            throw new IllegalArgumentException("Invalid command or missing arguments: " + line);
                        }
                        bytecode.add(INC); // INC command
                        if( tokens[1].matches("\\d+") ) {
                            bytecode.add( Integer.parseInt(tokens[1]) );
                        } else if (!identifiers.containsKey(tokens[1])) {
                            throw new IllegalArgumentException("Undefined variable: " + tokens[1]);
                        } else {
                            bytecode.add(identifiers.get(tokens[1])); // address
                        }
                        break;
                    case "DEC":
                        if (tokens.length < 2) {
                            throw new IllegalArgumentException("Invalid command or missing arguments: " + line);
                        }
                        bytecode.add(DEC); // DEC command
                        if( tokens[1].matches("\\d+") ) {
                            bytecode.add( Integer.parseInt(tokens[1]) );
                        } else if (!identifiers.containsKey(tokens[1])) {
                            throw new IllegalArgumentException("Undefined variable: " + tokens[1]);
                        } else {
                            bytecode.add(identifiers.get(tokens[1])); // address
                        }
                        break;
                    case "JNZ":
                        if (tokens.length < 3) {
                            throw new IllegalArgumentException("Invalid command or missing arguments: " + line);
                        }
                        bytecode.add(JNZ); // JNZ command
                        if( tokens[1].matches("\\d+") ) {
                            bytecode.add( Integer.parseInt(tokens[1]) );
                        } else if (!identifiers.containsKey(tokens[1])) {
                            throw new IllegalArgumentException("Undefined variable: " + tokens[1]);
                        } else {
                            bytecode.add(identifiers.get(tokens[1])); // address
                        }
                        if( tokens[2].matches("\\d+") ) {
                            bytecode.add( Integer.parseInt(tokens[2]) );
                        } else if (!identifiers.containsKey(tokens[2])) {
                            forwardIdentifiers.put(bytecode.size(),tokens[2]);
                            bytecode.add(null);
                        } else {
                            bytecode.add(identifiers.get(tokens[2])); // address
                        }
                        break;
                    case "do":
                        if (tokens.length != 2) {
                            throw new IllegalArgumentException("do must be followed by {: " + line);
                        }
                        blockStack.push(new StackEntry(bytecode.size(),"do"));
                        break;
                    case "}":
                        if( blockStack.empty() ) {
                            throw new IllegalArgumentException("unmatched }:" + line );
                        }
                        if( tokens.length > 1 ) {
                            StackEntry item = blockStack.peek();
                            if( ("do").equals(item.type) ) {
                                int variableIndex = strippedLine.indexOf("} while(");
                                if (variableIndex != -1) {
                                    String condition = strippedLine.substring(variableIndex+("} while(").length()).trim();
                                    String[] conditionTokens = condition.split("!");
                                    bytecode.add(JNZ);
                                    bytecode.add(identifiers.get(conditionTokens[0].trim()));
                                    bytecode.add(item.index);
                                } else {
                                    throw new IllegalArgumentException("After } only while( is allowed: " + line);
                                }
                            }
                        }
                        blockStack.pop();
                        break;
                    default:
                        throw new IllegalArgumentException("Invalid command: " + command);
                }
            }
        }

        for( var entry : forwardIdentifiers.entrySet() ) {
            final int i = entry.getKey();
            final String label = entry.getValue();
            if( !identifiers.containsKey(label) ) {
                throw new IllegalArgumentException("Undefined label: " + label);
            } else {
                bytecode.set(i, identifiers.get( label ) );
            }
        }

        return bytecode;
    }
}

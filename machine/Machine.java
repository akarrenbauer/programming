package machine;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.Collection;
import java.util.ArrayList;
import java.util.List;

/**
 * This class provides a simulator for a basic universial random access machine.
 * 
 * @author Andreas Karrenbauer 
 * @version 20230904
 */
public class Machine
{
    // instance variables 
    private ArrayList<Integer> S;
    private final String[] symbols  = { "HALT", "NOP",  "INC",  "DEC",     "JNZ" };
    private final int[] arguments   = {      0,     0,      1,      1,         2 };
    private final String[][] prefix = {     {},    {}, {"S["}, {"S["}, {"S[",""} };
    private final String[][] suffix = {     {},    {},  {"]"},  {"]"},  {"]",""} };
    private int verbosity;
    private String filename;

    private int stepCounter = 0;

    /**
     * Constructor for objects of class Machine
     * 
     * Yields a machine with empty memory
     */
    public Machine()
    {
        reset();
        verbosity = 0;
    }

    public void reset() {
        S = new ArrayList<Integer>();
        stepCounter = 0;
    }

    /**
     * Returns the value stored at the given index 
     * 
     * @param  index    the index of the memory cell
     * @return          the value stored at the specified index
     */
    public int get(int index) {
        return S.size() > index ? S.get(index) : 0;
    }

    /**
     * Sets the content of a specified memory cell
     * 
     * @param  index    the 0-based index of the memory cell
     * @param  value    the new content for the specified cell 
     */
    public void set(int index, int value)
    {
        if( index < S.size()) {
            S.set(index, value);
        } else {
            for( int i = S.size(); i < index; ++i ) {
                S.add(0);
            }
            S.add(value);
        }
    }

    /**
     * Returns the instruction pointer 
     * 
     * @return          the index of the cell containing the next instruction
     */
    public int getInstructionPointer() {
        return get(0);
    }

    /**
     * Returns the next instruction
     * 
     * @return          the opcode of the next instruction
     */
    public int getInstruction() {
        return get(getInstructionPointer());
    }

    /**
     * Returns whether the next instruction is HALT
     * 
     * @return          true if the next instruction is HALT, false otherwise
     */
    public boolean isHalted() {
        return getInstruction() == 0;
    }

    /**
     * Returns the next instruction
     * 
     * @return          the symbol of the next instruction
     */
    public String getInstructionSymbol() {
        int opcode = getInstruction();
        return 0 <= opcode && opcode < symbols.length ? symbols[opcode] : "";
    }

    /**
     * Returns the next instruction with arguments
     * 
     * @return          the symbol of the next instruction and its arguments
     */
    public String getInstructionSymbolAndArguments() {
        int opcode = getInstruction();
        if( 0 <= opcode && opcode < symbols.length ) {
            String ret = symbols[opcode];
            final int i = getInstructionPointer();
            for( int l = 0; l < arguments[opcode]; ++l ) {
                final int j = i + 1 + l;
                ret += " " + prefix[opcode][l] + get(j) + suffix[opcode][l];
            }
            return ret;
        }
        return "";
    }

    /**
     * Returns the number of arguments of the next instruction
     * 
     * @return          the symbol of the next instruction and its arguments
     */
    public int getNumberOfArguments() {
        int opcode = getInstruction();
        return 0 <= opcode && opcode < arguments.length ? arguments[opcode] : 0;
    }

    /**
     * Starts execution with the current state of the memory
     */
    public void run() {
        if(verbosity > 0) { 
            System.out.println( "*** Speicherzustand vor dem Start ***" );
            print();
        }

        for( int iteration = 1; getInstruction() != 0; ++iteration ) {
            step();
            if(verbosity > 0) { 
                System.out.println( "*** Speicherzustand nach Schritt " + iteration + " ***" );
                print();
            }
        }
    }

    /**
     * Executes the current instruction
     */
    public void step() {    
        int i = get(0);
        int opcode = get(i);
        if(verbosity > 0) {
            System.out.print( getInstructionSymbol() );
        }
        switch(opcode) {
            case 0: return;
            case 1: set(0,++i);
                break;
            case 2: {
                    int j = get(++i);
                    if(verbosity > 0 ) {
                        System.out.print(" S[" + j + "]");
                    }
                    set(j,get(j)+1);
                    set(0,++i);
                    break;
                }
            case 3: {
                    int j = get(++i);
                    if(verbosity > 0 ) {
                        System.out.print(" S[" + j + "]");
                    }
                    set(j,Math.max(0,S.get(j)-1));
                    set(0,++i);
                    break;
                }
            case 4: {
                    int j = get(++i);
                    int k = get(++i);
                    if(verbosity > 0 ) {
                        System.out.print(" S[" + j + "] " + k);
                    }
                    set(0, get(j) != 0 ? k : ++i);
                    break;
                }
        }

        ++stepCounter;

        if(verbosity > 0 ) {
            System.out.println();
        }
    }   

    /**
     * Executes the next n instructions
     */
    public void step(int n) {
        for( int i = 0; i < n && getInstruction() != 0; ++i ) {
            step();
        }
    }

    /**
     * Prints the content of the memory
     */
    public void print() {
        int i = get(0);
        int size = S.size();
        String last = "" + Math.max(0,Math.max(i,size-1));
        String format = "%" + last.length() + "." + last.length() + "s";

        for(int c = 0; c < 6 + last.length(); ++ c) {
            System.out.print("-");
        }
        System.out.println();

        System.out.print( i > 0 ? " /-- " : "     " );
        System.out.format( format, 0 );
        System.out.println( ": " + i );

        for( int line = 1; line < i; ++line ) {
            System.out.print( " |   " );
            System.out.format( format, line );
            System.out.println( ": " + get(line) );
        }

        if( i > 0 ) {
            System.out.print( " \\-> " );
            System.out.format( format, i );
            System.out.println( ": " + get(i) );
        }

        for( int line = i+1; line < size; ++line ) {
            System.out.print( "     " );
            System.out.format( format, line );
            System.out.println( ": " + S.get(line) );
        }

        for(int c = 0; c < 6 + last.length(); ++ c) {
            System.out.print("-");
        }
        System.out.println();
    }

    /**
     * Gets the file name
     */
    public String getFilename() {
        return filename;
    }

    /**
     * Loads from memory
     * 
     * @param list  List containing the Bytecode
     */
    public void load( List<Integer> list ) {
        S = (ArrayList<Integer>) list;
        stepCounter = 0;
    }

    /**
     * Reads the memory from file
     * 
     * @param filename  name of a text file where each line starts with a non-negative
     *                  integer
     */
    public void load(String filename) {
        stepCounter = 0;
        this.filename = filename;
        S = new ArrayList<Integer>();
        try (BufferedReader br = new BufferedReader(new FileReader(filename))) {
            String line;
            while ((line = br.readLine()) != null) {
                Integer number = getStartingInteger(line);
                if (number != null) {
                    S.add(number);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Re-reads the memory from file
     * 
     * @param filename  name of a text file where each line starts with a non-negative
     *                  integer
     */
    public void reload()
    {
        if( filename != null ) {
            load(filename);
        }
    }

    /**
     * Extracts an integer at the beginning of a line
     * 
     * @param  line a string 
     * @return      an integer or null if line did not start with an integer         
     */
    private Integer getStartingInteger(String line) {
        StringBuilder sb = new StringBuilder();
        for (char c : line.toCharArray()) {
            if (Character.isDigit(c)) {
                sb.append(c);
            } else {
                break;
            }
        }

        try {
            return Integer.parseInt(sb.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Sets the verbosity level
     * 
     * @param  level    the new verbosity level
     */
    public void setVerbosity( int level ) {
        verbosity = level;
    }

    /**
     * Returns the amount of memory used.
     * 
     * @return the used memory
     */
    public int getMemorySize() {
        return S.size();
    }

    /**
     * Returns the current step counter.
     * 
     * @return the current step counter
     */
    public int getStepCounter() {
        return stepCounter;
    }
}

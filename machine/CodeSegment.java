package machine;

import java.util.LinkedHashSet;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Write a description of class CodeSegment here.
 *
 * @author (your name)
 * @version (a version number or a date)
 */
public class CodeSegment extends Segment
{
    private final Set<String> unresolvedLabels = new LinkedHashSet<>();
    private final Map<Integer, Integer> instructionSourceLines = new LinkedHashMap<>();
    private int sourceLine;

    int getSourceLine() {
        return sourceLine;
    }

    void setSourceLine(int sourceLine) {
        this.sourceLine = sourceLine;
    }

    Map<Integer, Integer> getInstructionSourceLines(int offset) {
        Map<Integer, Integer> absoluteLines = new LinkedHashMap<>();
        instructionSourceLines.forEach((address, line) -> absoluteLines.put(address + offset, line));
        return Collections.unmodifiableMap(absoluteLines);
    }

    private void addInstruction(Number opcode) {
        if (sourceLine > 0) {
            instructionSourceLines.put(size(), sourceLine);
        }
        add(opcode);
    }

    public void addHALT() {
        addInstruction(Number.HALT);
    }

    public void addNOP() {
        addInstruction(Number.NOP);
    }

    public void addINC( Number arg ) {
        addInstruction(Number.INC);
        add(arg);
    }

    public void addDEC( Number arg ) {
        addInstruction(Number.DEC);
        add(arg);
    }

    public void addJNZ( Number test, Number target ) {
        addInstruction(Number.JNZ);
        add(test);
        add(target);
    }
    
    public void addClear(Number arg) {
        Number loop = newLabel();
        addDEC(arg);
        addJNZ(arg, loop);
    }

    public Number newLabel() {
        return newEntry(size());
    }

    public Number newLabel(String id) {
        unresolvedLabels.add(id);
        return newEntry(id);
    }

    public Number getLabel(String id) {
        Number label = getEntry(id);
        return label != null ? label : newLabel(id);
    }

    public void placeLabel(Number label) {
        label.setValue(size());
        unresolvedLabels.removeIf(id -> getEntry(id) == label);
    }

    public void placeLabel(String id) {
        final int address = size();
        if( identifiers.containsKey(id) ) {
            identifiers.get(id).setValue(address);
        } else {
            identifiers.put(id, new Number(address) );
        }
        unresolvedLabels.remove(id);
    }

    public void validateLabels() throws ParseException {
        if( !unresolvedLabels.isEmpty() ) {
            throw new ParseException("Unresolved labels: " + String.join(", ", unresolvedLabels));
        }
    }
    
        public void applyOffset( int offset ) {
        for( var id : identifiers.values() ) {
            id.setValue( offset + id.getValue() );
        }
        for( var target : anonymous ) {
            target.setValue( offset + target.getValue() );
        }
    }
}

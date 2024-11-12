package machine;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * Write a description of class CodeSegment here.
 *
 * @author (your name)
 * @version (a version number or a date)
 */
public class CodeSegment extends Segment
{
    public void addINC( Number arg ) {
        add(Number.INC);
        add(arg);
    }

    public void addDEC( Number arg ) {
        add(Number.DEC);
        add(arg);
    }

    public void addJNZ( Number test, Number target ) {
        add(Number.JNZ);
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
        return newEntry(id);
    }

    public Number getLabel(String id) {
       return getEntry(id);
    }

    public void placeLabel(Number label) {
        label.setValue(size());
    }

    public void placeLabel(String id) {
        final int address = size();
        if( identifiers.containsKey(id) ) {
            identifiers.get(id).setValue(address);
        } else {
            identifiers.put(id, new Number(address) );
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

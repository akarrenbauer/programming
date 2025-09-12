package machine;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * Write a description of class DataSegment here.
 *
 * @author (your name)
 * @version (a version number or a date)
 */
public class DataSegment extends Segment
{
    public Number newVariable(int value) {
        Number anon = new Number(value);
        Number address = new Number(size());
        add(anon);
        anonymous.add(address);
        return address;
    }

    public Number newVariable(String id, int length, int value) {
        Number variable = new Number(value);
        Number address = new Number(size());
        for( int i = 0; i < length; ++i ) {
            add(variable);
        }
        identifiers.put(id, address);
        return address;
    }

    public Number newVariable(String id, int length, String s) {
        return newVariable(id, length, Integer.parseInt(s));
    }

    public Number newVariable(String id, String s) {
        return newVariable(id, 1, Integer.parseInt(s));
    }

    public Number getVariable(String id) {
        return getEntry(id);
    }

    public Number getVariable(String id, int offset) {
        return getEntry(id, offset);
    }
}

package machine;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * Write a description of class Segment here.
 *
 * @author (your name)
 * @version (a version number or a date)
 */
public class Segment extends ArrayList<Number>
{
    protected Map<String, Number> identifiers = new HashMap<>();
    protected List<Number> anonymous = new ArrayList<>();

    public Number newEntry(int value) {
        Number anon = new Number(value);
        anonymous.add(anon);
        return anon;
    }

    public Number newEntry() {
        return newEntry(0);
    }

    public Number newEntry(String id) {
        Number address = new Number();
        identifiers.put(id, address);
        return address;
    }

    public Number getEntry(String id) {
        if( identifiers.containsKey(id) ) {
            return identifiers.get(id);
        } else {
            return newEntry(id);
        }
    }

    public void placeEntry(Number label) {
        label.setValue(size());
    }

    public void placeEntry(String id) {
        final int address = size();
        if( identifiers.containsKey(id) ) {
            identifiers.get(id).setValue(address);
        } else {
            identifiers.put(id, new Number(address) );
        }
    }
}

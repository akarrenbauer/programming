package machine;

class Number {
    public static final Number HALT = new Number(0);
    public static final Number NOP = new Number(1);
    public static final Number INC = new Number(2);
    public static final Number DEC = new Number(3);
    public static final Number JNZ = new Number(4);

    private int value;
    Number() {
        this.value = 0;
    }

    Number( int value ) {
        this.value = value;
    }

    Number( String s ) {
        this.value = Integer.parseInt(s);
    }

    int getValue() {
        return value;
    }

    void setValue( int value ) {
        this.value = value;
    }

    void setValue( String s ) {
        this.value = Integer.parseInt(s);
    }
}

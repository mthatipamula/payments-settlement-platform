package io.paysettle.ach.nacha;

/** The file violates the NACHA format or its control totals; the whole file is rejected. */
public class NachaParseException extends RuntimeException {

    private final int line;

    public NachaParseException(int line, String message) {
        super(line > 0 ? "Line " + line + ": " + message : message);
        this.line = line;
    }

    public int line() {
        return line;
    }
}

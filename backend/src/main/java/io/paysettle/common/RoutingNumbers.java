package io.paysettle.common;

/**
 * ABA routing transit numbers: 9 digits whose weighted sum (weights 3,7,1 repeating) is divisible by 10.
 * The 9th digit is the check digit (NACHA calls it the "Check Digit" of the RDFI identification).
 */
public final class RoutingNumbers {

    private static final int[] WEIGHTS = {3, 7, 1, 3, 7, 1, 3, 7, 1};

    private RoutingNumbers() {
    }

    public static boolean isValid(String routing) {
        if (routing == null || !routing.matches("\\d{9}")) {
            return false;
        }
        int sum = 0;
        for (int i = 0; i < 9; i++) {
            sum += (routing.charAt(i) - '0') * WEIGHTS[i];
        }
        return sum % 10 == 0;
    }

    /** Check digit for an 8-digit routing prefix (as carried separately in NACHA entry detail records). */
    public static int checkDigit(String first8) {
        int sum = 0;
        for (int i = 0; i < 8; i++) {
            sum += (first8.charAt(i) - '0') * WEIGHTS[i];
        }
        return (10 - sum % 10) % 10;
    }
}

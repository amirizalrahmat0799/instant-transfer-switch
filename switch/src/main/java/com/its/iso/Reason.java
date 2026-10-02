package com.its.iso;

/** ISO 20022 external status reason codes used by the switch. */
public final class Reason {

    public static final String INCORRECT_ACCOUNT = "AC01";
    public static final String CLOSED_ACCOUNT = "AC04";
    public static final String INSUFFICIENT_FUNDS = "AM04"; // here: the sending bank's net debit cap is used up
    public static final String TIMEOUT_CREDITOR_AGENT = "AB05";
    public static final String OFFLINE_CREDITOR_AGENT = "AB08";
    public static final String INVALID_FORMAT = "FF01";
    public static final String UNKNOWN_BANK = "RC01";

    private Reason() {
    }

    public static String describe(String code) {
        if (code == null) {
            return "";
        }
        return switch (code) {
            case INCORRECT_ACCOUNT -> "Incorrect account number";
            case CLOSED_ACCOUNT -> "Account closed";
            case INSUFFICIENT_FUNDS -> "Net debit cap reached";
            case TIMEOUT_CREDITOR_AGENT -> "Receiving bank timed out";
            case OFFLINE_CREDITOR_AGENT -> "Receiving bank offline";
            case INVALID_FORMAT -> "Invalid message";
            case UNKNOWN_BANK -> "Unknown bank";
            default -> code;
        };
    }
}

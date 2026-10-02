package com.its.participant;

/**
 * A bank connected to the switch.
 *
 * @param netDebitCap the most (in sen) this bank may owe the others within one settlement cycle
 */
public record Participant(String bic, String name, String baseUrl, String apiKey, long netDebitCap) {

    public Participant {
        if (bic == null || !bic.matches("[A-Z]{6}[A-Z0-9]{2}")) {
            throw new IllegalStateException("Invalid BIC: " + bic);
        }
        if (baseUrl == null || apiKey == null || apiKey.length() < 8) {
            throw new IllegalStateException("Participant " + bic + " needs a base-url and an api-key of 8+ characters");
        }
    }

    /** Safe to show in responses and logs. */
    public record View(String bic, String name, long netDebitCap) {
    }

    public View view() {
        return new View(bic, name, netDebitCap);
    }
}

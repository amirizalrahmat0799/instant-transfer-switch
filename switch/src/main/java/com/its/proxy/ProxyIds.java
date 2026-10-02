package com.its.proxy;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Normalises DuitNow-style proxy IDs so the same person is found however the number is typed,
 * and masks account names for the payer's confirmation screen.
 */
public final class ProxyIds {

    public enum Type { MOBILE, NRIC, BUSINESS }

    private static final Pattern MY_MOBILE = Pattern.compile("601\\d{8,9}");
    private static final Pattern NRIC = Pattern.compile("\\d{12}");
    private static final Pattern BUSINESS = Pattern.compile("[A-Z0-9]{6,20}");

    private ProxyIds() {
    }

    public static Type type(String raw) {
        try {
            return Type.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Proxy type must be MOBILE, NRIC or BUSINESS");
        }
    }

    /** "012-345 6701", "+60123456701" and "60123456701" all become "+60123456701". */
    public static String normalize(Type type, String raw) {
        String v = raw == null ? "" : raw.trim();
        return switch (type) {
            case MOBILE -> {
                String digits = v.replaceAll("[\\s\\-+()]", "");
                if (digits.startsWith("0")) {
                    digits = "6" + digits;
                }
                if (!MY_MOBILE.matcher(digits).matches()) {
                    throw new IllegalArgumentException("Not a Malaysian mobile number: " + raw);
                }
                yield "+" + digits;
            }
            case NRIC -> {
                String digits = v.replaceAll("[\\s\\-]", "");
                if (!NRIC.matcher(digits).matches()) {
                    throw new IllegalArgumentException("NRIC must have 12 digits");
                }
                yield digits;
            }
            case BUSINESS -> {
                String id = v.replaceAll("[\\s\\-]", "").toUpperCase(Locale.ROOT);
                if (!BUSINESS.matcher(id).matches()) {
                    throw new IllegalArgumentException("Business registration number must be 6-20 letters or digits");
                }
                yield id;
            }
        };
    }

    /** "Hafiz Ismail" becomes "HAFIZ I*****": enough to confirm the recipient, not enough to read the full name. */
    public static String mask(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        String[] words = name.trim().toUpperCase(Locale.ROOT).split("\\s+");
        StringBuilder out = new StringBuilder(words[0]);
        for (int i = 1; i < words.length; i++) {
            out.append(' ').append(words[i].charAt(0)).append("*".repeat(words[i].length() - 1));
        }
        return out.toString();
    }
}

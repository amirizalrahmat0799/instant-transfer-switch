package com.its.iso;

import java.util.regex.Pattern;

/** ISO 20022 decimal amounts ("150.50") to and from integer sen. Money never goes through floating point. */
public final class Money {

    private static final Pattern AMOUNT = Pattern.compile("\\d{1,13}(\\.\\d{1,2})?");

    private Money() {
    }

    public static long parse(String text) {
        String s = text == null ? "" : text.trim();
        if (!AMOUNT.matcher(s).matches()) {
            throw new IsoFormatException("Invalid amount: " + text);
        }
        int dot = s.indexOf('.');
        if (dot < 0) {
            return Math.multiplyExact(Long.parseLong(s), 100L);
        }
        String frac = (s.substring(dot + 1) + "0").substring(0, 2);
        return Math.addExact(Math.multiplyExact(Long.parseLong(s.substring(0, dot)), 100L), Long.parseLong(frac));
    }

    public static String format(long sen) {
        return (sen / 100) + "." + String.format("%02d", Math.abs(sen % 100));
    }
}

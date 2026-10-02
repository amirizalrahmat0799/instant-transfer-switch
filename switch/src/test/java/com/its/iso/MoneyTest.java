package com.its.iso;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void parsesDecimalAmountsIntoSen() {
        assertEquals(12550, Money.parse("125.50"));
        assertEquals(12550, Money.parse("125.5"));
        assertEquals(12500, Money.parse("125"));
        assertEquals(1, Money.parse("0.01"));
        assertEquals(1, Money.parse(" 0.01 "));
    }

    @Test
    void formatsSenAsIsoDecimal() {
        assertEquals("125.50", Money.format(12550));
        assertEquals("0.05", Money.format(5));
        assertEquals("1000000.00", Money.format(100_000_000));
    }

    @Test
    void rejectsAmountsThatAreNotPlainDecimals() {
        for (String bad : new String[] {"", "-1.00", "1.001", "1e3", "1,000.00", "abc", "12345678901234.00"}) {
            assertThrows(IsoFormatException.class, () -> Money.parse(bad), bad);
        }
        assertThrows(IsoFormatException.class, () -> Money.parse(null));
    }
}

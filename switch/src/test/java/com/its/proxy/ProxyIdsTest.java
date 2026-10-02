package com.its.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.its.proxy.ProxyIds.Type;

class ProxyIdsTest {

    @Test
    void mobileNumbersNormaliseToOneForm() {
        for (String typed : new String[] {"0123456701", "012-345 6701", "+60123456701", "60123456701", "(012) 345-6701"}) {
            assertEquals("+60123456701", ProxyIds.normalize(Type.MOBILE, typed), typed);
        }
        assertEquals("+601123456789", ProxyIds.normalize(Type.MOBILE, "011-2345 6789"));
    }

    @Test
    void rejectsNonMalaysianMobiles() {
        assertThrows(IllegalArgumentException.class, () -> ProxyIds.normalize(Type.MOBILE, "+6591234567"));
        assertThrows(IllegalArgumentException.class, () -> ProxyIds.normalize(Type.MOBILE, "012345"));
        assertThrows(IllegalArgumentException.class, () -> ProxyIds.normalize(Type.MOBILE, null));
    }

    @Test
    void nricAndBusinessIds() {
        assertEquals("990101145678", ProxyIds.normalize(Type.NRIC, "990101-14-5678"));
        assertThrows(IllegalArgumentException.class, () -> ProxyIds.normalize(Type.NRIC, "9901011456"));
        assertEquals("202301012345", ProxyIds.normalize(Type.BUSINESS, "2023 0101 2345"));
        assertEquals("SA0123456X", ProxyIds.normalize(Type.BUSINESS, "sa0123456-x"));
    }

    @Test
    void typesAreCaseInsensitive() {
        assertEquals(Type.MOBILE, ProxyIds.type("mobile"));
        assertThrows(IllegalArgumentException.class, () -> ProxyIds.type("EMAIL"));
    }

    @Test
    void namesAreMaskedForConfirmation() {
        assertEquals("HAFIZ I*****", ProxyIds.mask("Hafiz Ismail"));
        assertEquals("NUR A***** B**** R*****", ProxyIds.mask("  Nur Aisyah binti Rahman "));
        assertEquals("CHONG", ProxyIds.mask("Chong"));
        assertEquals("", ProxyIds.mask(" "));
    }
}

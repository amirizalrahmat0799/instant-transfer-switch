package com.its.iso;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/** The fixtures were produced by the Go bank simulator, so these tests double as an interop check. */
class IsoXmlTest {

    private static String fixture(String name) throws IOException {
        try (InputStream in = IsoXmlTest.class.getResourceAsStream("/iso/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void parsesAPacs008FromTheGoBank() throws IOException {
        Pacs008 msg = IsoXml.parsePacs008(fixture("pacs008.xml"));
        assertEquals("ALFAMYKL2026100288639744A0200854", msg.msgId());
        assertEquals("E2E20261002243BA6AA7ECB0F74", msg.endToEndId());
        assertEquals(12550, msg.amount());
        assertEquals("MYR", msg.currency());
        assertEquals("ALFAMYKL", msg.debtorBic());
        assertEquals("1100000001", msg.debtorAccount());
        assertEquals("BRVOMYKL", msg.creditorBic());
        assertEquals("2200000001", msg.creditorAccount());
        assertEquals("Hafiz Ismail", msg.creditorName());
        assertEquals("Lunch & kopi", msg.remittance());
    }

    @Test
    void parsesACamt029Resolution() throws IOException {
        assertEquals("CNCL", IsoXml.parseCamt029Status(fixture("camt029.xml")));
    }

    @Test
    void pacs002RoundTrips() throws IOException {
        Pacs008 msg = IsoXml.parsePacs008(fixture("pacs008.xml"));

        StatusReport ok = IsoXml.parsePacs002(IsoXml.writePacs002(StatusReport.accepted(msg), msg.msgId(), "ITSWMYKL"));
        assertTrue(ok.accepted());
        assertEquals(msg.endToEndId(), ok.originalEndToEndId());
        assertNull(ok.reasonCode());

        StatusReport rejected = StatusReport.rejected(msg, Reason.INCORRECT_ACCOUNT, "No such account <2200000001>");
        StatusReport back = IsoXml.parsePacs002(IsoXml.writePacs002(rejected, msg.msgId(), "ITSWMYKL"));
        assertEquals(StatusReport.REJECTED, back.status());
        assertEquals("AC01", back.reasonCode());
        assertEquals("No such account <2200000001>", back.info());
    }

    @Test
    void camt056CarriesTheOriginalTransfer() {
        String xml = IsoXml.writeCamt056("ITSWMYKL", "E2E1", "TX1", 12550, Reason.TIMEOUT_CREDITOR_AGENT);
        assertTrue(xml.contains(IsoXml.NS_CAMT_056));
        assertTrue(xml.contains("<OrgnlEndToEndId>E2E1</OrgnlEndToEndId>"));
        assertTrue(xml.contains("<OrgnlIntrBkSttlmAmt Ccy=\"MYR\">125.50</OrgnlIntrBkSttlmAmt>"));
        assertTrue(xml.contains("<Cd>AB05</Cd>"));
    }

    @Test
    void rejectsTheWrongMessageType() throws IOException {
        assertThrows(IsoFormatException.class, () -> IsoXml.parsePacs008(fixture("camt029.xml")));
        assertThrows(IsoFormatException.class, () -> IsoXml.parsePacs008(""));
        assertThrows(IsoFormatException.class, () -> IsoXml.parsePacs008("<not-xml"));
    }

    @Test
    void rejectsOtherCurrenciesAndZeroAmounts() throws IOException {
        String xml = fixture("pacs008.xml");
        assertThrows(IsoFormatException.class, () -> IsoXml.parsePacs008(xml.replace("Ccy=\"MYR\"", "Ccy=\"USD\"")));
        assertThrows(IsoFormatException.class, () -> IsoXml.parsePacs008(xml.replace(">125.50<", ">0.00<")));
    }

    @Test
    void refusesExternalEntities() throws IOException {
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE d [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
            + fixture("pacs008.xml").replaceFirst("<\\?xml[^>]*\\?>", "").replace("Aisyah Rahman", "&x;");
        assertThrows(IsoFormatException.class, () -> IsoXml.parsePacs008(xxe));
    }

    @Test
    void newIdsFitTheIsoLimit() {
        String id = IsoXml.newId("ITSWMYKL");
        assertTrue(id.startsWith("ITSWMYKL"));
        assertEquals(35, id.length());
    }
}

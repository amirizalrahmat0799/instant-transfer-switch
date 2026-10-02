package com.its.iso;

import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Reads and writes the ISO 20022 messages the switch exchanges, using only the JDK's XML APIs.
 * Parsing is namespace-aware and hardened against XXE (no DTDs, no external entities).
 */
public final class IsoXml {

    public static final String NS_PACS_008 = "urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08";
    public static final String NS_PACS_002 = "urn:iso:std:iso:20022:tech:xsd:pacs.002.001.10";
    public static final String NS_CAMT_056 = "urn:iso:std:iso:20022:tech:xsd:camt.056.001.08";
    public static final String NS_CAMT_029 = "urn:iso:std:iso:20022:tech:xsd:camt.029.001.09";

    private static final ZoneOffset MYT = ZoneOffset.ofHours(8);
    private static final DateTimeFormatter ISO_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");

    private IsoXml() {
    }

    // -----------------------------------------------------------------------------------------
    // Parsing
    // -----------------------------------------------------------------------------------------

    public static Pacs008 parsePacs008(String xml) {
        Element root = parse(xml, NS_PACS_008);
        Element tx = child(child(root, "FIToFICstmrCdtTrf"), "CdtTrfTxInf");
        Element header = child(child(root, "FIToFICstmrCdtTrf"), "GrpHdr");
        Element amount = child(tx, "IntrBkSttlmAmt");
        Pacs008 msg = new Pacs008(
            text(header, "MsgId"),
            text(tx, "PmtId", "EndToEndId"),
            text(tx, "PmtId", "TxId"),
            Money.parse(amount.getTextContent()),
            amount.getAttribute("Ccy"),
            optText(tx, "Dbtr", "Nm"),
            text(tx, "DbtrAcct", "Id", "Othr", "Id"),
            text(tx, "DbtrAgt", "FinInstnId", "BICFI"),
            text(tx, "CdtrAgt", "FinInstnId", "BICFI"),
            optText(tx, "Cdtr", "Nm"),
            text(tx, "CdtrAcct", "Id", "Othr", "Id"),
            optText(tx, "RmtInf", "Ustrd"));
        if (!"MYR".equals(msg.currency())) {
            throw new IsoFormatException("Unsupported currency: " + msg.currency());
        }
        if (msg.amount() <= 0) {
            throw new IsoFormatException("Amount must be positive");
        }
        if (msg.endToEndId().length() > 35 || msg.msgId().length() > 35) {
            throw new IsoFormatException("Identifiers are limited to 35 characters");
        }
        return msg;
    }

    public static StatusReport parsePacs002(String xml) {
        Element root = parse(xml, NS_PACS_002);
        Element tx = child(child(root, "FIToFIPmtStsRpt"), "TxInfAndSts");
        return new StatusReport(
            text(tx, "OrgnlEndToEndId"),
            optText(tx, "OrgnlTxId"),
            text(tx, "TxSts"),
            optText(tx, "StsRsnInf", "Rsn", "Cd"),
            optText(tx, "StsRsnInf", "AddtlInf"));
    }

    /** The cancellation status from a camt.029: CNCL (reversed) or RJCR (nothing to cancel). */
    public static String parseCamt029Status(String xml) {
        Element root = parse(xml, NS_CAMT_029);
        return text(root, "RsltnOfInvstgtn", "CxlDtls", "TxInfAndSts", "TxCxlSts");
    }

    // -----------------------------------------------------------------------------------------
    // Writing
    // -----------------------------------------------------------------------------------------

    public static String writePacs002(StatusReport st, String originalMsgId, String senderBic) {
        Document doc = newDocument();
        Element root = doc.createElementNS(NS_PACS_002, "Document");
        doc.appendChild(root);
        Element rpt = add(root, "FIToFIPmtStsRpt");
        Element hdr = add(rpt, "GrpHdr");
        add(hdr, "MsgId", newId(senderBic));
        add(hdr, "CreDtTm", now());
        Element orgnl = add(rpt, "OrgnlGrpInfAndSts");
        add(orgnl, "OrgnlMsgId", originalMsgId == null ? "" : originalMsgId);
        add(orgnl, "OrgnlMsgNmId", "pacs.008.001.08");
        Element tx = add(rpt, "TxInfAndSts");
        add(tx, "OrgnlEndToEndId", st.originalEndToEndId());
        add(tx, "OrgnlTxId", st.originalTxId() == null ? "" : st.originalTxId());
        add(tx, "TxSts", st.status());
        if (st.reasonCode() != null) {
            Element rsn = add(tx, "StsRsnInf");
            add(add(rsn, "Rsn"), "Cd", st.reasonCode());
            if (st.info() != null && !st.info().isBlank()) {
                add(rsn, "AddtlInf", st.info().length() > 105 ? st.info().substring(0, 105) : st.info());
            }
        }
        return serialize(doc);
    }

    /** A cancellation request for a transfer the switch timed out on. */
    public static String writeCamt056(String switchBic, String endToEndId, String txId, long amount, String reasonCode) {
        Document doc = newDocument();
        Element root = doc.createElementNS(NS_CAMT_056, "Document");
        doc.appendChild(root);
        Element req = add(root, "FIToFIPmtCxlReq");
        Element asg = add(req, "Assgnmt");
        add(asg, "Id", newId(switchBic));
        add(asg, "CreDtTm", now());
        Element tx = add(add(req, "Undrlyg"), "TxInf");
        add(tx, "OrgnlEndToEndId", endToEndId);
        add(tx, "OrgnlTxId", txId == null ? "" : txId);
        Element amt = add(tx, "OrgnlIntrBkSttlmAmt", Money.format(amount));
        amt.setAttribute("Ccy", "MYR");
        add(add(add(tx, "CxlRsnInf"), "Rsn"), "Cd", reasonCode);
        return serialize(doc);
    }

    // -----------------------------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------------------------

    public static String newId(String prefix) {
        String p = prefix == null ? "" : prefix;
        String id = p + OffsetDateTime.now(MYT).format(DateTimeFormatter.BASIC_ISO_DATE).substring(0, 8)
            + UUID.randomUUID().toString().replace("-", "").toUpperCase();
        return id.length() > 35 ? id.substring(0, 35) : id;
    }

    public static String now() {
        return OffsetDateTime.now(MYT).format(ISO_TIME);
    }

    private static Element parse(String xml, String namespace) {
        if (xml == null || xml.isBlank()) {
            throw new IsoFormatException("Empty message");
        }
        Document doc;
        try {
            DocumentBuilder builder = factory().newDocumentBuilder();
            builder.setErrorHandler(null);
            doc = builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IsoFormatException("Not well-formed XML", e);
        }
        Element root = doc.getDocumentElement();
        if (!"Document".equals(root.getLocalName()) || !namespace.equals(root.getNamespaceURI())) {
            throw new IsoFormatException("Expected a Document in namespace " + namespace);
        }
        return root;
    }

    private static DocumentBuilderFactory factory() throws ParserConfigurationException {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        return f;
    }

    private static Element child(Element parent, String localName) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && localName.equals(e.getLocalName())) {
                return e;
            }
        }
        throw new IsoFormatException("Missing element " + localName + " in " + parent.getLocalName());
    }

    private static String text(Element start, String... path) {
        Element e = start;
        for (String p : path) {
            e = child(e, p);
        }
        String v = e.getTextContent().trim();
        if (v.isEmpty()) {
            throw new IsoFormatException("Empty element " + path[path.length - 1]);
        }
        return v;
    }

    private static String optText(Element start, String... path) {
        try {
            Element e = start;
            for (String p : path) {
                e = child(e, p);
            }
            String v = e.getTextContent().trim();
            return v.isEmpty() ? null : v;
        } catch (IsoFormatException missing) {
            return null;
        }
    }

    private static Document newDocument() {
        try {
            return DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Element add(Element parent, String name) {
        Element e = parent.getOwnerDocument().createElementNS(parent.getNamespaceURI(), name);
        parent.appendChild(e);
        return e;
    }

    private static Element add(Element parent, String name, String text) {
        Element e = add(parent, name);
        e.setTextContent(text);
        return e;
    }

    private static String serialize(Document doc) {
        try {
            Transformer t = TransformerFactory.newInstance().newTransformer();
            t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            t.setOutputProperty(OutputKeys.INDENT, "yes");
            t.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
            StringWriter out = new StringWriter();
            t.transform(new DOMSource(doc), new StreamResult(out));
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Could not write XML", e);
        }
    }
}

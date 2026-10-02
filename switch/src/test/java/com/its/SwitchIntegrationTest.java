package com.its;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.its.iso.IsoXml;
import com.its.iso.Pacs008;
import com.its.iso.StatusReport;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The whole switch against a real PostgreSQL (started by Testcontainers) and a fake receiving bank (Bravo). Charlie
 * points at a closed port, so it is offline. Needs Docker (Docker Desktop on Windows and macOS).
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SwitchIntegrationTest {

    static final String UNKNOWN_ACCOUNT = "9999999999";
    static final String SLOW_ACCOUNT = "5555555555";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    static final HttpServer bravo = fakeBank();
    static final CountDownLatch cancellation = new CountDownLatch(1);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("its.creditor-timeout", () -> "1s");
        // Overriding one list element would replace the whole participants list, so set the placeholders it uses instead
        registry.add("BRAVO_URL", () -> "http://localhost:" + bravo.getAddress().getPort());
        registry.add("CHARLIE_URL", () -> "http://localhost:1");
    }

    @AfterAll
    static void stop() {
        bravo.stop(0);
    }

    @LocalServerPort
    int port;

    final HttpClient http = HttpClient.newHttpClient();

    @Test
    void proxyLookupFindsTheRegisteredAccount() throws Exception {
        String mobile = "01" + (10_000_000 + Math.floorMod(System.nanoTime(), 89_999_999L));
        var created = call("POST", "/api/v1/proxies", "BRVOMYKL", "bravo-dev-key", "application/json",
            "{\"type\":\"MOBILE\",\"value\":\"" + mobile + "\",\"accountNumber\":\"2200000001\",\"accountName\":\"Hafiz Ismail\"}");
        assertEquals(201, created.statusCode(), created.body());

        var found = call("GET", "/api/v1/proxies/MOBILE/" + mobile, "ALFAMYKL", "alfa-dev-key", null, null);
        assertEquals(200, found.statusCode(), found.body());
        assertTrue(found.body().contains("\"bic\":\"BRVOMYKL\""), found.body());
        assertTrue(found.body().contains("\"maskedName\":\"HAFIZ I*****\""), found.body());

        var stolen = call("POST", "/api/v1/proxies", "ALFAMYKL", "alfa-dev-key", "application/json",
            "{\"type\":\"MOBILE\",\"value\":\"" + mobile + "\",\"accountNumber\":\"1\",\"accountName\":\"Someone Else\"}");
        assertEquals(409, stolen.statusCode());

        assertEquals(401, call("GET", "/api/v1/proxies/MOBILE/" + mobile, "ALFAMYKL", "wrong", null, null).statusCode());
    }

    @Test
    void completedTransfersAreIdempotent() throws Exception {
        String xml = pacs008("BRVOMYKL", "2200000001", "125.50");
        var first = send(xml);
        assertEquals("ACSC", first.status());
        assertEquals(first, send(xml)); // a retry gets the same answer and moves no money twice
    }

    @Test
    void rejectionsCarryIsoReasonCodes() throws Exception {
        assertEquals("AC01", send(pacs008("BRVOMYKL", UNKNOWN_ACCOUNT, "10.00")).reasonCode());
        assertEquals("AB08", send(pacs008("CHRLMYKL", "3300000001", "10.00")).reasonCode());
        assertEquals("RC01", send(pacs008("ZZZZMYKL", "1", "10.00")).reasonCode());
        assertEquals("AM04", send(pacs008("BRVOMYKL", "2200000001", "1000001.00")).reasonCode());
    }

    @Test
    void aTimeoutIsRejectedAndThenCancelledAtTheReceiver() throws Exception {
        assertEquals("AB05", send(pacs008("BRVOMYKL", SLOW_ACCOUNT, "50.00")).reasonCode());
        assertTrue(cancellation.await(15, TimeUnit.SECONDS), "no camt.056 reached the receiving bank");
    }

    @Test
    void aBankCannotSendOnAnotherBanksBehalf() throws Exception {
        var res = call("POST", "/api/v1/iso/pacs.008", "BRVOMYKL", "bravo-dev-key", "application/xml",
            pacs008("CHRLMYKL", "3300000001", "1.00"));
        assertEquals(403, res.statusCode());
    }

    @Test
    void settlementNetsToZero() throws Exception {
        send(pacs008("BRVOMYKL", "2200000001", "20.00"));
        var res = call("POST", "/api/v1/settlement/close", null, null, null, null, "local-admin-key");
        assertEquals(200, res.statusCode(), res.body());
        assertTrue(res.body().contains("\"status\":\"CLOSED\""), res.body());
        // after a close, a new cycle is open and transfers keep flowing
        assertEquals("ACSC", send(pacs008("BRVOMYKL", "2200000001", "1.00")).status());
    }

    @Test
    void apiDocsDescribeTheEndpointsAndTheirHeaders() throws Exception {
        var docs = call("GET", "/v3/api-docs", null, null, null, null);
        assertEquals(200, docs.statusCode());
        assertTrue(docs.body().contains("\"title\":\"Instant Transfer Switch\""), docs.body());
        assertTrue(docs.body().contains("/api/v1/iso/pacs.008"));
        assertTrue(docs.body().contains("\"security\":[{\"participant\":[],\"apiKey\":[]}]"), "bank endpoints need both headers");
        assertTrue(docs.body().contains("\"security\":[{\"adminKey\":[]}]"), "settlement close needs the admin key");
        assertEquals(200, call("GET", "/swagger-ui/index.html", null, null, null, null).statusCode());
    }

    // ------------------------------------------------------------------------------------------------------------

    StatusReport send(String xml) throws Exception {
        var res = call("POST", "/api/v1/iso/pacs.008", "ALFAMYKL", "alfa-dev-key", "application/xml", xml);
        assertEquals(200, res.statusCode(), res.body());
        return IsoXml.parsePacs002(res.body());
    }

    HttpResponse<String> call(String method, String path, String bic, String key, String type, String body) throws Exception {
        return call(method, path, bic, key, type, body, null);
    }

    HttpResponse<String> call(String method, String path, String bic, String key, String type, String body, String adminKey)
            throws Exception {
        var req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(10))
            .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (bic != null) {
            req.header("X-Participant", bic).header("X-Api-Key", key);
        }
        if (type != null) {
            req.header("Content-Type", type);
        }
        if (adminKey != null) {
            req.header("X-Admin-Key", adminKey);
        }
        return http.send(req.build(), HttpResponse.BodyHandlers.ofString());
    }

    static String pacs008(String creditorBic, String creditorAccount, String amount) throws IOException {
        String e2e = IsoXml.newId("E2E");
        return fixture()
            .replace("E2E20261002243BA6AA7ECB0F74", e2e)
            .replace("ALFAMYKL2026100288639744A0200854", IsoXml.newId("ALFAMYKL"))
            .replace("<BICFI>BRVOMYKL</BICFI>", "<BICFI>" + creditorBic + "</BICFI>")
            .replace("<Id>2200000001</Id>", "<Id>" + creditorAccount + "</Id>")
            .replace(">125.50<", ">" + amount + "<");
    }

    static String fixture() throws IOException {
        try (InputStream in = SwitchIntegrationTest.class.getResourceAsStream("/iso/pacs008.xml")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Bravo: accepts everything except two magic account numbers. */
    static HttpServer fakeBank() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.setExecutor(Executors.newCachedThreadPool());
            server.createContext("/health", ex -> reply(ex, "text/plain", "ok"));
            server.createContext("/iso/pacs.008", ex -> {
                Pacs008 msg = IsoXml.parsePacs008(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                if (SLOW_ACCOUNT.equals(msg.creditorAccount())) {
                    sleep(3000);
                }
                StatusReport st = UNKNOWN_ACCOUNT.equals(msg.creditorAccount())
                    ? StatusReport.rejected(msg, "AC01", "No such account")
                    : StatusReport.accepted(msg);
                reply(ex, "application/xml", IsoXml.writePacs002(st, msg.msgId(), "BRVOMYKL"));
            });
            server.createContext("/iso/camt.056", ex -> {
                ex.getRequestBody().readAllBytes();
                cancellation.countDown();
                reply(ex, "application/xml", """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <Document xmlns="urn:iso:std:iso:20022:tech:xsd:camt.029.001.09"><RsltnOfInvstgtn>
                      <Sts><Conf>CNCL</Conf></Sts>
                      <CxlDtls><TxInfAndSts><TxCxlSts>CNCL</TxCxlSts></TxInfAndSts></CxlDtls>
                    </RsltnOfInvstgtn></Document>""".strip());
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static void reply(HttpExchange ex, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(200, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

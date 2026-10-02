package com.its.transfer;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;

import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import com.its.config.SwitchProperties;
import com.its.iso.IsoFormatException;
import com.its.iso.IsoXml;
import com.its.iso.StatusReport;
import com.its.participant.Participant;

/** Sends ISO 20022 messages to participant banks and classifies what went wrong when they don't answer. */
@Component
public class CreditorBankClient {

    /** What happened when the switch forwarded a credit transfer. */
    public sealed interface Outcome {
        /** The bank answered with a pacs.002 (accepted or rejected). */
        record Answered(StatusReport status) implements Outcome {
        }

        /** The bank could not be reached at all, so it never saw the transfer: safe to reject without a reversal. */
        record Unreachable(String detail) implements Outcome {
        }

        /** The bank may have processed the transfer but didn't answer in time (or answered garbage): reject and reverse. */
        record NoAnswer(String detail) implements Outcome {
        }
    }

    private final RestClient http;
    private final String switchBic;

    public CreditorBankClient(SwitchProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(1000);
        factory.setReadTimeout((int) props.creditorTimeout().toMillis());
        this.http = RestClient.builder().requestFactory(factory).build();
        this.switchBic = props.bic();
    }

    public Outcome forwardCredit(Participant bank, String pacs008Xml, String endToEndId) {
        String body;
        try {
            body = http.post()
                .uri(bank.baseUrl() + "/iso/pacs.008")
                .contentType(MediaType.APPLICATION_XML)
                .header("X-Switch-Bic", switchBic)
                .body(pacs008Xml)
                .retrieve()
                .body(String.class);
        } catch (ResourceAccessException e) {
            return isConnectFailure(e) ? new Outcome.Unreachable(e.getMessage()) : new Outcome.NoAnswer(e.getMessage());
        } catch (RestClientResponseException e) {
            // 503 = the bank says it's offline and did not process anything
            return e.getStatusCode().value() == 503
                ? new Outcome.Unreachable("HTTP 503")
                : new Outcome.NoAnswer("HTTP " + e.getStatusCode().value());
        }
        try {
            StatusReport st = IsoXml.parsePacs002(body);
            if (!endToEndId.equals(st.originalEndToEndId())) {
                return new Outcome.NoAnswer("pacs.002 refers to another transfer: " + st.originalEndToEndId());
            }
            return new Outcome.Answered(st);
        } catch (IsoFormatException e) {
            return new Outcome.NoAnswer("Invalid pacs.002: " + e.getMessage());
        }
    }

    /** Sends a camt.056 and returns the camt.029 cancellation status (CNCL or RJCR). */
    public String requestCancellation(Participant bank, String camt056Xml) {
        String body = http.post()
            .uri(bank.baseUrl() + "/iso/camt.056")
            .contentType(MediaType.APPLICATION_XML)
            .header("X-Switch-Bic", switchBic)
            .body(camt056Xml)
            .retrieve()
            .body(String.class);
        return IsoXml.parseCamt029Status(body);
    }

    private static boolean isConnectFailure(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ConnectException) {
                return true;
            }
            if (t instanceof SocketTimeoutException || t instanceof HttpTimeoutException) {
                return false;
            }
        }
        return false;
    }
}

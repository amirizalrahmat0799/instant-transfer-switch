package com.its.monitor;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.its.participant.Participant;
import com.its.participant.ParticipantRegistry;

/**
 * Pings every bank's /health every few seconds. Transfers to a bank that is DOWN are rejected immediately (AB08)
 * instead of waiting for a timeout, which keeps the sender's customers from staring at a spinner.
 */
@Component
public class HeartbeatMonitor {

    public enum State { UNKNOWN, UP, DOWN }

    public record BankStatus(State state, OffsetDateTime since, OffsetDateTime lastChecked, Long latencyMs) {
    }

    private static final Logger log = LoggerFactory.getLogger(HeartbeatMonitor.class);

    private final ParticipantRegistry registry;
    private final RestClient http;
    private final Map<String, BankStatus> statuses = new ConcurrentHashMap<>();

    public HeartbeatMonitor(ParticipantRegistry registry) {
        this.registry = registry;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(1000);
        factory.setReadTimeout(1500);
        this.http = RestClient.builder().requestFactory(factory).build();
        for (Participant p : registry.all()) {
            statuses.put(p.bic(), new BankStatus(State.UNKNOWN, OffsetDateTime.now(), null, null));
        }
    }

    @Scheduled(fixedDelay = 3000, initialDelay = 1000)
    public void checkAll() {
        for (Participant p : registry.all()) {
            check(p);
        }
    }

    void check(Participant p) {
        long start = System.nanoTime();
        State state;
        Long latency = null;
        try {
            http.get().uri(p.baseUrl() + "/health").retrieve().toBodilessEntity();
            state = State.UP;
            latency = (System.nanoTime() - start) / 1_000_000;
        } catch (RuntimeException e) {
            state = State.DOWN;
        }
        OffsetDateTime now = OffsetDateTime.now();
        BankStatus previous = statuses.get(p.bic());
        boolean changed = previous == null || previous.state() != state;
        if (changed && previous != null && previous.state() != State.UNKNOWN) {
            log.warn("{} is now {}", p.bic(), state);
        }
        statuses.put(p.bic(), new BankStatus(state, changed ? now : previous.since(), now, latency));
    }

    /** Unknown counts as available, so a freshly started switch doesn't reject everything before its first check. */
    public boolean isAvailable(String bic) {
        BankStatus s = statuses.get(bic);
        return s == null || s.state() != State.DOWN;
    }

    public BankStatus status(String bic) {
        return statuses.getOrDefault(bic, new BankStatus(State.UNKNOWN, OffsetDateTime.now(), null, null));
    }
}

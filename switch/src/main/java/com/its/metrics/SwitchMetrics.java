package com.its.metrics;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import com.its.iso.Reason;
import com.its.monitor.HeartbeatMonitor;
import com.its.participant.Participant;
import com.its.participant.ParticipantRegistry;

/**
 * Business metrics for Prometheus (/actuator/prometheus). Tag values come only from the participant list and fixed
 * code sets, never straight from a message, so a malformed pacs.008 can't create unbounded time series.
 */
@Component
public class SwitchMetrics {

    private final MeterRegistry registry;
    private final ParticipantRegistry participants;
    private final Map<String, AtomicLong> netPositionSen = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> capUsedPpm = new ConcurrentHashMap<>();
    private final AtomicLong pendingReversals = new AtomicLong();
    private final AtomicLong registeredProxies = new AtomicLong();

    public SwitchMetrics(MeterRegistry registry, ParticipantRegistry participants, HeartbeatMonitor heartbeat) {
        this.registry = registry;
        this.participants = participants;
        for (Participant p : participants.all()) {
            String bic = p.bic();
            Gauge.builder("its.bank.up", heartbeat, h -> switch (h.status(bic).state()) {
                    case UP -> 1;
                    case DOWN -> 0;
                    case UNKNOWN -> Double.NaN;
                })
                .description("1 when the bank answers its heartbeat, 0 when it is down")
                .tag("bank", bic)
                .register(registry);
            Gauge.builder("its.bank.net.position.ringgit", netPositionSen.computeIfAbsent(bic, k -> new AtomicLong()), v -> v.get() / 100.0)
                .description("Received minus sent in the open settlement cycle")
                .tag("bank", bic)
                .register(registry);
            Gauge.builder("its.bank.cap.utilisation.ratio", capUsedPpm.computeIfAbsent(bic, k -> new AtomicLong()), v -> v.get() / 1_000_000.0)
                .description("Share of the net debit cap in use (0 to 1)")
                .tag("bank", bic)
                .register(registry);
        }
        Gauge.builder("its.reversals.pending", pendingReversals, AtomicLong::get)
            .description("Cancellations (camt.056) still being retried")
            .register(registry);
        Gauge.builder("its.proxies.registered", registeredProxies, AtomicLong::get)
            .description("Registered proxy IDs")
            .register(registry);
    }

    /** A transfer reached a final answer: outcome is completed, rejected or timed_out. */
    public void transfer(String debtorBic, String creditorBic, String outcome, String reasonCode) {
        Counter.builder("its.transfers")
            .description("Transfers by final outcome and ISO reason code")
            .tag("debtor", known(debtorBic))
            .tag("creditor", known(creditorBic))
            .tag("outcome", outcome.toLowerCase())
            .tag("reason", reason(reasonCode))
            .register(registry)
            .increment();
    }

    /** How long the receiving bank took to answer a forwarded pacs.008. */
    public void creditorLatency(String creditorBic, String outcome, Duration latency) {
        Timer.builder("its.transfer.creditor.latency")
            .description("Round trip from the switch to the receiving bank")
            .tag("creditor", known(creditorBic))
            .tag("outcome", outcome.toLowerCase())
            .publishPercentileHistogram()
            .serviceLevelObjectives(Duration.ofMillis(100), Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofSeconds(5))
            .register(registry)
            .record(latency);
    }

    /** A camt.056 round finished: cancelled, nothing_to_cancel or retry. */
    public void reversal(String result) {
        Counter.builder("its.reversals").description("Cancellation attempts by result").tag("result", result).register(registry).increment();
    }

    public Timer settlementClose() {
        return Timer.builder("its.settlement.close").description("Time to close and net a settlement cycle").register(registry);
    }

    void netPosition(String bic, long sen, double capUsed) {
        netPositionSen.computeIfAbsent(bic, k -> new AtomicLong()).set(sen);
        capUsedPpm.computeIfAbsent(bic, k -> new AtomicLong()).set(Math.round(capUsed * 1_000_000));
    }

    void pendingReversals(long n) {
        pendingReversals.set(n);
    }

    void registeredProxies(long n) {
        registeredProxies.set(n);
    }

    /** ISO codes the scheme uses; anything else a bank sends is counted as "other". */
    private static final Set<String> REASONS = Set.of(Reason.INCORRECT_ACCOUNT, Reason.CLOSED_ACCOUNT, Reason.INSUFFICIENT_FUNDS,
        Reason.TIMEOUT_CREDITOR_AGENT, Reason.OFFLINE_CREDITOR_AGENT, Reason.INVALID_FORMAT, Reason.UNKNOWN_BANK, "AM02", "AG01", "MS03");

    private static String reason(String code) {
        return code == null ? "none" : REASONS.contains(code) ? code : "other";
    }

    private String known(String bic) {
        return bic != null && participants.find(bic).isPresent() ? bic : "unknown";
    }
}

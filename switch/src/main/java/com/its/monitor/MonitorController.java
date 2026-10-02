package com.its.monitor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.its.iso.Reason;
import com.its.participant.Participant;
import com.its.participant.ParticipantRegistry;
import com.its.settlement.CycleRepository;
import com.its.settlement.Netting;
import com.its.settlement.Netting.Line;

/** Read-only numbers for the operations dashboard (served from /). */
@Tag(name = "Monitoring", description = "Numbers behind the operations dashboard")
@RestController
@RequestMapping("/api/v1/monitor")
public class MonitorController {

    public record Window(long total, long completed, long rejected, long timedOut, double successRate, Long avgLatencyMs,
        Long p95LatencyMs) {
    }

    public record Bank(String bic, String name, String state, OffsetDateTime since, Long pingMs, long sentCount, long sentAmount,
        long receivedCount, long receivedAmount, long net, long netDebitCap, double capUsed) {
    }

    public record Recent(String endToEndId, String debtorBic, String creditorBic, long amount, String status, String reasonCode,
        String reason, Integer latencyMs, OffsetDateTime at) {
    }

    public record ReasonCount(String code, String description, long count) {
    }

    public record Minute(OffsetDateTime minute, long completed, long failed) {
    }

    public record Summary(OffsetDateTime now, Long cycleId, Window last15m, List<Bank> banks, List<Recent> recent,
        List<ReasonCount> reasons, List<Minute> perMinute, long registeredProxies) {
    }

    private final JdbcClient jdbc;
    private final ParticipantRegistry registry;
    private final HeartbeatMonitor heartbeat;
    private final CycleRepository cycles;

    public MonitorController(JdbcClient jdbc, ParticipantRegistry registry, HeartbeatMonitor heartbeat, CycleRepository cycles) {
        this.jdbc = jdbc;
        this.registry = registry;
        this.heartbeat = heartbeat;
        this.cycles = cycles;
    }

    @Operation(summary = "Dashboard summary", description = "Last 15 minutes, bank status and positions, recent transfers.")
    @GetMapping("/summary")
    public Summary summary() {
        Long cycleId = cycles.openCycleId().orElse(null);
        Map<String, Line> lines = cycleId == null ? Map.of()
            : Netting.live(cycles.positions(cycleId)).stream().collect(Collectors.toMap(Line::bic, Function.identity()));

        List<Bank> banks = registry.all().stream().map(p -> bank(p, lines.get(p.bic()))).toList();

        Window window = jdbc.sql("""
                SELECT count(*) AS total,
                       count(*) FILTER (WHERE status = 'COMPLETED') AS completed,
                       count(*) FILTER (WHERE status = 'REJECTED') AS rejected,
                       count(*) FILTER (WHERE status = 'TIMED_OUT') AS timed_out,
                       round(avg(latency_ms) FILTER (WHERE status <> 'FORWARDED' AND latency_ms > 0)) AS avg_latency,
                       round(percentile_cont(0.95) WITHIN GROUP (ORDER BY latency_ms) FILTER (WHERE latency_ms > 0)) AS p95_latency
                  FROM transfers WHERE received_at > now() - interval '15 minutes'""")
            .query((rs, i) -> {
                long total = rs.getLong("total");
                long completed = rs.getLong("completed");
                long finished = completed + rs.getLong("rejected") + rs.getLong("timed_out");
                long avg = rs.getLong("avg_latency");
                Long avgLatency = rs.wasNull() ? null : avg;
                long p95 = rs.getLong("p95_latency");
                Long p95Latency = rs.wasNull() ? null : p95;
                return new Window(total, completed, rs.getLong("rejected"), rs.getLong("timed_out"),
                    finished == 0 ? 1.0 : (double) completed / finished, avgLatency, p95Latency);
            })
            .single();

        List<Recent> recent = jdbc.sql("""
                SELECT end_to_end_id, debtor_bic, creditor_bic, amount, status, reason_code, latency_ms, received_at
                  FROM transfers ORDER BY received_at DESC LIMIT 15""")
            .query((rs, i) -> {
                int latency = rs.getInt("latency_ms");
                Integer latencyMs = rs.wasNull() ? null : latency;
                String code = rs.getString("reason_code");
                return new Recent(rs.getString("end_to_end_id"), rs.getString("debtor_bic"), rs.getString("creditor_bic"),
                    rs.getLong("amount"), rs.getString("status"), code, Reason.describe(code), latencyMs,
                    rs.getObject("received_at", OffsetDateTime.class));
            })
            .list();

        List<ReasonCount> reasons = jdbc.sql("""
                SELECT reason_code, count(*) AS n FROM transfers
                 WHERE reason_code IS NOT NULL AND received_at > now() - interval '15 minutes'
                 GROUP BY reason_code ORDER BY n DESC""")
            .query((rs, i) -> new ReasonCount(rs.getString("reason_code"), Reason.describe(rs.getString("reason_code")), rs.getLong("n")))
            .list();

        List<Minute> perMinute = jdbc.sql("""
                SELECT m.minute,
                       count(t.id) FILTER (WHERE t.status = 'COMPLETED') AS completed,
                       count(t.id) FILTER (WHERE t.status IN ('REJECTED', 'TIMED_OUT')) AS failed
                  FROM generate_series(date_trunc('minute', now()) - interval '14 minutes', date_trunc('minute', now()),
                                       interval '1 minute') AS m(minute)
                  LEFT JOIN transfers t ON date_trunc('minute', t.received_at) = m.minute
                 GROUP BY m.minute ORDER BY m.minute""")
            .query((rs, i) -> new Minute(rs.getObject("minute", OffsetDateTime.class), rs.getLong("completed"), rs.getLong("failed")))
            .list();

        long proxies = jdbc.sql("SELECT count(*) FROM proxies").query(Long.class).single();
        return new Summary(OffsetDateTime.now(), cycleId, window, banks, recent, reasons, perMinute, proxies);
    }

    private Bank bank(Participant p, Line line) {
        HeartbeatMonitor.BankStatus s = heartbeat.status(p.bic());
        long sentCount = line == null ? 0 : line.sentCount();
        long sent = line == null ? 0 : line.sentAmount();
        long receivedCount = line == null ? 0 : line.receivedCount();
        long received = line == null ? 0 : line.receivedAmount();
        long net = received - sent;
        double capUsed = net >= 0 ? 0 : Math.min(1.0, (double) -net / p.netDebitCap());
        return new Bank(p.bic(), p.name(), s.state().name(), s.since(), s.latencyMs(), sentCount, sent, receivedCount, received, net,
            p.netDebitCap(), capUsed);
    }
}

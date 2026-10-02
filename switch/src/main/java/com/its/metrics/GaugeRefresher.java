package com.its.metrics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.its.participant.Participant;
import com.its.participant.ParticipantRegistry;
import com.its.proxy.ProxyRepository;
import com.its.settlement.CycleRepository;
import com.its.settlement.Netting;
import com.its.transfer.TransferRepository;

/** Reads the database-backed gauges every few seconds, so a Prometheus scrape never waits on SQL. */
@Component
public class GaugeRefresher {

    private static final Logger log = LoggerFactory.getLogger(GaugeRefresher.class);

    private final SwitchMetrics metrics;
    private final ParticipantRegistry participants;
    private final CycleRepository cycles;
    private final TransferRepository transfers;
    private final ProxyRepository proxies;

    public GaugeRefresher(SwitchMetrics metrics, ParticipantRegistry participants, CycleRepository cycles, TransferRepository transfers,
            ProxyRepository proxies) {
        this.metrics = metrics;
        this.participants = participants;
        this.cycles = cycles;
        this.transfers = transfers;
        this.proxies = proxies;
    }

    @Scheduled(fixedDelay = 5000, initialDelay = 2000)
    public void refresh() {
        try {
            var lines = cycles.openCycleId().map(id -> Netting.live(cycles.positions(id))).orElse(java.util.List.of());
            for (Participant p : participants.all()) {
                long net = lines.stream().filter(l -> l.bic().equals(p.bic())).mapToLong(Netting.Line::net).findFirst().orElse(0);
                double capUsed = net >= 0 ? 0 : Math.min(1.0, (double) -net / p.netDebitCap());
                metrics.netPosition(p.bic(), net, capUsed);
            }
            metrics.pendingReversals(transfers.countPendingReversals());
            metrics.registeredProxies(proxies.count());
        } catch (RuntimeException e) {
            log.warn("Could not refresh metrics: {}", e.getMessage());
        }
    }
}

package com.its.settlement;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.its.config.SwitchProperties;
import com.its.participant.ParticipantRegistry;
import com.its.settlement.CycleRepository.Cycle;
import com.its.settlement.Netting.Line;
import com.its.transfer.TransferRepository;
import com.its.web.ApiException;

/**
 * End-of-day settlement. Closing a cycle is a cut-over:
 * <ol>
 *   <li>mark the open cycle CLOSING and open the next one in the same transaction, so new transfers carry on
 *       immediately in the new cycle;</li>
 *   <li>wait for transfers still in flight in the closing cycle (they finish within the creditor timeout);</li>
 *   <li>net every bank's position, check the nets add up to zero, store the report and mark the cycle CLOSED.</li>
 * </ol>
 */
@Service
public class SettlementService implements ApplicationRunner {

    public record CycleReport(long cycleId, String status, OffsetDateTime openedAt, OffsetDateTime closedAt, long transferCount,
        long grossAmount, List<Line> lines) {
    }

    private static final Logger log = LoggerFactory.getLogger(SettlementService.class);

    private final CycleRepository cycles;
    private final TransferRepository transfers;
    private final ParticipantRegistry registry;
    private final TransactionTemplate tx;
    private final Duration drainTimeout;

    public SettlementService(CycleRepository cycles, TransferRepository transfers, ParticipantRegistry registry,
            PlatformTransactionManager txManager, SwitchProperties props) {
        this.cycles = cycles;
        this.transfers = transfers;
        this.registry = registry;
        this.tx = new TransactionTemplate(txManager);
        this.drainTimeout = props.creditorTimeout().multipliedBy(3);
    }

    /** Makes sure there is an open cycle with a position row for every bank when the switch starts. */
    @Override
    public void run(ApplicationArguments args) {
        tx.executeWithoutResult(s -> {
            long id = cycles.openCycleId().orElseGet(cycles::open);
            registry.bics().forEach(bic -> cycles.ensurePosition(id, bic));
        });
    }

    @Scheduled(cron = "${its.settlement.cron:0 59 23 * * *}", zone = "Asia/Kuala_Lumpur")
    public void scheduledClose() {
        CycleReport report = close();
        log.info("Settlement cycle {} closed: {} transfers, RM {}", report.cycleId(), report.transferCount(), report.grossAmount() / 100.0);
    }

    public synchronized CycleReport close() {
        // Finish a cut-over that was interrupted (e.g. the switch restarted mid-close) before starting a new one
        long closing = cycles.closingCycleId().orElseGet(this::cutOver);
        drain(closing);
        tx.executeWithoutResult(s -> {
            List<Line> lines = Netting.net(cycles.positions(closing));
            cycles.saveReport(closing, lines);
            cycles.markClosed(closing);
        });
        return report(closing);
    }

    private long cutOver() {
        return tx.execute(s -> {
            long id = cycles.lockOpenExclusive().orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "No open cycle"));
            cycles.markClosing(id);
            long next = cycles.open();
            registry.bics().forEach(bic -> cycles.ensurePosition(next, bic));
            return id;
        });
    }

    private void drain(long closing) {
        long deadline = System.nanoTime() + drainTimeout.toNanos();
        while (transfers.countInFlight(closing) > 0 && System.nanoTime() < deadline) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        long stillInFlight = transfers.countInFlight(closing);
        if (stillInFlight > 0) {
            throw new ApiException(HttpStatus.CONFLICT,
                stillInFlight + " transfers are still in flight in cycle " + closing + "; try closing again shortly");
        }
    }

    public CycleReport report(long cycleId) {
        Cycle c = cycles.find(cycleId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No cycle " + cycleId));
        List<Line> lines = "CLOSED".equals(c.status())
            ? cycles.report(cycleId)
            : Netting.live(cycles.positions(cycleId)); // live view of an open or closing cycle
        long count = lines.stream().mapToLong(Line::sentCount).sum();
        long gross = lines.stream().mapToLong(Line::sentAmount).sum();
        return new CycleReport(c.id(), c.status(), c.openedAt(), c.closedAt(), count, gross, lines);
    }

    public List<Cycle> recent() {
        return cycles.recent(30);
    }
}

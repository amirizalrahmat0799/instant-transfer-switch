package com.its.transfer;

import java.time.Duration;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.its.config.SwitchProperties;
import com.its.iso.IsoFormatException;
import com.its.iso.IsoXml;
import com.its.iso.Pacs008;
import com.its.iso.Reason;
import com.its.iso.StatusReport;
import com.its.metrics.SwitchMetrics;
import com.its.monitor.HeartbeatMonitor;
import com.its.participant.Participant;
import com.its.participant.ParticipantRegistry;
import com.its.settlement.CycleRepository;
import com.its.transfer.CreditorBankClient.Outcome;
import com.its.web.ApiException;

/**
 * The real-time credit transfer flow:
 *
 * <ol>
 *   <li>validate the pacs.008 and recognise retries (same sending bank + end-to-end id);</li>
 *   <li>reject straight away if the receiving bank is unknown or offline;</li>
 *   <li>reserve the amount against the sending bank's net debit cap (one SQL update, so concurrent transfers can't
 *       overshoot the cap);</li>
 *   <li>forward to the receiving bank and wait up to the creditor timeout for its pacs.002;</li>
 *   <li>book the outcome: completed (credit the receiver's position), rejected (release the reservation), or no
 *       answer (release, reject with AB05, and send the receiver a cancellation so a late credit gets reversed).</li>
 * </ol>
 *
 * The forward happens outside any database transaction, so a slow bank never holds locks.
 */
@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    private final TransferRepository transfers;
    private final CycleRepository cycles;
    private final ParticipantRegistry registry;
    private final HeartbeatMonitor heartbeat;
    private final CreditorBankClient banks;
    private final TransactionTemplate tx;
    private final SwitchMetrics metrics;
    private final String switchBic;

    public TransferService(TransferRepository transfers, CycleRepository cycles, ParticipantRegistry registry,
            HeartbeatMonitor heartbeat, CreditorBankClient banks, PlatformTransactionManager txManager, SwitchMetrics metrics,
            SwitchProperties props) {
        this.transfers = transfers;
        this.cycles = cycles;
        this.registry = registry;
        this.heartbeat = heartbeat;
        this.banks = banks;
        this.tx = new TransactionTemplate(txManager);
        this.metrics = metrics;
        this.switchBic = props.bic();
    }

    /** Processes a pacs.008 from a sending bank and returns the pacs.002 to send back. */
    public String submit(Participant sender, String xml) {
        Pacs008 msg;
        try {
            msg = IsoXml.parsePacs008(xml);
        } catch (IsoFormatException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid pacs.008: " + e.getMessage());
        }
        if (!sender.bic().equals(msg.debtorBic())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "DbtrAgt must be your own BIC (" + sender.bic() + ")");
        }

        var existing = transfers.find(sender.bic(), msg.endToEndId());
        if (existing.isPresent()) {
            return replay(existing.get());
        }

        Participant creditor = registry.find(msg.creditorBic()).orElse(null);
        if (creditor == null) {
            return rejectUpfront(msg, Reason.UNKNOWN_BANK, "Unknown creditor agent " + msg.creditorBic());
        }
        if (creditor.bic().equals(sender.bic())) {
            return rejectUpfront(msg, Reason.UNKNOWN_BANK, "On-us transfers are settled inside the bank, not through the switch");
        }
        if (!heartbeat.isAvailable(creditor.bic())) {
            return rejectUpfront(msg, Reason.OFFLINE_CREDITOR_AGENT, creditor.name() + " is offline");
        }

        UUID id = UUID.randomUUID();
        Long cycleId;
        try {
            cycleId = tx.execute(status -> reserve(id, msg, sender));
        } catch (DuplicateKeyException race) {
            // The same transfer arrived twice at the same moment; the other request owns it
            return replay(transfers.find(sender.bic(), msg.endToEndId()).orElseThrow());
        }
        if (cycleId == null) {
            metrics.transfer(msg.debtorBic(), msg.creditorBic(), TransferRecord.REJECTED, Reason.INSUFFICIENT_FUNDS);
            return transfers.findById(id).orElseThrow().responseXml(); // rejected: net debit cap
        }

        long start = System.nanoTime();
        Outcome outcome;
        try {
            outcome = banks.forwardCredit(creditor, xml, msg.endToEndId());
        } catch (RuntimeException e) {
            // Never leave a reserved transfer in flight: it would hold the cap and block the settlement close
            log.error("Forwarding {} to {} failed unexpectedly", msg.endToEndId(), creditor.bic(), e);
            outcome = new Outcome.NoAnswer(e.toString());
        }
        long elapsedNanos = System.nanoTime() - start;
        int latencyMs = (int) (elapsedNanos / 1_000_000);
        Outcome result = outcome;
        String response = tx.execute(status -> book(id, msg, cycleId, result, latencyMs));
        String outcomeTag = switch (result) {
            case Outcome.Answered a when a.status().accepted() -> "completed";
            case Outcome.Answered a -> "rejected";
            case Outcome.Unreachable u -> "unreachable";
            case Outcome.NoAnswer n -> "no_answer";
        };
        metrics.creditorLatency(msg.creditorBic(), outcomeTag, Duration.ofNanos(elapsedNanos));
        return response;
    }

    private Long reserve(UUID id, Pacs008 msg, Participant sender) {
        long cycleId = cycles.lockOpenShared();
        cycles.ensurePosition(cycleId, sender.bic());
        if (!cycles.reserveDebit(cycleId, sender.bic(), msg.amount(), sender.netDebitCap())) {
            String xml = IsoXml.writePacs002(StatusReport.rejected(msg, Reason.INSUFFICIENT_FUNDS, "Net debit cap reached for this cycle"),
                msg.msgId(), switchBic);
            transfers.insert(id, msg, TransferRecord.REJECTED, null, Reason.INSUFFICIENT_FUNDS, "Net debit cap reached", xml);
            return null;
        }
        transfers.insert(id, msg, TransferRecord.FORWARDED, cycleId, null, null, null);
        return cycleId;
    }

    private String book(UUID id, Pacs008 msg, long cycleId, Outcome outcome, int latencyMs) {
        StatusReport report;
        String status;
        boolean reverse = false;
        switch (outcome) {
            case Outcome.Answered a when a.status().accepted() -> {
                report = StatusReport.accepted(msg);
                status = TransferRecord.COMPLETED;
            }
            case Outcome.Answered a -> {
                report = StatusReport.rejected(msg, a.status().reasonCode(), a.status().info());
                status = TransferRecord.REJECTED;
            }
            case Outcome.Unreachable u -> {
                report = StatusReport.rejected(msg, Reason.OFFLINE_CREDITOR_AGENT, "Receiving bank unreachable");
                status = TransferRecord.REJECTED;
            }
            case Outcome.NoAnswer n -> {
                report = StatusReport.rejected(msg, Reason.TIMEOUT_CREDITOR_AGENT, "Receiving bank did not answer in time");
                status = TransferRecord.TIMED_OUT;
                reverse = true;
                log.warn("{} -> {} timed out ({}); scheduling a cancellation", msg.debtorBic(), msg.creditorBic(), n.detail());
            }
        }

        String xml = IsoXml.writePacs002(report, msg.msgId(), switchBic);
        if (!transfers.finish(id, status, report.reasonCode(), report.info(), latencyMs, xml)) {
            return transfers.findById(id).orElseThrow().responseXml();
        }
        metrics.transfer(msg.debtorBic(), msg.creditorBic(), status, report.reasonCode());
        if (TransferRecord.COMPLETED.equals(status)) {
            cycles.credit(cycleId, msg.creditorBic(), msg.amount());
        } else {
            cycles.releaseDebit(cycleId, msg.debtorBic(), msg.amount());
        }
        if (reverse) {
            transfers.scheduleReversal(id);
        }
        return xml;
    }

    private String rejectUpfront(Pacs008 msg, String reason, String info) {
        String xml = IsoXml.writePacs002(StatusReport.rejected(msg, reason, info), msg.msgId(), switchBic);
        try {
            transfers.insert(UUID.randomUUID(), msg, TransferRecord.REJECTED, null, reason, info, xml);
        } catch (DuplicateKeyException race) {
            return replay(transfers.find(msg.debtorBic(), msg.endToEndId()).orElseThrow());
        }
        metrics.transfer(msg.debtorBic(), msg.creditorBic(), TransferRecord.REJECTED, reason);
        return xml;
    }

    private String replay(TransferRecord t) {
        if (t.inFlight()) {
            throw new ApiException(HttpStatus.CONFLICT, "Transfer " + t.endToEndId() + " is still being processed");
        }
        return t.responseXml();
    }

    /** The pacs.002 for a transfer, for a bank that lost the original answer. */
    public String status(Participant sender, String endToEndId) {
        TransferRecord t = transfers.find(sender.bic(), endToEndId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No transfer " + endToEndId + " from " + sender.bic()));
        return replay(t);
    }
}

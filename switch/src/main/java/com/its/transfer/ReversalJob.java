package com.its.transfer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.its.config.SwitchProperties;
import com.its.iso.IsoXml;
import com.its.iso.Reason;
import com.its.participant.ParticipantRegistry;

/**
 * After a timeout the sender was told "rejected", so any credit the receiving bank applied late must be undone.
 * This job sends a camt.056 cancellation for each timed-out transfer until the bank confirms (camt.029):
 * CNCL = it reversed the credit, RJCR = it never applied one. Retries back off up to 5 minutes, for about a day.
 */
@Component
public class ReversalJob {

    private static final Logger log = LoggerFactory.getLogger(ReversalJob.class);
    private static final int MAX_ATTEMPTS = 300;

    private final TransferRepository transfers;
    private final ParticipantRegistry registry;
    private final CreditorBankClient banks;
    private final String switchBic;

    public ReversalJob(TransferRepository transfers, ParticipantRegistry registry, CreditorBankClient banks, SwitchProperties props) {
        this.transfers = transfers;
        this.registry = registry;
        this.banks = banks;
        this.switchBic = props.bic();
    }

    @Scheduled(fixedDelay = 2000, initialDelay = 2000)
    public void run() {
        for (var r : transfers.dueReversals(20)) {
            var bank = registry.find(r.creditorBic()).orElse(null);
            if (bank == null) {
                transfers.retryReversal(r.transferId(), "Unknown bank", 0, true);
                continue;
            }
            try {
                String camt056 = IsoXml.writeCamt056(switchBic, r.endToEndId(), r.txId(), r.amount(), Reason.TIMEOUT_CREDITOR_AGENT);
                String result = banks.requestCancellation(bank, camt056);
                String status = "CNCL".equals(result) ? "CANCELLED" : "NOTHING_TO_CANCEL";
                transfers.resolveReversal(r.transferId(), status);
                log.info("Cancellation for {} at {}: {}", r.endToEndId(), r.creditorBic(), status);
            } catch (RuntimeException e) {
                long delay = Math.min(300, 5L << Math.min(r.attempts(), 6));
                transfers.retryReversal(r.transferId(), e.getMessage(), delay, r.attempts() + 1 >= MAX_ATTEMPTS);
            }
        }
    }
}

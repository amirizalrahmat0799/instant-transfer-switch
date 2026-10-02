package com.its.settlement;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.its.participant.Participant;
import com.its.settlement.CycleRepository.Cycle;
import com.its.settlement.SettlementService.CycleReport;
import com.its.transfer.TransferRepository;
import com.its.transfer.TransferRepository.CycleTransaction;
import com.its.web.ApiException;
import com.its.web.ParticipantAuthInterceptor;

@Tag(name = "Settlement", description = "Settlement cycles, net positions and reconciliation")
@RestController
@RequestMapping("/api/v1/settlement")
public class SettlementController {

    private final SettlementService settlement;
    private final CycleRepository cycles;
    private final TransferRepository transfers;

    public SettlementController(SettlementService settlement, CycleRepository cycles, TransferRepository transfers) {
        this.settlement = settlement;
        this.cycles = cycles;
        this.transfers = transfers;
    }

    /** Operator action (X-Admin-Key): end the current cycle now and settle it. */
    @Operation(summary = "Close the open cycle now and settle it",
        description = "Opens the next cycle, waits for transfers in flight, then nets every bank (the nets sum to zero).")
    @PostMapping("/close")
    public CycleReport close() {
        return settlement.close();
    }

    @Operation(summary = "Recent settlement cycles")
    @GetMapping("/cycles")
    public List<Cycle> cycles() {
        return settlement.recent();
    }

    @Operation(summary = "Settlement report for a cycle", description = "Each bank's sent, received and net amounts.")
    @GetMapping("/cycles/{id}")
    public CycleReport report(@PathVariable long id) {
        return settlement.report(id);
    }

    /** What the switch settled for the calling bank in a cycle, for its reconciliation. */
    @Operation(summary = "Your settled transactions in a cycle", description = "For reconciling your ledger with the switch.")
    @GetMapping("/cycles/{id}/transactions")
    public List<CycleTransaction> transactions(@Parameter(hidden = true) @RequestAttribute(ParticipantAuthInterceptor.ATTRIBUTE) Participant bank,
            @PathVariable long id) {
        if (cycles.find(id).isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "No cycle " + id);
        }
        return transfers.settledIn(id, bank.bic());
    }
}

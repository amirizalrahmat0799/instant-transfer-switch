package com.its.transfer;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.its.participant.Participant;
import com.its.web.ParticipantAuthInterceptor;

/** ISO 20022 endpoints for participant banks. Bodies are the XML messages themselves. */
@Tag(name = "Transfers", description = "ISO 20022 credit transfers between banks")
@RestController
@RequestMapping("/api/v1/iso")
public class TransferController {

    private final TransferService service;

    public TransferController(TransferService service) {
        this.service = service;
    }

    /** Submit a pacs.008 credit transfer; the response is the pacs.002 status report. */
    @Operation(summary = "Send a pacs.008 credit transfer",
        description = "The body is the pacs.008 XML; the response is the pacs.002 (ACSC or RJCT with a reason code). "
            + "Resending the same end-to-end id returns the original answer.")
    @PostMapping(path = "/pacs.008", consumes = {MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE},
        produces = MediaType.APPLICATION_XML_VALUE)
    public String creditTransfer(@Parameter(hidden = true) @RequestAttribute(ParticipantAuthInterceptor.ATTRIBUTE) Participant bank, @RequestBody String xml) {
        return service.submit(bank, xml);
    }

    /** The pacs.002 for an earlier transfer, by end-to-end id (for a bank that lost the response). */
    @Operation(summary = "Get the pacs.002 for a transfer you sent", description = "For a bank that lost the original response.")
    @GetMapping(path = "/pacs.002/{endToEndId}", produces = MediaType.APPLICATION_XML_VALUE)
    public String status(@Parameter(hidden = true) @RequestAttribute(ParticipantAuthInterceptor.ATTRIBUTE) Participant bank, @PathVariable String endToEndId) {
        return service.status(bank, endToEndId);
    }
}

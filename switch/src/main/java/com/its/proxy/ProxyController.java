package com.its.proxy;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.its.participant.Participant;
import com.its.participant.ParticipantRegistry;
import com.its.proxy.ProxyRepository.ProxyRecord;
import com.its.web.ApiException;
import com.its.web.ParticipantAuthInterceptor;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * DuitNow-style addressing: banks register their customers' phone, IC or business numbers, and any bank can
 * resolve one to the bank and account behind it before sending a transfer.
 */
@RestController
@RequestMapping("/api/v1/proxies")
public class ProxyController {

    public record RegisterRequest(
        @NotBlank String type,
        @NotBlank @Size(max = 40) String value,
        @NotBlank @Size(max = 34) String accountNumber,
        @NotBlank @Size(max = 140) String accountName) {
    }

    /** What the payer's bank gets back. The bank shows only the masked name to its customer. */
    public record Resolution(String type, String value, String bic, String bankName, String accountNumber, String accountName,
        String maskedName) {
    }

    private final ProxyRepository proxies;
    private final ParticipantRegistry registry;

    public ProxyController(ProxyRepository proxies, ParticipantRegistry registry) {
        this.proxies = proxies;
        this.registry = registry;
    }

    @PostMapping
    public ResponseEntity<Resolution> register(
            @RequestAttribute(ParticipantAuthInterceptor.ATTRIBUTE) Participant bank,
            @Valid @RequestBody RegisterRequest req) {
        ProxyIds.Type type = ProxyIds.type(req.type());
        String value = ProxyIds.normalize(type, req.value());
        boolean saved = proxies.upsert(new ProxyRecord(type.name(), value, bank.bic(), req.accountNumber().trim(), req.accountName().trim()));
        if (!saved) {
            throw new ApiException(HttpStatus.CONFLICT, "This " + type.name().toLowerCase() + " is already registered with another bank");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(resolve(type.name(), value));
    }

    @GetMapping("/{type}/{value}")
    public Resolution lookup(@PathVariable String type, @PathVariable String value) {
        ProxyIds.Type t = ProxyIds.type(type);
        return resolve(t.name(), ProxyIds.normalize(t, value));
    }

    @DeleteMapping("/{type}/{value}")
    public ResponseEntity<Void> deregister(
            @RequestAttribute(ParticipantAuthInterceptor.ATTRIBUTE) Participant bank,
            @PathVariable String type, @PathVariable String value) {
        ProxyIds.Type t = ProxyIds.type(type);
        if (!proxies.delete(t.name(), ProxyIds.normalize(t, value), bank.bic())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "No such proxy registered by your bank");
        }
        return ResponseEntity.noContent().build();
    }

    private Resolution resolve(String type, String value) {
        ProxyRecord p = proxies.find(type, value)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No account is registered to this " + type.toLowerCase()));
        String bankName = registry.find(p.bic()).map(Participant::name).orElse(p.bic());
        return new Resolution(p.type(), p.value(), p.bic(), bankName, p.accountNumber(), p.accountName(), ProxyIds.mask(p.accountName()));
    }
}

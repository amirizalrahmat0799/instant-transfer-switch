package com.its.participant;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.its.config.SwitchProperties;

@Component
public class ParticipantRegistry {

    private final Map<String, Participant> byBic = new LinkedHashMap<>();

    public ParticipantRegistry(SwitchProperties props) {
        for (Participant p : props.participants()) {
            if (byBic.put(p.bic(), p) != null) {
                throw new IllegalStateException("Duplicate participant " + p.bic());
            }
        }
    }

    public Optional<Participant> find(String bic) {
        return Optional.ofNullable(bic == null ? null : byBic.get(bic.trim().toUpperCase()));
    }

    public List<Participant> all() {
        return List.copyOf(byBic.values());
    }

    public List<String> bics() {
        return List.copyOf(byBic.keySet());
    }

    /** Constant-time key comparison, so response timing doesn't leak how much of a key was right. */
    public Optional<Participant> authenticate(String bic, String apiKey) {
        return find(bic).filter(p -> apiKey != null
            && MessageDigest.isEqual(p.apiKey().getBytes(StandardCharsets.UTF_8), apiKey.getBytes(StandardCharsets.UTF_8)));
    }
}

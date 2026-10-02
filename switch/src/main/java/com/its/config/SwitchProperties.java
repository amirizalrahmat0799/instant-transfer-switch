package com.its.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.its.participant.Participant;

/** Switch settings and the participating banks (from application.yml). */
@ConfigurationProperties("its")
public record SwitchProperties(
    String bic,
    String adminKey,
    Duration creditorTimeout,
    Settlement settlement,
    List<Participant> participants) {

    public record Settlement(String cron) {
    }

    public SwitchProperties {
        if (participants == null || participants.isEmpty()) {
            throw new IllegalStateException("its.participants must list at least one bank");
        }
        if (creditorTimeout == null) {
            creditorTimeout = Duration.ofSeconds(5);
        }
        if (adminKey == null || adminKey.length() < 8) {
            throw new IllegalStateException("its.admin-key must be at least 8 characters");
        }
    }
}

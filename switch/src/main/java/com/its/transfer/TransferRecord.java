package com.its.transfer;

import java.time.OffsetDateTime;
import java.util.UUID;

public record TransferRecord(
    UUID id,
    String endToEndId,
    String debtorBic,
    String creditorBic,
    long amount,
    String status,
    String reasonCode,
    Long cycleId,
    OffsetDateTime receivedAt,
    Integer latencyMs,
    String responseXml,
    String txId) {

    public static final String FORWARDED = "FORWARDED";
    public static final String COMPLETED = "COMPLETED";
    public static final String REJECTED = "REJECTED";
    public static final String TIMED_OUT = "TIMED_OUT";

    public boolean inFlight() {
        return FORWARDED.equals(status);
    }
}

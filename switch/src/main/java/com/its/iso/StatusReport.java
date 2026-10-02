package com.its.iso;

/** A pacs.002 status for one transaction: ACSC (completed) or RJCT with a reason code. */
public record StatusReport(String originalEndToEndId, String originalTxId, String status, String reasonCode, String info) {

    public static final String ACCEPTED = "ACSC";
    public static final String REJECTED = "RJCT";

    public boolean accepted() {
        return ACCEPTED.equals(status);
    }

    public static StatusReport accepted(Pacs008 msg) {
        return new StatusReport(msg.endToEndId(), msg.txId(), ACCEPTED, null, null);
    }

    public static StatusReport rejected(Pacs008 msg, String reasonCode, String info) {
        return new StatusReport(msg.endToEndId(), msg.txId(), REJECTED, reasonCode, info);
    }
}

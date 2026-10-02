package com.its.iso;

/** The fields of a single-transaction pacs.008.001.08 credit transfer that the switch works with. */
public record Pacs008(
    String msgId,
    String endToEndId,
    String txId,
    long amount,
    String currency,
    String debtorName,
    String debtorAccount,
    String debtorBic,
    String creditorBic,
    String creditorName,
    String creditorAccount,
    String remittance) {
}

package com.its.transfer;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.its.iso.Pacs008;

@Repository
public class TransferRepository {

    private final JdbcClient jdbc;

    public TransferRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UUID id, Pacs008 m, String status, Long cycleId, String reasonCode, String reasonInfo, String responseXml) {
        jdbc.sql("""
                INSERT INTO transfers (id, msg_id, end_to_end_id, tx_id, debtor_bic, creditor_bic, debtor_account, debtor_name,
                                       creditor_account, creditor_name, amount, remittance, status, reason_code, reason_info,
                                       cycle_id, response_xml, completed_at, latency_ms)
                VALUES (:id, :msg, :e2e, :tx, :dbic, :cbic, :dacct, :dname, :cacct, :cname, :amount, :rmt, :status, :reason,
                        :info, :cycle, :xml, CASE WHEN :status = 'FORWARDED' THEN NULL ELSE now() END,
                        CASE WHEN :status = 'FORWARDED' THEN NULL ELSE 0 END)""")
            .param("id", id)
            .param("msg", m.msgId())
            .param("e2e", m.endToEndId())
            .param("tx", m.txId())
            .param("dbic", m.debtorBic())
            .param("cbic", m.creditorBic())
            .param("dacct", m.debtorAccount())
            .param("dname", m.debtorName())
            .param("cacct", m.creditorAccount())
            .param("cname", m.creditorName())
            .param("amount", m.amount())
            .param("rmt", m.remittance())
            .param("status", status)
            .param("reason", reasonCode)
            .param("info", reasonInfo)
            .param("cycle", cycleId)
            .param("xml", responseXml)
            .update();
    }

    /** Records the final outcome of a forwarded transfer. Only moves FORWARDED rows, so it can't overwrite an outcome. */
    public boolean finish(UUID id, String status, String reasonCode, String reasonInfo, int latencyMs, String responseXml) {
        return jdbc.sql("""
                UPDATE transfers SET status = :status, reason_code = :reason, reason_info = :info, latency_ms = :latency,
                                     response_xml = :xml, completed_at = now()
                 WHERE id = :id AND status = 'FORWARDED'""")
            .param("id", id)
            .param("status", status)
            .param("reason", reasonCode)
            .param("info", reasonInfo)
            .param("latency", latencyMs)
            .param("xml", responseXml)
            .update() == 1;
    }

    public Optional<TransferRecord> find(String debtorBic, String endToEndId) {
        return jdbc.sql("SELECT * FROM transfers WHERE debtor_bic = :bic AND end_to_end_id = :e2e")
            .param("bic", debtorBic)
            .param("e2e", endToEndId)
            .query(TransferRepository::map)
            .optional();
    }

    public Optional<TransferRecord> findById(UUID id) {
        return jdbc.sql("SELECT * FROM transfers WHERE id = :id").param("id", id).query(TransferRepository::map).optional();
    }

    public long countInFlight(long cycleId) {
        return jdbc.sql("SELECT count(*) FROM transfers WHERE cycle_id = :c AND status = 'FORWARDED'")
            .param("c", cycleId)
            .query(Long.class)
            .single();
    }

    public record CycleTransaction(String endToEndId, String direction, long amount) {
    }

    /** Completed transfers in a cycle that involve one bank, from that bank's point of view. */
    public List<CycleTransaction> settledIn(long cycleId, String bic) {
        return jdbc.sql("""
                SELECT end_to_end_id, CASE WHEN debtor_bic = :bic THEN 'SENT' ELSE 'RECEIVED' END AS direction, amount
                  FROM transfers
                 WHERE cycle_id = :c AND status = 'COMPLETED' AND (debtor_bic = :bic OR creditor_bic = :bic)
                 ORDER BY received_at""")
            .param("c", cycleId)
            .param("bic", bic)
            .query((rs, i) -> new CycleTransaction(rs.getString("end_to_end_id"), rs.getString("direction"), rs.getLong("amount")))
            .list();
    }

    // ---------------------------------------------------------------------------------------------
    // Reversals (camt.056 owed to receiving banks after a timeout)
    // ---------------------------------------------------------------------------------------------

    public void scheduleReversal(UUID transferId) {
        jdbc.sql("INSERT INTO reversals (transfer_id, status) VALUES (:id, 'PENDING') ON CONFLICT DO NOTHING")
            .param("id", transferId)
            .update();
    }

    public record DueReversal(UUID transferId, String endToEndId, String txId, String creditorBic, long amount, int attempts) {
    }

    public List<DueReversal> dueReversals(int limit) {
        return jdbc.sql("""
                SELECT r.transfer_id, t.end_to_end_id, t.tx_id, t.creditor_bic, t.amount, r.attempts
                  FROM reversals r JOIN transfers t ON t.id = r.transfer_id
                 WHERE r.status = 'PENDING' AND r.next_attempt_at <= now()
                 ORDER BY r.next_attempt_at LIMIT :limit""")
            .param("limit", limit)
            .query((rs, i) -> new DueReversal(rs.getObject("transfer_id", UUID.class), rs.getString("end_to_end_id"),
                rs.getString("tx_id"), rs.getString("creditor_bic"), rs.getLong("amount"), rs.getInt("attempts")))
            .list();
    }

    /** Cancellations still being retried (for the metrics gauge). */
    public long countPendingReversals() {
        return jdbc.sql("SELECT count(*) FROM reversals WHERE status = 'PENDING'").query(Long.class).single();
    }

    public void resolveReversal(UUID transferId, String status) {
        jdbc.sql("UPDATE reversals SET status = :status, resolved_at = now(), attempts = attempts + 1 WHERE transfer_id = :id")
            .param("id", transferId)
            .param("status", status)
            .update();
    }

    public void retryReversal(UUID transferId, String error, long delaySeconds, boolean giveUp) {
        jdbc.sql("""
                UPDATE reversals SET attempts = attempts + 1, last_error = :err,
                       next_attempt_at = now() + make_interval(secs => :delay),
                       status = CASE WHEN :giveUp THEN 'FAILED' ELSE status END
                 WHERE transfer_id = :id""")
            .param("id", transferId)
            .param("err", error == null ? null : error.substring(0, Math.min(error.length(), 500)))
            .param("delay", delaySeconds)
            .param("giveUp", giveUp)
            .update();
    }

    private static TransferRecord map(ResultSet rs, int i) throws SQLException {
        long cycle = rs.getLong("cycle_id");
        Long cycleId = rs.wasNull() ? null : cycle;
        int latency = rs.getInt("latency_ms");
        Integer latencyMs = rs.wasNull() ? null : latency;
        return new TransferRecord(rs.getObject("id", UUID.class), rs.getString("end_to_end_id"), rs.getString("debtor_bic"),
            rs.getString("creditor_bic"), rs.getLong("amount"), rs.getString("status"), rs.getString("reason_code"), cycleId,
            rs.getObject("received_at", OffsetDateTime.class), latencyMs, rs.getString("response_xml"), rs.getString("tx_id"));
    }
}

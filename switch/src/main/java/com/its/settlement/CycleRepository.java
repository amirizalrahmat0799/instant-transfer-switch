package com.its.settlement;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.its.settlement.Netting.Line;
import com.its.settlement.Netting.Position;

@Repository
public class CycleRepository {

    public record Cycle(long id, String status, OffsetDateTime openedAt, OffsetDateTime closedAt) {
    }

    private final JdbcClient jdbc;

    public CycleRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The open cycle, locked FOR SHARE: many transfers can reserve in it at once, but the cut-over (which locks it
     * FOR UPDATE) waits for them, so no reservation lands in a cycle that is being closed.
     */
    public long lockOpenShared() {
        return jdbc.sql("SELECT id FROM settlement_cycles WHERE status = 'OPEN' FOR SHARE").query(Long.class).single();
    }

    public Optional<Long> lockOpenExclusive() {
        return jdbc.sql("SELECT id FROM settlement_cycles WHERE status = 'OPEN' FOR UPDATE").query(Long.class).optional();
    }

    public Optional<Long> openCycleId() {
        return jdbc.sql("SELECT id FROM settlement_cycles WHERE status = 'OPEN'").query(Long.class).optional();
    }

    public Optional<Long> closingCycleId() {
        return jdbc.sql("SELECT id FROM settlement_cycles WHERE status = 'CLOSING' ORDER BY id LIMIT 1").query(Long.class).optional();
    }

    public long open() {
        return jdbc.sql("INSERT INTO settlement_cycles (status) VALUES ('OPEN') RETURNING id").query(Long.class).single();
    }

    public void markClosing(long id) {
        jdbc.sql("UPDATE settlement_cycles SET status = 'CLOSING', closing_at = now() WHERE id = :id").param("id", id).update();
    }

    public void markClosed(long id) {
        jdbc.sql("UPDATE settlement_cycles SET status = 'CLOSED', closed_at = now() WHERE id = :id").param("id", id).update();
    }

    public void ensurePosition(long cycleId, String bic) {
        jdbc.sql("INSERT INTO positions (cycle_id, bic) VALUES (:c, :bic) ON CONFLICT DO NOTHING")
            .param("c", cycleId)
            .param("bic", bic)
            .update();
    }

    /**
     * Reserves an outgoing amount against the sender's net debit cap, atomically: the update only happens while
     * received - sent stays above -cap. Returns false when the cap would be exceeded.
     */
    public boolean reserveDebit(long cycleId, String bic, long amount, long cap) {
        return jdbc.sql("""
                UPDATE positions SET sent_count = sent_count + 1, sent_amount = sent_amount + :amount
                 WHERE cycle_id = :c AND bic = :bic AND received_amount - sent_amount - :amount + :cap >= 0""")
            .param("c", cycleId)
            .param("bic", bic)
            .param("amount", amount)
            .param("cap", cap)
            .update() == 1;
    }

    public void releaseDebit(long cycleId, String bic, long amount) {
        jdbc.sql("UPDATE positions SET sent_count = sent_count - 1, sent_amount = sent_amount - :amount WHERE cycle_id = :c AND bic = :bic")
            .param("c", cycleId)
            .param("bic", bic)
            .param("amount", amount)
            .update();
    }

    public void credit(long cycleId, String bic, long amount) {
        ensurePosition(cycleId, bic);
        jdbc.sql("UPDATE positions SET received_count = received_count + 1, received_amount = received_amount + :amount WHERE cycle_id = :c AND bic = :bic")
            .param("c", cycleId)
            .param("bic", bic)
            .param("amount", amount)
            .update();
    }

    public List<Position> positions(long cycleId) {
        return jdbc.sql("SELECT * FROM positions WHERE cycle_id = :c ORDER BY bic")
            .param("c", cycleId)
            .query((rs, i) -> new Position(rs.getString("bic"), rs.getLong("sent_count"), rs.getLong("sent_amount"),
                rs.getLong("received_count"), rs.getLong("received_amount")))
            .list();
    }

    public void saveReport(long cycleId, List<Line> lines) {
        for (Line l : lines) {
            jdbc.sql("""
                    INSERT INTO settlement_reports (cycle_id, bic, sent_count, sent_amount, received_count, received_amount, net_amount)
                    VALUES (:c, :bic, :sc, :sa, :rc, :ra, :net)""")
                .param("c", cycleId)
                .param("bic", l.bic())
                .param("sc", l.sentCount())
                .param("sa", l.sentAmount())
                .param("rc", l.receivedCount())
                .param("ra", l.receivedAmount())
                .param("net", l.net())
                .update();
        }
    }

    public List<Line> report(long cycleId) {
        return jdbc.sql("SELECT * FROM settlement_reports WHERE cycle_id = :c ORDER BY bic")
            .param("c", cycleId)
            .query((rs, i) -> new Line(rs.getString("bic"), rs.getLong("sent_count"), rs.getLong("sent_amount"),
                rs.getLong("received_count"), rs.getLong("received_amount"), rs.getLong("net_amount")))
            .list();
    }

    public Optional<Cycle> find(long id) {
        return jdbc.sql("SELECT * FROM settlement_cycles WHERE id = :id").param("id", id).query(CycleRepository::map).optional();
    }

    public List<Cycle> recent(int limit) {
        return jdbc.sql("SELECT * FROM settlement_cycles ORDER BY id DESC LIMIT :limit").param("limit", limit).query(CycleRepository::map).list();
    }

    private static Cycle map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Cycle(rs.getLong("id"), rs.getString("status"), rs.getObject("opened_at", OffsetDateTime.class),
            rs.getObject("closed_at", OffsetDateTime.class));
    }
}

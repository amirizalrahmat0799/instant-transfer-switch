package com.its.proxy;

import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ProxyRepository {

    public record ProxyRecord(String type, String value, String bic, String accountNumber, String accountName) {
    }

    private final JdbcClient jdbc;

    public ProxyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ProxyRecord> find(String type, String value) {
        return jdbc.sql("SELECT * FROM proxies WHERE proxy_type = :type AND proxy_value = :value")
            .param("type", type)
            .param("value", value)
            .query((rs, i) -> new ProxyRecord(rs.getString("proxy_type"), rs.getString("proxy_value"), rs.getString("bic"),
                rs.getString("account_number"), rs.getString("account_name")))
            .optional();
    }

    /**
     * Registers or updates a proxy. Returns false when the proxy belongs to another bank: an ID can only point to one
     * account in the whole network.
     */
    public boolean upsert(ProxyRecord p) {
        int rows = jdbc.sql("""
                INSERT INTO proxies (proxy_type, proxy_value, bic, account_number, account_name)
                VALUES (:type, :value, :bic, :account, :name)
                ON CONFLICT (proxy_type, proxy_value) DO UPDATE
                   SET account_number = EXCLUDED.account_number, account_name = EXCLUDED.account_name, registered_at = now()
                 WHERE proxies.bic = EXCLUDED.bic""")
            .param("type", p.type())
            .param("value", p.value())
            .param("bic", p.bic())
            .param("account", p.accountNumber())
            .param("name", p.accountName())
            .update();
        return rows > 0;
    }

    public boolean delete(String type, String value, String bic) {
        return jdbc.sql("DELETE FROM proxies WHERE proxy_type = :type AND proxy_value = :value AND bic = :bic")
            .param("type", type)
            .param("value", value)
            .param("bic", bic)
            .update() > 0;
    }

    public long count() {
        return jdbc.sql("SELECT count(*) FROM proxies").query(Long.class).single();
    }
}

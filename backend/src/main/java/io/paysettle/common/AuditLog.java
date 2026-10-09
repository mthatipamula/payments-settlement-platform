package io.paysettle.common;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Append-only audit trail of payment state transitions (who, what, when). */
@Component
public class AuditLog {

    private final JdbcClient jdbc;

    public AuditLog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void record(Rail rail, UUID paymentId, String from, String to, String actor, String detail) {
        jdbc.sql("""
                INSERT INTO payment_events (rail, payment_id, from_status, to_status, actor, detail)
                VALUES (:rail, :id, :from, :to, :actor, :detail)""")
                .param("rail", rail.name()).param("id", paymentId).param("from", from).param("to", to)
                .param("actor", actor).param("detail", detail).update();
    }

    public List<Map<String, Object>> history(UUID paymentId) {
        return jdbc.sql("SELECT from_status, to_status, actor, detail, created_at FROM payment_events WHERE payment_id = :id ORDER BY id")
                .param("id", paymentId).query().listOfRows();
    }
}

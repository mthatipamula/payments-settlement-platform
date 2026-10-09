package io.paysettle.fraud;

import io.micrometer.core.instrument.MeterRegistry;
import io.paysettle.common.Rail;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Persists fraud alerts and duplicate events, and counts them as metrics (Prometheus / Cloud Monitoring). */
@Component
public class RiskRecorder {

    private final JdbcClient jdbc;
    private final MeterRegistry meters;

    public RiskRecorder(JdbcClient jdbc, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.meters = meters;
    }

    public UUID fraudAlert(Rail rail, UUID paymentId, FraudDecision d, BigDecimal amount, String summary) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO fraud_alerts (id, rail, payment_id, score, action, rules, amount, summary)
                VALUES (:id, :rail, :pid, :score, :action, :rules, :amount, :summary)""")
                .param("id", id).param("rail", rail.name()).param("pid", paymentId).param("score", d.score())
                .param("action", d.action().name()).param("rules", d.reasons().toArray(String[]::new))
                .param("amount", amount).param("summary", summary).update();
        meters.counter("paysettle.fraud.alerts", "rail", rail.name(), "action", d.action().name()).increment();
        return id;
    }

    public void duplicate(Rail rail, String kind, UUID paymentId, UUID duplicateOf, BigDecimal amount, String detail) {
        jdbc.sql("""
                INSERT INTO duplicate_events (rail, kind, payment_id, duplicate_of, amount, detail)
                VALUES (:rail, :kind, :pid, :dup, :amount, :detail)""")
                .param("rail", rail.name()).param("kind", kind).param("pid", paymentId).param("dup", duplicateOf)
                .param("amount", amount).param("detail", detail).update();
        meters.counter("paysettle.duplicates", "rail", rail.name(), "kind", kind).increment();
    }

    public void payment(Rail rail, String status) {
        meters.counter("paysettle.payments", "rail", rail.name(), "status", status).increment();
    }
}

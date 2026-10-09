package io.paysettle.observability;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operational views for the console: today's summary, hourly statistics per rail, duplicates and settlement.
 * Everything is computed from the system of record, so the numbers always reconcile with the payments.
 * The same events are also exported as Micrometer metrics at /actuator/prometheus for alerting.
 */
@RestController
public class ObservabilityController {

    private final JdbcClient jdbc;

    public ObservabilityController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/api/v1/observability/summary")
    public Map<String, Object> summary() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ach", jdbc.sql("""
                SELECT count(*) AS entries, COALESCE(sum(amount_cents), 0) / 100.0 AS amount,
                       count(*) FILTER (WHERE status = 'SETTLED') AS settled,
                       count(*) FILTER (WHERE status IN ('ACCEPTED', 'SENT')) AS in_flight,
                       count(*) FILTER (WHERE status = 'HELD_FRAUD') AS held,
                       count(*) FILTER (WHERE status = 'REJECTED') AS rejected,
                       count(*) FILTER (WHERE status = 'DUPLICATE') AS duplicates,
                       count(*) FILTER (WHERE same_day) AS same_day
                FROM ach_entries WHERE created_at >= date_trunc('day', now())""").query().singleRow());
        out.put("achFiles", jdbc.sql("""
                SELECT count(*) AS files, count(*) FILTER (WHERE status = 'REJECTED') AS rejected,
                       count(*) FILTER (WHERE status IN ('RECEIVED', 'PROCESSING')) AS processing
                FROM ach_files WHERE direction = 'INBOUND' AND received_at >= date_trunc('day', now())""").query().singleRow());
        out.put("wire", jdbc.sql("""
                SELECT count(*) AS wires, COALESCE(sum(amount), 0) AS amount,
                       count(*) FILTER (WHERE status = 'SETTLED') AS settled,
                       COALESCE(sum(amount) FILTER (WHERE status = 'SETTLED'), 0) AS settled_amount,
                       count(*) FILTER (WHERE status IN ('RELEASED', 'SENT')) AS in_flight,
                       count(*) FILTER (WHERE status LIKE 'HELD_%') AS held,
                       count(*) FILTER (WHERE status = 'REJECTED') AS rejected,
                       count(*) FILTER (WHERE status = 'HELD_SANCTIONS') AS sanctions_holds
                FROM wires WHERE created_at >= date_trunc('day', now())""").query().singleRow());
        out.put("fraud", jdbc.sql("""
                SELECT count(*) FILTER (WHERE status = 'OPEN') AS open_alerts,
                       count(*) FILTER (WHERE status = 'OPEN' AND rail = 'ACH') AS open_ach,
                       count(*) FILTER (WHERE status = 'OPEN' AND rail = 'WIRE') AS open_wire,
                       count(*) FILTER (WHERE created_at >= date_trunc('day', now())) AS alerts_today,
                       count(*) FILTER (WHERE status = 'CONFIRMED_FRAUD' AND resolved_at >= date_trunc('day', now())) AS confirmed_today,
                       COALESCE(sum(amount) FILTER (WHERE status = 'OPEN'), 0) AS open_amount
                FROM fraud_alerts""").query().singleRow());
        out.put("duplicates", jdbc.sql("""
                SELECT count(*) AS today, count(*) FILTER (WHERE rail = 'ACH') AS ach, count(*) FILTER (WHERE rail = 'WIRE') AS wire
                FROM duplicate_events WHERE created_at >= date_trunc('day', now())""").query().singleRow());
        out.put("outbox", jdbc.sql("""
                SELECT count(*) FILTER (WHERE status = 'PENDING') AS pending, count(*) FILTER (WHERE status = 'FAILED') AS failed
                FROM outbox""").query().singleRow());
        return out;
    }

    /** Last N hours, one row per hour and rail: volume, value, fraud alerts, duplicates, rejects, settled. */
    @GetMapping("/api/v1/observability/hourly")
    public List<Map<String, Object>> hourly(@RequestParam(defaultValue = "24") int hours) {
        int h = Math.max(1, Math.min(hours, 168));
        return jdbc.sql("""
                WITH hours AS (
                  SELECT generate_series(date_trunc('hour', now()) - make_interval(hours => :h - 1), date_trunc('hour', now()),
                                         interval '1 hour') AS hour),
                ach AS (
                  SELECT date_trunc('hour', created_at) AS hour, count(*) AS n, sum(amount_cents) / 100.0 AS amount,
                         count(*) FILTER (WHERE status = 'REJECTED') AS rejected,
                         count(*) FILTER (WHERE status = 'SETTLED') AS settled
                  FROM ach_entries WHERE created_at >= date_trunc('hour', now()) - make_interval(hours => :h - 1) GROUP BY 1),
                wire AS (
                  SELECT date_trunc('hour', created_at) AS hour, count(*) AS n, sum(amount) AS amount,
                         count(*) FILTER (WHERE status = 'REJECTED') AS rejected,
                         count(*) FILTER (WHERE status = 'SETTLED') AS settled
                  FROM wires WHERE created_at >= date_trunc('hour', now()) - make_interval(hours => :h - 1) GROUP BY 1),
                alerts AS (
                  SELECT date_trunc('hour', created_at) AS hour, rail, count(*) AS n FROM fraud_alerts
                  WHERE created_at >= date_trunc('hour', now()) - make_interval(hours => :h - 1) GROUP BY 1, 2),
                dups AS (
                  SELECT date_trunc('hour', created_at) AS hour, rail, count(*) AS n FROM duplicate_events
                  WHERE created_at >= date_trunc('hour', now()) - make_interval(hours => :h - 1) GROUP BY 1, 2)
                SELECT to_char(hours.hour AT TIME ZONE 'America/New_York', 'YYYY-MM-DD"T"HH24:00') AS hour_et,
                       COALESCE(ach.n, 0) AS ach_count, COALESCE(ach.amount, 0) AS ach_amount,
                       COALESCE(ach.rejected, 0) AS ach_rejected, COALESCE(ach.settled, 0) AS ach_settled,
                       COALESCE(fa.n, 0) AS ach_fraud_alerts, COALESCE(da.n, 0) AS ach_duplicates,
                       COALESCE(wire.n, 0) AS wire_count, COALESCE(wire.amount, 0) AS wire_amount,
                       COALESCE(wire.rejected, 0) AS wire_rejected, COALESCE(wire.settled, 0) AS wire_settled,
                       COALESCE(fw.n, 0) AS wire_fraud_alerts, COALESCE(dw.n, 0) AS wire_duplicates
                FROM hours
                LEFT JOIN ach ON ach.hour = hours.hour
                LEFT JOIN wire ON wire.hour = hours.hour
                LEFT JOIN alerts fa ON fa.hour = hours.hour AND fa.rail = 'ACH'
                LEFT JOIN alerts fw ON fw.hour = hours.hour AND fw.rail = 'WIRE'
                LEFT JOIN dups da ON da.hour = hours.hour AND da.rail = 'ACH'
                LEFT JOIN dups dw ON dw.hour = hours.hour AND dw.rail = 'WIRE'
                ORDER BY hours.hour""").param("h", h).query().listOfRows();
    }

    @GetMapping("/api/v1/observability/duplicates")
    public List<Map<String, Object>> duplicates(@RequestParam(required = false) String rail) {
        return jdbc.sql("""
                SELECT id, rail, kind, payment_id, duplicate_of, amount, detail, created_at FROM duplicate_events
                WHERE CAST(:rail AS varchar) IS NULL OR rail = :rail ORDER BY created_at DESC LIMIT 300""")
                .param("rail", rail).query().listOfRows();
    }

    @GetMapping("/api/v1/observability/fraud-rules")
    public List<Map<String, Object>> fraudRules() {
        return jdbc.sql("""
                SELECT rail, unnest(rules) AS rule, count(*) AS hits, sum(amount) AS amount FROM fraud_alerts
                WHERE created_at >= now() - interval '7 days' GROUP BY rail, rule ORDER BY hits DESC""").query().listOfRows();
    }
}

package io.paysettle.fraud;

import io.paysettle.ach.AchRepository;
import io.paysettle.common.ApiException;
import io.paysettle.common.AuditLog;
import io.paysettle.common.Rail;
import io.paysettle.security.Actor;
import io.paysettle.wire.WireRepository;
import io.paysettle.wire.WireService;
import io.paysettle.wire.WireStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Fraud and sanctions alert review (ANALYST role). Clearing an alert releases the held payment; confirming it
 * rejects the payment. The person who submitted a payment (OPERATOR) cannot clear its alert: maker-checker.
 */
@RestController
public class FraudAlertController {

    private final JdbcClient jdbc;
    private final AchRepository ach;
    private final WireRepository wires;
    private final WireService wireService;
    private final AuditLog audit;

    public FraudAlertController(JdbcClient jdbc, AchRepository ach, WireRepository wires, WireService wireService,
                                AuditLog audit) {
        this.jdbc = jdbc;
        this.ach = ach;
        this.wires = wires;
        this.wireService = wireService;
        this.audit = audit;
    }

    @GetMapping("/api/v1/fraud-alerts")
    public List<Map<String, Object>> list(@RequestParam(defaultValue = "OPEN") String status,
                                          @RequestParam(required = false) String rail) {
        return jdbc.sql("""
                SELECT a.id, a.rail, a.payment_id, a.score, a.action, a.rules, a.amount, a.summary, a.status, a.resolved_by,
                       a.resolved_at, a.note, a.created_at,
                       COALESCE(w.status, e.status) AS payment_status
                FROM fraud_alerts a
                LEFT JOIN wires w ON a.rail = 'WIRE' AND w.id = a.payment_id
                LEFT JOIN ach_entries e ON a.rail = 'ACH' AND e.id = a.payment_id
                WHERE (:status = 'ALL' OR a.status = :status) AND (CAST(:rail AS varchar) IS NULL OR a.rail = :rail)
                ORDER BY a.created_at DESC LIMIT 300""")
                .param("status", status).param("rail", rail).query().listOfRows();
    }

    public record Resolution(@NotBlank @Size(max = 1000) String note) {
    }

    /** False positive: release the payment. */
    @PostMapping("/api/v1/fraud-alerts/{id}/clear")
    @Transactional
    public Map<String, Object> clear(@PathVariable UUID id, @Valid @RequestBody Resolution r) {
        Map<String, Object> alert = openAlert(id);
        UUID paymentId = (UUID) alert.get("payment_id");
        if ("ACH".equals(alert.get("rail"))) {
            if (ach.setEntryStatus(paymentId, "HELD_FRAUD", "ACCEPTED") == 1) {
                audit.record(Rail.ACH, paymentId, "HELD_FRAUD", "ACCEPTED", Actor.current(), "alert cleared: " + r.note());
            }
        } else {
            WireStatus current = wires.state(paymentId).orElseThrow().status();
            if (current.isHeld()) {
                wireService.releaseAfterReview(paymentId, current, Actor.current(), r.note());
            }
        }
        return resolve(id, "CLEARED", r.note());
    }

    /** Confirmed fraud or a true sanctions match: reject the payment. */
    @PostMapping("/api/v1/fraud-alerts/{id}/confirm")
    @Transactional
    public Map<String, Object> confirm(@PathVariable UUID id, @Valid @RequestBody Resolution r) {
        Map<String, Object> alert = openAlert(id);
        UUID paymentId = (UUID) alert.get("payment_id");
        if ("ACH".equals(alert.get("rail"))) {
            if (ach.setEntryStatus(paymentId, "HELD_FRAUD", "REJECTED") == 1) {
                audit.record(Rail.ACH, paymentId, "HELD_FRAUD", "REJECTED", Actor.current(), "fraud confirmed: " + r.note());
            }
        } else {
            WireStatus current = wires.state(paymentId).orElseThrow().status();
            if (current == WireStatus.HELD_FRAUD || current == WireStatus.HELD_SANCTIONS) {
                wireService.rejectAfterReview(paymentId, current, Actor.current(), r.note());
            }
        }
        return resolve(id, "CONFIRMED_FRAUD", r.note());
    }

    private Map<String, Object> openAlert(UUID id) {
        Map<String, Object> a = jdbc.sql("SELECT * FROM fraud_alerts WHERE id = :id FOR UPDATE").param("id", id)
                .query().listOfRows().stream().findFirst().orElseThrow(() -> ApiException.notFound("Alert not found"));
        if (!"OPEN".equals(a.get("status"))) {
            throw ApiException.conflict("ALERT_RESOLVED", "Alert is already " + a.get("status"));
        }
        return a;
    }

    private Map<String, Object> resolve(UUID id, String status, String note) {
        jdbc.sql("UPDATE fraud_alerts SET status = :s, resolved_by = :by, resolved_at = now(), note = :n WHERE id = :id")
                .param("s", status).param("by", Actor.current()).param("n", note).param("id", id).update();
        return jdbc.sql("SELECT id, status, resolved_by, resolved_at, note FROM fraud_alerts WHERE id = :id").param("id", id)
                .query().singleRow();
    }
}

package io.paysettle.wire.settlement;

import io.paysettle.common.AuditLog;
import io.paysettle.common.Rail;
import io.paysettle.wire.WireInstruction;
import io.paysettle.wire.WireRepository;
import io.paysettle.wire.WireStatus;
import io.paysettle.wire.iso.Iso20022Mapper;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Transactional-outbox relay to Fedwire. Released wires are written to the outbox in the same transaction as
 * the status change, so a crash can never lose a payment hand-off. ShedLock lets one instance relay at a time;
 * each row is sent and the result recorded, and transient failures retry with exponential backoff.
 * The UETR travels with the message, so a resend after an ambiguous failure is recognisable downstream.
 */
@Component
@ConditionalOnProperty(name = "paysettle.settlement.sweep-enabled", havingValue = "true")
public class WireSettlementWorker {

    private static final Logger log = LoggerFactory.getLogger(WireSettlementWorker.class);
    private static final int MAX_ATTEMPTS = 8;

    private final JdbcClient jdbc;
    private final WireRepository wires;
    private final FedwireGateway fedwire;
    private final Iso20022Mapper iso;
    private final AuditLog audit;
    private final TransactionTemplate tx;

    public WireSettlementWorker(JdbcClient jdbc, WireRepository wires, FedwireGateway fedwire, Iso20022Mapper iso,
                                AuditLog audit, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.wires = wires;
        this.fedwire = fedwire;
        this.iso = iso;
        this.audit = audit;
        this.tx = tx;
    }

    @Scheduled(fixedDelayString = "${paysettle.settlement.sweep-interval-ms:5000}", initialDelay = 5000)
    @SchedulerLock(name = "wire-settlement-relay", lockAtMostFor = "PT2M")
    public void relay() {
        List<Map<String, Object>> batch = jdbc.sql("""
                SELECT id, aggregate_id, attempts FROM outbox
                WHERE status = 'PENDING' AND event_type = 'WIRE_SETTLE' AND next_attempt_at <= now()
                ORDER BY id LIMIT 25""").query().listOfRows();
        for (Map<String, Object> row : batch) {
            long outboxId = ((Number) row.get("id")).longValue();
            UUID wireId = (UUID) row.get("aggregate_id");
            int attempts = ((Number) row.get("attempts")).intValue();
            try {
                settle(outboxId, wireId);
            } catch (RuntimeException e) {
                log.warn("Wire {} settlement attempt {} failed: {}", wireId, attempts + 1, e.toString());
                boolean giveUp = attempts + 1 >= MAX_ATTEMPTS;
                jdbc.sql("""
                        UPDATE outbox SET attempts = attempts + 1, last_error = :err, status = :s,
                            next_attempt_at = now() + make_interval(secs => :delay) WHERE id = :id""")
                        .param("err", String.valueOf(e.getMessage())).param("s", giveUp ? "FAILED" : "PENDING")
                        .param("delay", Math.min(600, (int) Math.pow(2, attempts) * 5)).param("id", outboxId).update();
            }
        }
    }

    private void settle(long outboxId, UUID wireId) {
        Map<String, Object> w = wires.forSettlement(wireId).orElseThrow();
        WireStatus status = WireStatus.valueOf((String) w.get("status"));
        if (status != WireStatus.RELEASED && status != WireStatus.SENT) {
            done(outboxId); // cancelled or already settled: nothing to send
            return;
        }
        int version = ((Number) w.get("version")).intValue();
        if (status == WireStatus.RELEASED) {
            tx.executeWithoutResult(t -> {
                if (!wires.transition(wireId, WireStatus.RELEASED, WireStatus.SENT, "sent to " + fedwire.name(), version)) {
                    throw new IllegalStateException("wire changed concurrently");
                }
                audit.record(Rail.WIRE, wireId, "RELEASED", "SENT", "wire-settlement-relay", fedwire.name());
            });
        }
        FedwireGateway.Result result = fedwire.send((String) w.get("pacs008"));
        WireInstruction instruction = new WireInstruction((UUID) w.get("uetr"), (String) w.get("msg_id"),
                (String) w.get("instruction_id"), (String) w.get("end_to_end_id"), (BigDecimal) w.get("amount"),
                (String) w.get("currency"), ((java.sql.Date) w.get("value_date")).toLocalDate(), (String) w.get("debtor_name"),
                "", (String) w.get("debtor_agent_aba"), (String) w.get("creditor_name"), "", (String) w.get("creditor_agent_aba"), null);
        String pacs002 = iso.toPacs002(instruction, result.accepted() ? "ACSC" : "RJCT", result.reasonCode(),
                result.reasonText(), OffsetDateTime.now());
        int current = wires.state(wireId).orElseThrow().version();
        tx.executeWithoutResult(t -> {
            WireStatus to = result.accepted() ? WireStatus.SETTLED : WireStatus.REJECTED;
            String reason = result.accepted() ? "settled, IMAD " + result.reference()
                    : "rejected by Fedwire " + result.reasonCode() + ": " + result.reasonText();
            wires.transition(wireId, WireStatus.SENT, to, reason, current);
            wires.networkResult(wireId, result.reference(), pacs002);
            audit.record(Rail.WIRE, wireId, "SENT", to.name(), "fedwire", reason);
            done(outboxId);
        });
    }

    private void done(long outboxId) {
        jdbc.sql("UPDATE outbox SET status = 'DONE' WHERE id = :id").param("id", outboxId).update();
    }
}

package io.paysettle.ach.batch;

import io.paysettle.ach.AchEntryRecord;
import io.paysettle.ach.AchRepository;
import io.paysettle.common.AuditLog;
import io.paysettle.common.Rail;
import io.paysettle.fraud.FraudDecision;
import io.paysettle.fraud.RiskRecorder;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;

/** Writes a chunk of entries in one transaction, with their fraud alerts, duplicate events and audit records. */
public class AchEntryWriter implements ItemWriter<AchEntryRecord> {

    private final AchRepository repo;
    private final RiskRecorder risk;
    private final AuditLog audit;

    public AchEntryWriter(AchRepository repo, RiskRecorder risk, AuditLog audit) {
        this.repo = repo;
        this.risk = risk;
        this.audit = audit;
    }

    @Override
    public void write(Chunk<? extends AchEntryRecord> chunk) {
        List<AchEntryRecord> items = List.copyOf(chunk.getItems());
        repo.insertEntries(items);
        for (AchEntryRecord e : items) {
            BigDecimal amount = BigDecimal.valueOf(e.amountCents(), 2);
            audit.record(Rail.ACH, e.id(), null, e.status(), "ach-ingest-job",
                    "trace " + e.traceNumber() + (e.rejectReason() == null ? "" : ": " + e.rejectReason()));
            risk.payment(Rail.ACH, e.status());
            if (e.duplicateKind() != null) {
                risk.duplicate(Rail.ACH, e.duplicateKind(), e.id(), e.duplicateOf(), amount,
                        e.companyName() + " -> " + e.individualName() + " ****" + e.accountLast4() + " trace " + e.traceNumber());
            }
            if (e.fraud() != null && e.fraud().action() != FraudDecision.Action.ALLOW) {
                risk.fraudAlert(Rail.ACH, e.id(), e.fraud(), amount,
                        e.secCode() + " " + e.direction().toLowerCase() + " from " + e.companyName() + " to " + e.individualName()
                                + " ****" + e.accountLast4());
            }
        }
    }
}

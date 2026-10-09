package io.paysettle.ach.batch;

import io.paysettle.ach.AchEntryRecord;
import io.paysettle.ach.AchRepository;
import io.paysettle.ach.nacha.NachaFile;
import io.paysettle.common.FieldCrypto;
import io.paysettle.config.PaySettleProperties;
import io.paysettle.fraud.FraudDecision;
import io.paysettle.fraud.FraudEngine;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.batch.item.ItemProcessor;

/**
 * Per-entry business checks: duplicate detection (trace number, and same originator + receiver account +
 * amount + effective date), Same Day ACH eligibility, and fraud scoring. Account numbers are encrypted here
 * and never stored in clear text.
 */
public class AchEntryProcessor implements ItemProcessor<AchEntryItem, AchEntryRecord> {

    private final AchRepository repo;
    private final FieldCrypto crypto;
    private final FraudEngine fraud;
    private final PaySettleProperties props;
    private final Map<Integer, UUID> batchIds;
    private final Set<String> seenTraces = new HashSet<>();
    private final Map<String, UUID> seenEntries = new HashMap<>();
    private final Map<String, Integer> velocityInFile = new HashMap<>();
    private final LocalDate today;
    private final LocalTime now;

    public AchEntryProcessor(AchRepository repo, FieldCrypto crypto, FraudEngine fraud, PaySettleProperties props,
                             UUID fileId) {
        this.repo = repo;
        this.crypto = crypto;
        this.fraud = fraud;
        this.props = props;
        this.batchIds = repo.batchIds(fileId);
        ZoneId zone = ZoneId.of(props.ach().timezone());
        this.today = LocalDate.now(zone);
        this.now = LocalTime.now(zone);
    }

    @Override
    public AchEntryRecord process(AchEntryItem item) {
        NachaFile.Entry e = item.entry();
        NachaFile.BatchHeader b = item.batch();
        FieldCrypto.Protected account = crypto.protect(e.accountNumber());
        UUID id = UUID.randomUUID();
        String direction = e.isDebit() ? "DEBIT" : "CREDIT";

        // Same Day ACH: effective today, before the last same-day cutoff, and within the per-payment limit.
        LocalTime lastSameDayCutoff = props.ach().windows().stream().filter(PaySettleProperties.Window::sameDay)
                .map(PaySettleProperties.Window::cutoff).max(LocalTime::compareTo).orElse(LocalTime.MIN);
        boolean sameDayRequested = !b.effectiveEntryDate().isAfter(today);
        boolean sameDay = sameDayRequested && now.isBefore(lastSameDayCutoff)
                && e.amountCents() <= props.ach().sameDayLimitCents();

        String status = "ACCEPTED";
        String reason = null;
        UUID duplicateOf = null;
        String duplicateKind = null;

        // 1. Duplicates
        String entryKey = b.companyId() + "|" + account.fingerprint() + "|" + e.amountCents() + "|" + b.effectiveEntryDate();
        if (!seenTraces.add(e.traceNumber())) {
            status = "DUPLICATE";
            duplicateKind = "ACH_TRACE";
            reason = "trace number repeated within the file";
        } else {
            var byTrace = repo.entryByTrace(e.traceNumber());
            if (byTrace.isPresent()) {
                status = "DUPLICATE";
                duplicateKind = "ACH_TRACE";
                duplicateOf = byTrace.get();
                reason = "trace number already processed";
            } else {
                var match = repo.matchingEntry(b.companyId(), account.fingerprint(), e.amountCents(), b.effectiveEntryDate(), item.fileId());
                UUID inFile = seenEntries.get(entryKey);
                if (match.isPresent()) {
                    status = "DUPLICATE";
                    duplicateKind = "ACH_ENTRY_MATCH";
                    duplicateOf = match.get();
                    reason = "same originator, receiver account, amount and effective date already submitted";
                }
                seenEntries.putIfAbsent(entryKey, id);
                if (inFile != null && duplicateOf == null) {
                    // Identical entries inside one file can be legitimate (e.g. two equal payments); flag, don't drop.
                    duplicateKind = "ACH_ENTRY_MATCH_IN_FILE";
                    duplicateOf = inFile;
                }
            }
        }

        // 2. Fraud (prenotes carry no money and are skipped)
        FraudDecision decision = null;
        if (!"DUPLICATE".equals(status) && !e.isPrenote()) {
            int velocity = repo.receiverVelocity(account.fingerprint(), props.fraud().velocityWindowMinutes())
                    + velocityInFile.merge(account.fingerprint(), 1, Integer::sum) - 1;
            boolean newReceiver = !repo.knownReceiver(b.companyId(), account.fingerprint());
            decision = fraud.evaluateAch(new FraudEngine.AchSignals(e.amountCents(), e.isDebit(), b.secCode(), sameDay,
                    velocity, newReceiver));
            if (decision.action() == FraudDecision.Action.BLOCK) {
                status = "REJECTED";
                reason = "blocked by fraud rules: " + String.join(", ", decision.reasons());
            } else if (decision.action() == FraudDecision.Action.REVIEW) {
                status = "HELD_FRAUD";
                reason = "held for fraud review: " + String.join(", ", decision.reasons());
            }
        }

        return new AchEntryRecord(id, item.fileId(), batchIds.get(b.batchNumber()), e.traceNumber(), e.transactionCode(),
                direction, e.rdfiRouting(), account.ciphertext(), account.fingerprint(), account.last4(), e.amountCents(),
                e.individualId(), e.individualName(), b.secCode(), b.companyId(), b.companyName(), b.effectiveEntryDate(),
                sameDay, status, reason, decision, duplicateOf, duplicateKind);
    }
}

package io.paysettle.wire;

import io.paysettle.common.ApiException;
import io.paysettle.common.AuditLog;
import io.paysettle.common.FieldCrypto;
import io.paysettle.common.Hashing;
import io.paysettle.common.Rail;
import io.paysettle.common.RoutingNumbers;
import io.paysettle.config.PaySettleProperties;
import io.paysettle.fraud.FraudDecision;
import io.paysettle.fraud.FraudEngine;
import io.paysettle.fraud.RiskRecorder;
import io.paysettle.fraud.SanctionsScreener;
import io.paysettle.wire.iso.Iso20022Mapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Real-time wire intake. Pipeline for each request:
 * <ol>
 *   <li>Idempotency-Key: a retry with the same key and body returns the original wire (no second payment);
 *       the same key with a different body is rejected</li>
 *   <li>Validation: amount, USD, ABA check digits, ISO 20022 field sizes</li>
 *   <li>Duplicates: UETR already used (reject); same accounts and amount within minutes (hold)</li>
 *   <li>Sanctions screening of debtor and creditor names (hold for compliance)</li>
 *   <li>Fraud scoring (hold for review, or block)</li>
 *   <li>Otherwise RELEASED, with the settlement hand-off written to the outbox in the same transaction</li>
 * </ol>
 */
@Service
public class WireService {

    private final WireRepository repo;
    private final WireStateMachine states;
    private final Iso20022Mapper iso;
    private final FieldCrypto crypto;
    private final FraudEngine fraud;
    private final SanctionsScreener sanctions;
    private final RiskRecorder risk;
    private final AuditLog audit;
    private final PaySettleProperties props;

    public WireService(WireRepository repo, WireStateMachine states, Iso20022Mapper iso, FieldCrypto crypto,
                       FraudEngine fraud, SanctionsScreener sanctions, RiskRecorder risk, AuditLog audit,
                       PaySettleProperties props) {
        this.repo = repo;
        this.states = states;
        this.iso = iso;
        this.crypto = crypto;
        this.fraud = fraud;
        this.sanctions = sanctions;
        this.risk = risk;
        this.audit = audit;
        this.props = props;
    }

    public record Outcome(UUID wireId, WireStatus status, boolean replay) {
    }

    @Transactional
    public Outcome submitJson(String idempotencyKey, WireRequest r, String actor) {
        String ourAba = props.institution().routingNumber();
        LocalDate valueDate = r.valueDate() == null ? LocalDate.now(ZoneId.of(props.wire().timezone())) : r.valueDate();
        String e2e = r.endToEndId() == null || r.endToEndId().isBlank() ? "E2E" + shortId() : r.endToEndId();
        WireInstruction w = new WireInstruction(r.uetr() == null ? UUID.randomUUID() : r.uetr(), "MSG" + shortId(),
                "INS" + shortId(), e2e, r.amount().setScale(2, RoundingMode.UNNECESSARY), "USD", valueDate,
                r.debtor().name().trim(), r.debtor().accountNumber(),
                r.debtor().routingNumber() == null ? ourAba : r.debtor().routingNumber(), r.creditor().name().trim(),
                r.creditor().accountNumber(), r.creditor().routingNumber(), r.remittanceInfo());
        return submit(idempotencyKey, Hashing.sha256(canonical(w, r.uetr() != null)), w, "API_JSON", actor);
    }

    @Transactional
    public Outcome submitIso(String idempotencyKey, String pacs008Xml, String actor) {
        WireInstruction w = iso.fromPacs008(pacs008Xml);
        return submit(idempotencyKey, Hashing.sha256(pacs008Xml.strip()), w, "API_ISO20022", actor);
    }

    /** Runs inside the caller's transaction (public entry points are @Transactional). */
    Outcome submit(String idempotencyKey, String requestHash, WireInstruction w, String channel, String actor) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 100) {
            throw ApiException.badRequest("IDEMPOTENCY_KEY_REQUIRED", "Send a unique Idempotency-Key header (max 100 chars)");
        }
        var existing = repo.byIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), requestHash, idempotencyKey);
        }
        validate(w);
        if (repo.byUetr(w.uetr()).isPresent()) {
            risk.duplicate(Rail.WIRE, "WIRE_UETR", null, repo.byUetr(w.uetr()).get(), w.amount(), "UETR " + w.uetr() + " reused");
            throw ApiException.conflict("DUPLICATE_UETR", "A wire with UETR " + w.uetr() + " already exists");
        }

        FieldCrypto.Protected debtor = crypto.protect(w.debtorAccount());
        FieldCrypto.Protected creditor = crypto.protect(w.creditorAccount());
        UUID id = UUID.randomUUID();
        WireStatus status = WireStatus.RELEASED;
        String reason = null;
        List<String> reasons = new ArrayList<>();
        String sanctionsHit = null;
        UUID duplicateOf = null;
        FraudDecision decision = null;

        var near = repo.nearDuplicate(debtor.fingerprint(), creditor.fingerprint(), w.amount(), props.wire().duplicateWindowMinutes());
        var hit = sanctions.screen(w.debtorName(), w.creditorName());
        if (near.isPresent()) {
            status = WireStatus.HELD_DUPLICATE;
            duplicateOf = near.get();
            reason = "same debtor, creditor and amount as wire " + near.get() + " within " + props.wire().duplicateWindowMinutes()
                    + " minutes; confirm before release";
        } else if (hit.isPresent()) {
            status = WireStatus.HELD_SANCTIONS;
            sanctionsHit = hit.get().partyName() + " ~ " + hit.get().listName() + " (" + hit.get().program() + ", "
                    + hit.get().similarity() + ")";
            reason = "potential sanctions match: " + sanctionsHit;
            reasons.add("SANCTIONS_POTENTIAL_MATCH");
        } else {
            boolean newBeneficiary = !repo.knownBeneficiary(debtor.fingerprint(), creditor.fingerprint());
            decision = fraud.evaluateWire(new FraudEngine.WireSignals(w.amount(), newBeneficiary,
                    repo.debtorVelocity(debtor.fingerprint(), props.fraud().velocityWindowMinutes()),
                    ZonedDateTime.now(), debtor.fingerprint().equals(creditor.fingerprint())));
            reasons.addAll(decision.reasons());
            if (decision.action() == FraudDecision.Action.BLOCK) {
                status = WireStatus.REJECTED;
                reason = "blocked by fraud rules: " + String.join(", ", decision.reasons());
            } else if (decision.action() == FraudDecision.Action.REVIEW) {
                status = WireStatus.HELD_FRAUD;
                reason = "held for fraud review: " + String.join(", ", decision.reasons());
            }
        }

        String pacs008 = iso.toPacs008(w, OffsetDateTime.now());
        try {
            repo.insert(id, idempotencyKey, requestHash, w, debtor, creditor, channel, status, reason,
                    decision == null ? (sanctionsHit != null ? 100 : 0) : decision.score(), reasons, sanctionsHit,
                    duplicateOf, pacs008);
        } catch (DuplicateKeyException e) {
            // Concurrent request with the same Idempotency-Key or UETR won the race.
            return repo.byIdempotencyKey(idempotencyKey).map(s -> replay(s, requestHash, idempotencyKey))
                    .orElseThrow(() -> ApiException.conflict("DUPLICATE_UETR", "Duplicate wire"));
        }
        audit.record(Rail.WIRE, id, null, WireStatus.RECEIVED.name(), actor, channel + " " + w.endToEndId());
        audit.record(Rail.WIRE, id, WireStatus.RECEIVED.name(), WireStatus.VALIDATED.name(), "wire-service", null);
        audit.record(Rail.WIRE, id, WireStatus.VALIDATED.name(), status.name(), "wire-service", reason);
        risk.payment(Rail.WIRE, status.name());

        String summary = w.debtorName() + " -> " + w.creditorName() + " (ABA " + w.creditorAgentAba() + ")";
        if (status == WireStatus.HELD_DUPLICATE) {
            risk.duplicate(Rail.WIRE, "WIRE_MATCH", id, duplicateOf, w.amount(), summary);
        } else if (status == WireStatus.HELD_SANCTIONS) {
            risk.fraudAlert(Rail.WIRE, id, new FraudDecision(100, reasons, FraudDecision.Action.REVIEW), w.amount(),
                    "OFAC screening: " + sanctionsHit + " | " + summary);
        } else if (decision != null && decision.flagged()) {
            risk.fraudAlert(Rail.WIRE, id, decision, w.amount(), summary);
        }
        if (status == WireStatus.RELEASED) {
            repo.enqueueSettlement(id);
        }
        return new Outcome(id, status, false);
    }

    /** Operator confirms a held possible-duplicate is intended. Fraud and sanctions holds are released via their alert. */
    @Transactional
    public void releaseDuplicateHold(UUID wireId, String actor) {
        states.move(wireId, WireStatus.HELD_DUPLICATE, WireStatus.RELEASED, "duplicate check confirmed by operator", actor);
        repo.enqueueSettlement(wireId);
    }

    @Transactional
    public void releaseAfterReview(UUID wireId, WireStatus from, String actor, String note) {
        states.move(wireId, from, WireStatus.RELEASED, "alert cleared: " + note, actor);
        repo.enqueueSettlement(wireId);
    }

    @Transactional
    public void rejectAfterReview(UUID wireId, WireStatus from, String actor, String note) {
        states.move(wireId, from, WireStatus.REJECTED, "confirmed by analyst: " + note, actor);
    }

    @Transactional
    public void cancel(UUID wireId, String actor) {
        WireStatus current = repo.state(wireId).orElseThrow(() -> ApiException.notFound("Wire not found")).status();
        if (!current.canMoveTo(WireStatus.CANCELLED)) {
            throw ApiException.conflict("NOT_CANCELLABLE", "A wire in status " + current + " can no longer be cancelled");
        }
        states.move(wireId, current, WireStatus.CANCELLED, "cancelled by operator", actor);
    }

    private Outcome replay(WireRepository.Stored s, String requestHash, String key) {
        if (!s.requestHash().equals(requestHash)) {
            throw ApiException.unprocessable("IDEMPOTENCY_KEY_REUSED",
                    "Idempotency-Key " + key + " was already used for a different request");
        }
        risk.duplicate(Rail.WIRE, "WIRE_IDEMPOTENT_REPLAY", s.id(), s.id(), null, "client retry with Idempotency-Key " + key);
        return new Outcome(s.id(), s.status(), true);
    }

    private void validate(WireInstruction w) {
        if (!"USD".equals(w.currency())) {
            throw ApiException.unprocessable("UNSUPPORTED_CURRENCY", "Fedwire Funds settles in USD only");
        }
        if (w.amount().signum() <= 0 || w.amount().compareTo(new BigDecimal("9999999999.99")) > 0 || w.amount().scale() > 2) {
            throw ApiException.unprocessable("INVALID_AMOUNT", "Amount must be between 0.01 and 9,999,999,999.99");
        }
        if (!RoutingNumbers.isValid(w.debtorAgentAba()) || !RoutingNumbers.isValid(w.creditorAgentAba())) {
            throw ApiException.unprocessable("INVALID_ROUTING_NUMBER", "Debtor or creditor agent ABA routing number is invalid");
        }
        if (w.valueDate() == null || w.valueDate().isBefore(LocalDate.now(ZoneId.of(props.wire().timezone())))) {
            throw ApiException.unprocessable("INVALID_VALUE_DATE", "Value date must be today or later");
        }
        if (w.endToEndId() == null || w.endToEndId().length() > 35) {
            throw ApiException.unprocessable("INVALID_END_TO_END_ID", "EndToEndId is required (max 35 characters)");
        }
    }

    private static String canonical(WireInstruction w, boolean includeUetr) {
        return String.join("|", w.amount().toPlainString(), w.currency(), String.valueOf(w.valueDate()), w.debtorName(),
                w.debtorAccount(), w.debtorAgentAba(), w.creditorName(), w.creditorAccount(), w.creditorAgentAba(),
                String.valueOf(w.remittanceInfo()), includeUetr ? w.uetr().toString() : "", w.endToEndId().startsWith("E2E") ? "" : w.endToEndId());
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 20).toUpperCase();
    }
}

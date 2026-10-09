package io.paysettle.fraud;

import io.paysettle.config.PaySettleProperties;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Explainable rule-based fraud scoring for both rails. Each rule adds points and a named reason; the total
 * maps to ALLOW, REVIEW (hold for an analyst) or BLOCK. Deterministic rules are auditable, which matters to
 * bank model-risk teams; an ML score can be added later as one more input.
 */
@Component
public class FraudEngine {

    private final PaySettleProperties.Fraud cfg;
    private final ZoneId wireZone;
    private final java.time.LocalTime wireCutoff;

    public FraudEngine(PaySettleProperties props) {
        this.cfg = props.fraud();
        this.wireZone = ZoneId.of(props.wire().timezone());
        this.wireCutoff = props.wire().customerCutoff();
    }

    /** Signals for an ACH entry. */
    public record AchSignals(long amountCents, boolean debit, String secCode, boolean sameDay, int receiverVelocity,
                             boolean newReceiverForOriginator) {
    }

    /** Signals for a wire. */
    public record WireSignals(BigDecimal amount, boolean newBeneficiary, int debtorVelocity, ZonedDateTime submittedAt,
                              boolean debtorEqualsCreditor) {
    }

    public FraudDecision evaluateAch(AchSignals s) {
        int score = 0;
        List<String> reasons = new ArrayList<>();
        long high = cfg.achHighAmountCents();
        if (s.amountCents() >= high * 4) {
            score += 50;
            reasons.add("ACH_VERY_HIGH_AMOUNT");
        } else if (s.amountCents() >= high) {
            score += 30;
            reasons.add("ACH_HIGH_AMOUNT");
        }
        // Consumer internet-authorised debits (WEB) are the most common account-takeover vector.
        if (s.debit() && "WEB".equals(s.secCode()) && s.amountCents() >= 500_000) {
            score += 35;
            reasons.add("WEB_DEBIT_HIGH_VALUE");
        }
        if (s.receiverVelocity() >= cfg.achVelocityLimit()) {
            score += 50; // many credits to one account in a short window: classic money-mule pattern
            reasons.add("RECEIVER_VELOCITY");
        }
        if (s.newReceiverForOriginator() && s.amountCents() >= high / 2) {
            score += 25;
            reasons.add("NEW_RECEIVER_HIGH_AMOUNT");
        }
        if (s.amountCents() >= 1_000_000 && s.amountCents() % 100_000 == 0) {
            score += 10;
            reasons.add("ROUND_AMOUNT");
        }
        if (s.sameDay() && s.amountCents() >= high * 2) {
            score += 10;
            reasons.add("SAME_DAY_HIGH_VALUE");
        }
        return decide(score, reasons);
    }

    public FraudDecision evaluateWire(WireSignals s) {
        int score = 0;
        List<String> reasons = new ArrayList<>();
        BigDecimal high = cfg.wireHighAmount();
        if (s.amount().compareTo(high.multiply(BigDecimal.valueOf(4))) >= 0) {
            score += 45;
            reasons.add("WIRE_VERY_HIGH_AMOUNT");
        } else if (s.amount().compareTo(high) >= 0) {
            score += 30;
            reasons.add("WIRE_HIGH_AMOUNT");
        }
        if (s.newBeneficiary()) {
            score += 25;
            reasons.add("NEW_BENEFICIARY");
            if (s.amount().compareTo(high.divide(BigDecimal.TWO)) >= 0) {
                score += 20;
                reasons.add("NEW_BENEFICIARY_HIGH_AMOUNT");
            }
        }
        if (s.debtorVelocity() >= cfg.wireVelocityLimit()) {
            score += 35;
            reasons.add("DEBTOR_VELOCITY");
        }
        ZonedDateTime local = s.submittedAt().withZoneSameInstant(wireZone);
        boolean weekend = local.getDayOfWeek() == DayOfWeek.SATURDAY || local.getDayOfWeek() == DayOfWeek.SUNDAY;
        if (weekend || local.toLocalTime().isAfter(wireCutoff) || local.getHour() < 6) {
            score += 10;
            reasons.add("OUTSIDE_BUSINESS_HOURS");
        }
        if (s.amount().compareTo(BigDecimal.valueOf(10_000)) >= 0
                && s.amount().remainder(BigDecimal.valueOf(1_000)).signum() == 0) {
            score += 10;
            reasons.add("ROUND_AMOUNT");
        }
        if (s.debtorEqualsCreditor()) {
            score += 5;
            reasons.add("SELF_TRANSFER");
        }
        return decide(score, reasons);
    }

    private FraudDecision decide(int score, List<String> reasons) {
        int capped = Math.min(100, score);
        FraudDecision.Action action = capped >= cfg.blockScore() ? FraudDecision.Action.BLOCK
                : capped >= cfg.reviewScore() ? FraudDecision.Action.REVIEW : FraudDecision.Action.ALLOW;
        return new FraudDecision(capped, List.copyOf(reasons), action);
    }
}

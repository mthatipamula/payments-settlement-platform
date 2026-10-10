package io.paysettle.fraud;

import static org.assertj.core.api.Assertions.assertThat;

import io.paysettle.config.PaySettleProperties;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class FraudEngineTest {

    static PaySettleProperties props() {
        return new PaySettleProperties(null, null, null,
                new PaySettleProperties.Ach("America/New_York", 100_000_000, List.of()),
                new PaySettleProperties.Wire("America/New_York", LocalTime.of(18, 0), 30),
                new PaySettleProperties.Fraud(50, 85, 2_500_000, new BigDecimal("250000.00"), 60, 20, 5, 0.92),
                null, null, null);
    }

    private final FraudEngine engine = new FraudEngine(props());
    private final ZonedDateTime businessHours = ZonedDateTime.of(2026, 10, 7, 11, 0, 0, 0, ZoneId.of("America/New_York"));

    @Test
    void ordinaryPayrollIsAllowed() {
        var d = engine.evaluateAch(new FraudEngine.AchSignals(250_000, false, "PPD", false, 1, false));
        assertThat(d.action()).isEqualTo(FraudDecision.Action.ALLOW);
    }

    @Test
    void largeWebDebitToNewReceiverIsHeld() {
        var d = engine.evaluateAch(new FraudEngine.AchSignals(1_250_000, true, "WEB", false, 1, true));
        assertThat(d.reasons()).contains("WEB_DEBIT_HIGH_VALUE", "NEW_RECEIVER_HIGH_AMOUNT");
        assertThat(d.action()).isEqualTo(FraudDecision.Action.REVIEW);
    }

    @Test
    void velocityPlusHighAmountIsBlocked() {
        var d = engine.evaluateAch(new FraudEngine.AchSignals(12_000_000, false, "PPD", true, 25, true));
        assertThat(d.action()).isEqualTo(FraudDecision.Action.BLOCK);
    }

    @Test
    void wireRules() {
        assertThat(engine.evaluateWire(new FraudEngine.WireSignals(new BigDecimal("12500.37"), false, 1, businessHours, false))
                .action()).isEqualTo(FraudDecision.Action.ALLOW);
        var held = engine.evaluateWire(new FraudEngine.WireSignals(new BigDecimal("300000.45"), true, 1, businessHours, false));
        assertThat(held.reasons()).contains("WIRE_HIGH_AMOUNT", "NEW_BENEFICIARY", "NEW_BENEFICIARY_HIGH_AMOUNT");
        assertThat(held.action()).isEqualTo(FraudDecision.Action.REVIEW);
        var blocked = engine.evaluateWire(new FraudEngine.WireSignals(new BigDecimal("2000000.00"), true, 6,
                businessHours.withHour(22), false));
        assertThat(blocked.action()).isEqualTo(FraudDecision.Action.BLOCK);
    }

    @Test
    void sanctionsNameMatchingIsFuzzy() {
        assertThat(SanctionsScreener.normalize("Northwind Shadow Trading, L.L.C.")).isEqualTo("northwind shadow trading l l c");
        assertThat(SanctionsScreener.jaroWinkler(SanctionsScreener.normalize("Northwind Shadow Trading LLC"),
                SanctionsScreener.normalize("Northwind Shadow Trading"))).isEqualTo(1.0);
        assertThat(SanctionsScreener.jaroWinkler("viktor malenkov", "victor malenkov")).isGreaterThan(0.92);
        assertThat(SanctionsScreener.jaroWinkler("alicia gomez", "viktor malenkov")).isLessThan(0.7);
    }
}

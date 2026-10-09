package io.paysettle.wire;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.paysettle.common.ApiException;
import io.paysettle.wire.iso.Iso20022Mapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class Iso20022MapperTest {

    private final Iso20022Mapper mapper = new Iso20022Mapper();

    @Test
    void pacs008RoundTrip() {
        WireInstruction w = new WireInstruction(UUID.randomUUID(), "MSG1", "INS1", "E2E-INV-2048", new BigDecimal("125000.50"),
                "USD", LocalDate.of(2026, 10, 9), "Acme Treasury", "88001234", "123456780", "Bluefin Holdings",
                "44009876", "021000021", "Invoice 2048");
        String xml = mapper.toPacs008(w, OffsetDateTime.now());
        assertThat(xml).contains("pacs.008.001.08", "<ClrSys>", "FDW", "USABA", w.uetr().toString(), "125000.50");

        WireInstruction back = mapper.fromPacs008(xml);
        assertThat(back.uetr()).isEqualTo(w.uetr());
        assertThat(back.amount()).isEqualByComparingTo(w.amount());
        assertThat(back.creditorAgentAba()).isEqualTo("021000021");
        assertThat(back.creditorAccount()).isEqualTo("44009876");
        assertThat(back.remittanceInfo()).isEqualTo("Invoice 2048");
    }

    @Test
    void pacs002CarriesStatusAndReason() {
        WireInstruction w = new WireInstruction(UUID.randomUUID(), "MSG1", "INS1", "E2E1", BigDecimal.TEN, "USD",
                LocalDate.now(), "A", "1", "123456780", "B", "2", "021000021", null);
        String xml = mapper.toPacs002(w, "RJCT", "AC01", "Incorrect account number", OffsetDateTime.now());
        assertThat(xml).contains("pacs.002.001.10", "<TxSts>RJCT</TxSts>", "AC01", w.uetr().toString());
    }

    @Test
    void rejectsGarbage() {
        assertThatThrownBy(() -> mapper.fromPacs008("<Document/>")).isInstanceOf(ApiException.class);
    }

    @Test
    void wireStatusRules() {
        assertThat(WireStatus.HELD_SANCTIONS.canMoveTo(WireStatus.CANCELLED)).isFalse();   // compliance must decide
        assertThat(WireStatus.HELD_DUPLICATE.canMoveTo(WireStatus.RELEASED)).isTrue();
        assertThat(WireStatus.SETTLED.canMoveTo(WireStatus.REJECTED)).isFalse();          // Fedwire is final
        assertThat(WireStatus.REJECTED.canMoveTo(WireStatus.SENT)).isFalse();
    }
}

package io.paysettle.ach.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class FedLineAckPollerTest {

    @Test
    void parsesAcceptedAcknowledgement() {
        var ack = FedLineAckPoller.parse("""
                FILE=PAYSETTLE_SAME_DAY_1.ach
                STATUS=ACCEPTED
                REFERENCE=FED-ACK-1
                """);
        assertThat(ack.fileName()).isEqualTo("PAYSETTLE_SAME_DAY_1.ach");
        assertThat(ack.accepted()).isTrue();
        assertThat(ack.reference()).isEqualTo("FED-ACK-1");
    }

    @Test
    void parsesRejectionWithReason() {
        var ack = FedLineAckPoller.parse("FILE=x.ach\nSTATUS=REJECTED\nREASON=Batch control total mismatch\n");
        assertThat(ack.accepted()).isFalse();
        assertThat(ack.reason()).isEqualTo("Batch control total mismatch");
    }

    @Test
    void rejectsMalformedAcknowledgement() {
        assertThatThrownBy(() -> FedLineAckPoller.parse("FILE=x.ach\nSTATUS=MAYBE")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FedLineAckPoller.parse("STATUS=ACCEPTED")).isInstanceOf(IllegalArgumentException.class);
    }
}

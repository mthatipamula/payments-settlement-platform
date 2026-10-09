package io.paysettle.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RoutingNumbersTest {

    @Test
    void validatesAbaChecksum() {
        assertThat(RoutingNumbers.isValid("021000021")).isTrue();
        assertThat(RoutingNumbers.isValid("011000015")).isTrue();
        assertThat(RoutingNumbers.isValid("123456780")).isTrue();
        assertThat(RoutingNumbers.isValid("021000022")).isFalse();
        assertThat(RoutingNumbers.isValid("12345678")).isFalse();
        assertThat(RoutingNumbers.isValid("A21000021")).isFalse();
        assertThat(RoutingNumbers.checkDigit("02100002")).isEqualTo(1);
    }
}

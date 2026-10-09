package io.paysettle.wire;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** JSON wire request. Field limits follow ISO 20022 (names 140, ids 35) and Fedwire (USD, max 9,999,999,999.99). */
public record WireRequest(
        @NotNull @DecimalMin("0.01") @DecimalMax("9999999999.99") @Digits(integer = 10, fraction = 2) BigDecimal amount,
        @Pattern(regexp = "USD", message = "Fedwire Funds settles in USD only") String currency,
        LocalDate valueDate,
        @NotNull @Valid Party debtor,
        @NotNull @Valid Party creditor,
        @Size(max = 140) String remittanceInfo,
        @Size(max = 35) String endToEndId,
        UUID uetr) {

    public record Party(@NotBlank @Size(max = 140) String name,
                        @NotBlank @Pattern(regexp = "[A-Za-z0-9-]{4,34}", message = "4-34 letters, digits or dashes") String accountNumber,
                        @Pattern(regexp = "\\d{9}", message = "9-digit ABA routing number") String routingNumber) {
    }
}

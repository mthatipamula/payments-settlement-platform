package io.paysettle.wire;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** A customer credit transfer, independent of how it arrived (JSON API or ISO 20022 pacs.008). */
public record WireInstruction(UUID uetr, String msgId, String instructionId, String endToEndId, BigDecimal amount,
                              String currency, LocalDate valueDate, String debtorName, String debtorAccount,
                              String debtorAgentAba, String creditorName, String creditorAccount, String creditorAgentAba,
                              String remittanceInfo) {
}

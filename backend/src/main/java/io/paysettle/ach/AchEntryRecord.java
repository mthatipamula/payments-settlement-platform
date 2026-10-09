package io.paysettle.ach;

import io.paysettle.fraud.FraudDecision;
import java.time.LocalDate;
import java.util.UUID;

/** An entry after validation, duplicate and fraud checks, ready to be stored. */
public record AchEntryRecord(UUID id, UUID fileId, UUID batchId, String traceNumber, String transactionCode,
                             String direction, String rdfiRouting, String accountCiphertext, String accountFingerprint,
                             String accountLast4, long amountCents, String individualId, String individualName,
                             String secCode, String companyId, String companyName, LocalDate effectiveDate,
                             boolean sameDay, String status, String rejectReason, FraudDecision fraud,
                             UUID duplicateOf, String duplicateKind) {
}

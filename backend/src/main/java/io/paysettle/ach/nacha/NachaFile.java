package io.paysettle.ach.nacha;

import java.time.LocalDate;
import java.util.List;

/**
 * A parsed NACHA file. Record layouts follow the Nacha Operating Rules (94-character records):
 * 1 File Header, 5 Batch Header, 6 Entry Detail, 7 Addenda, 8 Batch Control, 9 File Control.
 */
public record NachaFile(FileHeader header, List<Batch> batches, FileControl control) {

    public record FileHeader(String immediateDestination, String immediateOrigin, String creationDate,
                             String creationTime, char fileIdModifier, String destinationName, String originName,
                             String referenceCode) {
    }

    public record Batch(BatchHeader header, List<Entry> entries, BatchControl control) {
    }

    public record BatchHeader(String serviceClassCode, String companyName, String discretionaryData,
                              String companyId, String secCode, String entryDescription, String descriptiveDate,
                              LocalDate effectiveEntryDate, String settlementDate, char originatorStatus,
                              String odfiId, int batchNumber) {
    }

    /** Entry Detail record plus its optional addenda. */
    public record Entry(String transactionCode, String rdfiRouting8, char checkDigit, String accountNumber,
                       long amountCents, String individualId, String individualName, String discretionaryData,
                       boolean addendaIndicator, String traceNumber, List<String> addenda) {

        public String rdfiRouting() {
            return rdfiRouting8 + checkDigit;
        }

        /** NACHA transaction codes: x2/x3/x4 credits, x7/x8/x9 debits (checking 2x, savings 3x, GL 4x, loan 5x). */
        public boolean isDebit() {
            char last = transactionCode.charAt(1);
            return last == '7' || last == '8' || last == '9' || "55".equals(transactionCode);
        }

        public boolean isPrenote() {
            char last = transactionCode.charAt(1);
            return last == '3' || last == '8';
        }
    }

    public record BatchControl(String serviceClassCode, int entryAddendaCount, long entryHash, long totalDebitCents,
                               long totalCreditCents, String companyId, String odfiId, int batchNumber) {
    }

    public record FileControl(int batchCount, int blockCount, int entryAddendaCount, long entryHash,
                              long totalDebitCents, long totalCreditCents) {
    }

    public int entryCount() {
        return batches.stream().mapToInt(b -> b.entries().size()).sum();
    }
}

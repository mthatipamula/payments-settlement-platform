package io.paysettle.ach.nacha;

import io.paysettle.common.RoutingNumbers;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds a NACHA file (e.g. the outbound origination file sent to FedACH). Alphanumeric fields are
 * left-justified and space-filled, numeric fields right-justified and zero-filled; the file is padded with
 * filler records to a multiple of 10 (blocking factor 10).
 */
public final class NachaFileBuilder {

    private static final DateTimeFormatter YYMMDD = DateTimeFormatter.ofPattern("yyMMdd");
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HHmm");

    public record OutEntry(String transactionCode, String rdfiRouting, String accountNumber, long amountCents,
                           String individualId, String individualName, String traceNumber, String addenda) {
    }

    public record OutBatch(String companyName, String companyId, String secCode, String entryDescription,
                           LocalDate effectiveDate, List<OutEntry> entries) {
    }

    private final String immediateDestination;
    private final String immediateOrigin;
    private final String destinationName;
    private final String originName;
    private final String odfi8;

    public NachaFileBuilder(String immediateDestination, String immediateOrigin, String destinationName,
                            String originName) {
        this.immediateDestination = immediateDestination;
        this.immediateOrigin = immediateOrigin;
        this.destinationName = destinationName;
        this.originName = originName;
        this.odfi8 = immediateOrigin.substring(0, 8);
    }

    public String build(List<OutBatch> batches, LocalDateTime createdAt, char fileIdModifier) {
        List<String> records = new ArrayList<>();
        records.add("1" + "01" + " " + n(immediateDestination, 9) + " " + n(immediateOrigin, 9) + createdAt.format(YYMMDD)
                + createdAt.format(HHMM) + fileIdModifier + "094" + "10" + "1" + a(destinationName, 23) + a(originName, 23)
                + a("", 8));
        long fileHash = 0;
        long fileDebits = 0;
        long fileCredits = 0;
        int fileEntryAddenda = 0;
        int batchNumber = 0;
        for (OutBatch b : batches) {
            batchNumber++;
            long debits = b.entries().stream().filter(e -> isDebit(e.transactionCode())).mapToLong(OutEntry::amountCents).sum();
            long credits = b.entries().stream().filter(e -> !isDebit(e.transactionCode())).mapToLong(OutEntry::amountCents).sum();
            String scc = debits > 0 && credits > 0 ? "200" : debits > 0 ? "225" : "220";
            records.add("5" + scc + a(b.companyName(), 16) + a("", 20) + a(b.companyId(), 10) + a(b.secCode(), 3)
                    + a(b.entryDescription(), 10) + a("", 6) + b.effectiveDate().format(YYMMDD) + "   " + "1" + odfi8
                    + n(String.valueOf(batchNumber), 7));
            long hash = 0;
            int entryAddenda = 0;
            for (OutEntry e : b.entries()) {
                String rdfi8 = e.rdfiRouting().substring(0, 8);
                boolean hasAddenda = e.addenda() != null && !e.addenda().isBlank();
                records.add("6" + e.transactionCode() + rdfi8 + RoutingNumbers.checkDigit(rdfi8) + a(e.accountNumber(), 17)
                        + n(String.valueOf(e.amountCents()), 10) + a(e.individualId(), 15) + a(e.individualName(), 22) + "  "
                        + (hasAddenda ? "1" : "0") + e.traceNumber());
                entryAddenda++;
                if (hasAddenda) {
                    records.add("7" + "05" + a(e.addenda(), 80) + "0001" + e.traceNumber().substring(8));
                    entryAddenda++;
                }
                hash += Long.parseLong(rdfi8);
            }
            records.add("8" + scc + n(String.valueOf(entryAddenda), 6) + n(String.valueOf(hash % 10_000_000_000L), 10)
                    + n(String.valueOf(debits), 12) + n(String.valueOf(credits), 12) + a(b.companyId(), 10) + a("", 19)
                    + a("", 6) + odfi8 + n(String.valueOf(batchNumber), 7));
            fileHash += hash;
            fileDebits += debits;
            fileCredits += credits;
            fileEntryAddenda += entryAddenda;
        }
        int recordCount = records.size() + 1;
        int blocks = (recordCount + 9) / 10;
        records.add("9" + n(String.valueOf(batches.size()), 6) + n(String.valueOf(blocks), 6)
                + n(String.valueOf(fileEntryAddenda), 8) + n(String.valueOf(fileHash % 10_000_000_000L), 10)
                + n(String.valueOf(fileDebits), 12) + n(String.valueOf(fileCredits), 12) + a("", 39));
        while (records.size() % 10 != 0) {
            records.add("9".repeat(94));
        }
        return String.join("\n", records) + "\n";
    }

    static boolean isDebit(String code) {
        char last = code.charAt(1);
        return last == '7' || last == '8' || last == '9' || "55".equals(code);
    }

    /** Alphanumeric: left-justified, space-filled, truncated, upper-case (NACHA recommends upper case). */
    static String a(String value, int len) {
        String v = value == null ? "" : value.toUpperCase();
        return v.length() >= len ? v.substring(0, len) : String.format("%-" + len + "s", v);
    }

    /** Numeric: right-justified, zero-filled. */
    static String n(String value, int len) {
        String v = value == null ? "" : value;
        if (v.length() > len) {
            throw new IllegalArgumentException("Numeric field overflow: " + v + " > " + len + " digits");
        }
        return "0".repeat(len - v.length()) + v;
    }
}

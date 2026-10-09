package io.paysettle.ach.nacha;

import io.paysettle.ach.nacha.NachaFile.Batch;
import io.paysettle.ach.nacha.NachaFile.BatchControl;
import io.paysettle.ach.nacha.NachaFile.BatchHeader;
import io.paysettle.ach.nacha.NachaFile.Entry;
import io.paysettle.ach.nacha.NachaFile.FileControl;
import io.paysettle.ach.nacha.NachaFile.FileHeader;
import io.paysettle.common.RoutingNumbers;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Parses and validates a NACHA file. Structural or control-total errors reject the whole file (as the ACH
 * Operator would); entry-level business checks (fraud, duplicates, limits) happen later in the batch job.
 *
 * <p>Checks: record length and order, record type codes, numeric fields, RDFI check digits, batch and file
 * entry counts, entry hash (sum of RDFI routing prefixes, low 10 digits), debit and credit totals, batch
 * numbering, and that the file is blocked in records of 10 (filler records of all 9s).
 */
public final class NachaParser {

    static final Set<String> SEC_CODES = Set.of("PPD", "CCD", "WEB", "TEL", "CTX", "CIE", "IAT", "POP", "ARC", "BOC", "RCK");
    /** Forward entries and prenotes (returns/NOCs 21, 26, 31, 36 are handled by a separate return flow). */
    static final Set<String> TRANSACTION_CODES = Set.of("22", "23", "24", "27", "28", "29", "32", "33", "34",
            "37", "38", "39", "42", "47", "52", "55");
    private static final DateTimeFormatter YYMMDD = DateTimeFormatter.ofPattern("yyMMdd");

    private NachaParser() {
    }

    public static NachaFile parse(String content) {
        String[] raw = content.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        List<String> lines = new ArrayList<>();
        for (String l : raw) {
            if (!l.isBlank()) {
                if (l.length() > 94) {
                    throw new NachaParseException(lines.size() + 1, "record longer than 94 characters");
                }
                lines.add(String.format("%-94s", l)); // tolerate stripped trailing spaces
            }
        }
        if (lines.isEmpty()) {
            throw new NachaParseException(0, "empty file");
        }
        int i = 0;
        FileHeader header = fileHeader(lines.get(i), ++i);
        List<Batch> batches = new ArrayList<>();
        FileControl control = null;
        long fileHash = 0;
        long fileDebits = 0;
        long fileCredits = 0;
        int fileEntryAddenda = 0;
        int expectedBatch = 1;

        while (i < lines.size()) {
            String line = lines.get(i);
            char type = line.charAt(0);
            if (type == '5') {
                BatchHeader bh = batchHeader(line, i + 1);
                if (bh.batchNumber() != expectedBatch) {
                    throw new NachaParseException(i + 1, "batch number " + bh.batchNumber() + " out of sequence (expected " + expectedBatch + ")");
                }
                expectedBatch++;
                i++;
                List<Entry> entries = new ArrayList<>();
                long hash = 0;
                long debits = 0;
                long credits = 0;
                int entryAddenda = 0;
                while (i < lines.size() && lines.get(i).charAt(0) == '6') {
                    int lineNo = i + 1;
                    String e = lines.get(i++);
                    List<String> addenda = new ArrayList<>();
                    while (i < lines.size() && lines.get(i).charAt(0) == '7') {
                        addenda.add(lines.get(i++).substring(3, 83).trim());
                    }
                    Entry entry = entry(e, lineNo, addenda);
                    entries.add(entry);
                    entryAddenda += 1 + addenda.size();
                    hash += Long.parseLong(entry.rdfiRouting8());
                    if (entry.isDebit()) {
                        debits += entry.amountCents();
                    } else {
                        credits += entry.amountCents();
                    }
                }
                if (i >= lines.size() || lines.get(i).charAt(0) != '8') {
                    throw new NachaParseException(i + 1, "batch " + bh.batchNumber() + " has no batch control record");
                }
                BatchControl bc = batchControl(lines.get(i), i + 1);
                verify(bc.entryAddendaCount() == entryAddenda, i + 1, "batch entry/addenda count " + bc.entryAddendaCount() + " != " + entryAddenda);
                verify(bc.entryHash() == hash % 10_000_000_000L, i + 1, "batch entry hash mismatch");
                verify(bc.totalDebitCents() == debits, i + 1, "batch total debits " + bc.totalDebitCents() + " != " + debits);
                verify(bc.totalCreditCents() == credits, i + 1, "batch total credits " + bc.totalCreditCents() + " != " + credits);
                verify(bc.batchNumber() == bh.batchNumber(), i + 1, "batch control number does not match header");
                verify(bc.serviceClassCode().equals(bh.serviceClassCode()), i + 1, "service class code mismatch");
                checkServiceClass(bh, debits, credits, i + 1);
                i++;
                batches.add(new Batch(bh, entries, bc));
                fileHash += hash;
                fileDebits += debits;
                fileCredits += credits;
                fileEntryAddenda += entryAddenda;
            } else if (type == '9') {
                if (control == null) {
                    control = fileControl(line, i + 1);
                } else if (!line.chars().allMatch(c -> c == '9')) {
                    throw new NachaParseException(i + 1, "only filler records (all 9s) may follow the file control");
                }
                i++;
            } else {
                throw new NachaParseException(i + 1, "unexpected record type '" + type + "'");
            }
        }
        if (control == null) {
            throw new NachaParseException(lines.size(), "missing file control record");
        }
        verify(control.batchCount() == batches.size(), 0, "file batch count " + control.batchCount() + " != " + batches.size());
        verify(control.entryAddendaCount() == fileEntryAddenda, 0, "file entry/addenda count mismatch");
        verify(control.entryHash() == fileHash % 10_000_000_000L, 0, "file entry hash mismatch");
        verify(control.totalDebitCents() == fileDebits, 0, "file total debits mismatch");
        verify(control.totalCreditCents() == fileCredits, 0, "file total credits mismatch");
        verify(lines.size() % 10 == 0, 0, "file is not blocked in multiples of 10 records (" + lines.size() + " records)");
        verify(control.blockCount() == lines.size() / 10, 0, "block count " + control.blockCount() + " != " + lines.size() / 10);
        return new NachaFile(header, batches, control);
    }

    private static FileHeader fileHeader(String l, int lineNo) {
        verify(l.charAt(0) == '1', lineNo, "first record must be a File Header (type 1)");
        verify("094".equals(l.substring(34, 37)), lineNo, "record size must be 094");
        verify("10".equals(l.substring(37, 39)), lineNo, "blocking factor must be 10");
        String destination = l.substring(3, 13).trim();
        String origin = l.substring(13, 23).trim();
        verify(destination.matches("\\d{9}") && RoutingNumbers.isValid(destination), lineNo, "invalid immediate destination routing number");
        verify(origin.matches("\\d{9,10}"), lineNo, "invalid immediate origin");
        String date = l.substring(23, 29);
        date(date, lineNo, "file creation date");
        char modifier = l.charAt(33);
        verify(Character.isUpperCase(modifier) || Character.isDigit(modifier), lineNo, "File ID Modifier must be A-Z or 0-9");
        return new FileHeader(destination, origin, date, l.substring(29, 33).trim(), modifier,
                l.substring(40, 63).trim(), l.substring(63, 86).trim(), l.substring(86, 94).trim());
    }

    private static BatchHeader batchHeader(String l, int lineNo) {
        String scc = l.substring(1, 4);
        verify(Set.of("200", "220", "225").contains(scc), lineNo, "service class code must be 200, 220 or 225");
        String sec = l.substring(50, 53);
        verify(SEC_CODES.contains(sec), lineNo, "unsupported SEC code " + sec);
        String companyId = l.substring(40, 50).trim();
        verify(!companyId.isEmpty(), lineNo, "company identification is required");
        String odfi = l.substring(79, 87);
        verify(odfi.matches("\\d{8}"), lineNo, "ODFI identification must be 8 digits");
        return new BatchHeader(scc, l.substring(4, 20).trim(), l.substring(20, 40).trim(), companyId, sec,
                l.substring(53, 63).trim(), l.substring(63, 69).trim(), date(l.substring(69, 75), lineNo, "effective entry date"),
                l.substring(75, 78).trim(), l.charAt(78), odfi, num(l.substring(87, 94), lineNo, "batch number"));
    }

    private static Entry entry(String l, int lineNo, List<String> addenda) {
        String code = l.substring(1, 3);
        verify(TRANSACTION_CODES.contains(code), lineNo, "unsupported transaction code " + code);
        String rdfi = l.substring(3, 11);
        verify(rdfi.matches("\\d{8}"), lineNo, "RDFI routing must be 8 digits");
        char check = l.charAt(11);
        verify(Character.isDigit(check) && RoutingNumbers.checkDigit(rdfi) == check - '0', lineNo,
                "RDFI check digit is wrong for " + rdfi + check);
        String account = l.substring(12, 29).trim();
        verify(!account.isEmpty(), lineNo, "DFI account number is required");
        long amount = num(l.substring(29, 39), lineNo, "amount");
        boolean addendaIndicator = l.charAt(78) == '1';
        verify(addendaIndicator == !addenda.isEmpty(), lineNo, "addenda indicator does not match addenda records");
        String trace = l.substring(79, 94);
        verify(trace.matches("\\d{15}"), lineNo, "trace number must be 15 digits");
        String name = l.substring(54, 76).trim();
        verify(!name.isEmpty(), lineNo, "individual/receiving company name is required");
        return new Entry(code, rdfi, check, account, amount, l.substring(39, 54).trim(), name,
                l.substring(76, 78).trim(), addendaIndicator, trace, List.copyOf(addenda));
    }

    private static BatchControl batchControl(String l, int lineNo) {
        return new BatchControl(l.substring(1, 4), num(l.substring(4, 10), lineNo, "entry/addenda count"),
                lnum(l.substring(10, 20), lineNo, "entry hash"), lnum(l.substring(20, 32), lineNo, "total debits"),
                lnum(l.substring(32, 44), lineNo, "total credits"), l.substring(44, 54).trim(), l.substring(79, 87),
                num(l.substring(87, 94), lineNo, "batch number"));
    }

    private static FileControl fileControl(String l, int lineNo) {
        return new FileControl(num(l.substring(1, 7), lineNo, "batch count"), num(l.substring(7, 13), lineNo, "block count"),
                num(l.substring(13, 21), lineNo, "entry/addenda count"), lnum(l.substring(21, 31), lineNo, "entry hash"),
                lnum(l.substring(31, 43), lineNo, "total debits"), lnum(l.substring(43, 55), lineNo, "total credits"));
    }

    private static void checkServiceClass(BatchHeader bh, long debits, long credits, int lineNo) {
        if ("220".equals(bh.serviceClassCode())) {
            verify(debits == 0, lineNo, "service class 220 (credits only) batch contains debits");
        }
        if ("225".equals(bh.serviceClassCode())) {
            verify(credits == 0, lineNo, "service class 225 (debits only) batch contains credits");
        }
    }

    private static LocalDate date(String yymmdd, int lineNo, String field) {
        try {
            return LocalDate.parse(yymmdd, YYMMDD);
        } catch (DateTimeParseException e) {
            throw new NachaParseException(lineNo, field + " is not a valid YYMMDD date: '" + yymmdd + "'");
        }
    }

    private static int num(String s, int lineNo, String field) {
        return (int) lnum(s, lineNo, field);
    }

    private static long lnum(String s, int lineNo, String field) {
        if (!s.matches("\\d+")) {
            throw new NachaParseException(lineNo, field + " must be numeric: '" + s + "'");
        }
        return Long.parseLong(s);
    }

    private static void verify(boolean ok, int lineNo, String message) {
        if (!ok) {
            throw new NachaParseException(lineNo, message);
        }
    }
}

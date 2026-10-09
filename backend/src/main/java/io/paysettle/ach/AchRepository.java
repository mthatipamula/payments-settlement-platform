package io.paysettle.ach;

import io.paysettle.ach.nacha.NachaFile;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AchRepository {

    private final JdbcClient jdbc;
    private final JdbcTemplate template;

    public AchRepository(JdbcClient jdbc, JdbcTemplate template) {
        this.jdbc = jdbc;
        this.template = template;
    }

    // ---------- Files ----------

    public void insertFile(UUID id, String direction, String name, String sha, String content, NachaFile.FileHeader h,
                           String status, String rejectReason) {
        jdbc.sql("""
                INSERT INTO ach_files (id, direction, file_name, content_sha256, content, immediate_destination, immediate_origin,
                    file_creation_date, file_creation_time, file_id_modifier, status, reject_reason)
                VALUES (:id, :dir, :name, :sha, :content, :dest, :origin, :date, :time, :mod, :status, :reason)""")
                .param("id", id).param("dir", direction).param("name", name).param("sha", sha).param("content", content)
                .param("dest", h.immediateDestination()).param("origin", h.immediateOrigin()).param("date", h.creationDate())
                .param("time", h.creationTime()).param("mod", String.valueOf(h.fileIdModifier())).param("status", status)
                .param("reason", rejectReason).update();
    }

    public Optional<UUID> fileBySha(String direction, String sha) {
        return jdbc.sql("SELECT id FROM ach_files WHERE direction = :d AND content_sha256 = :s")
                .param("d", direction).param("s", sha).query(UUID.class).optional();
    }

    public Optional<UUID> fileByNachaId(String direction, NachaFile.FileHeader h) {
        return jdbc.sql("""
                SELECT id FROM ach_files WHERE direction = :d AND immediate_origin = :o AND file_creation_date = :date
                  AND COALESCE(file_creation_time, '') = COALESCE(:time, '') AND file_id_modifier = :mod AND status <> 'REJECTED'""")
                .param("d", direction).param("o", h.immediateOrigin()).param("date", h.creationDate())
                .param("time", h.creationTime()).param("mod", String.valueOf(h.fileIdModifier())).query(UUID.class).optional();
    }

    public String fileContent(UUID fileId) {
        return jdbc.sql("SELECT content FROM ach_files WHERE id = :id").param("id", fileId).query(String.class).single();
    }

    public void fileStatus(UUID fileId, String status, String reason) {
        jdbc.sql("""
                UPDATE ach_files SET status = :s, reject_reason = :r,
                    processed_at = CASE WHEN :s IN ('PROCESSED', 'REJECTED') THEN now() ELSE processed_at END
                WHERE id = :id""").param("s", status).param("r", reason).param("id", fileId).update();
    }

    public void fileTotals(UUID fileId, NachaFile f, Long jobExecutionId) {
        jdbc.sql("""
                UPDATE ach_files SET batch_count = :b, entry_count = :e, total_debit_cents = :d, total_credit_cents = :c,
                    job_execution_id = COALESCE(:job, job_execution_id) WHERE id = :id""")
                .param("b", f.batches().size()).param("e", f.entryCount()).param("d", f.control().totalDebitCents())
                .param("c", f.control().totalCreditCents()).param("job", jobExecutionId).param("id", fileId).update();
    }

    public void jobExecution(UUID fileId, long jobExecutionId) {
        jdbc.sql("UPDATE ach_files SET job_execution_id = :j WHERE id = :id").param("j", jobExecutionId).param("id", fileId).update();
    }

    public Map<Integer, UUID> insertBatches(UUID fileId, NachaFile f) {
        Map<Integer, UUID> ids = new HashMap<>();
        for (NachaFile.Batch b : f.batches()) {
            UUID id = jdbc.sql("""
                    INSERT INTO ach_batches (id, file_id, batch_number, service_class_code, company_name, company_id, sec_code,
                        entry_description, effective_entry_date, odfi_id, entry_count, total_debit_cents, total_credit_cents)
                    VALUES (:id, :file, :num, :scc, :cname, :cid, :sec, :desc, :eff, :odfi, :count, :deb, :cred)
                    ON CONFLICT (file_id, batch_number) DO UPDATE SET entry_count = EXCLUDED.entry_count
                    RETURNING id""")
                    .param("id", UUID.randomUUID()).param("file", fileId).param("num", b.header().batchNumber())
                    .param("scc", b.header().serviceClassCode()).param("cname", b.header().companyName())
                    .param("cid", b.header().companyId()).param("sec", b.header().secCode())
                    .param("desc", b.header().entryDescription()).param("eff", b.header().effectiveEntryDate())
                    .param("odfi", b.header().odfiId()).param("count", b.entries().size())
                    .param("deb", b.control().totalDebitCents()).param("cred", b.control().totalCreditCents())
                    .query(UUID.class).single();
            ids.put(b.header().batchNumber(), id);
        }
        return ids;
    }

    public Map<Integer, UUID> batchIds(UUID fileId) {
        Map<Integer, UUID> ids = new HashMap<>();
        jdbc.sql("SELECT batch_number, id FROM ach_batches WHERE file_id = :f").param("f", fileId)
                .query((rs, i) -> ids.put(rs.getInt(1), rs.getObject(2, UUID.class))).list();
        return ids;
    }

    // ---------- Entry checks ----------

    public Optional<UUID> entryByTrace(String trace) {
        return jdbc.sql("SELECT id FROM ach_entries WHERE trace_number = :t AND status <> 'DUPLICATE' LIMIT 1")
                .param("t", trace).query(UUID.class).optional();
    }

    /** Same originator, receiver account, amount and effective date in a different file: a likely double submission. */
    public Optional<UUID> matchingEntry(String companyId, String accountFp, long amountCents, LocalDate effective, UUID fileId) {
        return jdbc.sql("""
                SELECT id FROM ach_entries WHERE company_id = :c AND account_fingerprint = :fp AND amount_cents = :a
                  AND effective_date = :e AND file_id <> :f AND status NOT IN ('DUPLICATE', 'REJECTED') LIMIT 1""")
                .param("c", companyId).param("fp", accountFp).param("a", amountCents).param("e", effective)
                .param("f", fileId).query(UUID.class).optional();
    }

    public int receiverVelocity(String accountFp, int minutes) {
        return jdbc.sql("""
                SELECT count(*) FROM ach_entries WHERE account_fingerprint = :fp
                  AND created_at > now() - make_interval(mins => :m)""")
                .param("fp", accountFp).param("m", minutes).query(Integer.class).single();
    }

    public boolean knownReceiver(String companyId, String accountFp) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM ach_entries WHERE company_id = :c AND account_fingerprint = :fp AND status = 'SETTLED')")
                .param("c", companyId).param("fp", accountFp).query(Boolean.class).single();
    }

    // ---------- Entry writes ----------

    public void insertEntries(List<AchEntryRecord> entries) {
        Instant now = Instant.now();
        template.batchUpdate("""
                INSERT INTO ach_entries (id, file_id, batch_id, trace_number, transaction_code, direction, rdfi_routing,
                    account_ciphertext, account_fingerprint, account_last4, amount_cents, individual_id, individual_name,
                    sec_code, company_id, company_name, effective_date, same_day, status, reject_reason, fraud_score,
                    fraud_reasons, duplicate_of, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                entries, 500, (ps, e) -> {
                    ps.setObject(1, e.id());
                    ps.setObject(2, e.fileId());
                    ps.setObject(3, e.batchId());
                    ps.setString(4, e.traceNumber());
                    ps.setString(5, e.transactionCode());
                    ps.setString(6, e.direction());
                    ps.setString(7, e.rdfiRouting());
                    ps.setString(8, e.accountCiphertext());
                    ps.setString(9, e.accountFingerprint());
                    ps.setString(10, e.accountLast4());
                    ps.setLong(11, e.amountCents());
                    ps.setString(12, e.individualId());
                    ps.setString(13, e.individualName());
                    ps.setString(14, e.secCode());
                    ps.setString(15, e.companyId());
                    ps.setString(16, e.companyName());
                    ps.setObject(17, e.effectiveDate());
                    ps.setBoolean(18, e.sameDay());
                    ps.setString(19, e.status());
                    ps.setString(20, e.rejectReason());
                    ps.setInt(21, e.fraud() == null ? 0 : e.fraud().score());
                    ps.setArray(22, ps.getConnection().createArrayOf("text",
                            e.fraud() == null ? new String[0] : e.fraud().reasons().toArray(String[]::new)));
                    ps.setObject(23, e.duplicateOf());
                    ps.setTimestamp(24, Timestamp.from(now));
                    ps.setTimestamp(25, Timestamp.from(now));
                });
    }

    public int setEntryStatus(UUID id, String from, String to) {
        return jdbc.sql("UPDATE ach_entries SET status = :to, updated_at = now() WHERE id = :id AND status = :from")
                .param("to", to).param("id", id).param("from", from).update();
    }

    public Optional<Map<String, Object>> entry(UUID id) {
        return jdbc.sql("""
                SELECT id, file_id, trace_number, transaction_code, direction, rdfi_routing, account_last4, amount_cents,
                       individual_name, sec_code, company_id, company_name, effective_date, same_day, status, reject_reason,
                       fraud_score, fraud_reasons, duplicate_of, settlement_batch_id, return_code, created_at, settled_at
                FROM ach_entries WHERE id = :id""").param("id", id).query().listOfRows().stream().findFirst();
    }
}

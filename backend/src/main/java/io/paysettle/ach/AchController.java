package io.paysettle.ach;

import io.paysettle.ach.settlement.AchSettlementService;
import io.paysettle.common.ApiException;
import io.paysettle.common.AuditLog;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** ACH origination: receive NACHA files, track processing, inspect entries, run settlement windows. */
@RestController
public class AchController {

    private final AchIngestService ingest;
    private final AchRepository repo;
    private final AchSettlementService settlement;
    private final JdbcClient jdbc;
    private final AuditLog audit;
    private final JobLauncher launcher;
    private final Job achSettlementJob;

    public AchController(AchIngestService ingest, AchRepository repo, AchSettlementService settlement, JdbcClient jdbc,
                         AuditLog audit, @Qualifier("jobLauncher") JobLauncher launcher,
                         Job achSettlementJob) {
        this.ingest = ingest;
        this.repo = repo;
        this.settlement = settlement;
        this.jdbc = jdbc;
        this.audit = audit;
        this.launcher = launcher;
        this.achSettlementJob = achSettlementJob;
    }

    /** Upload as multipart (field "file"). Returns 202 with the file id and batch job execution id. */
    @PostMapping(value = "/api/v1/ach/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<AchIngestService.Accepted> upload(@RequestPart("file") MultipartFile file) throws IOException {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ingest.receive(
                file.getOriginalFilename() == null ? "upload.ach" : file.getOriginalFilename(),
                new String(file.getBytes(), StandardCharsets.US_ASCII)));
    }

    /** Upload as a raw body (Content-Type: text/plain), e.g. from a mainframe bridge or MFT. */
    @PostMapping(value = "/api/v1/ach/files", consumes = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<AchIngestService.Accepted> uploadRaw(@RequestBody String content,
                                                               @RequestParam(defaultValue = "upload.ach") String fileName) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ingest.receive(fileName, content));
    }

    @GetMapping("/api/v1/ach/files")
    public List<Map<String, Object>> files(@RequestParam(defaultValue = "INBOUND") String direction) {
        return jdbc.sql("""
                SELECT f.id, f.direction, f.file_name, f.immediate_origin, f.file_creation_date, f.file_id_modifier,
                       f.batch_count, f.entry_count, f.total_debit_cents, f.total_credit_cents, f.status, f.reject_reason,
                       f.job_execution_id, f.received_at, f.processed_at,
                       (SELECT count(*) FROM ach_entries e WHERE e.file_id = f.id AND e.status = 'HELD_FRAUD') AS held,
                       (SELECT count(*) FROM ach_entries e WHERE e.file_id = f.id AND e.status = 'DUPLICATE') AS duplicates,
                       (SELECT count(*) FROM ach_entries e WHERE e.file_id = f.id AND e.status = 'REJECTED') AS rejected
                FROM ach_files f WHERE f.direction = :d ORDER BY f.received_at DESC LIMIT 100""")
                .param("d", direction).query().listOfRows();
    }

    @GetMapping("/api/v1/ach/files/{id}")
    public Map<String, Object> file(@PathVariable UUID id) {
        Map<String, Object> f = jdbc.sql("""
                SELECT id, direction, file_name, immediate_destination, immediate_origin, file_creation_date, file_creation_time,
                       file_id_modifier, batch_count, entry_count, total_debit_cents, total_credit_cents, status, reject_reason,
                       job_execution_id, received_at, processed_at FROM ach_files WHERE id = :id""")
                .param("id", id).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("ACH file not found"));
        Map<String, Object> out = new LinkedHashMap<>(f);
        out.put("entriesByStatus", jdbc.sql("SELECT status, count(*) AS count, sum(amount_cents) AS amount_cents FROM ach_entries WHERE file_id = :id GROUP BY status")
                .param("id", id).query().listOfRows());
        if (f.get("job_execution_id") != null) {
            out.put("job", jdbc.sql("""
                    SELECT e.status, e.exit_code, e.start_time, e.end_time,
                           (SELECT json_agg(json_build_object('step', s.step_name, 'status', s.status, 'read', s.read_count,
                                   'write', s.write_count, 'commits', s.commit_count) ORDER BY s.step_execution_id)
                            FROM batch_step_execution s WHERE s.job_execution_id = e.job_execution_id)::text AS steps
                    FROM batch_job_execution e WHERE e.job_execution_id = :j""")
                    .param("j", f.get("job_execution_id")).query().listOfRows().stream().findFirst().orElse(null));
        }
        return out;
    }

    @GetMapping("/api/v1/ach/entries")
    public List<Map<String, Object>> entries(@RequestParam(required = false) UUID fileId,
                                             @RequestParam(required = false) String status) {
        return jdbc.sql("""
                SELECT id, file_id, trace_number, transaction_code, direction, rdfi_routing, account_last4, amount_cents,
                       individual_name, sec_code, company_name, effective_date, same_day, status, reject_reason,
                       fraud_score, fraud_reasons, duplicate_of, created_at, settled_at
                FROM ach_entries
                WHERE (CAST(:file AS uuid) IS NULL OR file_id = :file) AND (CAST(:status AS varchar) IS NULL OR status = :status)
                ORDER BY created_at DESC LIMIT 500""")
                .param("file", fileId).param("status", status).query().listOfRows();
    }

    @GetMapping("/api/v1/ach/entries/{id}")
    public Map<String, Object> entry(@PathVariable UUID id) {
        Map<String, Object> e = new LinkedHashMap<>(repo.entry(id).orElseThrow(() -> ApiException.notFound("Entry not found")));
        e.put("history", audit.history(id));
        return e;
    }

    // ---------- Settlement ----------

    @GetMapping("/api/v1/ach/settlement/batches")
    public List<Map<String, Object>> settlementBatches() {
        return settlement.recentBatches();
    }

    /** Runs one window now (e.g. from Cloud Scheduler at each cutoff, or manually by operations). */
    @PostMapping("/api/v1/ach/settlement/windows/{window}/run")
    public Map<String, Object> runWindow(@PathVariable String window, @RequestParam(defaultValue = "false") boolean forceSettle)
            throws Exception {
        settlement.window(window);
        JobExecution exec = launcher.run(achSettlementJob, new JobParametersBuilder()
                .addString("window", window)
                .addString("date", LocalDate.now(ZoneId.of("America/New_York")).toString())
                .addString("forceSettle", String.valueOf(forceSettle))
                .addLong("run", System.currentTimeMillis()).toJobParameters());
        return Map.of("jobExecutionId", exec.getId(), "status", exec.getStatus().name(), "window", window);
    }

    /** Cloud Scheduler target: run due windows and complete settlements whose time has passed. */
    @PostMapping("/api/v1/ach/settlement/sweep")
    public Map<String, Object> sweep() throws Exception {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("America/New_York"));
        List<String> due = settlement.dueWindows(now);
        for (String w : due) {
            launcher.run(achSettlementJob, new JobParametersBuilder().addString("window", w)
                    .addString("date", now.toLocalDate().toString()).addString("forceSettle", "false")
                    .addLong("run", System.currentTimeMillis()).toJobParameters());
        }
        int settled = settlement.completeSettlements(now, false);
        return Map.of("windowsRun", due, "batchesSettled", settled);
    }

}

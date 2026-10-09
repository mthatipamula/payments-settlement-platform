package io.paysettle.ach;

import io.paysettle.ach.nacha.NachaFile;
import io.paysettle.ach.nacha.NachaParseException;
import io.paysettle.common.ApiException;
import io.paysettle.common.Hashing;
import io.paysettle.common.Rail;
import io.paysettle.fraud.RiskRecorder;
import java.util.UUID;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * Receives a NACHA file, rejects exact and NACHA-level duplicate files up front, stores it, and starts the
 * achIngestJob asynchronously. File-level duplicates (same content, or same origin + creation date/time + File
 * ID Modifier) are a classic mainframe-migration failure mode when a file is re-sent after a timeout.
 */
@Service
public class AchIngestService {

    private final AchRepository repo;
    private final RiskRecorder risk;
    private final JobLauncher launcher;
    private final Job achIngestJob;

    public AchIngestService(AchRepository repo, RiskRecorder risk, @Qualifier("asyncJobLauncher") JobLauncher launcher,
                            Job achIngestJob) {
        this.repo = repo;
        this.risk = risk;
        this.launcher = launcher;
        this.achIngestJob = achIngestJob;
    }

    public record Accepted(UUID fileId, long jobExecutionId, String status) {
    }

    public Accepted receive(String fileName, String content) {
        String sha = Hashing.sha256(content);
        var sameContent = repo.fileBySha("INBOUND", sha);
        if (sameContent.isPresent()) {
            risk.duplicate(Rail.ACH, "ACH_FILE", null, sameContent.get(), null, "identical file content re-submitted: " + fileName);
            throw ApiException.conflict("DUPLICATE_FILE", "This exact file was already received as " + sameContent.get());
        }
        NachaFile.FileHeader header;
        try {
            header = headerOnly(content);
        } catch (NachaParseException e) {
            throw ApiException.unprocessable("INVALID_NACHA", e.getMessage());
        }
        var sameId = repo.fileByNachaId("INBOUND", header);
        if (sameId.isPresent()) {
            risk.duplicate(Rail.ACH, "ACH_FILE", null, sameId.get(), null,
                    "same immediate origin, creation date/time and File ID Modifier as an earlier file: " + fileName);
            throw ApiException.conflict("DUPLICATE_FILE",
                    "A file with the same origin, creation date/time and File ID Modifier was already received ("
                            + sameId.get() + "). Change the File ID Modifier to send a new file.");
        }
        UUID fileId = UUID.randomUUID();
        try {
            repo.insertFile(fileId, "INBOUND", fileName, sha, content, header, "RECEIVED", null);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("DUPLICATE_FILE", "Duplicate file (concurrent submission)");
        }
        try {
            JobExecution exec = launcher.run(achIngestJob, new JobParametersBuilder()
                    .addString("fileId", fileId.toString()).toJobParameters());
            return new Accepted(fileId, exec.getId(), "PROCESSING");
        } catch (Exception e) {
            repo.fileStatus(fileId, "REJECTED", "could not start processing: " + e.getMessage());
            throw new IllegalStateException("Could not start the ACH ingest job", e);
        }
    }

    /** Parses only the File Header so obviously invalid uploads fail fast with 422. */
    private static NachaFile.FileHeader headerOnly(String content) {
        String first = content.lines().filter(l -> !l.isBlank()).findFirst()
                .orElseThrow(() -> new NachaParseException(0, "empty file"));
        String padded = String.format("%-94s", first);
        if (padded.charAt(0) != '1') {
            throw new NachaParseException(1, "first record must be a File Header (type 1)");
        }
        return new NachaFile.FileHeader(padded.substring(3, 13).trim(), padded.substring(13, 23).trim(),
                padded.substring(23, 29), padded.substring(29, 33).trim(), padded.charAt(33),
                padded.substring(40, 63).trim(), padded.substring(63, 86).trim(), padded.substring(86, 94).trim());
    }
}

package io.paysettle.ach.batch;

import io.paysettle.ach.AchRepository;
import io.paysettle.ach.AchEntryRecord;
import io.paysettle.ach.nacha.NachaFile;
import io.paysettle.ach.nacha.NachaParseException;
import io.paysettle.ach.nacha.NachaParser;
import io.paysettle.common.AuditLog;
import io.paysettle.common.FieldCrypto;
import io.paysettle.config.PaySettleProperties;
import io.paysettle.fraud.FraudEngine;
import io.paysettle.fraud.RiskRecorder;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * achIngestJob: validate and register a NACHA file, then process its entries in restartable chunks.
 * <pre>
 * validateFile --REJECTED--> end (file rejected as a whole, like the ACH Operator would)
 *      |
 *      +--> processEntries (chunk 500: duplicates, Same Day eligibility, fraud) --> finalizeFile
 * </pre>
 */
@Configuration
public class AchIngestJobConfig {

    private static final Logger log = LoggerFactory.getLogger(AchIngestJobConfig.class);
    static final String REJECTED = "REJECTED";

    /**
     * Ingest jobs are queued and run one at a time, so the upload API returns immediately while cross-file
     * duplicate detection always sees every earlier file's entries (parallel files could miss each other).
     * Throughput comes from chunked processing inside a job, not from running files concurrently.
     */
    @Bean
    JobLauncher asyncJobLauncher(JobRepository jobRepository) throws Exception {
        TaskExecutorJobLauncher launcher = new TaskExecutorJobLauncher();
        launcher.setJobRepository(jobRepository);
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("ach-ingest-");
        executor.initialize();
        launcher.setTaskExecutor(executor);
        launcher.afterPropertiesSet();
        return launcher;
    }

    @Bean
    Job achIngestJob(JobRepository jobs, Step validateFile, Step processEntries, Step finalizeFile) {
        return new JobBuilder("achIngestJob", jobs)
                .start(validateFile).on(REJECTED).end()
                .from(validateFile).on("*").to(processEntries).next(finalizeFile)
                .end().build();
    }

    @Bean
    Step validateFile(JobRepository jobs, PlatformTransactionManager tx, AchRepository repo) {
        return new StepBuilder("validateFile", jobs).tasklet((contribution, chunkContext) -> {
            UUID fileId = UUID.fromString((String) chunkContext.getStepContext().getJobParameters().get("fileId"));
            long jobExecutionId = chunkContext.getStepContext().getStepExecution().getJobExecutionId();
            repo.jobExecution(fileId, jobExecutionId);
            try {
                NachaFile file = NachaParser.parse(repo.fileContent(fileId));
                repo.insertBatches(fileId, file);
                repo.fileTotals(fileId, file, jobExecutionId);
                repo.fileStatus(fileId, "PROCESSING", null);
            } catch (NachaParseException e) {
                log.warn("ACH file {} rejected: {}", fileId, e.getMessage());
                repo.fileStatus(fileId, "REJECTED", e.getMessage());
                contribution.setExitStatus(new ExitStatus(REJECTED, e.getMessage()));
            }
            return RepeatStatus.FINISHED;
        }, tx).build();
    }

    @Bean
    Step processEntries(JobRepository jobs, PlatformTransactionManager tx, NachaEntryReader nachaEntryReader,
                        AchEntryProcessor achEntryProcessor, AchEntryWriter achEntryWriter) {
        return new StepBuilder("processEntries", jobs)
                .<AchEntryItem, AchEntryRecord>chunk(500, tx)
                .reader(nachaEntryReader)
                .processor(achEntryProcessor)
                .writer(achEntryWriter)
                .build();
    }

    @Bean
    Step finalizeFile(JobRepository jobs, PlatformTransactionManager tx, AchRepository repo) {
        return new StepBuilder("finalizeFile", jobs).tasklet((contribution, chunkContext) -> {
            UUID fileId = UUID.fromString((String) chunkContext.getStepContext().getJobParameters().get("fileId"));
            repo.fileStatus(fileId, "PROCESSED", null);
            return RepeatStatus.FINISHED;
        }, tx).build();
    }

    @Bean
    @StepScope
    NachaEntryReader nachaEntryReader(AchRepository repo, @Value("#{jobParameters['fileId']}") String fileId) {
        return new NachaEntryReader(repo, UUID.fromString(fileId));
    }

    @Bean
    @StepScope
    AchEntryProcessor achEntryProcessor(AchRepository repo, FieldCrypto crypto, FraudEngine fraud,
                                        PaySettleProperties props, @Value("#{jobParameters['fileId']}") String fileId) {
        return new AchEntryProcessor(repo, crypto, fraud, props, UUID.fromString(fileId));
    }

    @Bean
    AchEntryWriter achEntryWriter(AchRepository repo, RiskRecorder risk, AuditLog audit) {
        return new AchEntryWriter(repo, risk, audit);
    }
}

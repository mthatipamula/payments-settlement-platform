package io.paysettle.ach.settlement;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs achSettlementJob for every FedACH window whose cutoff has passed, and completes settlements whose
 * settlement time has passed. On Cloud Run the same work is triggered by Cloud Scheduler calling
 * POST /api/v1/ach/settlement/sweep, so it also runs when the service has scaled to zero.
 */
@Component
@ConditionalOnProperty(name = "paysettle.settlement.sweep-enabled", havingValue = "true")
public class AchSettlementScheduler {

    private static final Logger log = LoggerFactory.getLogger(AchSettlementScheduler.class);

    private final AchSettlementService service;
    private final JobLauncher launcher;
    private final Job achSettlementJob;

    public AchSettlementScheduler(AchSettlementService service, @Qualifier("jobLauncher") JobLauncher launcher,
                                  Job achSettlementJob) {
        this.service = service;
        this.launcher = launcher;
        this.achSettlementJob = achSettlementJob;
    }

    @Scheduled(fixedDelayString = "60000", initialDelay = 20000)
    @SchedulerLock(name = "ach-settlement-sweep", lockAtMostFor = "PT10M")
    public void sweep() {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("America/New_York"));
        for (String window : service.dueWindows(now)) {
            run(window, now.toLocalDate(), false);
        }
        service.completeSettlements(now, false);
    }

    public JobExecution run(String window, LocalDate date, boolean forceSettle) {
        try {
            JobParameters params = new JobParametersBuilder()
                    .addString("window", window)
                    .addString("date", date.toString())
                    .addString("forceSettle", String.valueOf(forceSettle))
                    .addLong("run", System.currentTimeMillis())
                    .toJobParameters();
            return launcher.run(achSettlementJob, params);
        } catch (Exception e) {
            log.error("ACH settlement job failed for {} {}", window, date, e);
            throw new IllegalStateException(e);
        }
    }
}

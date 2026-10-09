package io.paysettle.ach.settlement;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * achSettlementJob(window, date): build and transmit the window's FedACH origination file, then complete any
 * settlements whose settlement time has passed. Job parameters make each (window, date) run a unique,
 * restartable job instance in the Spring Batch job repository.
 */
@Configuration
public class AchSettlementJobConfig {

    @Bean
    Job achSettlementJob(JobRepository jobs, Step originateWindow, Step completeSettlement) {
        return new JobBuilder("achSettlementJob", jobs).start(originateWindow).next(completeSettlement).build();
    }

    @Bean
    Step originateWindow(JobRepository jobs, PlatformTransactionManager tx, AchSettlementService service) {
        return new StepBuilder("originateWindow", jobs).tasklet((contribution, ctx) -> {
            var params = ctx.getStepContext().getJobParameters();
            service.runWindow((String) params.get("window"), LocalDate.parse((String) params.get("date")))
                    .ifPresent(r -> ctx.getStepContext().getStepExecution().getExecutionContext()
                            .putString("result", r.entries() + " entries, ref " + r.networkReference()));
            return RepeatStatus.FINISHED;
        }, tx).build();
    }

    @Bean
    Step completeSettlement(JobRepository jobs, PlatformTransactionManager tx, AchSettlementService service) {
        return new StepBuilder("completeSettlement", jobs).tasklet((contribution, ctx) -> {
            boolean force = "true".equals(ctx.getStepContext().getJobParameters().get("forceSettle"));
            service.completeSettlements(ZonedDateTime.now(ZoneId.of("America/New_York")), force);
            return RepeatStatus.FINISHED;
        }, tx).build();
    }
}

package io.paysettle.ach.settlement;

import io.paysettle.config.PaySettleProperties;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Reads FedACH acknowledgements that the bank's file transfer software drops into the ack directory, applies
 * them to the matching settlement batch, and archives each file. It also warns about transmitted files with no
 * acknowledgement after the configured timeout, which operations must chase before the settlement time.
 *
 * <p>PLACEHOLDER FORMAT: the Fed defines the real acknowledgement content during FedLine onboarding. Until
 * then this reads a simple key=value file named {@code <outbound file name>.ack}:
 * <pre>
 * FILE=PAYSETTLE_SAME_DAY_1_20261009.ach
 * STATUS=ACCEPTED            (or REJECTED)
 * REFERENCE=FED-ACK-123456
 * REASON=...                 (when rejected)
 * </pre>
 * Only {@link #parse} needs to change for the real format.
 */
@Component
@ConditionalOnProperty(name = "paysettle.fedline.enabled", havingValue = "true")
public class FedLineAckPoller {

    private static final Logger log = LoggerFactory.getLogger(FedLineAckPoller.class);

    record Ack(String fileName, boolean accepted, String reference, String reason) {
    }

    private final AchSettlementService settlement;
    private final Path ackDir;
    private final Path archiveDir;
    private final int ackTimeoutMinutes;

    public FedLineAckPoller(AchSettlementService settlement, PaySettleProperties props) {
        this.settlement = settlement;
        this.ackDir = FedLineAchGateway.writableDirectory(props.fedline().ackDir(), "ack-dir");
        this.archiveDir = FedLineAchGateway.writableDirectory(props.fedline().archiveDir(), "archive-dir");
        this.ackTimeoutMinutes = props.fedline().ackTimeoutMinutes();
    }

    @Scheduled(fixedDelayString = "${paysettle.fedline.ack-poll-interval-ms:30000}", initialDelay = 15000)
    @SchedulerLock(name = "fedline-ack-poller", lockAtMostFor = "PT5M")
    public void poll() {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(ackDir, "*.ack")) {
            for (Path file : files) {
                process(file);
            }
        } catch (IOException e) {
            log.error("Cannot read FedLine ack directory {}", ackDir, e);
        }
        List<String> overdue = settlement.overdueAcknowledgements(ackTimeoutMinutes);
        if (!overdue.isEmpty()) {
            log.warn("No FedACH acknowledgement after {} minutes for {}", ackTimeoutMinutes, overdue);
        }
    }

    private void process(Path file) {
        try {
            Ack ack = parse(Files.readString(file, StandardCharsets.US_ASCII));
            boolean matched = settlement.acknowledge(ack.fileName(), ack.accepted(), ack.reference(), ack.reason());
            if (!matched) {
                log.warn("FedLine ack {} does not match a transmitted batch (unknown file or already applied)", file);
            }
            archive(file, matched ? "" : ".unmatched");
        } catch (RuntimeException e) {
            log.error("Unreadable FedLine ack {}; moved aside for operations", file, e);
            archive(file, ".error");
        } catch (IOException e) {
            log.error("Cannot read FedLine ack {}", file, e);   // left in place, retried on the next poll
        }
    }

    static Ack parse(String content) {
        Map<String, String> f = new HashMap<>();
        content.lines().map(String::trim).filter(l -> l.contains("=")).forEach(l -> {
            int i = l.indexOf('=');
            f.put(l.substring(0, i).trim().toUpperCase(), l.substring(i + 1).trim());
        });
        String name = f.get("FILE");
        String status = f.getOrDefault("STATUS", "");
        if (name == null || !(status.equals("ACCEPTED") || status.equals("REJECTED"))) {
            throw new IllegalArgumentException("ack needs FILE and STATUS=ACCEPTED|REJECTED");
        }
        return new Ack(name, status.equals("ACCEPTED"), f.get("REFERENCE"), f.getOrDefault("REASON", "rejected by FedACH"));
    }

    private void archive(Path file, String suffix) {
        try {
            Files.move(file, archiveDir.resolve(file.getFileName() + suffix), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.error("Could not archive FedLine ack {}", file, e);
        }
    }
}

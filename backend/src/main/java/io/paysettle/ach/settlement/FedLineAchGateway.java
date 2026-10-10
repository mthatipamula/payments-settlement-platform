package io.paysettle.ach.settlement;

import io.paysettle.ach.nacha.NachaParser;
import io.paysettle.config.PaySettleProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * FedACH over FedLine Direct. FedLine is not a public API: it is a private connection (Fed-supplied VPN
 * devices and Fed-issued certificates) that only a bank with a Federal Reserve account can get, after the
 * Fed's onboarding and certification testing. Banks exchange ACH files over it with managed file transfer
 * software such as IBM Sterling Connect:Direct.
 *
 * <p>This gateway therefore does not talk to the Fed itself. It writes the origination file into the
 * directory the bank's file transfer software sends from, and returns. The Fed's acknowledgement arrives
 * later and is processed by {@link FedLineAckPoller}; until then the batch stays TRANSMITTED and is not
 * settled.
 *
 * <p>Skeleton status: the hand-off is complete (validation, atomic write, fail-fast directory checks).
 * Still to be done with the bank's FedLine team: file naming required by the Fed, the acknowledgement format
 * (see {@link FedLineAckPoller}), and running where the transfer directories live (on-premises or GKE with a
 * mounted volume, not Cloud Run).
 */
@Component
@ConditionalOnProperty(name = "paysettle.fedline.enabled", havingValue = "true")
public class FedLineAchGateway implements FedAchGateway {

    private static final Logger log = LoggerFactory.getLogger(FedLineAchGateway.class);

    private final Path outboundDir;

    public FedLineAchGateway(PaySettleProperties props) {
        this.outboundDir = writableDirectory(props.fedline().outboundDir(), "outbound-dir");
        writableDirectory(props.fedline().ackDir(), "ack-dir");
        writableDirectory(props.fedline().archiveDir(), "archive-dir");
        log.info("FedLine gateway enabled: origination files are written to {}", outboundDir);
    }

    @Override
    public String transmit(String fileName, String nachaContent) {
        NachaParser.parse(nachaContent); // never hand the Fed a file it would reject for format
        Path target = outboundDir.resolve(fileName);
        if (Files.exists(target)) {
            throw new IllegalStateException("Outbound file already exists, refusing to overwrite: " + target);
        }
        // Write under a temporary name, then rename: the transfer software must never pick up a half-written file.
        Path temp = outboundDir.resolve("." + fileName + ".part");
        try {
            Files.writeString(temp, nachaContent, StandardCharsets.US_ASCII);
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not hand " + fileName + " to FedLine file transfer", e);
        }
        log.info("[FedLine] {} handed to file transfer ({} bytes); waiting for FedACH acknowledgement",
                fileName, nachaContent.length());
        return "FEDLINE-HANDOFF:" + fileName;
    }

    @Override
    public String name() {
        return "FEDACH-FEDLINE";
    }

    @Override
    public boolean acknowledgesOnTransmit() {
        return false;
    }

    static Path writableDirectory(String dir, String property) {
        if (dir == null || dir.isBlank()) {
            throw new IllegalStateException("paysettle.fedline." + property + " must be set when FedLine is enabled");
        }
        Path path = Path.of(dir);
        if (!Files.isDirectory(path) || !Files.isWritable(path)) {
            throw new IllegalStateException("paysettle.fedline." + property + " is not a writable directory: " + path);
        }
        return path;
    }
}

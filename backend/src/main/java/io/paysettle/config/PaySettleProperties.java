package io.paysettle.config;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "paysettle")
public record PaySettleProperties(Institution institution, Security security, Crypto crypto, Ach ach, Wire wire,
                                  Fraud fraud, Settlement settlement, Simulator simulator, FedLine fedline) {

    public record Institution(String name, String routingNumber, String fedAchRouting) {
    }

    public enum SecurityMode { API_KEY, JWT }

    public record Security(String mode, String operatorApiKey, String analystApiKey) {
        public SecurityMode securityMode() {
            return "jwt".equalsIgnoreCase(mode) ? SecurityMode.JWT : SecurityMode.API_KEY;
        }
    }

    public record Crypto(String dataKey, String fingerprintKey) {
    }

    public record Window(String name, LocalTime cutoff, LocalTime settlement, boolean sameDay) {
    }

    public record Ach(String timezone, long sameDayLimitCents, List<Window> windows) {
    }

    public record Wire(String timezone, LocalTime customerCutoff, int duplicateWindowMinutes) {
    }

    public record Fraud(int reviewScore, int blockScore, long achHighAmountCents, BigDecimal wireHighAmount,
                        int velocityWindowMinutes, int achVelocityLimit, int wireVelocityLimit,
                        double sanctionsMatchThreshold) {
    }

    public record Settlement(boolean sweepEnabled, long sweepIntervalMs) {
    }

    public record Simulator(boolean enabled) {
    }
    /**
     * FedLine Direct file exchange. The bank's managed file transfer (e.g. IBM Sterling Connect:Direct) sends
     * whatever lands in outboundDir to FedACH and drops the Fed's acknowledgements into ackDir.
     */
    public record FedLine(boolean enabled, String outboundDir, String ackDir, String archiveDir, long ackPollIntervalMs,
                          int ackTimeoutMinutes) {
    }
}

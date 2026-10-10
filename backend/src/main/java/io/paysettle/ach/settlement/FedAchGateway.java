package io.paysettle.ach.settlement;

/**
 * Connection to the ACH Operator (FedACH via FedLine, or The Clearing House EPN). Real connectivity needs a
 * bank's FedLine credentials, so the default implementation is a simulator with the same contract.
 * Set {@code paysettle.fedline.enabled=true} to use {@link FedLineAchGateway} instead.
 */
public interface FedAchGateway {

    /** Hands an origination file to the operator; returns a reference for the hand-off or acknowledgement. */
    String transmit(String fileName, String nachaContent);

    String name();

    /**
     * True when {@link #transmit} returns only after the operator accepted the file. False when the
     * acknowledgement arrives later (FedLine): the batch then stays TRANSMITTED until
     * {@link AchSettlementService#acknowledge} records the operator's answer, and is never settled before that.
     */
    default boolean acknowledgesOnTransmit() {
        return true;
    }
}

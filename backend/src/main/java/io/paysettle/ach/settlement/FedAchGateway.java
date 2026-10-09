package io.paysettle.ach.settlement;

/**
 * Connection to the ACH Operator (FedACH via FedLine, or The Clearing House EPN). Real connectivity needs a
 * bank's FedLine credentials, so the default implementation is a simulator with the same contract.
 */
public interface FedAchGateway {

    /** Transmits an origination file; returns the operator's acknowledgement reference. */
    String transmit(String fileName, String nachaContent);

    String name();
}

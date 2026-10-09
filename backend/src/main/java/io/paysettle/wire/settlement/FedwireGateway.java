package io.paysettle.wire.settlement;

/**
 * Fedwire Funds Service connection (FedLine Direct / FedLine Advantage with ISO 20022 messages). Fedwire is a
 * real-time gross settlement system: an accepted payment is final and irrevocable.
 */
public interface FedwireGateway {

    /** Sends a pacs.008; returns the network result. */
    Result send(String pacs008);

    String name();

    /**
     * @param accepted true when Fedwire accepted and settled the payment
     * @param reference Fedwire IMAD/OMAD (input and output message accountability data)
     * @param reasonCode ISO 20022 reject reason when not accepted (e.g. AC01, AM04)
     */
    record Result(boolean accepted, String reference, String reasonCode, String reasonText) {
    }
}

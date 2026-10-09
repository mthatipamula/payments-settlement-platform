package io.paysettle.ach.settlement;

import io.paysettle.ach.nacha.NachaParser;
import java.time.LocalDate;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Stands in for FedACH: validates the file exactly as the operator would, then acknowledges it. */
@Component
public class SimulatedFedAchGateway implements FedAchGateway {

    private static final Logger log = LoggerFactory.getLogger(SimulatedFedAchGateway.class);

    @Override
    public String transmit(String fileName, String nachaContent) {
        NachaParser.parse(nachaContent); // an invalid file would be rejected by the Fed
        String ack = "FEDACH-" + LocalDate.now() + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        log.info("[SIMULATED FedACH] accepted {} -> {}", fileName, ack);
        return ack;
    }

    @Override
    public String name() {
        return "FEDACH-SIMULATOR";
    }
}

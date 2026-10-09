package io.paysettle.wire.settlement;

import com.prowidesoftware.swift.model.mx.MxPacs00800108;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Stands in for Fedwire Funds. Parses the pacs.008 like the network would and returns an IMAD. Rejects a
 * small, deterministic set of cases so reject handling can be exercised: creditor accounts ending in "0000"
 * (AC01 incorrect account number) and amounts ending in .13 (AM04 insufficient funds).
 */
@Component
public class SimulatedFedwireGateway implements FedwireGateway {

    private static final Logger log = LoggerFactory.getLogger(SimulatedFedwireGateway.class);
    private final AtomicInteger sequence = new AtomicInteger(100000);

    @Override
    public Result send(String pacs008) {
        MxPacs00800108 mx = MxPacs00800108.parse(pacs008);
        var tx = mx.getFIToFICstmrCdtTrf().getCdtTrfTxInf().getFirst();
        String account = tx.getCdtrAcct().getId().getOthr().getId();
        BigDecimal amount = tx.getIntrBkSttlmAmt().getValue();
        if (account.endsWith("0000")) {
            return new Result(false, null, "AC01", "Incorrect account number at the receiving bank");
        }
        if (amount.remainder(BigDecimal.ONE).compareTo(new BigDecimal("0.13")) == 0) {
            return new Result(false, null, "AM04", "Insufficient funds in the sender's Fed account");
        }
        String imad = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "PSB1FT01" + sequence.incrementAndGet();
        log.info("[SIMULATED Fedwire] settled UETR {} amount {} IMAD {}", tx.getPmtId().getUETR(), amount, imad);
        return new Result(true, imad, null, null);
    }

    @Override
    public String name() {
        return "FEDWIRE-SIMULATOR";
    }
}

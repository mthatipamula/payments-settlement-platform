package io.paysettle.simulator;

import io.paysettle.ach.AchIngestService;
import io.paysettle.ach.nacha.NachaFileBuilder;
import io.paysettle.common.ApiException;
import io.paysettle.config.PaySettleProperties;
import io.paysettle.wire.WireRequest;
import io.paysettle.wire.WireService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Local demo traffic (paysettle.simulator.enabled=true): realistic ACH files and wires including fraud
 * patterns, sanctions matches, duplicates, client retries and network rejects. Disabled in production.
 */
@RestController
public class SimulatorController {

    private static final String[] RDFIS = {"021000021", "026009593", "121000248", "111000025", "071000013", "061000104"};
    private static final String[] NAMES = {"Alicia Gomez", "Brian Chen", "Carla Novak", "Devon Price", "Elena Rossi",
            "Farah Khan", "George Okoro", "Hana Sato", "Ivan Petrov", "Julia Meyer", "Kofi Mensah", "Lena Park"};
    private static final String[] COMPANIES = {"ACME PAYROLL", "NORTHSTAR LLC", "BLUEFIN INS", "CITY UTILITIES"};

    private final PaySettleProperties props;
    private final AchIngestService ach;
    private final WireService wires;
    private final Random random = new Random();
    private final AtomicInteger fileCounter = new AtomicInteger();
    /** Entries carried into the next file with new trace numbers: a resubmission the duplicate check must catch. */
    private volatile List<NachaFileBuilder.OutEntry> carryOver = List.of();
    private final AtomicInteger traceCounter = new AtomicInteger((int) (System.currentTimeMillis() % 1_000_000));

    public SimulatorController(PaySettleProperties props, AchIngestService ach, WireService wires) {
        this.props = props;
        this.ach = ach;
        this.wires = wires;
    }

    @PostMapping("/api/v1/simulator/run")
    public Map<String, Object> run(@RequestParam(defaultValue = "3") int achFiles, @RequestParam(defaultValue = "25") int wireCount) {
        requireEnabled();
        Map<String, Object> out = new LinkedHashMap<>();
        List<Object> files = new ArrayList<>();
        String lastFile = null;
        for (int i = 0; i < Math.min(achFiles, 20); i++) {
            String content = achFile(i);
            files.add(submit("SIM_" + LocalDate.now() + "_" + fileCounter.incrementAndGet() + ".ach", content));
            lastFile = content;
        }
        if (lastFile != null) {
            files.add(submit("SIM_RESEND_" + fileCounter.get() + ".ach", lastFile)); // re-sent file: rejected as duplicate
        }
        out.put("achFiles", files);
        out.put("wires", simulateWires(Math.min(wireCount, 200)));
        return out;
    }

    /** A valid sample NACHA file to download and upload through the API or console. */
    @GetMapping(value = "/api/v1/simulator/sample-nacha", produces = MediaType.TEXT_PLAIN_VALUE)
    public String sample() {
        requireEnabled();
        return achFile(0);
    }

    private Object submit(String name, String content) {
        try {
            return ach.receive(name, content);
        } catch (ApiException e) {
            return Map.of("file", name, "rejected", e.code(), "message", e.getMessage());
        }
    }

    private String achFile(int index) {
        String ourRouting = props.institution().routingNumber();
        LocalDate effective = LocalDate.now(ZoneId.of(props.ach().timezone()));
        List<NachaFileBuilder.OutBatch> batches = new ArrayList<>();
        String company = COMPANIES[index % COMPANIES.length];
        String companyId = "1" + String.format("%09d", 100000000 + index % COMPANIES.length);

        // Payroll credits (PPD), mostly ordinary.
        List<NachaFileBuilder.OutEntry> payroll = new ArrayList<>();
        for (int i = 0; i < 25 + random.nextInt(25); i++) {
            long cents = 120_000 + random.nextInt(400_000);
            payroll.add(entry("22", RDFIS[random.nextInt(RDFIS.length)], "10" + (2000000 + random.nextInt(900000)), cents,
                    NAMES[random.nextInt(NAMES.length)]));
        }
        // A large bonus to a brand-new account (fraud signal), and a receiver hit many times (velocity).
        payroll.add(entry("22", RDFIS[0], "99" + (1000000 + random.nextInt(800000)), 6_500_000L, "Unknown Payee"));
        String velocityAccount = "77" + (1000000 + index);
        for (int i = 0; i < 22; i++) {
            payroll.add(entry("32", RDFIS[1], velocityAccount, 15_000L + i, "Mule Account"));
        }
        for (NachaFileBuilder.OutEntry e : carryOver) {
            payroll.add(entry(e.transactionCode(), e.rdfiRouting(), e.accountNumber(), e.amountCents(), e.individualName()));
        }
        carryOver = List.copyOf(payroll.subList(0, 3));
        batches.add(new NachaFileBuilder.OutBatch(COMPANIES[0], "1100000000", "PPD", "PAYROLL", effective, payroll));

        // Consumer WEB debits, one suspiciously large.
        List<NachaFileBuilder.OutEntry> debits = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            debits.add(entry("27", RDFIS[random.nextInt(RDFIS.length)], "55" + (3000000 + random.nextInt(900000)),
                    5_000 + random.nextInt(30_000), NAMES[random.nextInt(NAMES.length)]));
        }
        debits.add(entry("27", RDFIS[2], "55" + (4000000 + random.nextInt(900000)), 1_250_000L, "Account Takeover"));
        batches.add(new NachaFileBuilder.OutBatch(company, companyId, "WEB", "SUBSCRIPT", effective, debits));
        // Same originator for payroll across files, so a resubmitted entry matches on company + account + amount + date.

        char modifier = "ABCDEFGHIJKLMNOPQRSTUVWXYZ".charAt(fileCounter.get() % 26);
        return new NachaFileBuilder(ourRouting, ourRouting, props.institution().name(), company)
                .build(batches, LocalDateTime.now().withSecond(0).plusMinutes(fileCounter.get()), modifier);
    }

    private NachaFileBuilder.OutEntry entry(String code, String rdfi, String account, long cents, String name) {
        String trace = props.institution().routingNumber().substring(0, 8) + String.format("%07d", traceCounter.incrementAndGet() % 10_000_000);
        return new NachaFileBuilder.OutEntry(code, rdfi, account, cents, "ID" + (1000 + random.nextInt(9000)), name, trace, null);
    }

    private List<Object> simulateWires(int count) {
        List<Object> results = new ArrayList<>();
        String debtorAccount = "8800" + (100000 + random.nextInt(800000));
        for (int i = 0; i < count; i++) {
            int kind = i % 10;
            BigDecimal amount = BigDecimal.valueOf(5_000 + random.nextInt(95_000)).add(BigDecimal.valueOf(random.nextInt(100), 2));
            String creditorName = NAMES[random.nextInt(NAMES.length)] + " Holdings";
            String creditorAccount = "4400" + (100000 + random.nextInt(800000));
            switch (kind) {
                case 2 -> amount = BigDecimal.valueOf(1_250_000);                       // very high, new beneficiary
                case 4 -> creditorName = "Northwind Shadow Trading, L.L.C.";            // sanctions potential match
                case 6 -> creditorAccount = "4400990000";                                // Fedwire AC01 reject
                default -> { }
            }
            WireRequest req = new WireRequest(amount, "USD", null,
                    new WireRequest.Party("Schwab Client " + (i % 4), debtorAccount + (i % 4), null),
                    new WireRequest.Party(creditorName, creditorAccount, RDFIS[random.nextInt(RDFIS.length)]),
                    "Invoice " + (10000 + i), null, null);
            String key = "sim-" + UUID.randomUUID();
            results.add(wire(key, req));
            if (kind == 7) {
                results.add(wire(key, req));                                             // client retry: idempotent replay
            }
            if (kind == 8) {
                results.add(wire("sim-" + UUID.randomUUID(), req));                      // double submission: held
            }
        }
        return results;
    }

    private Object wire(String key, WireRequest req) {
        try {
            WireService.Outcome o = wires.submitJson(key, req, "simulator");
            return Map.of("wireId", o.wireId(), "status", o.status(), "replay", o.replay());
        } catch (ApiException e) {
            return Map.of("rejected", e.code(), "message", e.getMessage());
        }
    }

    private void requireEnabled() {
        if (!props.simulator().enabled()) {
            throw ApiException.notFound("Simulator is disabled");
        }
    }
}

package io.paysettle.ach.settlement;

import io.paysettle.ach.nacha.NachaFileBuilder;
import io.paysettle.common.AuditLog;
import io.paysettle.common.FieldCrypto;
import io.paysettle.common.Hashing;
import io.paysettle.common.Rail;
import io.paysettle.config.PaySettleProperties;
import io.paysettle.fraud.RiskRecorder;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ACH settlement per FedACH window. Eligible entries are packed into one origination file, transmitted to the
 * ACH Operator and moved to SENT; when the window's settlement time passes they become SETTLED.
 *
 * <p>Same-day windows take entries flagged Same Day (effective today, under the $1M limit, before the cutoff).
 * The next-day window takes every remaining accepted entry due by the next business day. Federal Reserve
 * holidays are not modelled here; production would use the Fed's holiday calendar.
 */
@Service
public class AchSettlementService {

    private static final Logger log = LoggerFactory.getLogger(AchSettlementService.class);

    private final JdbcClient jdbc;
    private final FieldCrypto crypto;
    private final FedAchGateway gateway;
    private final AuditLog audit;
    private final RiskRecorder risk;
    private final PaySettleProperties props;
    private final ZoneId zone;

    public AchSettlementService(JdbcClient jdbc, FieldCrypto crypto, FedAchGateway gateway, AuditLog audit,
                                RiskRecorder risk, PaySettleProperties props) {
        this.jdbc = jdbc;
        this.crypto = crypto;
        this.gateway = gateway;
        this.audit = audit;
        this.risk = risk;
        this.props = props;
        this.zone = ZoneId.of(props.ach().timezone());
    }

    public record Result(UUID settlementBatchId, String window, LocalDate settlementDate, int entries,
                         long totalDebitCents, long totalCreditCents, String networkReference, String status) {
    }

    public PaySettleProperties.Window window(String name) {
        return props.ach().windows().stream().filter(w -> w.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown settlement window " + name));
    }

    /** Settlement date for a window run today: same-day windows settle today, next-day on the next business day. */
    public LocalDate settlementDate(PaySettleProperties.Window w, LocalDate today) {
        return w.sameDay() ? today : nextBusinessDay(today);
    }

    /** Builds and transmits the origination file for one window. Idempotent per (window, settlement date). */
    @Transactional
    public Optional<Result> runWindow(String windowName, LocalDate today) {
        PaySettleProperties.Window w = window(windowName);
        LocalDate settlementDate = settlementDate(w, today);
        if (jdbc.sql("SELECT count(*) FROM ach_settlement_batches WHERE settlement_window = :w AND settlement_date = :d")
                .param("w", w.name()).param("d", settlementDate).query(Integer.class).single() > 0) {
            return Optional.empty();
        }
        List<Map<String, Object>> rows = jdbc.sql("""
                SELECT e.id, e.transaction_code, e.rdfi_routing, e.account_ciphertext, e.amount_cents, e.individual_id,
                       e.individual_name, e.trace_number, e.company_id, e.company_name, e.sec_code, e.effective_date,
                       b.entry_description
                FROM ach_entries e JOIN ach_batches b ON b.id = e.batch_id
                WHERE e.status = 'ACCEPTED' AND e.settlement_batch_id IS NULL
                  AND (:sameDay AND e.same_day OR NOT :sameDay AND e.effective_date <= :due)
                ORDER BY e.company_id, e.sec_code, e.effective_date, e.trace_number
                FOR UPDATE OF e SKIP LOCKED""")
                .param("sameDay", w.sameDay()).param("due", settlementDate).query().listOfRows();
        if (rows.isEmpty()) {
            return Optional.empty();
        }

        // Group into batches by originator, SEC code and effective date, as NACHA requires.
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            groups.computeIfAbsent(r.get("company_id") + "|" + r.get("sec_code") + "|" + r.get("effective_date"),
                    k -> new ArrayList<>()).add(r);
        }
        List<NachaFileBuilder.OutBatch> batches = new ArrayList<>();
        long debits = 0;
        long credits = 0;
        for (List<Map<String, Object>> g : groups.values()) {
            Map<String, Object> first = g.getFirst();
            List<NachaFileBuilder.OutEntry> entries = new ArrayList<>();
            for (Map<String, Object> r : g) {
                String code = (String) r.get("transaction_code");
                long amount = ((Number) r.get("amount_cents")).longValue();
                if (code.charAt(1) >= '7' || "55".equals(code)) {
                    debits += amount;
                } else {
                    credits += amount;
                }
                entries.add(new NachaFileBuilder.OutEntry(code, (String) r.get("rdfi_routing"),
                        crypto.decrypt((String) r.get("account_ciphertext")), amount, (String) r.get("individual_id"),
                        (String) r.get("individual_name"), (String) r.get("trace_number"), null));
            }
            LocalDate effective = ((java.sql.Date) first.get("effective_date")).toLocalDate();
            batches.add(new NachaFileBuilder.OutBatch((String) first.get("company_name"), (String) first.get("company_id"),
                    (String) first.get("sec_code"), (String) first.get("entry_description"),
                    effective.isBefore(settlementDate) ? settlementDate : effective, entries));
        }

        UUID settlementId = UUID.randomUUID();
        LocalDateTime createdAt = LocalDateTime.now(zone);
        String institution = props.institution().routingNumber();
        String content = new NachaFileBuilder(props.institution().fedAchRouting(), institution, "FEDERAL RESERVE BANK",
                props.institution().name()).build(batches, createdAt, nextFileIdModifier(createdAt.toLocalDate()));
        String fileName = "FEDACH_" + w.name() + "_" + settlementDate + "_" + settlementId.toString().substring(0, 8) + ".ach";

        UUID outboundFileId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO ach_files (id, direction, file_name, content_sha256, content, immediate_destination, immediate_origin,
                    file_creation_date, file_creation_time, file_id_modifier, batch_count, entry_count, total_debit_cents,
                    total_credit_cents, status, processed_at)
                VALUES (:id, 'OUTBOUND', :name, :sha, :content, :dest, :origin, :date, :time, :mod, :b, :e, :d, :c, 'PROCESSED', now())""")
                .param("id", outboundFileId).param("name", fileName).param("sha", Hashing.sha256(content))
                .param("content", content).param("dest", props.institution().fedAchRouting()).param("origin", institution)
                .param("date", content.substring(23, 29)).param("time", content.substring(29, 33))
                .param("mod", content.substring(33, 34)).param("b", batches.size()).param("e", rows.size())
                .param("d", debits).param("c", credits).update();
        jdbc.sql("""
                INSERT INTO ach_settlement_batches (id, settlement_window, settlement_date, entry_count, total_debit_cents,
                    total_credit_cents, net_cents, outbound_file_id, status)
                VALUES (:id, :w, :d, :n, :deb, :cred, :net, :file, 'CREATED')""")
                .param("id", settlementId).param("w", w.name()).param("d", settlementDate).param("n", rows.size())
                .param("deb", debits).param("cred", credits).param("net", credits - debits).param("file", outboundFileId).update();
        List<UUID> ids = rows.stream().map(r -> (UUID) r.get("id")).toList();
        jdbc.sql("UPDATE ach_entries SET status = 'SENT', settlement_batch_id = :s, updated_at = now() WHERE id IN (:ids)")
                .param("s", settlementId).param("ids", ids).update();

        String ack = gateway.transmit(fileName, content);
        jdbc.sql("UPDATE ach_settlement_batches SET status = 'ACKNOWLEDGED', network_reference = :ack, transmitted_at = now() WHERE id = :id")
                .param("ack", ack).param("id", settlementId).update();
        for (UUID id : ids) {
            audit.record(Rail.ACH, id, "ACCEPTED", "SENT", "ach-settlement-job", w.name() + " " + ack);
        }
        log.info("ACH {} window: {} entries, debits {} credits {} -> {}", w.name(), rows.size(), debits, credits, ack);
        return Optional.of(new Result(settlementId, w.name(), settlementDate, rows.size(), debits, credits, ack, "ACKNOWLEDGED"));
    }

    /** Marks transmitted batches SETTLED once their window's settlement time has passed (or immediately when forced). */
    @Transactional
    public int completeSettlements(ZonedDateTime now, boolean force) {
        int settled = 0;
        for (Map<String, Object> b : jdbc.sql("""
                SELECT id, settlement_window, settlement_date FROM ach_settlement_batches WHERE status = 'ACKNOWLEDGED'
                FOR UPDATE SKIP LOCKED""").query().listOfRows()) {
            PaySettleProperties.Window w = window((String) b.get("settlement_window"));
            LocalDate date = ((java.sql.Date) b.get("settlement_date")).toLocalDate();
            ZonedDateTime settlesAt = date.atTime(w.settlement()).atZone(zone);
            if (!force && now.isBefore(settlesAt)) {
                continue;
            }
            UUID id = (UUID) b.get("id");
            jdbc.sql("UPDATE ach_settlement_batches SET status = 'SETTLED', settled_at = now() WHERE id = :id").param("id", id).update();
            List<UUID> entries = jdbc.sql("SELECT id FROM ach_entries WHERE settlement_batch_id = :id AND status = 'SENT'")
                    .param("id", id).query(UUID.class).list();
            if (!entries.isEmpty()) {
                jdbc.sql("UPDATE ach_entries SET status = 'SETTLED', settled_at = now(), updated_at = now() WHERE id IN (:ids)")
                        .param("ids", entries).update();
            }
            entries.forEach(e -> {
                audit.record(Rail.ACH, e, "SENT", "SETTLED", "ach-settlement-job", w.name() + " " + date);
                risk.payment(Rail.ACH, "SETTLED");
            });
            settled++;
        }
        return settled;
    }

    /** Windows whose cutoff has passed today (in Eastern time) and that have not run yet. */
    public List<String> dueWindows(ZonedDateTime now) {
        ZonedDateTime local = now.withZoneSameInstant(zone);
        LocalDate today = local.toLocalDate();
        if (!isBusinessDay(today)) {
            return List.of();
        }
        LocalTime time = local.toLocalTime();
        return props.ach().windows().stream()
                .filter(w -> !time.isBefore(w.cutoff()))
                .filter(w -> jdbc.sql("SELECT count(*) FROM ach_settlement_batches WHERE settlement_window = :w AND settlement_date = :d")
                        .param("w", w.name()).param("d", settlementDate(w, today)).query(Integer.class).single() == 0)
                .map(PaySettleProperties.Window::name).toList();
    }

    public List<Map<String, Object>> recentBatches() {
        return jdbc.sql("""
                SELECT s.id, s.network, s.settlement_window, s.settlement_date, s.entry_count, s.total_debit_cents,
                       s.total_credit_cents, s.net_cents, s.status, s.network_reference, s.created_at, s.transmitted_at,
                       s.settled_at, f.file_name
                FROM ach_settlement_batches s LEFT JOIN ach_files f ON f.id = s.outbound_file_id
                ORDER BY s.created_at DESC LIMIT 100""").query().listOfRows();
    }

    private char nextFileIdModifier(LocalDate date) {
        int used = jdbc.sql("SELECT count(*) FROM ach_files WHERE direction = 'OUTBOUND' AND file_creation_date = :d")
                .param("d", date.format(java.time.format.DateTimeFormatter.ofPattern("yyMMdd"))).query(Integer.class).single();
        String mods = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        return mods.charAt(Math.min(used, mods.length() - 1));
    }

    static boolean isBusinessDay(LocalDate d) {
        return d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY;
    }

    static LocalDate nextBusinessDay(LocalDate d) {
        LocalDate next = d.plusDays(1);
        while (!isBusinessDay(next)) {
            next = next.plusDays(1);
        }
        return next;
    }
}

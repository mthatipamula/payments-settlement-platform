package io.paysettle.wire;

import io.paysettle.common.FieldCrypto;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class WireRepository {

    static final String SUMMARY_COLUMNS = """
            id, uetr, end_to_end_id, amount, currency, value_date, debtor_name, debtor_account_last4, debtor_agent_aba,
            creditor_name, creditor_account_last4, creditor_agent_aba, remittance_info, channel, status, status_reason,
            fraud_score, fraud_reasons, sanctions_hit, duplicate_of, network, network_reference, created_at, updated_at,
            settled_at""";

    private final JdbcClient jdbc;

    public WireRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Stored(UUID id, String requestHash, WireStatus status, int version) {
    }

    public Optional<Stored> byIdempotencyKey(String key) {
        return jdbc.sql("SELECT id, request_hash, status, version FROM wires WHERE idempotency_key = :k").param("k", key)
                .query((rs, i) -> new Stored(rs.getObject(1, UUID.class), rs.getString(2), WireStatus.valueOf(rs.getString(3)),
                        rs.getInt(4))).optional();
    }

    public Optional<UUID> byUetr(UUID uetr) {
        return jdbc.sql("SELECT id FROM wires WHERE uetr = :u AND status <> 'REJECTED'").param("u", uetr).query(UUID.class).optional();
    }

    /** Same debtor and creditor accounts and amount within the window: likely a double submission. */
    public Optional<UUID> nearDuplicate(String debtorFp, String creditorFp, BigDecimal amount, int minutes) {
        return jdbc.sql("""
                SELECT id FROM wires WHERE debtor_account_fp = :d AND creditor_account_fp = :c AND amount = :a
                  AND status NOT IN ('REJECTED', 'CANCELLED') AND created_at > now() - make_interval(mins => :m)
                ORDER BY created_at DESC LIMIT 1""")
                .param("d", debtorFp).param("c", creditorFp).param("a", amount).param("m", minutes).query(UUID.class).optional();
    }

    public int debtorVelocity(String debtorFp, int minutes) {
        return jdbc.sql("SELECT count(*) FROM wires WHERE debtor_account_fp = :d AND created_at > now() - make_interval(mins => :m)")
                .param("d", debtorFp).param("m", minutes).query(Integer.class).single();
    }

    public boolean knownBeneficiary(String debtorFp, String creditorFp) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM wires WHERE debtor_account_fp = :d AND creditor_account_fp = :c AND status = 'SETTLED')")
                .param("d", debtorFp).param("c", creditorFp).query(Boolean.class).single();
    }

    public void insert(UUID id, String idempotencyKey, String requestHash, WireInstruction w, FieldCrypto.Protected debtor,
                       FieldCrypto.Protected creditor, String channel, WireStatus status, String reason, int fraudScore,
                       List<String> fraudReasons, String sanctionsHit, UUID duplicateOf, String pacs008) {
        jdbc.sql("""
                INSERT INTO wires (id, idempotency_key, request_hash, uetr, msg_id, instruction_id, end_to_end_id, amount, currency,
                    value_date, debtor_name, debtor_account_ct, debtor_account_fp, debtor_account_last4, debtor_agent_aba,
                    creditor_name, creditor_account_ct, creditor_account_fp, creditor_account_last4, creditor_agent_aba,
                    remittance_info, channel, status, status_reason, fraud_score, fraud_reasons, sanctions_hit, duplicate_of, pacs008)
                VALUES (:id, :key, :hash, :uetr, :msg, :instr, :e2e, :amount, :ccy, :vd, :dn, :dct, :dfp, :dl4, :daba,
                    :cn, :cct, :cfp, :cl4, :caba, :rmt, :channel, :status, :reason, :score, :reasons, :sanc, :dup, :pacs)""")
                .param("id", id).param("key", idempotencyKey).param("hash", requestHash).param("uetr", w.uetr())
                .param("msg", w.msgId()).param("instr", w.instructionId()).param("e2e", w.endToEndId())
                .param("amount", w.amount()).param("ccy", w.currency()).param("vd", w.valueDate())
                .param("dn", w.debtorName()).param("dct", debtor.ciphertext()).param("dfp", debtor.fingerprint())
                .param("dl4", debtor.last4()).param("daba", w.debtorAgentAba()).param("cn", w.creditorName())
                .param("cct", creditor.ciphertext()).param("cfp", creditor.fingerprint()).param("cl4", creditor.last4())
                .param("caba", w.creditorAgentAba()).param("rmt", w.remittanceInfo()).param("channel", channel)
                .param("status", status.name()).param("reason", reason).param("score", fraudScore)
                .param("reasons", fraudReasons.toArray(String[]::new)).param("sanc", sanctionsHit).param("dup", duplicateOf)
                .param("pacs", pacs008).update();
    }

    /** Compare-and-set transition: succeeds only if the wire is still in the expected status and version. */
    public boolean transition(UUID id, WireStatus from, WireStatus to, String reason, int version) {
        return jdbc.sql("""
                UPDATE wires SET status = :to, status_reason = COALESCE(:reason, status_reason), version = version + 1,
                    updated_at = now(), settled_at = CASE WHEN :to = 'SETTLED' THEN now() ELSE settled_at END
                WHERE id = :id AND status = :from AND version = :v""")
                .param("to", to.name()).param("reason", reason).param("id", id).param("from", from.name())
                .param("v", version).update() == 1;
    }

    public void networkResult(UUID id, String reference, String pacs002) {
        jdbc.sql("UPDATE wires SET network_reference = :r, pacs002 = :p WHERE id = :id")
                .param("r", reference).param("p", pacs002).param("id", id).update();
    }

    public Optional<Stored> state(UUID id) {
        return jdbc.sql("SELECT id, request_hash, status, version FROM wires WHERE id = :id").param("id", id)
                .query((rs, i) -> new Stored(rs.getObject(1, UUID.class), rs.getString(2), WireStatus.valueOf(rs.getString(3)),
                        rs.getInt(4))).optional();
    }

    public Optional<Map<String, Object>> view(UUID id) {
        return jdbc.sql("SELECT " + SUMMARY_COLUMNS + ", pacs008, pacs002 FROM wires WHERE id = :id").param("id", id)
                .query().listOfRows().stream().findFirst();
    }

    public List<Map<String, Object>> list(String status) {
        return jdbc.sql("SELECT " + SUMMARY_COLUMNS + " FROM wires WHERE (CAST(:s AS varchar) IS NULL OR status = :s) ORDER BY created_at DESC LIMIT 300")
                .param("s", status).query().listOfRows();
    }

    public Optional<Map<String, Object>> forSettlement(UUID id) {
        return jdbc.sql("SELECT * FROM wires WHERE id = :id").param("id", id).query().listOfRows().stream().findFirst();
    }

    public void enqueueSettlement(UUID wireId) {
        jdbc.sql("INSERT INTO outbox (aggregate_type, aggregate_id, event_type) VALUES ('WIRE', :id, 'WIRE_SETTLE')")
                .param("id", wireId).update();
    }
}

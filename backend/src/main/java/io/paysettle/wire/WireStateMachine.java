package io.paysettle.wire;

import io.paysettle.common.ApiException;
import io.paysettle.common.AuditLog;
import io.paysettle.common.Rail;
import io.paysettle.fraud.RiskRecorder;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Applies wire transitions: validates against WireStatus rules, uses optimistic locking, and audits each move. */
@Component
public class WireStateMachine {

    private final WireRepository repo;
    private final AuditLog audit;
    private final RiskRecorder risk;

    public WireStateMachine(WireRepository repo, AuditLog audit, RiskRecorder risk) {
        this.repo = repo;
        this.audit = audit;
        this.risk = risk;
    }

    public void move(UUID wireId, WireStatus expectedFrom, WireStatus to, String reason, String actor) {
        WireRepository.Stored s = repo.state(wireId).orElseThrow(() -> ApiException.notFound("Wire not found"));
        if (s.status() != expectedFrom) {
            throw ApiException.conflict("INVALID_STATE", "Wire is " + s.status() + ", expected " + expectedFrom);
        }
        if (!expectedFrom.canMoveTo(to)) {
            throw ApiException.conflict("INVALID_TRANSITION", expectedFrom + " -> " + to + " is not allowed");
        }
        if (!repo.transition(wireId, expectedFrom, to, reason, s.version())) {
            throw ApiException.conflict("CONCURRENT_UPDATE", "Wire was changed by another request; retry");
        }
        audit.record(Rail.WIRE, wireId, expectedFrom.name(), to.name(), actor, reason);
        risk.payment(Rail.WIRE, to.name());
    }
}

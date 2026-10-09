package io.paysettle.wire;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Wire lifecycle. Every allowed transition is listed; anything else is rejected, so a wire can never, for
 * example, go from REJECTED back to SENT.
 * <pre>
 * RECEIVED -> VALIDATED -> RELEASED -> SENT -> SETTLED
 *                 |            ^         \-> REJECTED (network)
 *                 +-> HELD_FRAUD / HELD_SANCTIONS / HELD_DUPLICATE -> RELEASED | REJECTED | CANCELLED
 *                 +-> REJECTED
 * </pre>
 */
public enum WireStatus {
    RECEIVED, VALIDATED, HELD_FRAUD, HELD_SANCTIONS, HELD_DUPLICATE, RELEASED, SENT, SETTLED, REJECTED, CANCELLED;

    private static final Map<WireStatus, Set<WireStatus>> ALLOWED = Map.of(
            RECEIVED, EnumSet.of(VALIDATED, REJECTED),
            VALIDATED, EnumSet.of(HELD_FRAUD, HELD_SANCTIONS, HELD_DUPLICATE, RELEASED, REJECTED),
            HELD_FRAUD, EnumSet.of(RELEASED, REJECTED, CANCELLED),
            HELD_SANCTIONS, EnumSet.of(RELEASED, REJECTED),
            HELD_DUPLICATE, EnumSet.of(RELEASED, CANCELLED),
            RELEASED, EnumSet.of(SENT, CANCELLED),
            SENT, EnumSet.of(SETTLED, REJECTED),
            SETTLED, EnumSet.noneOf(WireStatus.class),
            REJECTED, EnumSet.noneOf(WireStatus.class),
            CANCELLED, EnumSet.noneOf(WireStatus.class));

    public boolean canMoveTo(WireStatus next) {
        return ALLOWED.get(this).contains(next);
    }

    public boolean isHeld() {
        return this == HELD_FRAUD || this == HELD_SANCTIONS || this == HELD_DUPLICATE;
    }
}

package io.paysettle.fraud;

import java.util.List;

/** Outcome of fraud scoring: 0-100 score, the rules that fired, and the resulting action. */
public record FraudDecision(int score, List<String> reasons, Action action) {

    public enum Action { ALLOW, REVIEW, BLOCK }

    public boolean flagged() {
        return action != Action.ALLOW;
    }
}

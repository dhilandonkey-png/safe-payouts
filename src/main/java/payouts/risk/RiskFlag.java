package payouts.risk;

import java.util.Objects;

/** One rule's objection to a payout, with a reason a person reviewing it can read. */
public record RiskFlag(String rule, RiskOutcome outcome, String reason) {

    public RiskFlag {
        Objects.requireNonNull(rule, "rule");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(reason, "reason");
        if (outcome == RiskOutcome.ALLOW) {
            throw new IllegalArgumentException("a flag must ask for REVIEW or BLOCK");
        }
    }

    public static RiskFlag review(String rule, String reason) {
        return new RiskFlag(rule, RiskOutcome.REVIEW, reason);
    }

    public static RiskFlag block(String rule, String reason) {
        return new RiskFlag(rule, RiskOutcome.BLOCK, reason);
    }

    /** How the flag is stored on a payout, e.g. {@code "velocity: 5 payouts in the last 1 hour"}. */
    @Override
    public String toString() {
        return rule + ": " + reason;
    }
}

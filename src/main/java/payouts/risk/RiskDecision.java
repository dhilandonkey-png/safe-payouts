package payouts.risk;

import java.util.List;
import java.util.Objects;

/** The combined result of every rule: the strictest outcome, plus every flag that was raised. */
public record RiskDecision(RiskOutcome outcome, List<RiskFlag> flags) {

    public RiskDecision {
        Objects.requireNonNull(outcome, "outcome");
        flags = List.copyOf(flags);
    }

    /** Readable reasons to store on the payout, so a reviewer can see why it was held. */
    public List<String> reasons() {
        return flags.stream().map(RiskFlag::toString).toList();
    }
}

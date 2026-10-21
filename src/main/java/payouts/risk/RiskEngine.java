package payouts.risk;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import payouts.payout.PayoutRequest;

/**
 * Runs every rule and keeps the strictest outcome. All rules run even after one blocks,
 * so a reviewer sees every reason at once instead of fixing them one by one.
 */
public class RiskEngine {

    private final List<RiskRule> rules;

    public RiskEngine(List<RiskRule> rules) {
        this.rules = List.copyOf(Objects.requireNonNull(rules));
    }

    /** An engine with no rules: every payout is allowed. */
    public static RiskEngine allowAll() {
        return new RiskEngine(List.of());
    }

    public RiskDecision evaluate(PayoutRequest request, MerchantHistory history) {
        RiskOutcome outcome = RiskOutcome.ALLOW;
        List<RiskFlag> flags = new ArrayList<>();
        for (RiskRule rule : rules) {
            var flag = rule.check(request, history);
            if (flag.isPresent()) {
                flags.add(flag.get());
                outcome = outcome.strictest(flag.get().outcome());
            }
        }
        return new RiskDecision(outcome, flags);
    }
}

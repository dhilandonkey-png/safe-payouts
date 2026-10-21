package payouts.risk.rules;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import payouts.payout.PayoutRequest;
import payouts.risk.MerchantHistory;
import payouts.risk.RiskFlag;
import payouts.risk.RiskRule;

/** Holds a payout when a merchant suddenly requests many in a short time. */
public class VelocityRule implements RiskRule {

    private final int maxPayouts;
    private final Duration window;

    /** Allows at most {@code maxPayouts} payouts per {@code window}; the next one is held. */
    public VelocityRule(int maxPayouts, Duration window) {
        if (maxPayouts < 1) {
            throw new IllegalArgumentException("maxPayouts must be at least 1");
        }
        this.maxPayouts = maxPayouts;
        this.window = Objects.requireNonNull(window);
    }

    @Override
    public Optional<RiskFlag> check(PayoutRequest request, MerchantHistory history) {
        int recent = history.liveSince(window).size();
        if (recent >= maxPayouts) {
            return Optional.of(RiskFlag.review("velocity",
                    recent + " payouts in the last " + window.toMinutes() + " minutes, limit is " + maxPayouts));
        }
        return Optional.empty();
    }
}

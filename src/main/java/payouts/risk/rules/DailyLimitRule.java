package payouts.risk.rules;

import java.time.Duration;
import java.util.Optional;
import payouts.payout.Payout;
import payouts.payout.PayoutRequest;
import payouts.risk.MerchantHistory;
import payouts.risk.RiskFlag;
import payouts.risk.RiskRule;

/** Holds a payout that would take a merchant's total for the last 24 hours over a limit. */
public class DailyLimitRule implements RiskRule {

    private static final Duration DAY = Duration.ofHours(24);

    private final long maxCentsPerDay;

    public DailyLimitRule(long maxCentsPerDay) {
        if (maxCentsPerDay <= 0) {
            throw new IllegalArgumentException("maxCentsPerDay must be positive");
        }
        this.maxCentsPerDay = maxCentsPerDay;
    }

    @Override
    public Optional<RiskFlag> check(PayoutRequest request, MerchantHistory history) {
        long sentToday = 0;
        for (Payout p : history.liveSince(DAY)) {
            sentToday = Math.addExact(sentToday, p.request().amountCents());
        }
        long total = Math.addExact(sentToday, request.amountCents());
        if (total > maxCentsPerDay) {
            return Optional.of(RiskFlag.review("daily_limit", "would bring the last 24 hours to " + total
                    + " cents, limit is " + maxCentsPerDay));
        }
        return Optional.empty();
    }
}

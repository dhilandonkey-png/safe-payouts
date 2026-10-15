package payouts.risk.rules;

import java.util.Optional;
import payouts.payout.PayoutRequest;
import payouts.risk.MerchantHistory;
import payouts.risk.RiskFlag;
import payouts.risk.RiskRule;

/**
 * Holds a large payout to a bank account this merchant has never been paid to.
 * A common takeover pattern: someone gets into a merchant's account, adds their own
 * bank account, and tries to drain the balance in one payout.
 */
public class NewDestinationRule implements RiskRule {

    private final long maxFirstPayoutCents;

    public NewDestinationRule(long maxFirstPayoutCents) {
        if (maxFirstPayoutCents < 0) {
            throw new IllegalArgumentException("maxFirstPayoutCents must not be negative");
        }
        this.maxFirstPayoutCents = maxFirstPayoutCents;
    }

    @Override
    public Optional<RiskFlag> check(PayoutRequest request, MerchantHistory history) {
        String destination = request.destinationBankAccountId();
        if (request.amountCents() > maxFirstPayoutCents && !history.hasPaidTo(destination)) {
            return Optional.of(RiskFlag.review("new_destination", "first payout to " + destination + " is "
                    + request.amountCents() + " cents, above the " + maxFirstPayoutCents + " cent limit"));
        }
        return Optional.empty();
    }
}

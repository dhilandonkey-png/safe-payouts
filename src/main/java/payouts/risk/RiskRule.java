package payouts.risk;

import java.util.Optional;
import payouts.payout.PayoutRequest;

/**
 * One fraud check. A rule only looks at the request and the merchant's past payouts;
 * it never moves money or changes state, so rules can be added, removed or reordered
 * without touching the payout logic.
 */
public interface RiskRule {

    /** Returns a flag if this request looks risky, or empty if it passes. */
    Optional<RiskFlag> check(PayoutRequest request, MerchantHistory history);
}

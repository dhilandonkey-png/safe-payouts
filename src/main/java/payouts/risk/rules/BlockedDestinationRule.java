package payouts.risk.rules;

import java.util.Optional;
import java.util.Set;
import payouts.payout.PayoutRequest;
import payouts.risk.MerchantHistory;
import payouts.risk.RiskFlag;
import payouts.risk.RiskRule;

/** Blocks payouts to bank accounts already known to be fraudulent. */
public class BlockedDestinationRule implements RiskRule {

    private final Set<String> blockedBankAccounts;

    public BlockedDestinationRule(Set<String> blockedBankAccounts) {
        this.blockedBankAccounts = Set.copyOf(blockedBankAccounts);
    }

    @Override
    public Optional<RiskFlag> check(PayoutRequest request, MerchantHistory history) {
        String destination = request.destinationBankAccountId();
        if (blockedBankAccounts.contains(destination)) {
            return Optional.of(RiskFlag.block("blocked_destination", "bank account " + destination + " is on the blocklist"));
        }
        return Optional.empty();
    }
}

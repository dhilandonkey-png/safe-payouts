package payouts.risk;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import payouts.payout.Payout;
import payouts.payout.PayoutStatus;

/** A merchant's earlier payouts and the current time: everything a rule is allowed to look at. */
public record MerchantHistory(List<Payout> payouts, Instant now) {

    public MerchantHistory {
        payouts = List.copyOf(payouts);
        Objects.requireNonNull(now, "now");
    }

    /**
     * Payouts created in the last {@code window} that can still send money or already did.
     * Blocked and failed payouts are left out because no money left the platform.
     */
    public List<Payout> liveSince(Duration window) {
        Instant cutoff = now.minus(window);
        return payouts.stream()
                .filter(p -> p.createdAt().isAfter(cutoff))
                .filter(p -> p.status() != PayoutStatus.BLOCKED && p.status() != PayoutStatus.FAILED)
                .toList();
    }

    /** True if a payout to this bank account has gone through before. */
    public boolean hasPaidTo(String destinationBankAccountId) {
        return payouts.stream().anyMatch(p -> p.status() == PayoutStatus.PAID
                && p.request().destinationBankAccountId().equals(destinationBankAccountId));
    }
}

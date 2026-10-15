package payouts.payout;

import java.util.List;
import java.util.Optional;

public interface PayoutStore {

    Optional<Payout> findById(String payoutId);

    /** Idempotency keys are scoped to a merchant, so two merchants can use the same key. */
    Optional<Payout> findByIdempotencyKey(String merchantId, String idempotencyKey);

    /** Every payout this merchant has requested, in no particular order. */
    List<Payout> findByMerchant(String merchantId);

    /** Inserts a new payout or replaces an existing one with the same id. */
    void save(Payout payout);
}

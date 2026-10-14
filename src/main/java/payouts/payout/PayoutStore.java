package payouts.payout;

import java.util.Optional;

public interface PayoutStore {

    Optional<Payout> findById(String payoutId);

    /** Idempotency keys are scoped to a merchant, so two merchants can use the same key. */
    Optional<Payout> findByIdempotencyKey(String merchantId, String idempotencyKey);

    /** Inserts a new payout or replaces an existing one with the same id. */
    void save(Payout payout);
}

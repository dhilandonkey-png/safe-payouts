package payouts.payout;

import java.time.Instant;
import java.util.Objects;

/** A payout and the request that created it. Immutable; a status change makes a new copy. */
public record Payout(String id, String idempotencyKey, PayoutRequest request, PayoutStatus status, Instant createdAt) {

    public Payout {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    Payout withStatus(PayoutStatus newStatus) {
        return new Payout(id, idempotencyKey, request, newStatus, createdAt);
    }
}

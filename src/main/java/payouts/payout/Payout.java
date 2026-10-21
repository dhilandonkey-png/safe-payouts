package payouts.payout;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A payout and the request that created it. Immutable; a status change makes a new copy.
 *
 * @param riskReasons why fraud rules held or blocked it; empty if nothing was flagged
 */
public record Payout(String id, String idempotencyKey, PayoutRequest request, PayoutStatus status, Instant createdAt,
        List<String> riskReasons) {

    public Payout {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        riskReasons = List.copyOf(riskReasons);
    }

    Payout withStatus(PayoutStatus newStatus) {
        return new Payout(id, idempotencyKey, request, newStatus, createdAt, riskReasons);
    }
}

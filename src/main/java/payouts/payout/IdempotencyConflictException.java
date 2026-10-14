package payouts.payout;

/**
 * The same idempotency key was sent with a different request. That almost always
 * means a bug in the caller, so it's refused instead of guessing (Decision 4).
 */
public class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException(String idempotencyKey) {
        super("idempotency key " + idempotencyKey + " was already used with a different request");
    }
}

package payouts.payout;

/**
 * Where a payout is in its life.
 *
 * <pre>
 *   IN_REVIEW ──► PENDING ──► PAID
 *      │             │
 *      │             └──────► FAILED    (money goes back to the merchant)
 *      └──────► BLOCKED                 (rejected in review; money goes back)
 *
 *   BLOCKED is also where a payout starts if a fraud rule blocks it outright.
 *   Then no money ever moves.
 * </pre>
 */
public enum PayoutStatus {
    /** A fraud rule held it. Funds are reserved until a person approves or rejects it. */
    IN_REVIEW,
    /** Funds are reserved and the payout is waiting to be sent to the bank. */
    PENDING,
    /** The bank confirmed it. Final. */
    PAID,
    /** The bank rejected it and the reserved funds were returned. Final. */
    FAILED,
    /** Fraud rules or a reviewer stopped it. Any reserved funds were returned. Final. */
    BLOCKED
}

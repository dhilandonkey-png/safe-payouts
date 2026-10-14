package payouts.payout;

/**
 * Where a payout is in its life.
 *
 * <pre>
 *   PENDING ──► PAID
 *      │
 *      └─────► FAILED   (money goes back to the merchant)
 * </pre>
 */
public enum PayoutStatus {
    /** Funds are reserved and the payout is waiting to be sent to the bank. */
    PENDING,
    /** The bank confirmed it. Final. */
    PAID,
    /** The bank rejected it and the reserved funds were returned. Final. */
    FAILED
}

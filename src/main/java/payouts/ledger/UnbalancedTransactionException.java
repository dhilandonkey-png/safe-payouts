package payouts.ledger;

/** Thrown when a transaction would break the double-entry rule. */
public class UnbalancedTransactionException extends IllegalArgumentException {
    public UnbalancedTransactionException(String message) {
        super(message);
    }
}

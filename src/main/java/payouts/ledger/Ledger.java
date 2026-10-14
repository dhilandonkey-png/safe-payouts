package payouts.ledger;

/**
 * The record of every money movement.
 *
 * <p>Balances are never stored; they're computed from the entries every time.
 * That way a balance can't drift away from the history that produced it.
 *
 * <p>Sign convention: an account's balance is its credits minus its debits.
 * Merchant accounts hold money we owe the merchant, so a payment to a merchant
 * is a credit and a payout from them is a debit.
 */
public class Ledger {

    private final LedgerStore store;

    public Ledger(LedgerStore store) {
        this.store = store;
    }

    /** Records a transaction. Its balance was already checked when it was constructed. */
    public void post(Transaction transaction) {
        store.append(transaction);
    }

    public long balanceCents(String accountId) {
        long balance = 0;
        for (Transaction t : store.all()) {
            for (Entry e : t.entries()) {
                if (e.accountId().equals(accountId)) {
                    balance = e.direction() == Direction.CREDIT
                            ? Math.addExact(balance, e.amountCents())
                            : Math.subtractExact(balance, e.amountCents());
                }
            }
        }
        return balance;
    }

    /**
     * Sum of every account's balance. Because every transaction is balanced,
     * this is always zero; the tests check it after lots of random postings.
     */
    public long totalOfAllBalancesCents() {
        long total = 0;
        for (Transaction t : store.all()) {
            for (Entry e : t.entries()) {
                total = e.direction() == Direction.CREDIT
                        ? Math.addExact(total, e.amountCents())
                        : Math.subtractExact(total, e.amountCents());
            }
        }
        return total;
    }
}

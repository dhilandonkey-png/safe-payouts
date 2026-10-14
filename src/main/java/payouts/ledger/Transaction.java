package payouts.ledger;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A group of entries that are posted together, all or nothing.
 *
 * <p>The double-entry rule: total debits must equal total credits. That's what
 * guarantees money is only ever moved between accounts, never created or lost.
 */
public record Transaction(String id, String description, List<Entry> entries, Instant createdAt) {

    public Transaction {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(entries, "entries");
        Objects.requireNonNull(createdAt, "createdAt");
        entries = List.copyOf(entries);

        if (entries.size() < 2) {
            throw new UnbalancedTransactionException("a transaction needs at least two entries, got " + entries.size());
        }

        long debits = 0;
        long credits = 0;
        for (Entry e : entries) {
            // addExact throws instead of silently wrapping around if the total overflows a long.
            if (e.direction() == Direction.DEBIT) {
                debits = Math.addExact(debits, e.amountCents());
            } else {
                credits = Math.addExact(credits, e.amountCents());
            }
        }
        if (debits != credits) {
            throw new UnbalancedTransactionException(
                    "debits (" + debits + ") must equal credits (" + credits + ") in transaction " + id);
        }
    }
}

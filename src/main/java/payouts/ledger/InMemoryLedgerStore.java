package payouts.ledger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Keeps transactions in a list. Not durable: everything is lost when the program stops. */
public class InMemoryLedgerStore implements LedgerStore {

    private final List<Transaction> transactions = new ArrayList<>();
    private final Set<String> ids = new HashSet<>();

    @Override
    public synchronized void append(Transaction transaction) {
        if (!ids.add(transaction.id())) {
            throw new IllegalStateException("transaction " + transaction.id() + " was already posted");
        }
        transactions.add(transaction);
    }

    @Override
    public synchronized List<Transaction> all() {
        return List.copyOf(transactions);
    }
}

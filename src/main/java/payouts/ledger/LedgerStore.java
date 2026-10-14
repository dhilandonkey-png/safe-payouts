package payouts.ledger;

import java.util.List;

/**
 * Where posted transactions are kept. Version 1 keeps them in memory
 * (Decision 2 in DESIGN.md); a database-backed store can replace it later
 * without changing the Ledger.
 */
public interface LedgerStore {

    /** Saves a transaction. Fails if a transaction with the same id already exists. */
    void append(Transaction transaction);

    List<Transaction> all();
}

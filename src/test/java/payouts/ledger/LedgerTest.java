package payouts.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class LedgerTest {

    private final Ledger ledger = new Ledger(new InMemoryLedgerStore());

    private static Transaction tx(String id, Entry... entries) {
        return new Transaction(id, "test", List.of(entries), Instant.EPOCH);
    }

    @Test
    void balancesAreComputedFromEntries() {
        ledger.post(tx("tx_1", Entry.debit("platform_cash", 10_000), Entry.credit("merchant_a", 10_000)));
        ledger.post(tx("tx_2", Entry.debit("merchant_a", 2_500), Entry.credit("platform_cash", 2_500)));

        assertEquals(7_500, ledger.balanceCents("merchant_a"));
        assertEquals(-7_500, ledger.balanceCents("platform_cash"));
        assertEquals(0, ledger.balanceCents("never_used"));
    }

    @Test
    void sameTransactionCannotBePostedTwice() {
        Transaction t = tx("tx_1", Entry.debit("platform_cash", 100), Entry.credit("merchant_a", 100));
        ledger.post(t);

        assertThrows(IllegalStateException.class, () -> ledger.post(t));
        assertEquals(100, ledger.balanceCents("merchant_a"));
    }

    /**
     * The core invariant: no matter what valid transactions are posted, the
     * balances of all accounts add up to zero. Money only moves; it is never
     * created or destroyed. A fixed seed keeps the test repeatable.
     */
    @Test
    void allBalancesAlwaysSumToZero() {
        Random random = new Random(42);
        String[] accounts = {"platform_cash", "platform_fees", "merchant_a", "merchant_b", "merchant_c"};

        for (int i = 0; i < 1_000; i++) {
            int legs = 2 + random.nextInt(3);
            List<Entry> entries = new ArrayList<>();
            long debits = 0;
            for (int j = 0; j < legs - 1; j++) {
                long amount = 1 + random.nextInt(1_000_000);
                entries.add(Entry.debit(accounts[random.nextInt(accounts.length)], amount));
                debits += amount;
            }
            entries.add(Entry.credit(accounts[random.nextInt(accounts.length)], debits));
            ledger.post(new Transaction("tx_" + i, "random", entries, Instant.EPOCH));

            assertEquals(0, ledger.totalOfAllBalancesCents(), "after transaction " + i);
        }
    }
}

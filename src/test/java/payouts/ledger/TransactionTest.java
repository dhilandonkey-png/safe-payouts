package payouts.ledger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class TransactionTest {

    private static Transaction tx(Entry... entries) {
        return new Transaction("tx_1", "test", List.of(entries), Instant.EPOCH);
    }

    @Test
    void balancedTransactionIsAccepted() {
        assertDoesNotThrow(() -> tx(Entry.debit("platform_cash", 500), Entry.credit("merchant_a", 500)));
    }

    @Test
    void unbalancedTransactionIsRejected() {
        assertThrows(UnbalancedTransactionException.class,
                () -> tx(Entry.debit("platform_cash", 500), Entry.credit("merchant_a", 499)));
    }

    @Test
    void singleEntryIsRejected() {
        assertThrows(UnbalancedTransactionException.class, () -> tx(Entry.credit("merchant_a", 500)));
    }

    @Test
    void zeroAndNegativeAmountsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> Entry.credit("merchant_a", 0));
        assertThrows(IllegalArgumentException.class, () -> Entry.credit("merchant_a", -100));
    }

    @Test
    void splitAcrossSeveralAccountsIsAcceptedWhenTotalsMatch() {
        // A $100 payment where the platform keeps a $3 fee.
        assertDoesNotThrow(() -> tx(
                Entry.debit("platform_cash", 10_000),
                Entry.credit("merchant_a", 9_700),
                Entry.credit("platform_fees", 300)));
    }

    @Test
    void overflowingTotalsAreRejectedInsteadOfWrappingAround() {
        assertThrows(ArithmeticException.class, () -> tx(
                Entry.debit("a", Long.MAX_VALUE),
                Entry.debit("a", 1),
                Entry.credit("b", Long.MAX_VALUE)));
    }
}

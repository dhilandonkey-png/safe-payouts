package payouts.payout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import payouts.ledger.Entry;
import payouts.ledger.InMemoryLedgerStore;
import payouts.ledger.Ledger;
import payouts.ledger.Transaction;

class PayoutServiceTest {

    private static final String MERCHANT = "acme";
    private static final String MERCHANT_ACCOUNT = Accounts.merchant(MERCHANT);

    private Ledger ledger;
    private PayoutService payouts;

    @BeforeEach
    void setUp() {
        ledger = new Ledger(new InMemoryLedgerStore());
        AtomicInteger next = new AtomicInteger();
        payouts = new PayoutService(ledger, new InMemoryPayoutStore(), () -> "po_" + next.incrementAndGet(),
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        fund(MERCHANT, 10_000);
    }

    /** Gives a merchant a balance, as if a customer had paid them. */
    private void fund(String merchantId, long cents) {
        ledger.post(new Transaction("payment:" + merchantId, "customer payment", List.of(
                Entry.debit(Accounts.PLATFORM_CASH, cents),
                Entry.credit(Accounts.merchant(merchantId), cents)), Instant.EPOCH));
    }

    private static PayoutRequest request(long cents) {
        return new PayoutRequest(MERCHANT, cents, "bank_1");
    }

    @Test
    void creatingAPayoutReservesTheFunds() {
        Payout payout = payouts.createPayout("key_1", request(2_500));

        assertEquals(PayoutStatus.PENDING, payout.status());
        assertEquals(7_500, ledger.balanceCents(MERCHANT_ACCOUNT));
        assertEquals(2_500, ledger.balanceCents(Accounts.PAYOUTS_IN_FLIGHT));
    }

    @Test
    void retryingWithTheSameKeyReturnsTheOriginalPayoutAndReservesOnce() {
        Payout first = payouts.createPayout("key_1", request(2_500));
        Payout retry = payouts.createPayout("key_1", request(2_500));

        assertSame(first, retry);
        assertEquals(7_500, ledger.balanceCents(MERCHANT_ACCOUNT));
    }

    @Test
    void reusingAKeyWithADifferentRequestIsRejected() {
        payouts.createPayout("key_1", request(2_500));

        assertThrows(IdempotencyConflictException.class, () -> payouts.createPayout("key_1", request(9_000)));
        assertThrows(IdempotencyConflictException.class,
                () -> payouts.createPayout("key_1", new PayoutRequest(MERCHANT, 2_500, "bank_2")));
        assertEquals(7_500, ledger.balanceCents(MERCHANT_ACCOUNT));
    }

    @Test
    void differentMerchantsCanUseTheSameKey() {
        fund("globex", 5_000);

        payouts.createPayout("key_1", request(1_000));
        Payout other = payouts.createPayout("key_1", new PayoutRequest("globex", 1_000, "bank_9"));

        assertEquals("globex", other.request().merchantId());
        assertEquals(4_000, ledger.balanceCents(Accounts.merchant("globex")));
    }

    @Test
    void payoutLargerThanTheBalanceIsRejectedAndNothingIsReserved() {
        assertThrows(InsufficientFundsException.class, () -> payouts.createPayout("key_1", request(10_001)));
        assertEquals(10_000, ledger.balanceCents(MERCHANT_ACCOUNT));
        assertEquals(0, ledger.balanceCents(Accounts.PAYOUTS_IN_FLIGHT));
    }

    @Test
    void reservedFundsCantBeSpentTwice() {
        payouts.createPayout("key_1", request(6_000));

        assertThrows(InsufficientFundsException.class, () -> payouts.createPayout("key_2", request(6_000)));
    }

    @Test
    void paidPayoutLeavesThePlatform() {
        Payout payout = payouts.createPayout("key_1", request(2_500));
        payouts.markPaid(payout.id());

        assertEquals(PayoutStatus.PAID, payouts.get(payout.id()).status());
        assertEquals(0, ledger.balanceCents(Accounts.PAYOUTS_IN_FLIGHT));
        assertEquals(7_500, ledger.balanceCents(MERCHANT_ACCOUNT));
    }

    @Test
    void failedPayoutReturnsTheMoneyToTheMerchant() {
        Payout payout = payouts.createPayout("key_1", request(2_500));
        payouts.markFailed(payout.id());

        assertEquals(PayoutStatus.FAILED, payouts.get(payout.id()).status());
        assertEquals(10_000, ledger.balanceCents(MERCHANT_ACCOUNT));
        assertEquals(0, ledger.balanceCents(Accounts.PAYOUTS_IN_FLIGHT));
    }

    @Test
    void finishedPayoutsCantChangeStatusAgain() {
        Payout payout = payouts.createPayout("key_1", request(2_500));
        payouts.markPaid(payout.id());

        assertThrows(IllegalStateException.class, () -> payouts.markPaid(payout.id()));
        assertThrows(IllegalStateException.class, () -> payouts.markFailed(payout.id()));
        assertEquals(0, ledger.balanceCents(Accounts.PAYOUTS_IN_FLIGHT));
    }

    @Test
    void retryAfterAFailedPayoutReturnsTheFailedPayoutRatherThanPayingAgain() {
        Payout payout = payouts.createPayout("key_1", request(2_500));
        payouts.markFailed(payout.id());

        assertEquals(PayoutStatus.FAILED, payouts.createPayout("key_1", request(2_500)).status());
        assertEquals(10_000, ledger.balanceCents(MERCHANT_ACCOUNT));
    }

    @Test
    void ledgerStaysBalancedThroughEveryStep() {
        Payout a = payouts.createPayout("key_1", request(1_000));
        Payout b = payouts.createPayout("key_2", request(2_000));
        payouts.markPaid(a.id());
        payouts.markFailed(b.id());

        assertEquals(0, ledger.totalOfAllBalancesCents());
    }
}

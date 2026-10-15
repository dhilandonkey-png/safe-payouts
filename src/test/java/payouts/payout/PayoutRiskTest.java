package payouts.payout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import payouts.ledger.Entry;
import payouts.ledger.InMemoryLedgerStore;
import payouts.ledger.Ledger;
import payouts.ledger.Transaction;
import payouts.risk.RiskEngine;
import payouts.risk.rules.BlockedDestinationRule;
import payouts.risk.rules.NewDestinationRule;
import payouts.risk.rules.VelocityRule;

/** How fraud decisions change what the payout service does with money. */
class PayoutRiskTest {

    private static final String MERCHANT = "acme";
    private static final String MERCHANT_ACCOUNT = Accounts.merchant(MERCHANT);

    /** A clock the test can move forward. */
    private static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-10-15T12:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    private Ledger ledger;
    private PayoutService payouts;
    private TestClock clock;

    @BeforeEach
    void setUp() {
        ledger = new Ledger(new InMemoryLedgerStore());
        clock = new TestClock();
        AtomicInteger next = new AtomicInteger();
        RiskEngine risk = new RiskEngine(List.of(
                new BlockedDestinationRule(Set.of("bank_stolen")),
                new NewDestinationRule(5_000),
                new VelocityRule(3, Duration.ofHours(1))));
        payouts = new PayoutService(ledger, new InMemoryPayoutStore(), () -> "po_" + next.incrementAndGet(), clock,
                risk);
        ledger.post(new Transaction("payment_1", "customer payment", List.of(
                Entry.debit(Accounts.PLATFORM_CASH, 100_000),
                Entry.credit(MERCHANT_ACCOUNT, 100_000)), clock.instant()));
    }

    private static PayoutRequest request(long cents, String bank) {
        return new PayoutRequest(MERCHANT, cents, bank);
    }

    @Test
    void anOrdinaryPayoutIsNotFlagged() {
        Payout payout = payouts.createPayout("key_1", request(1_000, "bank_1"));

        assertEquals(PayoutStatus.PENDING, payout.status());
        assertTrue(payout.riskReasons().isEmpty());
    }

    @Test
    void aHeldPayoutStillReservesTheFunds() {
        Payout held = payouts.createPayout("key_1", request(50_000, "bank_new"));

        assertEquals(PayoutStatus.IN_REVIEW, held.status());
        assertTrue(held.riskReasons().get(0).startsWith("new_destination: "));
        // Reserved, so the merchant can't spend the same money while it waits.
        assertEquals(50_000, ledger.balanceCents(MERCHANT_ACCOUNT));
        assertEquals(50_000, ledger.balanceCents(Accounts.PAYOUTS_IN_FLIGHT));
    }

    @Test
    void aHeldPayoutCantBeSentUntilItIsApproved() {
        Payout held = payouts.createPayout("key_1", request(50_000, "bank_new"));

        assertThrows(IllegalStateException.class, () -> payouts.markPaid(held.id()));
        assertThrows(IllegalStateException.class, () -> payouts.markFailed(held.id()));

        assertEquals(PayoutStatus.PENDING, payouts.approve(held.id()).status());
        assertEquals(PayoutStatus.PAID, payouts.markPaid(held.id()).status());
        assertEquals(0, ledger.balanceCents(Accounts.PAYOUTS_IN_FLIGHT));
        assertEquals(50_000, ledger.balanceCents(MERCHANT_ACCOUNT));
    }

    @Test
    void rejectingAHeldPayoutReturnsTheMoney() {
        Payout held = payouts.createPayout("key_1", request(50_000, "bank_new"));

        Payout rejected = payouts.reject(held.id());

        assertEquals(PayoutStatus.BLOCKED, rejected.status());
        assertEquals(100_000, ledger.balanceCents(MERCHANT_ACCOUNT));
        assertEquals(0, ledger.balanceCents(Accounts.PAYOUTS_IN_FLIGHT));
        assertThrows(IllegalStateException.class, () -> payouts.approve(held.id()));
        assertThrows(IllegalStateException.class, () -> payouts.reject(held.id()));
    }

    @Test
    void onlyHeldPayoutsCanBeApprovedOrRejected() {
        Payout normal = payouts.createPayout("key_1", request(1_000, "bank_1"));

        assertThrows(IllegalStateException.class, () -> payouts.approve(normal.id()));
        assertThrows(IllegalStateException.class, () -> payouts.reject(normal.id()));
    }

    @Test
    void aBlockedPayoutMovesNoMoney() {
        Payout blocked = payouts.createPayout("key_1", request(1_000, "bank_stolen"));

        assertEquals(PayoutStatus.BLOCKED, blocked.status());
        assertEquals(100_000, ledger.balanceCents(MERCHANT_ACCOUNT));
        assertEquals(0, ledger.balanceCents(Accounts.PAYOUTS_IN_FLIGHT));
        assertThrows(IllegalStateException.class, () -> payouts.markPaid(blocked.id()));
    }

    @Test
    void retryingABlockedPayoutGetsTheSameAnswer() {
        Payout blocked = payouts.createPayout("key_1", request(1_000, "bank_stolen"));

        assertSame(blocked, payouts.createPayout("key_1", request(1_000, "bank_stolen")));
    }

    @Test
    void retryingAHeldPayoutDoesNotReserveTwice() {
        Payout held = payouts.createPayout("key_1", request(50_000, "bank_new"));

        assertSame(held, payouts.createPayout("key_1", request(50_000, "bank_new")));
        assertEquals(50_000, ledger.balanceCents(Accounts.PAYOUTS_IN_FLIGHT));
    }

    @Test
    void aBurstOfPayoutsIsHeldAndTheLimitResetsAfterTheWindow() {
        for (int i = 1; i <= 3; i++) {
            assertEquals(PayoutStatus.PENDING, payouts.createPayout("key_" + i, request(100, "bank_1")).status());
        }
        assertEquals(PayoutStatus.IN_REVIEW, payouts.createPayout("key_4", request(100, "bank_1")).status());

        clock.advance(Duration.ofMinutes(61));
        assertEquals(PayoutStatus.PENDING, payouts.createPayout("key_5", request(100, "bank_1")).status());
    }

    @Test
    void anAccountIsTrustedForLargePayoutsOnceAPayoutToItIsPaid() {
        Payout first = payouts.createPayout("key_1", request(1_000, "bank_new"));
        payouts.markPaid(first.id());

        assertEquals(PayoutStatus.PENDING, payouts.createPayout("key_2", request(50_000, "bank_new")).status());
    }

    @Test
    void ledgerStaysBalancedThroughReviewsAndBlocks() {
        Payout approved = payouts.createPayout("key_1", request(20_000, "bank_new"));
        Payout rejected = payouts.createPayout("key_2", request(20_000, "bank_other"));
        payouts.createPayout("key_3", request(1_000, "bank_stolen"));
        payouts.approve(approved.id());
        payouts.markPaid(approved.id());
        payouts.reject(rejected.id());

        assertEquals(0, ledger.totalOfAllBalancesCents());
        assertEquals(80_000, ledger.balanceCents(MERCHANT_ACCOUNT));
    }
}

package payouts;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import payouts.ledger.Entry;
import payouts.ledger.InMemoryLedgerStore;
import payouts.ledger.Ledger;
import payouts.ledger.Transaction;
import payouts.payout.Accounts;
import payouts.payout.IdempotencyConflictException;
import payouts.payout.InMemoryPayoutStore;
import payouts.payout.InsufficientFundsException;
import payouts.payout.Payout;
import payouts.payout.PayoutRequest;
import payouts.payout.PayoutService;

/** Walks through the main scenarios and prints what happens. Run with {@code ./gradlew run}. */
public final class Demo {

    private static Ledger ledger;

    public static void main(String[] args) {
        ledger = new Ledger(new InMemoryLedgerStore());
        AtomicInteger ids = new AtomicInteger();
        PayoutService payouts = new PayoutService(ledger, new InMemoryPayoutStore(),
                () -> "po_" + ids.incrementAndGet(), Clock.systemUTC());

        step("A customer pays merchant acme $100.00");
        ledger.post(new Transaction("payment_1", "customer payment", List.of(
                Entry.debit(Accounts.PLATFORM_CASH, 10_000),
                Entry.credit(Accounts.merchant("acme"), 10_000)), Instant.now()));
        balances();

        step("acme requests a $25.00 payout with key \"key_1\"");
        Payout first = payouts.createPayout("key_1", new PayoutRequest("acme", 2_500, "bank_1"));
        System.out.println("  created " + first.id() + ", status " + first.status());
        balances();

        step("The network times out, so acme retries with the same key");
        Payout retry = payouts.createPayout("key_1", new PayoutRequest("acme", 2_500, "bank_1"));
        System.out.println("  got back " + retry.id() + " (same payout, no second charge)");
        balances();

        step("A buggy client reuses \"key_1\" for $90.00");
        try {
            payouts.createPayout("key_1", new PayoutRequest("acme", 9_000, "bank_1"));
        } catch (IdempotencyConflictException e) {
            System.out.println("  rejected: " + e.getMessage());
        }

        step("acme asks for $80.00, more than the $75.00 left");
        try {
            payouts.createPayout("key_2", new PayoutRequest("acme", 8_000, "bank_1"));
        } catch (InsufficientFundsException e) {
            System.out.println("  rejected: " + e.getMessage());
        }

        step("The bank confirms the $25.00 payout");
        payouts.markPaid(first.id());
        System.out.println("  " + first.id() + " is now " + payouts.get(first.id()).status());
        balances();

        step("acme requests $40.00, but the bank rejects it");
        Payout second = payouts.createPayout("key_3", new PayoutRequest("acme", 4_000, "bank_2"));
        System.out.println("  created " + second.id() + ", reserved $40.00");
        payouts.markFailed(second.id());
        System.out.println("  " + second.id() + " is now " + payouts.get(second.id()).status() + ", money returned");
        balances();

        step("Sanity check");
        System.out.println("  all balances add up to " + ledger.totalOfAllBalancesCents() + " (must be 0)");
    }

    private static void step(String text) {
        System.out.println();
        System.out.println("> " + text);
    }

    private static void balances() {
        System.out.printf("  acme balance: %s   in flight: %s%n",
                dollars(ledger.balanceCents(Accounts.merchant("acme"))),
                dollars(ledger.balanceCents(Accounts.PAYOUTS_IN_FLIGHT)));
    }

    private static String dollars(long cents) {
        long abs = Math.abs(cents);
        return String.format("%s$%d.%02d", cents < 0 ? "-" : "", abs / 100, abs % 100);
    }
}

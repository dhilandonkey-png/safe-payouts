package payouts.payout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.RepeatedTest;
import payouts.ledger.Entry;
import payouts.ledger.InMemoryLedgerStore;
import payouts.ledger.Ledger;
import payouts.ledger.Transaction;

/**
 * Fires many requests at the same moment. These are the races a real payments
 * API sees when a client retries after a timeout, or two requests arrive together.
 */
class PayoutConcurrencyTest {

    private static final int THREADS = 32;

    private Ledger ledger;
    private PayoutService payouts;

    private void setUp(long merchantBalance) {
        ledger = new Ledger(new InMemoryLedgerStore());
        AtomicInteger next = new AtomicInteger();
        payouts = new PayoutService(ledger, new InMemoryPayoutStore(), () -> "po_" + next.incrementAndGet(),
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        ledger.post(new Transaction("payment", "customer payment", List.of(
                Entry.debit(Accounts.PLATFORM_CASH, merchantBalance),
                Entry.credit(Accounts.merchant("acme"), merchantBalance)), Instant.EPOCH));
    }

    /** Runs every task at once (they all wait on one latch) and returns their results. */
    private static List<Future<Payout>> runTogether(List<Callable<Payout>> tasks) throws InterruptedException {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            List<Future<Payout>> futures = new ArrayList<>();
            for (Callable<Payout> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS));
            return futures;
        } finally {
            pool.shutdownNow();
        }
    }

    @RepeatedTest(20)
    void simultaneousRetriesWithOneKeyCreateExactlyOnePayout() throws Exception {
        setUp(10_000);
        PayoutRequest request = new PayoutRequest("acme", 2_500, "bank_1");

        List<Callable<Payout>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            tasks.add(() -> payouts.createPayout("key_1", request));
        }

        Set<String> ids = ConcurrentHashMap.newKeySet();
        for (Future<Payout> f : runTogether(tasks)) {
            ids.add(f.get().id());
        }

        assertEquals(1, ids.size(), "every retry should get the same payout");
        assertEquals(7_500, ledger.balanceCents(Accounts.merchant("acme")), "funds reserved exactly once");
    }

    @RepeatedTest(20)
    void simultaneousPayoutsNeverOverdrawTheMerchant() throws Exception {
        // 32 requests of $10 each against a $100 balance: exactly 10 can succeed.
        setUp(10_000);

        List<Callable<Payout>> tasks = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            String key = "key_" + i;
            tasks.add(() -> payouts.createPayout(key, new PayoutRequest("acme", 1_000, "bank_1")));
        }

        int succeeded = 0;
        int rejected = 0;
        for (Future<Payout> f : runTogether(tasks)) {
            try {
                f.get();
                succeeded++;
            } catch (java.util.concurrent.ExecutionException e) {
                if (!(e.getCause() instanceof InsufficientFundsException)) {
                    throw e;
                }
                rejected++;
            }
        }

        assertEquals(10, succeeded);
        assertEquals(THREADS - 10, rejected);
        assertEquals(0, ledger.balanceCents(Accounts.merchant("acme")));
        assertEquals(0, ledger.totalOfAllBalancesCents());
    }
}

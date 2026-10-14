package payouts.payout;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import payouts.ledger.Entry;
import payouts.ledger.Ledger;
import payouts.ledger.Transaction;

/**
 * Creates and settles payouts, recording every money movement in the ledger.
 *
 * <p>Money flow:
 * <pre>
 *   request:  merchant balance ──► payouts_in_flight        (reserve, Decision 5)
 *   paid:     payouts_in_flight ──► platform_cash leaves     (money sent to the bank)
 *   failed:   payouts_in_flight ──► merchant balance         (reservation released)
 * </pre>
 *
 * <p>Every public method holds one lock, so "check the balance, then reserve" can't
 * be interleaved with another request (Decision 6).
 */
public class PayoutService {

    private final Ledger ledger;
    private final PayoutStore store;
    private final Supplier<String> ids;
    private final Clock clock;

    public PayoutService(Ledger ledger, PayoutStore store, Supplier<String> ids, Clock clock) {
        this.ledger = Objects.requireNonNull(ledger);
        this.store = Objects.requireNonNull(store);
        this.ids = Objects.requireNonNull(ids);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * Requests a payout. Sending the same idempotency key with the same request
     * returns the original payout instead of creating a second one, so a client
     * can safely retry after a timeout.
     *
     * @throws IdempotencyConflictException if the key was used with a different request
     * @throws InsufficientFundsException if the merchant's balance can't cover it
     */
    public synchronized Payout createPayout(String idempotencyKey, PayoutRequest request) {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(request, "request");
        if (idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }

        var existing = store.findByIdempotencyKey(request.merchantId(), idempotencyKey);
        if (existing.isPresent()) {
            if (!existing.get().request().equals(request)) {
                throw new IdempotencyConflictException(idempotencyKey);
            }
            return existing.get();
        }

        String merchantAccount = Accounts.merchant(request.merchantId());
        long available = ledger.balanceCents(merchantAccount);
        if (available < request.amountCents()) {
            throw new InsufficientFundsException(request.merchantId(), request.amountCents(), available);
        }

        Payout payout = new Payout(ids.get(), idempotencyKey, request, PayoutStatus.PENDING, clock.instant());
        ledger.post(new Transaction("reserve:" + payout.id(), "reserve funds for payout " + payout.id(), List.of(
                Entry.debit(merchantAccount, request.amountCents()),
                Entry.credit(Accounts.PAYOUTS_IN_FLIGHT, request.amountCents())), clock.instant()));
        store.save(payout);
        return payout;
    }

    /** The bank confirmed the payout. */
    public synchronized Payout markPaid(String payoutId) {
        Payout payout = pending(payoutId);
        long amount = payout.request().amountCents();
        ledger.post(new Transaction("settle:" + payoutId, "payout " + payoutId + " paid", List.of(
                Entry.debit(Accounts.PAYOUTS_IN_FLIGHT, amount),
                Entry.credit(Accounts.PLATFORM_CASH, amount)), clock.instant()));
        Payout paid = payout.withStatus(PayoutStatus.PAID);
        store.save(paid);
        return paid;
    }

    /** The bank rejected the payout, so the reserved money goes back to the merchant. */
    public synchronized Payout markFailed(String payoutId) {
        Payout payout = pending(payoutId);
        long amount = payout.request().amountCents();
        ledger.post(new Transaction("release:" + payoutId, "payout " + payoutId + " failed", List.of(
                Entry.debit(Accounts.PAYOUTS_IN_FLIGHT, amount),
                Entry.credit(Accounts.merchant(payout.request().merchantId()), amount)), clock.instant()));
        Payout failed = payout.withStatus(PayoutStatus.FAILED);
        store.save(failed);
        return failed;
    }

    public synchronized Payout get(String payoutId) {
        return store.findById(payoutId)
                .orElseThrow(() -> new IllegalArgumentException("no payout with id " + payoutId));
    }

    private Payout pending(String payoutId) {
        Payout payout = get(payoutId);
        if (payout.status() != PayoutStatus.PENDING) {
            throw new IllegalStateException("payout " + payoutId + " is " + payout.status() + ", not PENDING");
        }
        return payout;
    }
}

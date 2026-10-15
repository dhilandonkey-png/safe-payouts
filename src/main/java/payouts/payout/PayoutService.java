package payouts.payout;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import payouts.ledger.Entry;
import payouts.ledger.Ledger;
import payouts.ledger.Transaction;
import payouts.risk.MerchantHistory;
import payouts.risk.RiskDecision;
import payouts.risk.RiskEngine;
import payouts.risk.RiskOutcome;

/**
 * Creates and settles payouts, recording every money movement in the ledger.
 *
 * <p>Money flow:
 * <pre>
 *   request:  merchant balance ──► payouts_in_flight        (reserve, Decision 5)
 *   paid:     payouts_in_flight ──► platform_cash leaves     (money sent to the bank)
 *   failed:   payouts_in_flight ──► merchant balance         (reservation released)
 *   rejected: payouts_in_flight ──► merchant balance         (reviewer stopped it)
 * </pre>
 *
 * <p>Before anything is reserved, fraud rules decide whether the payout goes ahead,
 * waits for a person to review it, or is blocked (Decision 7).
 *
 * <p>Every public method holds one lock, so "check the balance, then reserve" can't
 * be interleaved with another request (Decision 6).
 */
public class PayoutService {

    private final Ledger ledger;
    private final PayoutStore store;
    private final Supplier<String> ids;
    private final Clock clock;
    private final RiskEngine risk;

    /** A service with no fraud rules: every payout that can be afforded goes ahead. */
    public PayoutService(Ledger ledger, PayoutStore store, Supplier<String> ids, Clock clock) {
        this(ledger, store, ids, clock, RiskEngine.allowAll());
    }

    public PayoutService(Ledger ledger, PayoutStore store, Supplier<String> ids, Clock clock, RiskEngine risk) {
        this.ledger = Objects.requireNonNull(ledger);
        this.store = Objects.requireNonNull(store);
        this.ids = Objects.requireNonNull(ids);
        this.clock = Objects.requireNonNull(clock);
        this.risk = Objects.requireNonNull(risk);
    }

    /**
     * Requests a payout. Sending the same idempotency key with the same request
     * returns the original payout instead of creating a second one, so a client
     * can safely retry after a timeout.
     *
     * <p>The returned payout is {@code PENDING} if it can be sent, {@code IN_REVIEW} if a
     * fraud rule held it, or {@code BLOCKED} if a rule stopped it; {@link Payout#riskReasons()}
     * says why. A blocked payout is still saved, so a retry gets the same answer back.
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

        RiskDecision decision = risk.evaluate(request,
                new MerchantHistory(store.findByMerchant(request.merchantId()), clock.instant()));
        if (decision.outcome() == RiskOutcome.BLOCK) {
            Payout blocked = new Payout(ids.get(), idempotencyKey, request, PayoutStatus.BLOCKED, clock.instant(),
                    decision.reasons());
            store.save(blocked);
            return blocked;
        }

        PayoutStatus status = decision.outcome() == RiskOutcome.REVIEW ? PayoutStatus.IN_REVIEW : PayoutStatus.PENDING;
        Payout payout = new Payout(ids.get(), idempotencyKey, request, status, clock.instant(), decision.reasons());
        ledger.post(new Transaction("reserve:" + payout.id(), "reserve funds for payout " + payout.id(), List.of(
                Entry.debit(merchantAccount, request.amountCents()),
                Entry.credit(Accounts.PAYOUTS_IN_FLIGHT, request.amountCents())), clock.instant()));
        store.save(payout);
        return payout;
    }

    /** A reviewer decided a held payout is fine. It can now be sent like any other. */
    public synchronized Payout approve(String payoutId) {
        Payout approved = inStatus(payoutId, PayoutStatus.IN_REVIEW).withStatus(PayoutStatus.PENDING);
        store.save(approved);
        return approved;
    }

    /** A reviewer decided a held payout is fraud. The reserved money goes back to the merchant. */
    public synchronized Payout reject(String payoutId) {
        Payout payout = inStatus(payoutId, PayoutStatus.IN_REVIEW);
        release(payout, "reject:" + payoutId, "payout " + payoutId + " rejected in review");
        Payout rejected = payout.withStatus(PayoutStatus.BLOCKED);
        store.save(rejected);
        return rejected;
    }

    /** The bank confirmed the payout. */
    public synchronized Payout markPaid(String payoutId) {
        Payout payout = inStatus(payoutId, PayoutStatus.PENDING);
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
        Payout payout = inStatus(payoutId, PayoutStatus.PENDING);
        release(payout, "release:" + payoutId, "payout " + payoutId + " failed");
        Payout failed = payout.withStatus(PayoutStatus.FAILED);
        store.save(failed);
        return failed;
    }

    public synchronized Payout get(String payoutId) {
        return store.findById(payoutId)
                .orElseThrow(() -> new IllegalArgumentException("no payout with id " + payoutId));
    }

    private Payout inStatus(String payoutId, PayoutStatus expected) {
        Payout payout = get(payoutId);
        if (payout.status() != expected) {
            throw new IllegalStateException("payout " + payoutId + " is " + payout.status() + ", not " + expected);
        }
        return payout;
    }

    /** Moves a payout's reserved money back to the merchant. */
    private void release(Payout payout, String transactionId, String description) {
        long amount = payout.request().amountCents();
        ledger.post(new Transaction(transactionId, description, List.of(
                Entry.debit(Accounts.PAYOUTS_IN_FLIGHT, amount),
                Entry.credit(Accounts.merchant(payout.request().merchantId()), amount)), clock.instant()));
    }
}

# safe-payouts

A small payouts service in Java, built to learn how payment systems keep money
correct. It's modeled on problems Stripe works on: a double-entry ledger, payouts
that can't be sent twice, and fraud rules that hold suspicious payouts for review.

## Status

| Step | What | Status |
|---|---|---|
| 1 | Double-entry ledger | ✅ done |
| 2 | Payouts with idempotency keys | ✅ done |
| 3 | Fraud rules that hold risky payouts | ⏳ next |
| 4 | HTTP API and CI | planned |

## Step 1: the ledger

Every movement of money is a **transaction** made of **entries**. Each entry
debits or credits one account. A transaction is only accepted if its debits equal
its credits, so money can be moved between accounts but never created or lost.

```java
Ledger ledger = new Ledger(new InMemoryLedgerStore());

// A customer pays merchant_a $100.00 and the platform keeps a $3.00 fee.
ledger.post(new Transaction("tx_1", "payment", List.of(
        Entry.debit("platform_cash", 10_000),
        Entry.credit("merchant_a", 9_700),
        Entry.credit("platform_fees", 300)), Instant.now()));

ledger.balanceCents("merchant_a"); // 9700
```

### How it's tested

- Unbalanced transactions, single-entry transactions, and zero or negative amounts
  are rejected.
- The same transaction can't be posted twice.
- Totals that would overflow a `long` throw instead of wrapping around.
- **Invariant test:** 1,000 random valid transactions are posted, and after every
  one the balances of all accounts must still add up to exactly zero.

## Step 2: payouts

A merchant requests a payout with an **idempotency key**. The money is reserved
right away, then either sent or returned:

```
request:  merchant balance  ──► payouts_in_flight
paid:     payouts_in_flight ──► leaves the platform
failed:   payouts_in_flight ──► back to the merchant
```

```java
Payout p = payouts.createPayout("key_123", new PayoutRequest("acme", 2_500, "bank_1"));
payouts.createPayout("key_123", new PayoutRequest("acme", 2_500, "bank_1")); // retry: same payout, charged once
payouts.createPayout("key_123", new PayoutRequest("acme", 9_000, "bank_1")); // IdempotencyConflictException
payouts.markPaid(p.id());
```

### How it's tested

- A retry with the same key returns the original payout and reserves the money once.
- The same key with a different amount or bank account is rejected.
- Payouts can't exceed the balance, and reserved money can't be spent twice.
- Failed payouts return the money; finished payouts can't change state again.
- **Concurrency tests:** 32 threads hit the service at the same instant, repeated 20 times.
  32 retries with one key must create exactly one payout, and 32 requests for $10
  against a $100 balance must produce exactly 10 payouts and never overdraw.
  Removing the lock makes 24 of these 40 runs fail, so the tests do catch the race.

## Design decisions

Every major choice, with the alternatives and tradeoffs, is written up in
[DESIGN.md](DESIGN.md).

## Run it

Needs Java 21.

```bash
./gradlew test
```

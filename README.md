# safe-payouts

A small payouts service in Java, built to learn how payment systems keep money
correct. It's modeled on problems Stripe works on: a double-entry ledger, payouts
that can't be sent twice, and fraud rules that hold suspicious payouts for review.

## Status

| Step | What | Status |
|---|---|---|
| 1 | Double-entry ledger | ✅ done |
| 2 | Payouts with idempotency keys | ⏳ next |
| 3 | Fraud rules that hold risky payouts | planned |
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

## Design decisions

Every major choice, with the alternatives and tradeoffs, is written up in
[DESIGN.md](DESIGN.md).

## Run it

Needs Java 21.

```bash
./gradlew test
```

# safe-payouts

[![test](https://github.com/dhilandonkey-png/safe-payouts/actions/workflows/test.yml/badge.svg)](https://github.com/dhilandonkey-png/safe-payouts/actions/workflows/test.yml)
![Java 21](https://img.shields.io/badge/java-21-blue)
![License: MIT](https://img.shields.io/badge/license-MIT-green)

A small payouts service in Java, built to learn how payment systems keep money
correct. It's modeled on problems Stripe works on: a double-entry ledger, payouts
that can't be sent twice, and fraud rules that hold suspicious payouts for review.

Built by [Dhilan Avula](https://dhilanavula.dev).

## Status

| Step | What | Status |
|---|---|---|
| 1 | Double-entry ledger | ✅ done |
| 2 | Payouts with idempotency keys | ✅ done |
| 3 | Fraud rules that hold risky payouts | ✅ done |
| 4 | HTTP API | ⏳ next |

Tests run on every push with GitHub Actions.

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

## Step 3: fraud rules

Before any money is reserved, a **risk engine** runs a list of rules against the
request and the merchant's past payouts. Each rule can let it through, **hold it for
review**, or **block** it. The strictest answer wins, and every reason is saved on the
payout so a reviewer can see why it was stopped.

| Rule | Catches | Outcome |
|---|---|---|
| `BlockedDestinationRule` | a bank account already known to be fraudulent | block |
| `NewDestinationRule` | a large first payout to a bank account never paid before (a typical account takeover) | review |
| `VelocityRule` | a sudden burst of payouts in a short window | review |
| `DailyLimitRule` | more money in 24 hours than the merchant's limit | review |

```
held:      reserved like any payout, but can't be sent yet
approve:   IN_REVIEW ──► PENDING  (then paid or failed as usual)
reject:    IN_REVIEW ──► BLOCKED  (reserved money goes back to the merchant)
blocked:   saved as BLOCKED, no money moves
```

```java
RiskEngine risk = new RiskEngine(List.of(
        new BlockedDestinationRule(Set.of("bank_stolen")),
        new NewDestinationRule(5_000),                       // first payout to a new account: max $50
        new VelocityRule(5, Duration.ofHours(1))));          // max 5 payouts an hour
PayoutService payouts = new PayoutService(ledger, store, ids, clock, risk);

Payout p = payouts.createPayout("key_9", new PayoutRequest("acme", 6_000, "bank_new"));
p.status();       // IN_REVIEW
p.riskReasons();  // [new_destination: first payout to bank_new is 6000 cents, above the 5000 cent limit]
payouts.reject(p.id());   // money back to acme
```

### How it's tested

- Every rule has tests at and just past its limit, and for what it should ignore:
  old payouts, blocked or failed payouts, and accounts that were already paid.
- A held payout reserves the money, can't be marked paid until approved, and
  returns the money if rejected.
- A blocked payout moves no money, and retrying it with the same key returns the
  same blocked payout instead of running the rules again.
- The ledger still adds up to zero after a mix of approvals, rejections and blocks.
- Breaking the code on purpose (ignoring review, forgetting to refund a rejection,
  not saving blocked payouts) makes the tests fail.

## Design decisions

Every major choice, with the alternatives and tradeoffs, is written up in
[DESIGN.md](DESIGN.md).

## Run it

Needs Java 21. Gradle downloads itself the first time.

```bash
git clone https://github.com/dhilandonkey-png/safe-payouts
cd safe-payouts
./gradlew run     # a demo: payouts, retries, conflicts, failures and fraud holds
./gradlew test    # all tests
```

The demo prints each step with balances, for example:

```
> The network times out, so acme retries with the same key
  got back po_1 (same payout, no second charge)
  acme balance: $75.00   in flight: $25.00

> A buggy client reuses "key_1" for $90.00
  rejected: idempotency key key_1 was already used with a different request
```

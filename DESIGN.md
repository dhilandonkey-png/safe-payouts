# Design decisions

Every choice that shaped this project, with the alternatives and why I picked
what I did. Newest at the bottom.

## 1. Money is stored as whole cents in a `long`

**Options:** `long` cents, `BigDecimal` dollars, `double` dollars.

**Chose:** `long` cents. $12.34 is stored as `1234`.

**Why:** Integer math is exact and fast, and it's how Stripe's own API represents
amounts. `double` can't represent most decimal fractions exactly (`0.1 + 0.2`
is not `0.3`), so cents would drift. `BigDecimal` is exact but clunkier, and you
have to manage rounding and scale everywhere.

**Tradeoffs I accepted:**
- Amounts have to be converted for display.
- Totals could overflow a `long`. All sums use `Math.addExact`, which throws instead
  of silently wrapping around to a negative number (there's a test for this).
- Version 1 assumes a single currency (USD). Supporting more means storing the
  currency next to every amount and never adding different currencies together.

## 2. Storage starts in memory, behind an interface

**Options:** in memory, SQLite, Postgres.

**Chose:** in memory first, behind a `LedgerStore` interface.

**Why:** It keeps the first versions about the rules (double entry, idempotency,
fraud holds) instead of database setup, and tests run in milliseconds. Because
the `Ledger` only talks to the interface, a database-backed store can be swapped
in later without touching the core logic.

**Tradeoff I accepted:** nothing survives a restart yet.

## 3. Balances are computed, never stored

**Chose:** an account's balance is calculated from its ledger entries every time
it's asked for.

**Why:** A stored balance can drift away from the history that produced it if any
update is missed. Computing it means the entries are the single source of truth.

**Tradeoff I accepted:** reading a balance gets slower as history grows. A real
system would cache balances or keep snapshots, and check them against the entries.

## 4. Reusing an idempotency key with a different request is an error

**Context:** a client sends an idempotency key with every payout request. If the
network times out, it retries with the same key, and gets the original payout back
instead of creating a second one.

**Question:** what if the same key arrives with a *different* amount or bank account?

**Options:** reject it, return the original payout anyway, or create a new payout.

**Chose:** reject it with an `IdempotencyConflictException`. Same key plus same
request still returns the original payout.

**Why:** a changed request under the same key means the caller has a bug. Returning
the original would hide it (they asked for $200 and silently got a $100 payout back),
and creating a new payout defeats the point of the key and could pay someone twice.
This matches how Stripe's API behaves.

**Also:** keys are scoped per merchant, so two merchants can use the same key
without colliding.

## 5. Funds are reserved the moment a payout is requested

**Options:** move the money out of the merchant's balance at request time, or only
when the bank confirms the payout.

**Chose:** reserve immediately. The amount moves from the merchant's balance into a
`payouts_in_flight` account. When the bank confirms, it leaves the platform. If the
payout fails, it moves back to the merchant.

**Why:** if the balance were only touched at the end, two payouts requested at the
same time could each see enough money and together overdraw the merchant. Reserving
also makes `payouts_in_flight` an exact count of money that's promised but not sent.

## 6. One lock around each payout operation

**Problem:** "check the balance, then reserve" is two steps. Two requests running at
the same time can both pass the check before either reserves, which overdraws the
merchant. The same race lets two retries with one idempotency key both create a payout.

**Chose:** every public method on `PayoutService` is `synchronized`, so those steps
run as one unit.

**How I know it matters:** `PayoutConcurrencyTest` fires 32 requests at once, 20 times
over. With the lock removed, 24 of its 40 runs failed: duplicate payouts and
overdrawn merchants. With the lock, all pass.

**Tradeoffs I accepted:** one lock for the whole service means payouts are processed
one at a time, even for unrelated merchants. That's fine at this scale. A real system
would lock per merchant, or rely on the database: a unique constraint on
`(merchant, idempotency key)` and a transaction around the balance check and the
reservation, so it works across multiple servers too.

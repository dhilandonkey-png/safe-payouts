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

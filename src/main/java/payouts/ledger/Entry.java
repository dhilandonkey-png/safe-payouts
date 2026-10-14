package payouts.ledger;

import java.util.Objects;

/**
 * One line of a transaction: move {@code amountCents} into or out of one account.
 * Amounts are whole cents in a long (Decision 1 in DESIGN.md), and always positive;
 * the direction says which way the money goes.
 */
public record Entry(String accountId, Direction direction, long amountCents) {

    public Entry {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(direction, "direction");
        if (accountId.isBlank()) {
            throw new IllegalArgumentException("accountId must not be blank");
        }
        if (amountCents <= 0) {
            throw new IllegalArgumentException("amountCents must be positive, got " + amountCents);
        }
    }

    public static Entry debit(String accountId, long amountCents) {
        return new Entry(accountId, Direction.DEBIT, amountCents);
    }

    public static Entry credit(String accountId, long amountCents) {
        return new Entry(accountId, Direction.CREDIT, amountCents);
    }
}

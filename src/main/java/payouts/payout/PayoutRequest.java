package payouts.payout;

import java.util.Objects;

/** What a merchant asks for: send this many cents to this bank account. */
public record PayoutRequest(String merchantId, long amountCents, String destinationBankAccountId) {

    public PayoutRequest {
        Objects.requireNonNull(merchantId, "merchantId");
        Objects.requireNonNull(destinationBankAccountId, "destinationBankAccountId");
        if (merchantId.isBlank() || destinationBankAccountId.isBlank()) {
            throw new IllegalArgumentException("merchantId and destinationBankAccountId must not be blank");
        }
        if (amountCents <= 0) {
            throw new IllegalArgumentException("amountCents must be positive, got " + amountCents);
        }
    }
}

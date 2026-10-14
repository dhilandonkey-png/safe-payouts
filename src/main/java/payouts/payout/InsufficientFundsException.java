package payouts.payout;

public class InsufficientFundsException extends RuntimeException {
    public InsufficientFundsException(String merchantId, long requestedCents, long availableCents) {
        super("merchant " + merchantId + " requested " + requestedCents + " cents but only " + availableCents
                + " are available");
    }
}

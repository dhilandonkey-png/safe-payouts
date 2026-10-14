package payouts.payout;

/** Ledger account names used by payouts. */
public final class Accounts {

    /** Money the platform holds in the bank. */
    public static final String PLATFORM_CASH = "platform_cash";

    /** Money reserved for payouts that have been requested but not settled yet. */
    public static final String PAYOUTS_IN_FLIGHT = "payouts_in_flight";

    private Accounts() {}

    /** The balance we owe a merchant. */
    public static String merchant(String merchantId) {
        return "merchant:" + merchantId;
    }
}

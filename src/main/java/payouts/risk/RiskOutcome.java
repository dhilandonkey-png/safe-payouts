package payouts.risk;

/** What the fraud rules decided, from least to most severe. */
public enum RiskOutcome {
    /** Nothing looks wrong. The payout goes ahead. */
    ALLOW,
    /** Looks unusual. Funds are reserved, but a person has to approve it before it's sent. */
    REVIEW,
    /** Too risky to send at all. No money moves. */
    BLOCK;

    RiskOutcome strictest(RiskOutcome other) {
        return compareTo(other) >= 0 ? this : other;
    }
}

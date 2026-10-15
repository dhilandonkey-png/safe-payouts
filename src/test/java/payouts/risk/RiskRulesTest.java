package payouts.risk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import payouts.payout.Payout;
import payouts.payout.PayoutRequest;
import payouts.payout.PayoutStatus;
import payouts.risk.rules.BlockedDestinationRule;
import payouts.risk.rules.DailyLimitRule;
import payouts.risk.rules.NewDestinationRule;
import payouts.risk.rules.VelocityRule;

class RiskRulesTest {

    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");

    private static PayoutRequest request(long cents, String bank) {
        return new PayoutRequest("acme", cents, bank);
    }

    private static Payout past(String id, long cents, String bank, PayoutStatus status, Duration ago) {
        return new Payout(id, "key_" + id, request(cents, bank), status, NOW.minus(ago), List.of());
    }

    private static MerchantHistory history(Payout... payouts) {
        return new MerchantHistory(List.of(payouts), NOW);
    }

    @Test
    void blockedDestinationBlocksOnlyListedAccounts() {
        var rule = new BlockedDestinationRule(Set.of("bank_stolen"));

        Optional<RiskFlag> flag = rule.check(request(100, "bank_stolen"), history());
        assertEquals(RiskOutcome.BLOCK, flag.orElseThrow().outcome());
        assertTrue(rule.check(request(100, "bank_1"), history()).isEmpty());
    }

    @Test
    void newDestinationHoldsALargeFirstPayout() {
        var rule = new NewDestinationRule(5_000);

        assertEquals(RiskOutcome.REVIEW, rule.check(request(5_001, "bank_new"), history()).orElseThrow().outcome());
    }

    @Test
    void newDestinationAllowsSmallFirstPayoutsAndExactlyTheLimit() {
        var rule = new NewDestinationRule(5_000);

        assertTrue(rule.check(request(100, "bank_new"), history()).isEmpty());
        assertTrue(rule.check(request(5_000, "bank_new"), history()).isEmpty());
    }

    @Test
    void newDestinationTrustsAnAccountOnlyAfterAPayoutToItWasPaid() {
        var rule = new NewDestinationRule(5_000);

        assertTrue(rule.check(request(9_000, "bank_1"),
                history(past("a", 100, "bank_1", PayoutStatus.PAID, Duration.ofDays(3)))).isEmpty());
        // A payout that's still pending, or that failed, doesn't prove the account is real.
        assertTrue(rule.check(request(9_000, "bank_1"),
                history(past("a", 100, "bank_1", PayoutStatus.PENDING, Duration.ofDays(3)))).isPresent());
        assertTrue(rule.check(request(9_000, "bank_1"),
                history(past("a", 100, "bank_1", PayoutStatus.FAILED, Duration.ofDays(3)))).isPresent());
    }

    @Test
    void velocityHoldsThePayoutAfterTheLimit() {
        var rule = new VelocityRule(3, Duration.ofHours(1));
        var two = history(past("a", 100, "bank_1", PayoutStatus.PENDING, Duration.ofMinutes(10)),
                past("b", 100, "bank_1", PayoutStatus.PAID, Duration.ofMinutes(20)));
        var three = history(past("a", 100, "bank_1", PayoutStatus.PENDING, Duration.ofMinutes(10)),
                past("b", 100, "bank_1", PayoutStatus.PAID, Duration.ofMinutes(20)),
                past("c", 100, "bank_1", PayoutStatus.IN_REVIEW, Duration.ofMinutes(30)));

        assertTrue(rule.check(request(100, "bank_1"), two).isEmpty());
        assertEquals(RiskOutcome.REVIEW, rule.check(request(100, "bank_1"), three).orElseThrow().outcome());
    }

    @Test
    void velocityIgnoresOldBlockedAndFailedPayouts() {
        var rule = new VelocityRule(2, Duration.ofHours(1));
        var history = history(past("old", 100, "bank_1", PayoutStatus.PAID, Duration.ofHours(2)),
                past("blocked", 100, "bank_1", PayoutStatus.BLOCKED, Duration.ofMinutes(5)),
                past("failed", 100, "bank_1", PayoutStatus.FAILED, Duration.ofMinutes(5)),
                past("ok", 100, "bank_1", PayoutStatus.PAID, Duration.ofMinutes(5)));

        assertTrue(rule.check(request(100, "bank_1"), history).isEmpty());
    }

    @Test
    void dailyLimitCountsTheLast24HoursPlusThisRequest() {
        var rule = new DailyLimitRule(10_000);
        var history = history(past("a", 6_000, "bank_1", PayoutStatus.PAID, Duration.ofHours(23)),
                past("yesterday", 9_000, "bank_1", PayoutStatus.PAID, Duration.ofHours(25)));

        assertTrue(rule.check(request(4_000, "bank_1"), history).isEmpty());
        assertEquals(RiskOutcome.REVIEW, rule.check(request(4_001, "bank_1"), history).orElseThrow().outcome());
    }

    @Test
    void engineKeepsTheStrictestOutcomeAndEveryReason() {
        var engine = new RiskEngine(List.of(new NewDestinationRule(1_000),
                new BlockedDestinationRule(Set.of("bank_stolen")), new DailyLimitRule(500)));

        RiskDecision decision = engine.evaluate(request(2_000, "bank_stolen"), history());

        assertEquals(RiskOutcome.BLOCK, decision.outcome());
        assertEquals(3, decision.reasons().size());
        assertTrue(decision.reasons().get(1).startsWith("blocked_destination: "));
    }

    @Test
    void engineWithNoFlagsAllows() {
        var engine = new RiskEngine(List.of(new NewDestinationRule(1_000)));

        RiskDecision decision = engine.evaluate(request(500, "bank_1"), history());
        assertEquals(RiskOutcome.ALLOW, decision.outcome());
        assertTrue(decision.reasons().isEmpty());
    }

    @Test
    void rulesRejectNonsenseLimits() {
        assertThrows(IllegalArgumentException.class, () -> new VelocityRule(0, Duration.ofHours(1)));
        assertThrows(IllegalArgumentException.class, () -> new DailyLimitRule(0));
        assertThrows(IllegalArgumentException.class, () -> new NewDestinationRule(-1));
        assertThrows(IllegalArgumentException.class, () -> new RiskFlag("x", RiskOutcome.ALLOW, "fine"));
    }
}

package io.github.borey.recorderautotranscribe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RetryPolicyTest {
    @Test public void shortTextIsTerminalAndNeverGetsAnotherAutomaticAttempt() {
        RetryPolicy policy = new RetryPolicy(3, 1_000L, 8_000L);
        RetryPolicy.State started = policy.beginAttempt(RetryPolicy.State.initial(), 100L);
        RetryPolicy.State failed = policy.afterFailure(started, RetryPolicy.classify(0, 100013, null), 100L);

        assertEquals(RetryPolicy.ErrorKind.SHORT_TEXT, failed.lastError());
        assertTrue(failed.isTerminal());
        assertEquals(RetryPolicy.Decision.TERMINAL, policy.decision(failed, 100L));
        assertEquals(failed, policy.beginAttempt(failed, 10_000L));
    }

    @Test public void temporaryFailuresUseShortBackoffThenOneAttemptPerDay() {
        RetryPolicy policy = new RetryPolicy(3, 1_000L, 8_000L);
        RetryPolicy.State state = RetryPolicy.State.initial();

        state = policy.beginAttempt(state, 100L);
        assertEquals(1, state.attemptCount());
        state = policy.afterFailure(state, RetryPolicy.ErrorKind.NO_NETWORK, 100L);
        assertEquals(1_100L, state.nextEligibleAtMillis());
        assertEquals(RetryPolicy.Decision.BACKING_OFF, policy.decision(state, 1_099L));
        assertEquals(RetryPolicy.Decision.READY, policy.decision(state, 1_100L));

        state = policy.beginAttempt(state, 1_100L);
        assertEquals(2, state.attemptCount());
        state = policy.afterFailure(state, RetryPolicy.ErrorKind.TIMEOUT, 1_100L);
        assertEquals(3_100L, state.nextEligibleAtMillis());
        assertEquals(RetryPolicy.Decision.READY, policy.decision(state, 3_100L));

        state = policy.beginAttempt(state, 3_100L);
        state = policy.afterFailure(state, RetryPolicy.ErrorKind.UNKNOWN_TEMPORARY, 3_100L);
        assertEquals(3, state.attemptCount());
        long nextDay = 3_100L + RetryPolicy.SLOW_RETRY_DELAY_MILLIS;
        assertEquals(nextDay, policy.nextEligibleAtMillis(state));
        assertEquals(RetryPolicy.Decision.BACKING_OFF, policy.decision(state, 20_000L));
        assertEquals(state, policy.beginAttempt(state, 20_000L));
        assertEquals(RetryPolicy.Decision.READY, policy.decision(state, nextDay));

        state = policy.beginAttempt(state, nextDay);
        assertEquals(4, state.attemptCount());
        state = policy.afterFailure(state, RetryPolicy.ErrorKind.UNKNOWN_TEMPORARY, nextDay);
        assertEquals(nextDay + RetryPolicy.SLOW_RETRY_DELAY_MILLIS, policy.nextEligibleAtMillis(state));
        assertEquals(RetryPolicy.Decision.BACKING_OFF, policy.decision(state, nextDay + 1L));
    }

    @Test public void persistedDailyDelaySurvivesRestartAndCannotBeBypassed() {
        RetryPolicy policy = new RetryPolicy(3, 1_000L, 8_000L);
        RetryPolicy.State state = RetryPolicy.State.initial();
        for (int attempt = 1; attempt <= 3; attempt++) {
            long now = policy.nextEligibleAtMillis(state);
            state = policy.afterFailure(policy.beginAttempt(state, now), RetryPolicy.ErrorKind.NO_NETWORK, now);
        }

        RetryPolicy.State restored = RetryPolicy.State.fromPreferenceValue(state.toPreferenceValue());
        assertEquals(state, restored);
        long due = policy.nextEligibleAtMillis(restored);
        assertEquals(RetryPolicy.Decision.BACKING_OFF, new RetryPolicy().decision(restored, due - 1L));
        assertEquals(restored, new RetryPolicy().beginAttempt(restored, due - 1L));
        assertEquals(RetryPolicy.Decision.READY, new RetryPolicy().decision(restored, due));
    }

    @Test public void exhaustedV1StateWaitsAtLeastOneDayFromItsOldEligibility() {
        RetryPolicy policy = new RetryPolicy(3, 1_000L, 8_000L);
        RetryPolicy.State legacy = RetryPolicy.State.fromPreferenceValue("v1|3|10000|UNKNOWN_TEMPORARY|0");
        assertEquals("v1|3|10000|UNKNOWN_TEMPORARY|0", legacy.toPreferenceValue());
        assertEquals(10_000L + RetryPolicy.SLOW_RETRY_DELAY_MILLIS, policy.nextEligibleAtMillis(legacy));
        assertEquals(RetryPolicy.Decision.BACKING_OFF,
                policy.decision(legacy, 10_000L + RetryPolicy.SLOW_RETRY_DELAY_MILLIS - 1L));
        assertEquals(RetryPolicy.Decision.READY,
                policy.decision(legacy, 10_000L + RetryPolicy.SLOW_RETRY_DELAY_MILLIS));
        assertEquals(4, policy.beginAttempt(legacy,
                10_000L + RetryPolicy.SLOW_RETRY_DELAY_MILLIS).attemptCount());
        assertTrue(policy.beginAttempt(legacy,
                10_000L + RetryPolicy.SLOW_RETRY_DELAY_MILLIS).toPreferenceValue().startsWith("v2|"));

        long afterUpgrade = 10_000L + 2 * RetryPolicy.SLOW_RETRY_DELAY_MILLIS;
        RetryPolicy.State submitted = policy.beginAttempt(legacy, afterUpgrade);
        RetryPolicy.State failed = policy.afterFailure(submitted,
                RetryPolicy.ErrorKind.UNKNOWN_TEMPORARY, afterUpgrade);
        RetryPolicy.State afterRestart = RetryPolicy.State.fromPreferenceValue(failed.toPreferenceValue());
        assertEquals(RetryPolicy.Decision.BACKING_OFF,
                policy.decision(afterRestart, afterUpgrade + RetryPolicy.SLOW_RETRY_DELAY_MILLIS - 1L));
        assertEquals(afterRestart,
                policy.beginAttempt(afterRestart, afterUpgrade + RetryPolicy.SLOW_RETRY_DELAY_MILLIS - 1L));
    }

    @Test public void v1StatesBelowLimitRetainTheirOriginalShortDueTime() {
        RetryPolicy policy = new RetryPolicy(3, 1_000L, 8_000L);
        RetryPolicy.State legacy = RetryPolicy.State.fromPreferenceValue("v1|2|10000|TIMEOUT|0");
        assertEquals(10_000L, policy.nextEligibleAtMillis(legacy));
        assertEquals(RetryPolicy.Decision.READY, policy.decision(legacy, 10_000L));

        RetryPolicy fourQuickAttempts = new RetryPolicy(4, 1_000L, 8_000L);
        RetryPolicy.State third = RetryPolicy.State.fromPreferenceValue("v1|3|10000|TIMEOUT|0");
        assertEquals(10_000L, fourQuickAttempts.nextEligibleAtMillis(third));
    }

    @Test public void abandonedV1InFlightAtTheLimitGetsADurableDailyDelay() {
        RetryPolicy policy = new RetryPolicy(3, 1_000L, 8_000L);
        RetryPolicy.State inFlight = RetryPolicy.State.fromPreferenceValue("v1|3|0|NONE|1");
        assertEquals(RetryPolicy.Decision.IN_FLIGHT, policy.decision(inFlight, 1_000L));
        RetryPolicy.State abandoned = policy.abandonInFlight(inFlight, 1_000L);
        assertEquals("v2|3|86401000|UNKNOWN_TEMPORARY|0", abandoned.toPreferenceValue());
        assertEquals(RetryPolicy.Decision.BACKING_OFF,
                policy.decision(RetryPolicy.State.fromPreferenceValue(abandoned.toPreferenceValue()), 2_000L));
    }

    @Test public void terminalFailureAtOrAfterLimitNeverRetries() {
        RetryPolicy policy = new RetryPolicy(3, 1_000L, 8_000L);
        RetryPolicy.State state = RetryPolicy.State.fromPreferenceValue("v1|3|10000|UNKNOWN_TEMPORARY|0");
        state = policy.afterFailure(state, RetryPolicy.ErrorKind.FILE_TOO_LARGE, 20_000L);
        assertEquals(RetryPolicy.Decision.TERMINAL, policy.decision(state, Long.MAX_VALUE));
        assertEquals(state, policy.beginAttempt(state, Long.MAX_VALUE));
    }

    @Test public void dailyRetryTimeAndAttemptCountSaturateWithoutOverflow() {
        RetryPolicy policy = new RetryPolicy(3, 1_000L, 8_000L);
        RetryPolicy.State state = RetryPolicy.State.fromPreferenceValue(
                "v2|2147483647|0|UNKNOWN_TEMPORARY|0");
        state = policy.beginAttempt(state, Long.MAX_VALUE - 1L);
        assertEquals(Integer.MAX_VALUE, state.attemptCount());
        state = policy.afterFailure(state, RetryPolicy.ErrorKind.TIMEOUT, Long.MAX_VALUE - 1L);
        assertEquals(Long.MAX_VALUE, state.nextEligibleAtMillis());
        assertEquals(RetryPolicy.Decision.BACKING_OFF, policy.decision(state, Long.MAX_VALUE - 1L));

        RetryPolicy.State legacy = RetryPolicy.State.fromPreferenceValue(
                "v1|2147483647|9223372036854775807|UNKNOWN_TEMPORARY|0");
        assertEquals(Long.MAX_VALUE, policy.nextEligibleAtMillis(legacy));
    }

    @Test public void knownPermanentAndTemporaryCodesStayDistinct() {
        assertEquals(RetryPolicy.ErrorKind.SHORT_TEXT, RetryPolicy.classify(100013, 0, ""));
        assertEquals(RetryPolicy.ErrorKind.UNSUPPORTED_FORMAT, RetryPolicy.classify(5001, 0, ""));
        assertEquals(RetryPolicy.ErrorKind.UNSUPPORTED_DURATION, RetryPolicy.classify(5002, 0, ""));
        assertEquals(RetryPolicy.ErrorKind.FILE_TOO_LARGE, RetryPolicy.classify(5003, 0, ""));
        assertEquals(RetryPolicy.ErrorKind.NO_NETWORK, RetryPolicy.classify(4001, 0, ""));
        assertEquals(RetryPolicy.ErrorKind.TIMEOUT, RetryPolicy.classify(0, 2005, ""));
        assertEquals(RetryPolicy.ErrorKind.SHORT_TEXT, RetryPolicy.classify(0, 0, "server result 100013"));
        assertFalse(RetryPolicy.ErrorKind.NO_NETWORK.isTerminal());
        assertTrue(RetryPolicy.ErrorKind.FILE_TOO_LARGE.isTerminal());
    }

    @Test public void summaryCodesFollowTheRecordersOwnRetryDecision() {
        assertEquals(RetryPolicy.ErrorKind.SHORT_TEXT,
                RetryPolicy.classifySummary("摘要服务失败（100013）"));
        assertEquals(RetryPolicy.ErrorKind.NO_NETWORK,
                RetryPolicy.classifySummary("摘要服务失败（100010）"));
        assertEquals(RetryPolicy.ErrorKind.UNKNOWN_TEMPORARY,
                RetryPolicy.classifySummary("摘要服务失败（300002）"));
        assertEquals(RetryPolicy.ErrorKind.SUMMARY_NOT_RETRYABLE,
                RetryPolicy.classifySummary("摘要服务失败（100001）"));
        assertEquals(RetryPolicy.ErrorKind.SUMMARY_NOT_RETRYABLE,
                RetryPolicy.classifySummary("摘要 SDK 失败（100021）"));
    }

    @Test public void nativeNoRetrySummaryCodesAreTerminalAcrossEveryStoredStateShape() {
        RetryPolicy policy = new RetryPolicy();
        RetryPolicy.State v1 = RetryPolicy.State.fromPreferenceValue(
                "v1|3|10000|UNKNOWN_TEMPORARY|0");
        RetryPolicy.State migratedFlag = policy.afterFailure(
                policy.beginAttempt(RetryPolicy.State.initial(), 100L),
                RetryPolicy.ErrorKind.UNKNOWN_TEMPORARY, 100L);
        assertEquals(RetryPolicy.ErrorKind.SUMMARY_NOT_RETRYABLE,
                policy.preserveSummaryRetryDecision(v1, "摘要服务失败（100001）", 20_000L).lastError());
        assertEquals(RetryPolicy.ErrorKind.SUMMARY_NOT_RETRYABLE,
                policy.preserveSummaryRetryDecision(migratedFlag, "摘要服务失败（100021）", 20_000L).lastError());
        assertEquals(RetryPolicy.ErrorKind.SUMMARY_NOT_RETRYABLE,
                policy.preserveSummaryRetryDecision(RetryPolicy.State.initial(), "摘要服务失败（100001）", 20_000L).lastError());
        assertEquals(RetryPolicy.Decision.TERMINAL,
                policy.decision(policy.preserveSummaryRetryDecision(RetryPolicy.State.initial(),
                        "摘要服务失败（100021）", 20_000L), Long.MAX_VALUE));
        assertEquals(RetryPolicy.Decision.BACKING_OFF,
                policy.decision(policy.preserveSummaryRetryDecision(migratedFlag,
                        "摘要服务失败（100010）", 20_000L), 20_001L));
    }

    @Test public void stateRoundTripsThroughAPlainPreferenceString() {
        RetryPolicy policy = new RetryPolicy(4, 2_000L, 8_000L);
        RetryPolicy.State state = policy.beginAttempt(RetryPolicy.State.initial(), 50L);
        state = policy.afterFailure(state, RetryPolicy.ErrorKind.NO_NETWORK, 50L);

        String saved = state.toPreferenceValue();
        RetryPolicy.State restored = RetryPolicy.State.fromPreferenceValue(saved);
        assertEquals(state, restored);
        assertEquals(state.hashCode(), restored.hashCode());
        assertEquals(RetryPolicy.State.initial(), RetryPolicy.State.fromPreferenceValue("bad-value"));
    }

    @Test public void beginningDuringBackoffDoesNotConsumeAnAttempt() {
        RetryPolicy policy = new RetryPolicy(3, 1_000L, 8_000L);
        RetryPolicy.State state = policy.beginAttempt(RetryPolicy.State.initial(), 0L);
        state = policy.afterFailure(state, RetryPolicy.ErrorKind.NO_NETWORK, 0L);

        assertEquals(state, policy.beginAttempt(state, 999L));
        assertEquals(1, state.attemptCount());
    }

    @Test public void defaultPolicyImplementsSmoothBackoffLadderAndTransitionsToDailyRetry() {
        RetryPolicy policy = new RetryPolicy();
        RetryPolicy.State state = RetryPolicy.State.initial();
        long now = 10_000L;

        // Attempt 1: 30s backoff
        state = policy.beginAttempt(state, now);
        assertEquals(1, state.attemptCount());
        state = policy.afterFailure(state, RetryPolicy.ErrorKind.NO_NETWORK, now);
        assertEquals(now + 30_000L, state.nextEligibleAtMillis());
        assertEquals(RetryPolicy.Decision.BACKING_OFF, policy.decision(state, now + 29_999L));
        assertEquals(RetryPolicy.Decision.READY, policy.decision(state, now + 30_000L));

        // Attempt 2: 1m backoff
        now = state.nextEligibleAtMillis();
        state = policy.beginAttempt(state, now);
        assertEquals(2, state.attemptCount());
        state = policy.afterFailure(state, RetryPolicy.ErrorKind.TIMEOUT, now);
        assertEquals(now + 60_000L, state.nextEligibleAtMillis());
        assertEquals(RetryPolicy.Decision.BACKING_OFF, policy.decision(state, now + 59_999L));
        assertEquals(RetryPolicy.Decision.READY, policy.decision(state, now + 60_000L));

        // Attempt 3: 5m backoff
        now = state.nextEligibleAtMillis();
        state = policy.beginAttempt(state, now);
        assertEquals(3, state.attemptCount());
        state = policy.afterFailure(state, RetryPolicy.ErrorKind.UNKNOWN_TEMPORARY, now);
        assertEquals(now + 5 * 60_000L, state.nextEligibleAtMillis());
        assertEquals(RetryPolicy.Decision.BACKING_OFF, policy.decision(state, now + 5 * 60_000L - 1L));
        assertEquals(RetryPolicy.Decision.READY, policy.decision(state, now + 5 * 60_000L));

        // Attempt 4: 15m backoff
        now = state.nextEligibleAtMillis();
        state = policy.beginAttempt(state, now);
        assertEquals(4, state.attemptCount());
        state = policy.afterFailure(state, RetryPolicy.ErrorKind.NO_NETWORK, now);
        assertEquals(now + 15 * 60_000L, state.nextEligibleAtMillis());
        assertEquals(RetryPolicy.Decision.BACKING_OFF, policy.decision(state, now + 15 * 60_000L - 1L));
        assertEquals(RetryPolicy.Decision.READY, policy.decision(state, now + 15 * 60_000L));

        // Attempt 5: 1h backoff
        now = state.nextEligibleAtMillis();
        state = policy.beginAttempt(state, now);
        assertEquals(5, state.attemptCount());
        state = policy.afterFailure(state, RetryPolicy.ErrorKind.TIMEOUT, now);
        assertEquals(now + 60 * 60_000L, state.nextEligibleAtMillis());
        assertEquals(RetryPolicy.Decision.BACKING_OFF, policy.decision(state, now + 60 * 60_000L - 1L));
        assertEquals(RetryPolicy.Decision.READY, policy.decision(state, now + 60 * 60_000L));

        // Attempt 6: enters 24h slow retry
        now = state.nextEligibleAtMillis();
        state = policy.beginAttempt(state, now);
        assertEquals(6, state.attemptCount());
        state = policy.afterFailure(state, RetryPolicy.ErrorKind.UNKNOWN_TEMPORARY, now);
        long nextDay = now + RetryPolicy.SLOW_RETRY_DELAY_MILLIS;
        assertEquals(nextDay, state.nextEligibleAtMillis());
        assertEquals(RetryPolicy.Decision.BACKING_OFF, policy.decision(state, now + 12 * 60 * 60_000L));
        assertEquals(RetryPolicy.Decision.READY, policy.decision(state, nextDay));

        // Subsequent failure keeps daily slow retry
        now = nextDay;
        state = policy.beginAttempt(state, now);
        assertEquals(7, state.attemptCount());
        state = policy.afterFailure(state, RetryPolicy.ErrorKind.NO_NETWORK, now);
        assertEquals(now + RetryPolicy.SLOW_RETRY_DELAY_MILLIS, state.nextEligibleAtMillis());
    }

    @Test public void computeDelayMatchesLadderExactDelays() {
        RetryPolicy policy = new RetryPolicy();
        assertEquals(30_000L, policy.computeDelay(1));
        assertEquals(60_000L, policy.computeDelay(2));
        assertEquals(300_000L, policy.computeDelay(3));
        assertEquals(900_000L, policy.computeDelay(4));
        assertEquals(3_600_000L, policy.computeDelay(5));
        assertEquals(RetryPolicy.SLOW_RETRY_DELAY_MILLIS, policy.computeDelay(6));
        assertEquals(RetryPolicy.SLOW_RETRY_DELAY_MILLIS, policy.computeDelay(100));
    }

    @Test public void terminalErrorsBypassSmoothLadderImmediately() {
        RetryPolicy policy = new RetryPolicy();
        RetryPolicy.ErrorKind[] terminalKinds = {
            RetryPolicy.ErrorKind.SHORT_TEXT,
            RetryPolicy.ErrorKind.UNSUPPORTED_FORMAT,
            RetryPolicy.ErrorKind.UNSUPPORTED_DURATION,
            RetryPolicy.ErrorKind.FILE_TOO_LARGE,
            RetryPolicy.ErrorKind.SUMMARY_NOT_RETRYABLE
        };

        for (RetryPolicy.ErrorKind kind : terminalKinds) {
            RetryPolicy.State started = policy.beginAttempt(RetryPolicy.State.initial(), 1_000L);
            RetryPolicy.State failed = policy.afterFailure(started, kind, 1_000L);
            assertTrue("Expected " + kind + " to be terminal", failed.isTerminal());
            assertEquals(0L, failed.nextEligibleAtMillis());
            assertEquals(RetryPolicy.Decision.TERMINAL, policy.decision(failed, 1_000L));
            assertEquals(RetryPolicy.Decision.TERMINAL, policy.decision(failed, Long.MAX_VALUE));
            assertEquals(failed, policy.beginAttempt(failed, 10_000L));
        }
    }

    @Test public void temporaryErrorsSurvivePersistedStateRoundTripAcrossAllLadderStages() {
        RetryPolicy policy = new RetryPolicy();
        RetryPolicy.State state = RetryPolicy.State.initial();
        long now = 500L;

        for (int i = 1; i <= 6; i++) {
            now = state.nextEligibleAtMillis() > 0 ? state.nextEligibleAtMillis() : now;
            state = policy.afterFailure(policy.beginAttempt(state, now), RetryPolicy.ErrorKind.TIMEOUT, now);

            String saved = state.toPreferenceValue();
            assertTrue(saved.startsWith("v2|"));
            RetryPolicy.State restored = RetryPolicy.State.fromPreferenceValue(saved);
            assertEquals(state, restored);
            assertEquals(state.hashCode(), restored.hashCode());
            assertEquals(policy.decision(state, now), policy.decision(restored, now));
        }
    }

    @Test public void abandonInFlightUsesSmoothBackoffLadder() {
        RetryPolicy policy = new RetryPolicy();
        // Step 1: Attempt 1
        RetryPolicy.State s = policy.beginAttempt(RetryPolicy.State.initial(), 1_000L);
        s = policy.afterFailure(s, RetryPolicy.ErrorKind.NO_NETWORK, 1_000L);

        // Step 2: Attempt 2
        long now = s.nextEligibleAtMillis();
        s = policy.beginAttempt(s, now);
        s = policy.afterFailure(s, RetryPolicy.ErrorKind.NO_NETWORK, now);

        // Step 3: Attempt 3 in-flight
        now = s.nextEligibleAtMillis();
        s = policy.beginAttempt(s, now);
        assertTrue(s.inFlight());
        assertEquals(3, s.attemptCount());

        // Abandon in-flight at step 3: should receive 3rd ladder delay (5m)
        RetryPolicy.State abandoned = policy.abandonInFlight(s, now);
        assertFalse(abandoned.inFlight());
        assertEquals(RetryPolicy.ErrorKind.UNKNOWN_TEMPORARY, abandoned.lastError());
        assertEquals(now + 5 * 60_000L, abandoned.nextEligibleAtMillis());
    }
}

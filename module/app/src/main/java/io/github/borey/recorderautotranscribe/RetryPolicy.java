package io.github.borey.recorderautotranscribe;

import java.util.Locale;

/**
 * Pure retry state machine for one recorder or summary task.
 *
 * Persist {@link State#toPreferenceValue()} under a task-specific preference key. Call
 * {@link #beginAttempt(State, long)} only after {@link #decision(State, long)} returns READY,
 * then persist the returned in-flight state before invoking the recorder. Persist
 * {@link #afterFailure(State, ErrorKind, long)} for an immediate rejection or a confirmed
 * failure callback, and reset with {@link #onSuccess()} after a confirmed native completion.
 * After process recreation, check the native task first; only call {@link #abandonInFlight(State,
 * long)} when that task is no longer active.
 */
final class RetryPolicy {
    static final int DEFAULT_MAX_ATTEMPTS = 5;
    static final long DEFAULT_INITIAL_BACKOFF_MILLIS = 30_000L;
    static final long DEFAULT_MAX_BACKOFF_MILLIS = 60 * 60_000L;
    static final long SLOW_RETRY_DELAY_MILLIS = 24 * 60 * 60_000L;

    static final long[] BACKOFF_LADDER_MILLIS = new long[] {
        30_000L,        // 第 1 次失败后等待 30 秒
        60_000L,        // 第 2 次失败后等待 1 分钟
        5 * 60_000L,    // 第 3 次失败后等待 5 分钟
        15 * 60_000L,   // 第 4 次失败后等待 15 分钟
        60 * 60_000L    // 第 5 次失败后等待 1 小时
    };

    enum Decision { READY, BACKING_OFF, IN_FLIGHT, TERMINAL }

    enum ErrorKind {
        NONE(false),
        SHORT_TEXT(true),
        UNSUPPORTED_FORMAT(true),
        UNSUPPORTED_DURATION(true),
        FILE_TOO_LARGE(true),
        SUMMARY_NOT_RETRYABLE(true),
        NO_NETWORK(false),
        TIMEOUT(false),
        UNKNOWN_TEMPORARY(false);

        private final boolean terminal;

        ErrorKind(boolean terminal) { this.terminal = terminal; }

        boolean isTerminal() { return terminal; }
    }

    /** Attempts before temporary failures move from short backoff to daily retries. */
    private final int maxAttempts;
    private final long initialBackoffMillis;
    private final long maxBackoffMillis;
    private final boolean customLadder;

    RetryPolicy() {
        this(DEFAULT_MAX_ATTEMPTS, DEFAULT_INITIAL_BACKOFF_MILLIS, DEFAULT_MAX_BACKOFF_MILLIS, false);
    }

    RetryPolicy(int maxAttempts, long initialBackoffMillis, long maxBackoffMillis) {
        this(maxAttempts, initialBackoffMillis, maxBackoffMillis, true);
    }

    private RetryPolicy(int maxAttempts, long initialBackoffMillis, long maxBackoffMillis, boolean customLadder) {
        if (maxAttempts < 1) throw new IllegalArgumentException("maxAttempts must be positive");
        if (initialBackoffMillis < 1) throw new IllegalArgumentException("initial backoff must be positive");
        if (maxBackoffMillis < initialBackoffMillis) {
            throw new IllegalArgumentException("maximum backoff must not be smaller than initial backoff");
        }
        this.maxAttempts = maxAttempts;
        this.initialBackoffMillis = initialBackoffMillis;
        this.maxBackoffMillis = maxBackoffMillis;
        this.customLadder = customLadder;
    }

    Decision decision(State current, long nowMillis) {
        State state = current == null ? State.initial() : current;
        if (state.isTerminal()) return Decision.TERMINAL;
        if (state.inFlight()) return Decision.IN_FLIGHT;
        return nowMillis < nextEligibleAtMillis(state) ? Decision.BACKING_OFF : Decision.READY;
    }

    /** The effective due time, including the one-time cooldown for exhausted v1 states. */
    long nextEligibleAtMillis(State current) {
        State state = current == null ? State.initial() : current;
        if (state.legacyFormat && !state.inFlight() && !state.isTerminal()
                && state.lastError() != ErrorKind.NONE && state.attemptCount() >= maxAttempts) {
            // v1 stored a short due time even though the state was EXHAUSTED. Waiting one
            // additional day from that saved time is conservative and stable across restarts.
            return saturatingAdd(state.nextEligibleAtMillis(), SLOW_RETRY_DELAY_MILLIS);
        }
        return state.nextEligibleAtMillis();
    }

    /** Returns an unchanged state unless it is currently safe to start one native attempt. */
    State beginAttempt(State current, long nowMillis) {
        State state = current == null ? State.initial() : current;
        if (decision(state, nowMillis) != Decision.READY) return state;
        return new State(state.attemptCount() == Integer.MAX_VALUE
                ? Integer.MAX_VALUE : state.attemptCount() + 1, 0L, ErrorKind.NONE, true);
    }

    /** Records the result of an attempt previously started by {@link #beginAttempt(State, long)}. */
    State afterFailure(State current, ErrorKind error, long nowMillis) {
        State state = current == null ? State.initial() : current;
        ErrorKind kind = error == null ? ErrorKind.UNKNOWN_TEMPORARY : error;
        int attempts = state.attemptCount();
        if (attempts == 0) attempts = 1;
        if (kind.isTerminal()) return new State(attempts, 0L, kind, false);
        long delay = computeDelay(attempts);
        return new State(attempts, saturatingAdd(nowMillis, delay), kind, false);
    }

    long computeDelay(int attempts) {
        if (!customLadder) {
            return attempts <= BACKOFF_LADDER_MILLIS.length
                    ? BACKOFF_LADDER_MILLIS[attempts - 1]
                    : SLOW_RETRY_DELAY_MILLIS;
        }
        return attempts >= maxAttempts ? SLOW_RETRY_DELAY_MILLIS : backoffMillis(attempts);
    }

    /** Converts a stale persisted in-flight attempt into one bounded retry after native probing. */
    State abandonInFlight(State current, long nowMillis) {
        State state = current == null ? State.initial() : current;
        return state.inFlight() ? afterFailure(state, ErrorKind.UNKNOWN_TEMPORARY, nowMillis) : state;
    }

    State onSuccess() { return State.initial(); }

    /** Preserve the recorder's explicit no-retry decision across every persisted format. */
    State preserveSummaryRetryDecision(State current, String detail, long nowMillis) {
        State state = current == null ? State.initial() : current;
        if (state.inFlight() || state.isTerminal()
                || classifySummary(detail) != ErrorKind.SUMMARY_NOT_RETRYABLE) return state;
        return afterFailure(state, ErrorKind.SUMMARY_NOT_RETRYABLE, nowMillis);
    }

    private long backoffMillis(int failedAttemptCount) {
        long delay = initialBackoffMillis;
        for (int attempt = 1; attempt < failedAttemptCount && delay < maxBackoffMillis; attempt++) {
            delay = delay > maxBackoffMillis / 2 ? maxBackoffMillis : delay * 2;
        }
        return Math.min(delay, maxBackoffMillis);
    }

    private static long saturatingAdd(long value, long increment) {
        if (value > Long.MAX_VALUE - increment) return Long.MAX_VALUE;
        return value + increment;
    }

    static ErrorKind classify(int uploadCode, int convertCode, String detail) {
        if (hasCode(uploadCode, convertCode, 100013) || contains(detail, "100013")) return ErrorKind.SHORT_TEXT;
        if (hasCode(uploadCode, convertCode, 5001)) return ErrorKind.UNSUPPORTED_FORMAT;
        if (hasCode(uploadCode, convertCode, 5002) || hasCode(uploadCode, convertCode, 5005)) {
            return ErrorKind.UNSUPPORTED_DURATION;
        }
        if (hasCode(uploadCode, convertCode, 5003)) return ErrorKind.FILE_TOO_LARGE;
        if (hasCode(uploadCode, convertCode, 4001) || contains(detail, "network") || contains(detail, "网络")) {
            return ErrorKind.NO_NETWORK;
        }
        if (hasCode(uploadCode, convertCode, 2005) || contains(detail, "timeout") || contains(detail, "超时")) {
            return ErrorKind.TIMEOUT;
        }
        return ErrorKind.UNKNOWN_TEMPORARY;
    }

    /** Match the recorder's own summary UI retry policy for known service responses. */
    static ErrorKind classifySummary(String detail) {
        if (contains(detail, "100013")) return ErrorKind.SHORT_TEXT;
        if (contains(detail, "100001") || contains(detail, "100021")) {
            return ErrorKind.SUMMARY_NOT_RETRYABLE;
        }
        if (contains(detail, "100010")) return ErrorKind.NO_NETWORK;
        return classify(0, 0, detail);
    }

    private static boolean hasCode(int uploadCode, int convertCode, int code) {
        return uploadCode == code || convertCode == code;
    }

    private static boolean contains(String detail, String expected) {
        return detail != null && detail.toLowerCase(Locale.ROOT).contains(expected.toLowerCase(Locale.ROOT));
    }

    static final class State {
        private static final String VERSION = "v2";
        private static final String LEGACY_VERSION = "v1";
        private final int attemptCount;
        private final long nextEligibleAtMillis;
        private final ErrorKind lastError;
        private final boolean inFlight;
        private final boolean legacyFormat;

        private State(int attemptCount, long nextEligibleAtMillis, ErrorKind lastError, boolean inFlight) {
            this(attemptCount, nextEligibleAtMillis, lastError, inFlight, false);
        }

        private State(int attemptCount, long nextEligibleAtMillis, ErrorKind lastError,
                      boolean inFlight, boolean legacyFormat) {
            this.attemptCount = Math.max(0, attemptCount);
            this.lastError = lastError == null ? ErrorKind.NONE : lastError;
            this.nextEligibleAtMillis = this.lastError.isTerminal() ? 0L : Math.max(0L, nextEligibleAtMillis);
            this.inFlight = !this.lastError.isTerminal() && inFlight;
            this.legacyFormat = legacyFormat;
        }

        static State initial() { return new State(0, 0L, ErrorKind.NONE, false); }

        int attemptCount() { return attemptCount; }
        long nextEligibleAtMillis() { return nextEligibleAtMillis; }
        ErrorKind lastError() { return lastError; }
        boolean inFlight() { return inFlight; }
        boolean isTerminal() { return lastError.isTerminal(); }

        String toPreferenceValue() {
            return (legacyFormat ? LEGACY_VERSION : VERSION) + "|" + attemptCount + "|"
                    + nextEligibleAtMillis + "|" + lastError.name() + "|" + (inFlight ? "1" : "0");
        }

        static State fromPreferenceValue(String value) {
            if (value == null) return initial();
            String[] fields = value.split("\\|", -1);
            if (fields.length != 5 || !(VERSION.equals(fields[0]) || LEGACY_VERSION.equals(fields[0]))) {
                return initial();
            }
            try {
                int attempts = Integer.parseInt(fields[1]);
                long next = Long.parseLong(fields[2]);
                ErrorKind error = ErrorKind.valueOf(fields[3]);
                if (attempts < 0 || next < 0 || !("0".equals(fields[4]) || "1".equals(fields[4]))) return initial();
                return new State(attempts, next, error, "1".equals(fields[4]),
                        LEGACY_VERSION.equals(fields[0]));
            } catch (IllegalArgumentException ignored) {
                return initial();
            }
        }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof State)) return false;
            State state = (State) other;
            return attemptCount == state.attemptCount && nextEligibleAtMillis == state.nextEligibleAtMillis
                    && lastError == state.lastError && inFlight == state.inFlight
                    && legacyFormat == state.legacyFormat;
        }

        @Override public int hashCode() {
            int result = attemptCount;
            result = 31 * result + (int) (nextEligibleAtMillis ^ (nextEligibleAtMillis >>> 32));
            result = 31 * result + lastError.hashCode();
            result = 31 * result + (inFlight ? 1 : 0);
            return 31 * result + (legacyFormat ? 1 : 0);
        }
    }
}

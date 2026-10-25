package io.github.borey.recorderautotranscribe;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** Coordinates scheduled-job tokens with bridge readiness and serialized scans. */
public final class ScheduledJobCoordinator<P> {
    private final LinkedHashSet<P> waitingTokens = new LinkedHashSet<>();
    private final LinkedHashSet<P> inFlightTokens = new LinkedHashSet<>();
    private boolean bridgeReady;
    private long nextScanSequence = 1L;
    private long activeScanSequence;

    /**
     * Records a job token until a later scan covers it. Repeated tokens waiting for, or already
     * covered by, the active scan are coalesced and return false. A token may be recorded again
     * after its earlier scan has completed.
     */
    public synchronized boolean onJobStart(P token) {
        Objects.requireNonNull(token, "token");
        if (waitingTokens.contains(token) || inFlightTokens.contains(token)) return false;
        waitingTokens.add(token);
        return true;
    }

    /** Removes a stopped job token from either the waiting set or the active scan's coverage. */
    public synchronized boolean onJobStop(P token) {
        Objects.requireNonNull(token, "token");
        boolean removedWaiting = waitingTokens.remove(token);
        boolean removedInFlight = inFlightTokens.remove(token);
        return removedWaiting || removedInFlight;
    }

    /** Returns whether any waiting or in-flight job tokens remain. */
    public synchronized boolean hasActiveJobs() {
        return !waitingTokens.isEmpty() || !inFlightTokens.isEmpty();
    }

    /** Returns the tokens covered by the currently running scan. */
    public synchronized List<P> inFlightTokens() {
        return new ArrayList<>(inFlightTokens);
    }

    /** Marks the bridge ready and reports whether any tokens are waiting for a scan. */
    public synchronized boolean onBridgeReady() {
        bridgeReady = true;
        return !waitingTokens.isEmpty();
    }

    /**
     * Starts one serialized scan and returns its positive sequence number. Returns zero when the
     * bridge is not ready, there is no queued token, or another scan is already active.
     */
    public synchronized long beginScan() {
        if (!bridgeReady || waitingTokens.isEmpty() || activeScanSequence != 0L) return 0L;

        activeScanSequence = nextScanSequence;
        nextScanSequence = nextScanSequence == Long.MAX_VALUE ? 1L : nextScanSequence + 1L;
        inFlightTokens.addAll(waitingTokens);
        waitingTokens.clear();
        return activeScanSequence;
    }

    /**
     * Completes the matching active scan once and returns its covered tokens in arrival order.
     * An unknown, stale, or duplicate sequence returns an empty list without changing state.
     */
    public synchronized List<P> completeScan(long sequence) {
        if (sequence == 0L || sequence != activeScanSequence) return new ArrayList<>();

        List<P> completed = new ArrayList<>(inFlightTokens);
        inFlightTokens.clear();
        activeScanSequence = 0L;
        return completed;
    }
}

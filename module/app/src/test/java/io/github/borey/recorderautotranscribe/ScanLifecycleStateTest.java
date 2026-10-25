package io.github.borey.recorderautotranscribe;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ScanLifecycleStateTest {
    @Test public void scheduleRegistrationAndBridgeReadinessDoNotClaimASuccessfulScan() {
        ScanLifecycleState state = new ScanLifecycleState();

        state.onScheduleVerified(100L);
        state.onBridgeReady(200L);

        assertEquals(100L, state.scheduleVerifiedAt());
        assertEquals(200L, state.bridgeReadyAt());
        assertEquals(0L, state.lastScanSuccessAt());
        assertEquals("idle", state.scanState());
    }

    @Test public void failedScanIsVisibleAndKeepsThePreviousSuccessTime() {
        ScanLifecycleState state = new ScanLifecycleState();
        state.onScanStarted(100L);
        state.onScanSucceeded(110L);

        state.onScanStarted(200L);
        state.onScanFailed("IllegalStateException: recorder database unavailable");

        assertEquals(200L, state.scanAttemptAt());
        assertEquals(110L, state.lastScanSuccessAt());
        assertEquals("failed", state.scanState());
        assertEquals("IllegalStateException: recorder database unavailable", state.lastScanError());
    }

    @Test public void pausedCycleIsNotReportedAsASuccessfulScan() {
        ScanLifecycleState state = new ScanLifecycleState();
        state.onScanStarted(100L);
        state.onScanPaused();

        assertEquals(0L, state.lastScanSuccessAt());
        assertEquals("paused", state.scanState());
    }

    @Test public void systemInterruptionKeepsTheLastSuccessfulReceiptVisible() {
        ScanLifecycleState state = new ScanLifecycleState();
        state.onScanStarted(100L);
        state.onScanSucceeded(110L);

        state.onScanStarted(200L);
        state.onScanInterrupted();

        assertEquals(110L, state.lastScanSuccessAt());
        assertEquals("interrupted", state.scanState());
        assertEquals("系统提前停止任务", state.lastScanError());
    }
}

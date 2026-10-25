package io.github.borey.recorderautotranscribe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

public class ScheduledJobCoordinatorTest {
    @Test public void coldJobWaitsForBridgeThenRunsOnce() {
        ScheduledJobCoordinator<String> coordinator = new ScheduledJobCoordinator<>();

        assertTrue(coordinator.onJobStart("cold"));
        assertTrue(coordinator.onBridgeReady());
        long scan = coordinator.beginScan();

        assertTrue(scan > 0L);
        assertEquals(Collections.singletonList("cold"), coordinator.completeScan(scan));
    }

    @Test public void repeatedCallbackWithSameTokenIsCoveredOnlyOnce() {
        ScheduledJobCoordinator<String> coordinator = new ScheduledJobCoordinator<>();

        assertTrue(coordinator.onJobStart("job-1"));
        assertFalse(coordinator.onJobStart("job-1"));
        assertTrue(coordinator.onBridgeReady());
        long scan = coordinator.beginScan();

        assertEquals(Collections.singletonList("job-1"), coordinator.completeScan(scan));
        assertTrue(coordinator.completeScan(scan).isEmpty());
    }

    @Test public void jobArrivingDuringScanWaitsForNextScan() {
        ScheduledJobCoordinator<String> coordinator = new ScheduledJobCoordinator<>();
        coordinator.onJobStart("first");
        coordinator.onBridgeReady();
        long firstScan = coordinator.beginScan();

        assertTrue(coordinator.onJobStart("second"));
        assertEquals(Collections.singletonList("first"), coordinator.completeScan(firstScan));
        long secondScan = coordinator.beginScan();
        assertTrue(secondScan > firstScan);
        assertEquals(Collections.singletonList("second"), coordinator.completeScan(secondScan));
    }

    @Test public void duplicateOfInFlightTokenDoesNotCreateAnotherScan() {
        ScheduledJobCoordinator<String> coordinator = new ScheduledJobCoordinator<>();
        coordinator.onJobStart("same");
        coordinator.onBridgeReady();
        long scan = coordinator.beginScan();

        assertFalse(coordinator.onJobStart("same"));
        assertEquals(Collections.singletonList("same"), coordinator.completeScan(scan));
        assertEquals(0L, coordinator.beginScan());
    }

    @Test public void parallelJobsBeforeScanAreMergedIntoOneScan() {
        ScheduledJobCoordinator<String> coordinator = new ScheduledJobCoordinator<>();
        coordinator.onJobStart("job-a");
        coordinator.onJobStart("job-b");
        coordinator.onBridgeReady();

        long scan = coordinator.beginScan();

        assertTrue(scan > 0L);
        assertEquals(Arrays.asList("job-a", "job-b"), coordinator.completeScan(scan));
        assertEquals(0L, coordinator.beginScan());
    }

    @Test public void wrongSequenceCannotCompleteOrConsumeActiveScan() {
        ScheduledJobCoordinator<String> coordinator = new ScheduledJobCoordinator<>();
        coordinator.onJobStart("job");
        coordinator.onBridgeReady();
        long scan = coordinator.beginScan();

        assertTrue(coordinator.completeScan(scan + 1L).isEmpty());
        assertEquals(Collections.singletonList("job"), coordinator.completeScan(scan));
    }

    @Test public void scanCannotBeginUntilBridgeIsReady() {
        ScheduledJobCoordinator<String> coordinator = new ScheduledJobCoordinator<>();
        coordinator.onJobStart("job");

        assertEquals(0L, coordinator.beginScan());
        assertTrue(coordinator.onBridgeReady());
        assertTrue(coordinator.beginScan() > 0L);
    }

    @Test public void stoppingWaitingTokenRemovesItBeforeScan() {
        ScheduledJobCoordinator<String> coordinator = new ScheduledJobCoordinator<>();
        coordinator.onJobStart("waiting");

        assertTrue(coordinator.onJobStop("waiting"));
        assertFalse(coordinator.onBridgeReady());
        assertEquals(0L, coordinator.beginScan());
    }

    @Test public void stoppingInFlightTokenDoesNotCompleteItAndNextScanCanRun() {
        ScheduledJobCoordinator<String> coordinator = new ScheduledJobCoordinator<>();
        coordinator.onJobStart("stopped");
        coordinator.onBridgeReady();
        long firstScan = coordinator.beginScan();

        assertTrue(coordinator.onJobStop("stopped"));
        assertFalse(coordinator.onJobStop("stopped"));
        assertTrue(coordinator.onJobStart("next"));
        assertTrue(coordinator.completeScan(firstScan).isEmpty());

        long nextScan = coordinator.beginScan();
        assertTrue(nextScan > firstScan);
        assertEquals(Collections.singletonList("next"), coordinator.completeScan(nextScan));
    }

    @Test public void activeJobsIncludesWaitingAndInFlightUntilLastTokenStops() {
        ScheduledJobCoordinator<String> coordinator = new ScheduledJobCoordinator<>();
        coordinator.onJobStart("in-flight");
        coordinator.onBridgeReady();
        coordinator.beginScan();
        coordinator.onJobStart("waiting");

        assertTrue(coordinator.hasActiveJobs());
        assertTrue(coordinator.onJobStop("waiting"));
        assertTrue(coordinator.hasActiveJobs());
        assertTrue(coordinator.onJobStop("in-flight"));
        assertFalse(coordinator.hasActiveJobs());
    }

    @Test public void receiptCoverageIncludesOnlyTokensPresentWhenScanBegan() {
        ScheduledJobCoordinator<String> coordinator = new ScheduledJobCoordinator<>();
        coordinator.onJobStart("covered");
        coordinator.onBridgeReady();
        coordinator.beginScan();
        coordinator.onJobStart("arrived-later");

        assertEquals(Collections.singletonList("covered"), coordinator.inFlightTokens());
        assertTrue(coordinator.onJobStop("covered"));
        assertTrue(coordinator.inFlightTokens().isEmpty());
        assertTrue(coordinator.completeScan(1L).isEmpty());
        long nextScan = coordinator.beginScan();
        assertTrue(nextScan > 1L);
        assertEquals(Collections.singletonList("arrived-later"), coordinator.completeScan(nextScan));
    }
}

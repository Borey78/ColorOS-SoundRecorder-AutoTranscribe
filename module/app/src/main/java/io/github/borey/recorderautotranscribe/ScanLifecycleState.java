package io.github.borey.recorderautotranscribe;

/** Process-independent lifecycle facts for scheduling, bridge readiness, and scan receipts. */
final class ScanLifecycleState {
    private long scheduleVerifiedAt;
    private long jobStartedAt;
    private long bridgeReadyAt;
    private long scanAttemptAt;
    private long lastScanSuccessAt;
    private long lastJobFinishedAt;
    private long lastJobStoppedAt;
    private String scanState = "idle";
    private String lastScanError = "";
    private String lastJobOutcome = "";

    synchronized void restore(long scheduleVerifiedAt, long jobStartedAt, long bridgeReadyAt,
                              long scanAttemptAt, long lastScanSuccessAt,
                              long lastJobFinishedAt, long lastJobStoppedAt,
                              String scanState, String lastScanError, String lastJobOutcome) {
        this.scheduleVerifiedAt = scheduleVerifiedAt;
        this.jobStartedAt = jobStartedAt;
        this.bridgeReadyAt = bridgeReadyAt;
        this.scanAttemptAt = scanAttemptAt;
        this.lastScanSuccessAt = lastScanSuccessAt;
        this.lastJobFinishedAt = lastJobFinishedAt;
        this.lastJobStoppedAt = lastJobStoppedAt;
        this.scanState = nonEmpty(scanState, "idle");
        this.lastScanError = nonEmpty(lastScanError, "");
        this.lastJobOutcome = nonEmpty(lastJobOutcome, "");
    }

    synchronized void onScheduleVerified(long at) { scheduleVerifiedAt = at; }
    synchronized void onJobStarted(long at) { jobStartedAt = at; lastJobOutcome = "running"; }
    synchronized void onBridgeReady(long at) { bridgeReadyAt = at; }
    synchronized void onScanStarted(long at) { scanAttemptAt = at; scanState = "running"; lastScanError = ""; }
    synchronized void onScanSucceeded(long at) { lastScanSuccessAt = at; scanState = "success"; lastScanError = ""; }
    synchronized void onScanPaused() { scanState = "paused"; lastScanError = ""; }
    synchronized void onScanInterrupted() { scanState = "interrupted"; lastScanError = "系统提前停止任务"; }
    synchronized void onScanFailed(String error) { scanState = "failed"; lastScanError = nonEmpty(error, "未知错误"); }
    synchronized void onJobFinished(long at, boolean wantsReschedule) {
        lastJobFinishedAt = at;
        lastJobOutcome = wantsReschedule ? "retry requested" : "finished";
    }
    synchronized void onJobStopped(long at) { lastJobStoppedAt = at; lastJobOutcome = "interrupted; reschedule requested"; }

    synchronized long scheduleVerifiedAt() { return scheduleVerifiedAt; }
    synchronized long jobStartedAt() { return jobStartedAt; }
    synchronized long bridgeReadyAt() { return bridgeReadyAt; }
    synchronized long scanAttemptAt() { return scanAttemptAt; }
    synchronized long lastScanSuccessAt() { return lastScanSuccessAt; }
    synchronized long lastJobFinishedAt() { return lastJobFinishedAt; }
    synchronized long lastJobStoppedAt() { return lastJobStoppedAt; }
    synchronized String scanState() { return scanState; }
    synchronized String lastScanError() { return lastScanError; }
    synchronized String lastJobOutcome() { return lastJobOutcome; }

    private static String nonEmpty(String value, String fallback) {
        return value == null ? fallback : value;
    }
}

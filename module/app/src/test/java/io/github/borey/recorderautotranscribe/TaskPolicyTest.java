package io.github.borey.recorderautotranscribe;
import org.junit.Test;
import static org.junit.Assert.*;

public class TaskPolicyTest {
    @Test public void summaryDefaultFlagIsNotMistakenForAnIncompleteResult() {
        assertTrue(TaskPolicy.summaryHasResult(100,-1));
        assertTrue(TaskPolicy.summaryHasResult(100,0));
        assertFalse(TaskPolicy.summaryHasResult(100,1));
        assertFalse(TaskPolicy.summaryHasResult(0,-1));
    }
    @Test public void shortTextSummarySkipIsTerminal() {
        assertTrue(TaskPolicy.summarySatisfied(true,false));
        assertTrue(TaskPolicy.summarySatisfied(false,true));
        assertFalse(TaskPolicy.summarySatisfied(false,false));
    }
    @Test public void doneSnapshotSatisfiedWhenCompleteAndSummarySatisfiedOrDisabled() {
        // Complete transcription and summary done
        assertTrue(TaskPolicy.isDoneSatisfied(true, true, false, true));
        // Complete transcription and short-text summary skipped (< 100 chars)
        assertTrue(TaskPolicy.isDoneSatisfied(true, false, true, true));
        // Complete transcription and AI summary disabled in settings
        assertTrue(TaskPolicy.isDoneSatisfied(true, false, false, false));
        // Complete transcription with AI summary enabled, but summary not yet done or skipped
        assertFalse(TaskPolicy.isDoneSatisfied(true, false, false, true));
        // Incomplete transcription: never satisfied
        assertFalse(TaskPolicy.isDoneSatisfied(false, true, false, true));
        assertFalse(TaskPolicy.isDoneSatisfied(false, false, true, true));
        assertFalse(TaskPolicy.isDoneSatisfied(false, false, false, false));
    }
    @Test public void interruptedTasksRecoverOnceWithoutInfiniteRestarts() {
        assertTrue(TaskPolicy.canRecover(false,false,false,1));
        assertFalse(TaskPolicy.canRecover(false,false,false,2));
        assertFalse(TaskPolicy.canRecover(false,false,true,1));
        assertFalse(TaskPolicy.canRecover(true,false,false,1));
        assertFalse(TaskPolicy.canRecover(false,true,false,1));
    }
    @Test public void neverExceedsThreeIncludingManualTasks() {
        assertTrue(TaskPolicy.canStart(true,true,false,false,false,2,3));
        assertFalse(TaskPolicy.canStart(true,true,false,false,false,3,3));
    }
    @Test public void reservedTimeoutSlotsAlsoCapTheSameScanAtThree() {
        int reserved = 0;
        for (int submission = 0; submission < 4; submission++) {
            boolean allowed = TaskPolicy.canStart(true, true, false, false, false, reserved, 3);
            if (submission < 3) assertTrue(allowed);
            else assertFalse(allowed);
            if (allowed) reserved++;
        }
        assertEquals(3, reserved);
    }
    @Test public void completedActiveFailedAndDisabledTasksAreNotResubmitted() {
        assertFalse(TaskPolicy.canStart(true,true,true,false,false,0,3));
        assertFalse(TaskPolicy.canStart(true,true,false,true,false,0,3));
        assertFalse(TaskPolicy.canStart(true,true,false,false,true,0,3));
        assertFalse(TaskPolicy.canStart(false,true,false,false,false,0,3));
        assertFalse(TaskPolicy.canStart(true,false,false,false,false,0,3));
    }
    @Test public void summariesHaveTheirOwnSingleSlot() {
        assertTrue(TaskPolicy.canStart(true,true,false,false,false,0,1));
        assertFalse(TaskPolicy.canStart(true,true,false,false,false,1,1));
    }
    @Test public void databasePresenceDoesNotMeanCompletion() {
        assertEquals("待转写",TaskPolicy.transcription(false,false,false,1008,2007));
        assertEquals("转写中",TaskPolicy.transcription(false,false,true,1008,2004));
        assertEquals("失败：无网络",TaskPolicy.transcription(false,false,false,4001,2000));
        assertEquals("已转写（原应用隐藏）",TaskPolicy.transcription(true,true,false,1008,2007));
    }
}

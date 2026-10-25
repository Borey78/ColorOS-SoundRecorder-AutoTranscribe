package io.github.borey.recorderautotranscribe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ModulePolicyTest {
    @Test public void onlyTargetsTheInspectedRecorderVersion() {
        assertTrue(ModulePolicy.supports("16.17.8"));
        assertTrue(!ModulePolicy.supports("16.17.9"));
    }

    @Test public void keepsTheRecorderManualQueueCapacity() {
        assertEquals(3, ModulePolicy.MAX_CONCURRENT_TASKS);
    }


    @Test public void defaultSettingsWaitForFirstRunConsentBeforeProcessing() {
        ModuleSettings settings = ModuleSettings.defaults();
        assertFalse(settings.enabled);
        assertTrue(settings.aiSummaryEnabled);
        assertFalse(settings.respectRecorderConditions);
    }

    @Test public void summaryRequiresAutomaticProcessing() {
        assertFalse(ModuleSettings.canStartSummary(false, true));
        assertTrue(ModuleSettings.canStartSummary(true, true));
    }

}

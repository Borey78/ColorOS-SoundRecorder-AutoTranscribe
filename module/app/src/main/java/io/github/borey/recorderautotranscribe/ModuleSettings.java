package io.github.borey.recorderautotranscribe;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;

final class ModuleSettings {
    static final String PACKAGE = "io.github.borey.recorderautotranscribe";
    static final String AUTHORITY = "io.github.borey.recorderautotranscribe.config";
    static final Uri URI = Uri.parse("content://" + AUTHORITY + "/settings");
    static final String PREFS = "settings";
    static final String ENABLED = "enabled";
    static final String AI_SUMMARY = "ai_summary";
    static final String RESPECT_CONDITIONS = "respect_conditions";
    static final String ONBOARDING_COMPLETE = "onboarding_complete";

    final boolean enabled;
    final boolean aiSummaryEnabled;
    final boolean respectRecorderConditions;

    ModuleSettings(boolean enabled, boolean aiSummaryEnabled, boolean respectRecorderConditions) {
        this.enabled = enabled;
        this.aiSummaryEnabled = aiSummaryEnabled;
        this.respectRecorderConditions = respectRecorderConditions;
    }

    // A newly installed public build must not silently enqueue a user's existing recordings.
    // SettingsActivity enables processing only after the one-time explanation is accepted.
    static ModuleSettings defaults() { return new ModuleSettings(false, true, false); }

    static boolean canStartSummary(boolean enabled, boolean aiSummaryEnabled) {
        return enabled && aiSummaryEnabled;
    }

    static ModuleSettings load(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        ModuleSettings defaults = defaults();
        return new ModuleSettings(prefs.getBoolean(ENABLED, defaults.enabled),
                prefs.getBoolean(AI_SUMMARY, defaults.aiSummaryEnabled),
                prefs.getBoolean(RESPECT_CONDITIONS, defaults.respectRecorderConditions));
    }

    static boolean onboardingComplete(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ONBOARDING_COMPLETE,false);
    }

    static ModuleSettings loadFromProvider(Context context) {
        if (context == null) return new ModuleSettings(false, false, true);
        try {
            Bundle values = context.getContentResolver().call(URI, "get", null, null);
            return fromBundle(values);
        } catch (Throwable ignored) {
            return new ModuleSettings(false, false, true);
        }
    }

    static ModuleSettings fromBundle(Bundle values) {
        ModuleSettings defaults = defaults();
        if (values == null) return new ModuleSettings(false, false, true);
        return new ModuleSettings(values.getBoolean(ENABLED, defaults.enabled),
                values.getBoolean(AI_SUMMARY, defaults.aiSummaryEnabled),
                values.getBoolean(RESPECT_CONDITIONS, defaults.respectRecorderConditions));
    }

    Bundle toBundle() {
        Bundle values = new Bundle();
        values.putBoolean(ENABLED, enabled);
        values.putBoolean(AI_SUMMARY, aiSummaryEnabled);
        values.putBoolean(RESPECT_CONDITIONS, respectRecorderConditions);
        return values;
    }
}

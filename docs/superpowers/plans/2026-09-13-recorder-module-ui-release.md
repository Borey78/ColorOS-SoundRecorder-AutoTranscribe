# Recorder Module UI Release Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build one signed LSPosed module APK that exposes a settings page for automatic recorder transcription and AI summaries.

**Architecture:** The APK keeps the Xposed entry point and adds a native Android activity plus an exported read-only configuration provider. The activity saves its settings privately; the hook process queries the provider when it decides whether to bypass recorder constraints. The activity launches the recorder and sends a package-scoped refresh broadcast for the existing JobScheduler job.

**Tech Stack:** Android Gradle Plugin 8.1.3, Java, Android framework widgets, legacy Xposed API, JUnit 4.

## Global Constraints

- Target package is `com.coloros.soundrecorder` and verified recorder version is `16.17.8`.
- Default behavior: automatic processing and AI summaries enabled; original Wi-Fi/charging/idle constraints bypassed.
- Automatic transcription concurrency remains exactly `3`.
- AI summary uses the recorder's own summary queue; do not alter its concurrency.
- Release is one APK and requires Root plus LSPosed.

---

### Task 1: Configuration policy and provider

**Files:**
- Create: `module/app/src/main/java/io/github/borey/recorderautotranscribe/ModuleSettings.java`
- Create: `module/app/src/main/java/io/github/borey/recorderautotranscribe/ModuleConfigProvider.java`
- Modify: `module/app/src/main/AndroidManifest.xml`
- Modify: `module/app/src/test/java/io/github/borey/recorderautotranscribe/ModulePolicyTest.java`

**Interfaces:**
- Produces `ModuleSettings.load(Context): ModuleSettings` with boolean fields `enabled`, `aiSummaryEnabled`, and `respectRecorderConditions`.
- Produces provider URI `content://io.github.borey.recorderautotranscribe.config/settings` that returns these three fields in a `Bundle` from `call("get", null, null)`.

- [ ] **Step 1: Write failing configuration tests**

```java
@Test public void defaultSettingsEnableProcessingAndBypassRecorderConditions() {
    ModuleSettings settings = ModuleSettings.defaults();
    assertTrue(settings.enabled);
    assertTrue(settings.aiSummaryEnabled);
    assertFalse(settings.respectRecorderConditions);
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `wsl.exe -d Ubuntu -- sh -lc 'cd /mnt/c/Users/Borey/Documents/Codex/2026-09-13/adb-com-coloros-soundrecorder-github-x20/module && /home/borey/toolchains/gradle-8.0/bin/gradle :app:testDebugUnitTest --offline'`

Expected: compilation fails because `ModuleSettings` does not exist.

- [ ] **Step 3: Implement the immutable configuration value and provider**

```java
final class ModuleSettings {
    static ModuleSettings defaults() { return new ModuleSettings(true, true, false); }
    static ModuleSettings load(Context context) { /* read module preferences */ }
}
```

Declare `ModuleConfigProvider` as exported with `android:authorities="io.github.borey.recorderautotranscribe.config"`; its `call("get", ...)` returns a `Bundle` built from `ModuleSettings.load(getContext())` and exposes no mutation API.

- [ ] **Step 4: Run the test to verify it passes**

Run the Task 1 command. Expected: `BUILD SUCCESSFUL`.

### Task 2: Native settings activity

**Files:**
- Create: `module/app/src/main/java/io/github/borey/recorderautotranscribe/SettingsActivity.java`
- Modify: `module/app/src/main/AndroidManifest.xml`
- Modify: `module/app/src/test/java/io/github/borey/recorderautotranscribe/ModulePolicyTest.java`

**Interfaces:**
- Consumes `ModuleSettings.load(Context)` and writes its three preference keys.
- Produces launcher activity `SettingsActivity` with controls for processing, AI summary, and original conditions plus a scan-now action.

- [ ] **Step 1: Write failing UI state test**

```java
@Test public void summaryCannotRunWhenAutomaticProcessingIsDisabled() {
    assertFalse(ModuleSettings.canStartSummary(false, true));
    assertTrue(ModuleSettings.canStartSummary(true, true));
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run the Task 1 command. Expected: compilation fails because `canStartSummary` does not exist.

- [ ] **Step 3: Implement the activity and UI state rule**

Build the page with framework `LinearLayout`, `Switch`, `TextView`, and `Button`. Display the module version, installed recorder version, supported/unsupported status, `转写并行数：3`, three switches, and `立即扫描`. Disable the AI summary switch while automatic processing is disabled. The scan button launches the recorder's normal launch intent, then sends package-scoped action `io.github.borey.recorderautotranscribe.SCAN_NOW` after 1000 ms.

- [ ] **Step 4: Run the test to verify it passes**

Run the Task 1 command. Expected: `BUILD SUCCESSFUL`.

### Task 3: Apply configuration inside the Xposed hook

**Files:**
- Modify: `module/app/src/main/java/io/github/borey/recorderautotranscribe/MainHook.java`
- Modify: `module/app/src/test/java/io/github/borey/recorderautotranscribe/ModulePolicyTest.java`

**Interfaces:**
- Consumes `ModuleSettings` fields returned by the provider.
- Receives `io.github.borey.recorderautotranscribe.SCAN_NOW` in the recorder process and schedules `RUN_NOW_JOB_ID`.

- [ ] **Step 1: Write failing behavior tests**

```java
@Test public void disabledProcessingDoesNotBypassRecorderChecks() {
    assertFalse(ModulePolicy.shouldBypassRecorderConditions(false, false));
    assertTrue(ModulePolicy.shouldBypassRecorderConditions(true, false));
    assertFalse(ModulePolicy.shouldBypassRecorderConditions(true, true));
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run the Task 1 command. Expected: compilation fails because `shouldBypassRecorderConditions` does not exist.

- [ ] **Step 3: Implement dynamic hook decisions**

Have the two existing condition hooks query the provider for current settings. Return the bypass result only when `ModulePolicy.shouldBypassRecorderConditions(settings.enabled, settings.respectRecorderConditions)` is true. Mark automatic factory tasks with `AUTO_TASK_STATUS` only when both processing and summary are enabled. Register a receiver in the recorder process that calls `schedule(context)` for `SCAN_NOW`.

- [ ] **Step 4: Run tests and build debug APK**

Run: `wsl.exe -d Ubuntu -- sh -lc 'cd /mnt/c/Users/Borey/Documents/Codex/2026-09-13/adb-com-coloros-soundrecorder-github-x20/module && /home/borey/toolchains/gradle-8.0/bin/gradle :app:testDebugUnitTest :app:assembleDebug --offline'`

Expected: `BUILD SUCCESSFUL`.

### Task 4: Signed release and Coolapk material

**Files:**
- Create: `README.md`
- Modify: `module/app/build.gradle`
- Create: `outputs/RecorderAutoTranscribe-v1.2.0-release.apk`
- Create: `outputs/coolapk-description.md`

**Interfaces:**
- Consumes the built release APK.
- Produces an installable signed APK and Chinese release copy.

- [ ] **Step 1: Write the release checklist as a failing artifact check**

Run: `Test-Path outputs/RecorderAutoTranscribe-v1.2.0-release.apk`

Expected: `False`.

- [ ] **Step 2: Configure release signing and documentation**

Create a local release keystore outside the user-facing APK, configure `signingConfigs.release` from local properties, and add a release `README.md` covering Root, LSPosed, target scope, recorder version, switches, and uninstall behavior. Write `coolapk-description.md` with the same user-facing requirements.

- [ ] **Step 3: Build signed APK**

Run: `wsl.exe -d Ubuntu -- sh -lc 'cd /mnt/c/Users/Borey/Documents/Codex/2026-09-13/adb-com-coloros-soundrecorder-github-x20/module && /home/borey/toolchains/gradle-8.0/bin/gradle :app:assembleRelease --offline'`

Copy the result to `outputs/RecorderAutoTranscribe-v1.2.0-release.apk`.

- [ ] **Step 4: Verify packaging**

Run: `Test-Path outputs/RecorderAutoTranscribe-v1.2.0-release.apk; & 'D:/AndroidTools/aapt.exe' dump badging outputs/RecorderAutoTranscribe-v1.2.0-release.apk`

Expected: file exists and reports package `io.github.borey.recorderautotranscribe`.

### Task 5: Device verification

**Files:**
- Modify: none

**Interfaces:**
- Consumes the signed release APK and existing USB direct-ADB helper.
- Produces evidence that the target process loads the updated module and schedules its job.

- [ ] **Step 1: Install the release APK through the existing USB direct-ADB helper**

Use `work/direct-adb-usb.ps1` to transfer `outputs/RecorderAutoTranscribe-v1.2.0-release.apk`, then run `pm install -r`.

- [ ] **Step 2: Open the settings activity and recorder**

Run `am start -n io.github.borey.recorderautotranscribe/.SettingsActivity`, then use the activity scan action or send its package-scoped broadcast after launching the recorder.

- [ ] **Step 3: Verify live state**

Run `dumpsys package io.github.borey.recorderautotranscribe` and grep LSPosed logs for `RecorderAutoTranscribe`. Expected: release version installed, recorder process alive, hooks installed, and auto job scheduled with no module exception.

package io.github.borey.recorderautotranscribe;

final class ModulePolicy {
    static final int MAX_CONCURRENT_TASKS = 3;
    private static final String RECORDER_VERSION = "16.17.8";

    private ModulePolicy() {}

    static boolean supports(String versionName) {
        return RECORDER_VERSION.equals(versionName);
    }

}

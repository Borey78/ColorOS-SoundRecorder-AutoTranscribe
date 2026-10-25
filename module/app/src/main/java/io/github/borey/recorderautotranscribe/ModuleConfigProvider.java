package io.github.borey.recorderautotranscribe;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public final class ModuleConfigProvider extends ContentProvider {
    private static final String BRIDGE_PREFS = "bridge";
    private static final String STATUS_PRESENT = "status_present";
    private static final String STATUS_SAVED_AT = "status_saved_at";
    private static final String STATUS_KEYS = "status_keys";
    private static final String STATUS_TYPE_PREFIX = "status_type.";
    private static final String STATUS_VALUE_PREFIX = "status_value.";
    private static final long LIVE_SNAPSHOT_TTL_MILLIS = 45_000L;
    private static final String[] SNAPSHOT_COUNT_FIELDS = {
            "total", "running", "summaries", "transcribed", "summarized", "skipped", "unfinished"
    };
    private ArrayList<Bundle> rows = new ArrayList<>(), pending = new ArrayList<>();
    private Bundle status = new Bundle(), command = new Bundle();
    private long nextRequest;
    private boolean snapshotCurrent;
    private boolean hasPersistedStatus;
    private long statusSavedAt;
    private long statusGeneration;
    @Override public boolean onCreate() { restoreStatus(); return true; }
    private boolean own() { return Binder.getCallingUid() == Process.myUid(); }
    private void authorize() {
        if (own()) return;
        try {
            int uid = getContext().getPackageManager().getPackageUid(MainHook.TARGET, 0);
            if (uid == Binder.getCallingUid()) return;
        } catch (Exception ignored) { }
        throw new SecurityException("Recorder bridge only");
    }
    private String token() {
        android.content.SharedPreferences prefs = bridgePrefs();
        String value = prefs.getString("token", null);
        if (value == null) { value = UUID.randomUUID().toString(); prefs.edit().putString("token", value).commit(); }
        return value;
    }
    private android.content.SharedPreferences bridgePrefs() {
        return getContext().getSharedPreferences(BRIDGE_PREFS, 0);
    }
    private static String scalarType(Object value) {
        if (value instanceof String) return "string";
        if (value instanceof Boolean) return "boolean";
        if (value instanceof Integer) return "int";
        if (value instanceof Long) return "long";
        if (value instanceof Float) return "float";
        if (value instanceof Double) return "double";
        if (value instanceof Byte) return "byte";
        if (value instanceof Short) return "short";
        if (value instanceof Character) return "char";
        return null;
    }
    private static boolean sensitiveStatusKey(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        return lower.contains("path") || lower.contains("file") || lower.contains("uri") ||
                lower.contains("transcript") || lower.contains("source") || lower.contains("content") ||
                lower.contains("text") || "row".equals(lower) || "rows".equals(lower) ||
                "summary".equals(lower) || lower.contains("summary_text") ||
                "title".equals(lower) || lower.endsWith("title") ||
                "name".equals(lower) || lower.endsWith("name");
    }
    private static boolean persistableStatusField(String key, Object value) {
        if (key == null || key.trim().isEmpty() || key.startsWith("cached_") ||
                "stale".equals(key) || "cached".equals(key) || "rowsCurrent".equals(key) ||
                "cachedAt".equals(key) || "cachedMessage".equals(key)) return false;
        // The bridge only needs health metadata after a restart.  Never write a recording row,
        // a PendingIntent, or a file/source reference into preferences.
        if (sensitiveStatusKey(key) && !"message".equals(key)) return false;
        return scalarType(value) != null;
    }
    private static void putScalar(Bundle target, String key, String type, String value) {
        if ("string".equals(type)) target.putString(key, value);
        else if ("boolean".equals(type)) target.putBoolean(key, Boolean.parseBoolean(value));
        else if ("int".equals(type)) target.putInt(key, Integer.parseInt(value));
        else if ("long".equals(type)) target.putLong(key, Long.parseLong(value));
        else if ("float".equals(type)) target.putFloat(key, Float.parseFloat(value));
        else if ("double".equals(type)) target.putDouble(key, Double.parseDouble(value));
        else if ("byte".equals(type)) target.putByte(key, Byte.parseByte(value));
        else if ("short".equals(type)) target.putShort(key, Short.parseShort(value));
        else if ("char".equals(type) && value.length() == 1) target.putChar(key, value.charAt(0));
    }
    private static void copyScalar(Bundle target, String key, Object value) {
        String type = scalarType(value);
        if (type != null) putScalar(target, key, type, String.valueOf(value));
    }
    private void restoreStatus() {
        android.content.SharedPreferences prefs = bridgePrefs();
        hasPersistedStatus = prefs.getBoolean(STATUS_PRESENT, false);
        statusSavedAt = prefs.getLong(STATUS_SAVED_AT, 0L);
        if (!hasPersistedStatus) return;
        Set<String> keys = prefs.getStringSet(STATUS_KEYS, null);
        if (keys == null) return;
        for (String key : new HashSet<>(keys)) {
            if (key == null) continue;
            String type = prefs.getString(STATUS_TYPE_PREFIX + key, null);
            String value = prefs.getString(STATUS_VALUE_PREFIX + key, null);
            if (type == null || value == null) continue;
            try { putScalar(status, key, type, value); }
            catch (RuntimeException ignored) { }
        }
        // Only scalar health information survives a provider/process restart.  A fresh finish()
        // is required before the current recording list can be served again.
        snapshotCurrent = false;
        statusGeneration = status.getLong("generation", 0L);
    }
    private void advanceGeneration() {
        statusGeneration = Math.max(1L, statusGeneration + 1L);
        status.putLong("generation", statusGeneration);
    }
    private boolean persistStatus() {
        android.content.SharedPreferences prefs = bridgePrefs();
        Set<String> oldKeys = prefs.getStringSet(STATUS_KEYS, null);
        Set<String> newKeys = new HashSet<>();
        android.content.SharedPreferences.Editor editor = prefs.edit();
        if (oldKeys != null) {
            for (String key : oldKeys) {
                editor.remove(STATUS_TYPE_PREFIX + key).remove(STATUS_VALUE_PREFIX + key);
            }
        }
        for (String key : status.keySet()) {
            Object value = status.get(key);
            if (!persistableStatusField(key, value)) continue;
            String type = scalarType(value);
            newKeys.add(key);
            editor.putString(STATUS_TYPE_PREFIX + key, type);
            editor.putString(STATUS_VALUE_PREFIX + key, String.valueOf(value));
        }
        long savedAt = System.currentTimeMillis();
        boolean persisted = editor.putBoolean(STATUS_PRESENT, true).putLong(STATUS_SAVED_AT, savedAt)
                .putStringSet(STATUS_KEYS, newKeys).commit();
        if (persisted) {
            statusSavedAt = savedAt;
            hasPersistedStatus = true;
        }
        return persisted;
    }
    private Bundle statusPage(int offset) {
        long snapshotAt = status.getLong("snapshotAt", 0L);
        boolean liveSnapshot = snapshotCurrent && snapshotAt > 0L
                && System.currentTimeMillis() - snapshotAt <= LIVE_SNAPSHOT_TTL_MILLIS;
        if (!liveSnapshot && snapshotCurrent) {
            // TTL is itself a state transition: invalidate an in-progress paged read.
            snapshotCurrent = false;
            advanceGeneration();
        }
        Bundle out = new Bundle(status);
        out.putLong("generation", statusGeneration);
        if (!liveSnapshot) {
            String cachedMessage = out.getString("message", "尚未收到录音机回执");
            out.putBoolean("cached", hasPersistedStatus);
            out.putBoolean("stale", hasPersistedStatus);
            out.putBoolean("rowsCurrent", false);
            if (hasPersistedStatus) {
                out.putLong("cachedAt", snapshotAt > 0L ? snapshotAt : statusSavedAt);
                out.putString("cachedMessage", cachedMessage);
                out.putString("message", "缓存状态：录音机尚未确认当前录音清单\n" + cachedMessage);
                for (String field : SNAPSHOT_COUNT_FIELDS) {
                    if (out.containsKey(field)) copyScalar(out, "cached_" + field, out.get(field));
                    out.putInt(field, 0);
                }
            } else {
                out.putInt("total", 0);
            }
            out.putParcelableArrayList("rows", new ArrayList<Bundle>());
            return out;
        }
        out.putBoolean("cached", false);
        out.putBoolean("stale", false);
        out.putBoolean("rowsCurrent", true);
        int start = Math.min(Math.max(0, offset), rows.size());
        int end = Math.min(rows.size(), start + 50);
        out.putParcelableArrayList("rows", new ArrayList<>(rows.subList(start, end)));
        return out;
    }
    @Override public synchronized Bundle call(String method, String arg, Bundle extras) {
        authorize();
        if ("get".equals(method)) {
            Bundle out = ModuleSettings.load(getContext()).toBundle();
            out.putString("token", token());
            out.putBundle("command", new Bundle(command));
            return out;
        }
        if ("request".equals(method)) {
            if (!own()) throw new SecurityException();
            command = extras == null ? new Bundle() : new Bundle(extras);
            command.putLong("request", Math.max(System.currentTimeMillis(), ++nextRequest));
            nextRequest = command.getLong("request");
            // The next status belongs to a new scan request.  Do not leave old row actions
            // looking current while the recorder has not acknowledged this request yet.
            snapshotCurrent = false;
            advanceGeneration();
            return new Bundle(command);
        }
        if ("begin".equals(method)) { pending = new ArrayList<>(); return Bundle.EMPTY; }
        if ("rows".equals(method)) {
            ArrayList<Bundle> page = extras.getParcelableArrayList("rows");
            if (page != null) pending.addAll(page);
            return Bundle.EMPTY;
        }
        if ("finish".equals(method)) {
            long completedRequest = extras == null ? 0L : extras.getLong("request", 0L);
            long latestRequest = command.getLong("request", 0L);
            // A newer user request arrived while this scan was still collecting rows.  Drop the
            // obsolete snapshot so it cannot overwrite the newer request in the settings UI.
            if (latestRequest > completedRequest) {
                pending = new ArrayList<>();
                Bundle receipt = new Bundle();
                receipt.putBoolean("saved", false);
                receipt.putBoolean("superseded", true);
                return receipt;
            }
            rows = pending; pending = new ArrayList<>(); status = extras == null ? new Bundle() : new Bundle(extras);
            long now = System.currentTimeMillis();
            status.putLong("updated", now);
            status.putLong("snapshotAt", now);
            status.putInt("total", rows.size());
            snapshotCurrent = true;
            advanceGeneration();
            boolean persisted = persistStatus();
            snapshotCurrent = persisted;
            Bundle receipt = new Bundle();
            receipt.putBoolean("saved", persisted);
            receipt.putLong("snapshotAt", now);
            receipt.putLong("generation", statusGeneration);
            receipt.putInt("total", rows.size());
            return receipt;
        }
        if ("health".equals(method)) {
            if (extras != null) status.putAll(extras);
            status.putString("message", arg == null ? status.getString("message", "") : arg);
            status.putLong("updated", System.currentTimeMillis());
            // A health heartbeat says only that the bridge is alive. It must never make
            // an older recording list look like a fresh scan.
            snapshotCurrent = false;
            advanceGeneration();
            persistStatus();
            return Bundle.EMPTY;
        }
        if ("error".equals(method)) {
            status.putString("message", arg); status.putLong("updated", System.currentTimeMillis());
            snapshotCurrent = false;
            advanceGeneration();
            persistStatus();
            return Bundle.EMPTY;
        }
        if ("status".equals(method)) {
            if (!own()) throw new SecurityException();
            int offset = extras == null ? 0 : Math.max(0, extras.getInt("offset"));
            return statusPage(offset);
        }
        throw new IllegalArgumentException("Unknown bridge method");
    }
    @Override public String getType(Uri uri) { return null; }
    @Override public Cursor query(Uri uri, String[] p, String s, String[] a, String order) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String s, String[] a) { throw new UnsupportedOperationException(); }
}

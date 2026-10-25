package io.github.borey.recorderautotranscribe;

import android.app.Application;
import android.content.Context;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Completes the native background entry point's missing worker initialization. */
final class SummaryServiceHook {
    static final String PACKAGE = "com.coloros.accessibilityassistant";
    private static final String SUPPORTED_VERSION = "16.3.12";
    private static final Object INSTALL_LOCK = new Object();
    private static boolean attachHookInstalled;
    private static boolean installAttempted;

    static void install(ClassLoader loader) {
        synchronized (INSTALL_LOCK) {
            if (attachHookInstalled) return;
            attachHookInstalled = true;
        }
        try {
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Object attached = param.args != null && param.args.length > 0 ? param.args[0] : null;
                    if (attached instanceof Context) {
                        Context context = (Context) attached;
                        ClassLoader appLoader = context.getClassLoader();
                        installIfSupported(context, appLoader != null ? appLoader : loader);
                    } else {
                        logCompatibility("application attach did not provide a context; hook not installed", null);
                    }
                }
            });
        } catch (Throwable error) {
            logCompatibility("unable to register application attach hook", error);
        }
    }

    private static void installIfSupported(Context context, ClassLoader loader) {
        if (context == null || !PACKAGE.equals(context.getPackageName())) return;
        synchronized (INSTALL_LOCK) {
            if (installAttempted) return;
            installAttempted = true;
        }
        final String version;
        try {
            version = context.getPackageManager().getPackageInfo(PACKAGE, 0).versionName;
        } catch (Throwable error) {
            logCompatibility("could not read AI summary service version; hook not installed", error);
            return;
        }
        if (!SUPPORTED_VERSION.equals(version)) {
            logCompatibility("unsupported AI summary service version " + version + "; hook not installed", null);
            return;
        }

        final Class<?> binder;
        try {
            ClassLoader appLoader = context.getClassLoader();
            Class<?> foundBinder = null;
            if (appLoader != null) {
                try {
                    foundBinder = Class.forName(
                            "com.oplus.accessibilityassistant.aikit.runtime.entrance.AISummaryKitEntryService$KitServiceBinder",
                            false, appLoader);
                } catch (ClassNotFoundException ignored) { }
            }
            if (foundBinder == null && loader != null) {
                try {
                    foundBinder = Class.forName(
                            "com.oplus.accessibilityassistant.aikit.runtime.entrance.AISummaryKitEntryService$KitServiceBinder",
                            false, loader);
                } catch (ClassNotFoundException ignored) { }
            }
            if (foundBinder == null) {
                logCompatibility("AISummaryKitEntryService not found in current process; hook skipped", null);
                return;
            }
            binder = foundBinder;
            requireMethod(binder, "callSummaryKit", String.class, String.class, Bundle.class);
        } catch (Throwable error) {
            logCompatibility("AI summary service 16.3.12 is missing a required capability; hook not installed", error);
            return;
        }

        try {
            final ClassLoader effectiveLoader = binder.getClassLoader() != null
                    ? binder.getClassLoader()
                    : (context.getClassLoader() != null ? context.getClassLoader() : loader);
            XposedHelpers.findAndHookMethod(binder, "callSummaryKit", String.class, String.class, Bundle.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            handleCall(binder, effectiveLoader, param);
                        }
                    });
            logCompatibility("AI summary service 16.3.12 hook active", null);
        } catch (Throwable error) {
            logCompatibility("AI summary service hook installation failed", error);
        }
    }

    private static void handleCall(Class<?> binder, ClassLoader loader, XC_MethodHook.MethodHookParam param) {
        Object requestArgument = param.args != null && param.args.length > 2 ? param.args[2] : null;
        Object inputArgument = param.args != null && param.args.length > 0 ? param.args[0] : null;
        Object actionArgument = param.args != null && param.args.length > 1 ? param.args[1] : null;
        Bundle request = requestArgument instanceof Bundle ? (Bundle) requestArgument : null;
        String inputType = inputArgument instanceof String ? (String) inputArgument : null;
        String action = actionArgument instanceof String ? (String) actionArgument : null;
        boolean inspect = "recorderAutoStatus".equals(action);
        if (!"record".equals(inputType) || (!"startSummary".equals(action) && !inspect)
                || request == null || !request.getBoolean("recorder_auto_queue")) return;
        try {
            Field outer = binder.getDeclaredField("this$0");
            outer.setAccessible(true);
            Object outerService = outer.get(param.thisObject);
            if (!(outerService instanceof Context)) return;
            Context service = (Context) outerService;
            if (!isRecorderCaller(service)) return;
            if (!isSupported(service)) {
                if (inspect) {
                    Bundle unsupported = new Bundle();
                    unsupported.putBoolean("bridge", false);
                    param.setResult(unsupported);
                }
                return;
            }

            FutureTask<Bundle> initialize = new FutureTask<>(() -> initializeWorker(service, loader, inspect));
            if (Looper.myLooper() == Looper.getMainLooper()) initialize.run();
            else new Handler(Looper.getMainLooper()).post(initialize);
            Bundle result = initialize.get(8, TimeUnit.SECONDS);
            if (inspect) param.setResult(result);
        } catch (Throwable error) {
            logCompatibility("AI summary worker initialization failed", error);
            if (inspect) {
                Bundle unavailable = new Bundle();
                unavailable.putBoolean("bridge", false);
                param.setResult(unavailable);
            }
        }
    }

    private static boolean isSupported(Context context) {
        try {
            return SUPPORTED_VERSION.equals(
                    context.getPackageManager().getPackageInfo(PACKAGE, 0).versionName);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isRecorderCaller(Context service) {
        try {
            return Binder.getCallingUid()
                    == service.getPackageManager().getPackageUid(MainHook.TARGET, 0);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Bundle initializeWorker(Context service, ClassLoader loader, boolean inspect) throws Exception {
        ClassLoader effectiveLoader = service.getClassLoader();
        if (effectiveLoader == null) effectiveLoader = loader;
        if (effectiveLoader == null) effectiveLoader = SummaryServiceHook.class.getClassLoader();

        Class<?> runtime = Class.forName(
                "com.oplus.accessibilityassistant.aikit.runtime.KitRunningTimeManager", true, effectiveLoader);
        Object instance = runtime.getField("INSTANCE").get(null);
        Method getter = runtime.getDeclaredMethod("getRecordSummaryPanelInfo");
        getter.setAccessible(true);
        Object manager = getter.invoke(instance);

        if (inspect) {
            Bundle result = new Bundle();
            result.putBoolean("bridge", true);
            boolean isRunning = false;
            String activeRecordId = null;

            if (manager != null) {
                try {
                    Method isRunningMethod = manager.getClass().getMethod("isRecordSummaryRunning");
                    Object runningObj = isRunningMethod.invoke(manager);
                    if (runningObj instanceof Boolean) {
                        isRunning = (Boolean) runningObj;
                    }
                } catch (Throwable ignored) { }

                if (isRunning) {
                    try {
                        Field workerField = manager.getClass().getDeclaredField("soundRecordWorkManager");
                        workerField.setAccessible(true);
                        Object worker = workerField.get(manager);
                        if (worker != null) {
                            Field dataField = worker.getClass().getDeclaredField("mDataManager");
                            dataField.setAccessible(true);
                            Object data = dataField.get(worker);
                            if (data != null) {
                                Field recordId = data.getClass().getDeclaredField("E");
                                recordId.setAccessible(true);
                                Object value = recordId.get(data);
                                if (value != null) {
                                    String idStr = String.valueOf(value).trim();
                                    if (!idStr.isEmpty()) {
                                        activeRecordId = idStr;
                                    }
                                }
                            }
                        }
                    } catch (Throwable ignored) { }
                }
            }

            result.putBoolean("running", isRunning);
            if (isRunning && activeRecordId != null) {
                result.putString("recordId", activeRecordId);
            }
            return result;
        }

        if (manager == null) throw new IllegalStateException("SoundRecordSummaryManager unavailable");
        manager.getClass().getMethod("initSoundRecordWorkManager", Context.class, String.class)
                .invoke(manager, service, "record");
        Field workerField = manager.getClass().getDeclaredField("soundRecordWorkManager");
        workerField.setAccessible(true);
        Object worker = workerField.get(manager);
        if (worker != null) {
            Field dataField = worker.getClass().getDeclaredField("mDataManager");
            dataField.setAccessible(true);
            Object data = dataField.get(worker);
            if (data != null) {
                Field recordId = data.getClass().getDeclaredField("E");
                recordId.setAccessible(true);
                // Native requestSummary only parses the first bundle when a previous ID exists.
                // The normal panel establishes it; the background entry point omits that step.
                if (recordId.get(data) == null) recordId.set(data, "");
            }
        }
        XposedBridge.log("RecorderAutoTranscribe: background summary worker initialized");
        return null;
    }

    private static void requireMethod(Class<?> type, String name, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        Method method = type.getDeclaredMethod(name, parameterTypes);
        if (method == null) throw new NoSuchMethodException(type.getName() + "#" + name);
    }

    private static void logCompatibility(String state, Throwable error) {
        XposedBridge.log("RecorderAutoTranscribe: " + state);
        if (error != null) XposedBridge.log(error);
    }
}

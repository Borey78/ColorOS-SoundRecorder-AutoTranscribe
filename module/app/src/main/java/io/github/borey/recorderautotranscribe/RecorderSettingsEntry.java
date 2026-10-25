package io.github.borey.recorderautotranscribe;

import android.app.Activity;
import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Adds one native-looking entry to the supported recorder's own settings screen. */
final class RecorderSettingsEntry {
    private static final String FRAGMENT = "th.f";
    private static final String ACTIVITY = "com.soundrecorder.setting.setting.SettingRecorderActivity";
    private static final String KEY = "recorder_auto_transcribe_settings";

    static void install(ClassLoader loader) throws ClassNotFoundException {
        Class<?> fragment = Class.forName(FRAGMENT, false, loader);
        Class<?> preference = Class.forName("com.coui.appcompat.preference.COUIJumpPreference", false, loader);
        Class<?> preferenceBase = Class.forName("androidx.preference.Preference", false, loader);
        Class<?> clickListener = Class.forName("androidx.preference.q", false, loader);
        XposedHelpers.findAndHookMethod(fragment, "onViewCreated", View.class, Bundle.class,
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Activity host = (Activity) invoke(param.thisObject, "requireActivity", new Class<?>[0]);
                            if (!ACTIVITY.equals(host.getClass().getName())) return;
                            Object screen = invoke(param.thisObject, "getPreferenceScreen", new Class<?>[0]);
                            if (screen == null || invoke(param.thisObject, "findPreference",
                                    new Class<?>[]{CharSequence.class}, KEY) != null)
                                return;
                            Object entry = preference.getConstructor(Context.class).newInstance(host);
                            invoke(entry, "setKey", new Class<?>[]{String.class}, KEY);
                            invoke(entry, "setTitle", new Class<?>[]{CharSequence.class}, "录音自动转写");
                            invoke(entry, "setSummary", new Class<?>[]{CharSequence.class},
                                    "查看录音处理状态与自动处理设置");
                            // Put the module entry before the recorder's less-used options.
                            invoke(entry, "setOrder", new Class<?>[]{int.class}, Integer.MIN_VALUE);
                            Object listener = Proxy.newProxyInstance(loader, new Class<?>[]{clickListener},
                                    (proxy, method, args) -> {
                                        if ("equals".equals(method.getName())) return proxy == args[0];
                                        if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                                        if ("toString".equals(method.getName())) return "RecorderSettingsEntry";
                                        if (method.getReturnType() == boolean.class && args != null && args.length == 1) {
                                            try {
                                                Intent open = new Intent().setComponent(new ComponentName(
                                                        ModuleSettings.PACKAGE, SettingsActivity.class.getName()));
                                                host.startActivity(open);
                                            } catch (Exception error) {
                                                XposedBridge.log("RecorderAutoTranscribe: cannot open settings: " + error);
                                                Toast.makeText(host, "无法打开录音自动转写设置", Toast.LENGTH_SHORT).show();
                                            }
                                            return true;
                                        }
                                        return null;
                                    });
                            invoke(entry, "setOnPreferenceClickListener", new Class<?>[]{clickListener}, listener);
                            invoke(screen, "addPreference", new Class<?>[]{preferenceBase}, entry);
                        } catch (Throwable error) {
                            XposedBridge.log("RecorderAutoTranscribe: recorder settings entry failed");
                            XposedBridge.log(error);
                        }
                    }
                });
    }

    private static Object invoke(Object target, String name, Class<?>[] parameterTypes, Object... args)
            throws ReflectiveOperationException {
        Method method = target.getClass().getMethod(name, parameterTypes);
        return method.invoke(target, args);
    }

    private RecorderSettingsEntry() { }
}

package io.github.borey.recorderautotranscribe;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Adds a compact action immediately to the left of Search on the supported recorder home page. */
final class RecorderHomeEntry {
    private static final String HOME = "com.soundrecorder.browsefile.BrowseFile";
    private static final String TAG = "recorder_auto_transcribe_home_entry";

    static void install() {
        install(null);
    }

    static void install(ClassLoader loader) {
        try {
            Class<?> homeClass = resolveHomeClass(loader);
            if (homeClass != null) {
                XposedHelpers.findAndHookMethod(homeClass, "onResume", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (!(param.thisObject instanceof Activity)) return;
                        scheduleAdd((Activity) param.thisObject);
                    }
                });
                XposedBridge.log("RecorderAutoTranscribe: targeted BrowseFile.onResume hook installed");
            } else {
                XposedBridge.log("RecorderAutoTranscribe: direct BrowseFile lookup failed; using activity lifecycle fallback");
                XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (!(param.thisObject instanceof Activity)) return;
                        Activity host = (Activity) param.thisObject;
                        if (!HOME.equals(host.getClass().getName())) return;
                        scheduleAdd(host);
                    }
                });
            }
        } catch (Throwable error) {
            XposedBridge.log("RecorderAutoTranscribe: recorder home entry hook installation failed: " + error);
            XposedBridge.log(error);
        }
    }

    private static Class<?> resolveHomeClass(ClassLoader loader) {
        if (loader != null) {
            try {
                return Class.forName(HOME, false, loader);
            } catch (Throwable ignored) { }
        }
        ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
        if (contextLoader != null) {
            try {
                return Class.forName(HOME, false, contextLoader);
            } catch (Throwable ignored) { }
        }
        try {
            return Class.forName(HOME);
        } catch (Throwable ignored) { }
        return null;
    }

    private static void scheduleAdd(Activity host) {
        try {
            if (isHostUnavailable(host)) return;
            Window window = host.getWindow();
            if (window == null) return;
            View decor = window.getDecorView();
            if (decor == null) return;
            decor.post(() -> addWithRetry(host, 0));
        } catch (Throwable error) {
            XposedBridge.log("RecorderAutoTranscribe: scheduleAdd failed: " + error);
        }
    }

    private static void addWithRetry(Activity host, int retryCount) {
        try {
            if (isHostUnavailable(host)) return;
            Window window = host.getWindow();
            if (window == null) return;
            View decor = window.getDecorView();
            if (decor == null) return;

            if (decor.findViewWithTag(TAG) != null) return;

            int browseToolbarId = host.getResources().getIdentifier("browse_toolbar", "id", MainHook.TARGET);
            View browseToolbarView = browseToolbarId != 0 ? host.findViewById(browseToolbarId) : null;

            int moreViewId = host.getResources().getIdentifier("coui_toolbar_more_view", "id", MainHook.TARGET);
            View moreView = moreViewId != 0 ? host.findViewById(moreViewId) : null;

            if (browseToolbarView == null && moreView == null) {
                if (retryCount < 3) {
                    decor.postDelayed(() -> addWithRetry(host, retryCount + 1), 150);
                } else {
                    XposedBridge.log("RecorderAutoTranscribe: neither browse_toolbar nor coui_toolbar_more_view found after retries");
                }
                return;
            }

            ViewGroup parentToolbar = null;
            if (browseToolbarView instanceof ViewGroup) {
                parentToolbar = (ViewGroup) browseToolbarView;
            } else if (moreView != null && moreView.getParent() instanceof ViewGroup) {
                parentToolbar = (ViewGroup) moreView.getParent();
            }

            if (parentToolbar != null && parentToolbar.findViewWithTag(TAG) != null) return;
            if (moreView instanceof ViewGroup && ((ViewGroup) moreView).findViewWithTag(TAG) != null) return;

            FrameLayout entry = createEntryView(host);
            boolean added = false;

            if (parentToolbar != null) {
                try {
                    ViewGroup.LayoutParams lp = createToolbarLayoutParams(parentToolbar, host);
                    parentToolbar.addView(entry, lp);
                    parentToolbar.requestLayout();
                    added = true;
                    XposedBridge.log("RecorderAutoTranscribe: recorder home entry added to toolbar (" + parentToolbar.getClass().getName() + ")");
                } catch (Throwable error) {
                    XposedBridge.log("RecorderAutoTranscribe: adding to parent toolbar failed: " + error);
                    XposedBridge.log(error);
                }
            }

            if (!added && moreView instanceof ViewGroup) {
                ViewGroup actions = (ViewGroup) moreView;
                try {
                    ViewGroup.LayoutParams lp = null;
                    try {
                        lp = (ViewGroup.LayoutParams) invokeMethod(actions, "generateLayoutParams",
                                new Class<?>[]{ViewGroup.LayoutParams.class},
                                new ViewGroup.LayoutParams(dp(host, 48), ViewGroup.LayoutParams.MATCH_PARENT));
                    } catch (Throwable t) {
                        XposedBridge.log("RecorderAutoTranscribe: generateLayoutParams on actions fallback failed: " + t);
                    }
                    if (lp == null) {
                        lp = new ViewGroup.LayoutParams(dp(host, 48), ViewGroup.LayoutParams.MATCH_PARENT);
                    }
                    actions.addView(entry, 0, lp);
                    actions.requestLayout();
                    added = true;
                    XposedBridge.log("RecorderAutoTranscribe: recorder home entry added to actions fallback (" + actions.getClass().getName() + ")");
                } catch (Throwable error) {
                    XposedBridge.log("RecorderAutoTranscribe: adding to actions fallback failed: " + error);
                    XposedBridge.log(error);
                }
            }

            if (!added) {
                XposedBridge.log("RecorderAutoTranscribe: recorder home entry failed to attach to any container");
            }
        } catch (Throwable error) {
            XposedBridge.log("RecorderAutoTranscribe: recorder home entry failed: " + error);
            XposedBridge.log(error);
        }
    }

    private static ViewGroup.LayoutParams createToolbarLayoutParams(ViewGroup parentToolbar, Activity host) {
        ViewGroup.LayoutParams lp = null;
        try {
            lp = (ViewGroup.LayoutParams) invokeMethod(parentToolbar, "generateLayoutParams",
                    new Class<?>[]{ViewGroup.LayoutParams.class},
                    new ViewGroup.LayoutParams(dp(host, 48), ViewGroup.LayoutParams.MATCH_PARENT));
        } catch (Throwable t) {
            XposedBridge.log("RecorderAutoTranscribe: generateLayoutParams on toolbar failed: " + t);
        }
        if (lp == null) {
            try {
                Class<?> lpClass = Class.forName("androidx.appcompat.widget.Toolbar$LayoutParams", false, host.getClassLoader());
                Constructor<?> ctor = lpClass.getConstructor(int.class, int.class, int.class);
                lp = (ViewGroup.LayoutParams) ctor.newInstance(dp(host, 48), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END | Gravity.CENTER_VERTICAL);
            } catch (Throwable ignored) { }
        }
        if (lp == null) {
            try {
                lp = new android.widget.Toolbar.LayoutParams(dp(host, 48), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END | Gravity.CENTER_VERTICAL);
            } catch (Throwable ignored) { }
        }
        if (lp == null) {
            lp = new ViewGroup.LayoutParams(dp(host, 48), ViewGroup.LayoutParams.MATCH_PARENT);
        }
        setIntField(lp, "gravity", Gravity.END | Gravity.CENTER_VERTICAL);
        return lp;
    }

    private static FrameLayout createEntryView(Activity host) {
        FrameLayout entry = new FrameLayout(host);
        entry.setTag(TAG);
        entry.setContentDescription("打开录音自动转写状态与设置");
        entry.setClickable(true);
        entry.setFocusable(true);

        try {
            TypedValue outValue = new TypedValue();
            if (host.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)) {
                entry.setBackgroundResource(outValue.resourceId);
            } else if (host.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)) {
                entry.setBackgroundResource(outValue.resourceId);
            }
        } catch (Throwable ignored) { }

        entry.setOnClickListener(v -> {
            try {
                Intent intent = new Intent();
                intent.setComponent(new ComponentName(
                        ModuleSettings.PACKAGE, SettingsActivity.class.getName()));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                host.startActivity(intent);
            } catch (Throwable error) {
                XposedBridge.log("RecorderAutoTranscribe: cannot open home entry: " + error);
                Toast.makeText(host, "无法打开录音自动转写", Toast.LENGTH_SHORT).show();
            }
        });

        TextView badge = new TextView(host);
        badge.setText("转");
        badge.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        badge.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        badge.setTextColor(Color.WHITE);
        badge.setGravity(Gravity.CENTER);

        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.rgb(245, 92, 97));
        background.setCornerRadius(dp(host, 12));
        badge.setBackground(background);

        FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(
                dp(host, 36), dp(host, 36), Gravity.CENTER);
        entry.addView(badge, badgeParams);

        return entry;
    }

    private static Object invokeMethod(Object target, String name, Class<?>[] parameterTypes, Object... args)
            throws Exception {
        Method method = null;
        Class<?> current = target.getClass();
        while (current != null) {
            try {
                method = current.getDeclaredMethod(name, parameterTypes);
                method.setAccessible(true);
                break;
            } catch (NoSuchMethodException e) {
                current = current.getSuperclass();
            }
        }
        if (method == null) throw new NoSuchMethodException(name);
        return method.invoke(target, args);
    }

    private static void setIntField(Object target, String name, int value) {
        Class<?> current = target.getClass();
        while (current != null) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                field.setInt(target, value);
                return;
            } catch (Throwable e) {
                current = current.getSuperclass();
            }
        }
    }

    private static boolean isHostUnavailable(Activity host) {
        if (host == null || host.isFinishing()) return true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && host.isDestroyed()) return true;
        return false;
    }

    private static int dp(Activity host, int value) {
        if (host == null || host.getResources() == null) return value * 3;
        return (int) (value * host.getResources().getDisplayMetrics().density + .5f);
    }

    private RecorderHomeEntry() { }
}

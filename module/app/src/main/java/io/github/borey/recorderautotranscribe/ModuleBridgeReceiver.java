package io.github.borey.recorderautotranscribe;

import android.app.Activity;
import android.app.BroadcastOptions;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import de.robv.android.xposed.XposedBridge;

/** Relays recorder bridge calls through an explicit component when provider discovery is filtered. */
public final class ModuleBridgeReceiver extends BroadcastReceiver {
    static final String ACTION = "io.github.borey.recorderautotranscribe.MODULE_BRIDGE_CALL";
    static final String EXTRA_METHOD = "method";
    static final String EXTRA_ARGUMENT = "argument";
    static final String EXTRA_VALUES = "values";

    static void reportError(Context context, String message) {
        if (Build.VERSION.SDK_INT < 34) return;
        Intent call = new Intent(ACTION)
                .setComponent(new ComponentName(ModuleSettings.PACKAGE, ModuleBridgeReceiver.class.getName()))
                .putExtra(EXTRA_METHOD, "error")
                .putExtra(EXTRA_ARGUMENT, message);
        Bundle options = BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle();
        context.sendOrderedBroadcast(call, null, options, new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) {
                if (getResultCode() != Activity.RESULT_OK) {
                    Bundle result = getResultExtras(false);
                    String detail = result == null ? "no receiver acknowledgement"
                            : result.getString("bridgeError", "receiver rejected the request");
                    XposedBridge.log("RecorderAutoTranscribe: compatibility status relay failed: " + detail);
                }
            }
        }, new Handler(Looper.getMainLooper()), Activity.RESULT_CANCELED, null, null);
    }

    @Override public void onReceive(Context context, Intent intent) {
        Bundle response=new Bundle();
        try {
            if (intent == null || !ACTION.equals(intent.getAction())) {
                throw new SecurityException("Invalid bridge action");
            }
            if (Build.VERSION.SDK_INT < 34 || getSentFromUid() !=
                    context.getPackageManager().getPackageUid(MainHook.TARGET,0)) {
                throw new SecurityException("Only the recorder process may call the bridge");
            }
            String method=intent.getStringExtra(EXTRA_METHOD);
            if (!allowedRecorderMethod(method)) {
                throw new SecurityException("Recorder bridge method denied");
            }
            Bundle result=context.getContentResolver().call(ModuleSettings.URI,method,
                    intent.getStringExtra(EXTRA_ARGUMENT),intent.getBundleExtra(EXTRA_VALUES));
            if(result!=null)response.putAll(result);
            setResultCode(Activity.RESULT_OK);
        } catch (Throwable error) {
            response.putString("bridgeError",error.getClass().getSimpleName()+": "+String.valueOf(error.getMessage()));
            setResultCode(Activity.RESULT_CANCELED);
        }
        setResultExtras(response);
    }

    private static boolean allowedRecorderMethod(String method) {
        return "get".equals(method) || "begin".equals(method) || "rows".equals(method)
                || "finish".equals(method) || "health".equals(method) || "error".equals(method);
    }
}

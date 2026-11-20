package dev.androidroute.nativeclick;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Explicitly exported only to senders holding android.permission.DUMP.
 * adb shell holds DUMP; ordinary third-party apps do not.
 * Requests are fixed to one action; no package, selector, or shell input.
 */
public final class ClickReceiver extends BroadcastReceiver {
    public static final String ACTION = "dev.androidroute.nativeclick.CLICK_OPEN_GUIDE";
    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9_-]{8,64}");
    private static final Object LOCK = new Object();

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) return;
        String requestId = intent.getStringExtra("request_id");
        if (requestId == null || !REQUEST_ID.matcher(requestId).matches()) {
            writeSafely(context, requestId == null ? "" : requestId, "REFUSED_BAD_REQUEST_ID");
            return;
        }
        synchronized (LOCK) {
            SharedPreferences prefs = context.getSharedPreferences("requests", Context.MODE_PRIVATE);
            String key = "seen_" + requestId;
            if (prefs.getBoolean(key, false)) {
                writeSafely(context, requestId, "REFUSED_DUPLICATE_REQUEST");
                return;
            }
            // Fail closed if the finite benchmark request journal is full.
            if (prefs.getAll().size() >= 256) {
                writeSafely(context, requestId, "REFUSED_JOURNAL_FULL");
                return;
            }
            // Commit before touching Android. Crashes cannot replay the same request ID.
            if (!prefs.edit().putBoolean(key, true).commit()) {
                writeSafely(context, requestId, "REFUSED_JOURNAL_WRITE_FAILURE");
                return;
            }
            // Persist the uncertain marker BEFORE the possible click.
            if (!writeSafely(context, requestId, "EXECUTION_UNCERTAIN")) return;
            String result = NativeClickService.clickOpenGuide();
            writeSafely(context, requestId, result);
        }
    }

    private static boolean writeSafely(Context context, String requestId, String status) {
        AtomicFile file = new AtomicFile(new File(context.getFilesDir(), "last-result.json"));
        FileOutputStream out = null;
        try {
            JSONObject json = new JSONObject();
            json.put("requestId", requestId);
            json.put("operation", "CLICK_OPEN_GUIDE");
            json.put("status", status);
            json.put("destinationVerified", false);
            json.put("coordinateTapUsed", false);
            json.put("timeEpochMs", System.currentTimeMillis());
            out = file.startWrite();
            out.write(json.toString().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(out);
            return true;
        } catch (Exception e) {
            if (out != null) file.failWrite(out);
            return false;
        }
    }
}

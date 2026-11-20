package dev.androidroute.nativeclick;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class MainActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);
        TextView info = new TextView(this);
        info.setText("Benchmark-only native accessibility click helper.\n\n" +
            "This helper does not execute clicks unless its accessibility service is manually enabled " +
            "and an authorized ADB shell request is received. It only clicks Open Guide in the " +
            "Route Benchmark app. It does not use coordinates or control other applications.");
        layout.addView(info);
        Button settings = new Button(this);
        settings.setText("Open Accessibility Settings");
        settings.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        layout.addView(settings);
        setContentView(layout);
    }
}

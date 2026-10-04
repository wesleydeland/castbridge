package com.castbridge;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 42;

    private static final int COL_BG = 0xFF0E1218;
    private static final int COL_SURFACE = 0xFF171C24;
    private static final int COL_SECONDARY = 0xFF232A34;
    private static final int COL_ACCENT = 0xFF5B9DFF;
    private static final int COL_STOP = 0xFFC9403F;
    private static final int COL_TEXT = 0xFFF2F5F8;
    private static final int COL_DIM = 0xFF8A93A0;
    private static final int COL_WHITE = 0xFFFFFFFF;

    private MediaProjectionManager mpm;
    private EditText hostField;
    private TextView status;
    private TextView go;
    private TextView muteBtn;
    private TextView volUpBtn;
    private TextView volDownBtn;
    private LinearLayout devicesBox;
    private AirplayDiscovery discovery;
    private SharedPreferences prefs;
    private boolean running = false;
    private float d;
    private TextView diagLine;
    private final android.os.Handler ui = new android.os.Handler(android.os.Looper.getMainLooper());

    private final Runnable diagTick = new Runnable() {
        @Override public void run() {
            if (diagLine != null) {
                if (CastService.isRunning()) {
                    long secs = CastService.diagFrames / Math.max(1, CastService.diagRate);
                    diagLine.setText("capture " + CastService.diagRate + " Hz \u00b7 peak "
                            + CastService.takePeak() + " \u00b7 err " + CastService.diagErrors
                            + " \u00b7 " + secs + "s sent");
                } else {
                    diagLine.setText("");
                }
            }
            ui.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        prefs = getSharedPreferences("cast", MODE_PRIVATE);
        d = getResources().getDisplayMetrics().density;

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 7);
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(COL_BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        final int pad = dp(22);
        root.setPadding(pad, pad, pad, pad);
        // Android 15+ draws edge-to-edge by default; inset the content so the
        // top of the screen isn't hidden under the status bar. (Base padding is
        // set above FIRST — attaching the listener dispatches current insets
        // immediately, and a later setPadding() would clobber them.)
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top;
            if (Build.VERSION.SDK_INT >= 30) {
                top = insets.getInsets(android.view.WindowInsets.Type.statusBars()).top;
            } else {
                @SuppressWarnings("deprecation")
                int legacy = insets.getSystemWindowInsetTop();
                top = legacy;
            }
            v.setPadding(pad, pad + top, pad, pad);
            return insets;
        });
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(text("CastBridge", 26, COL_TEXT, true));
        status = text("idle", 14, COL_DIM, false);
        status.setPadding(0, dp(4), 0, dp(20));
        root.addView(status);

        // --- device card ---
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(COL_SURFACE, 18));
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        root.addView(card, top(dp(0)));

        TextView label = text("SPEAKER IP", 11, COL_DIM, true);
        label.setLetterSpacing(0.12f);
        card.addView(label);

        hostField = new EditText(this);
        // Generic placeholder only — mDNS discovery is the normal path. Never a
        // real address here: this string ships inside every user's APK.
        hostField.setText(prefs.getString("host", ""));
        hostField.setTextSize(17);
        hostField.setTextColor(COL_TEXT);
        hostField.setSingleLine(true);
        hostField.setBackground(rounded(COL_BG, 12));
        hostField.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.addView(hostField, top(dp(8)));

        TextView find = button("Find speakers (mDNS)", COL_SECONDARY, COL_TEXT, 14, 12);
        find.setOnClickListener(v -> startDiscovery());
        card.addView(find, top(dp(10)));

        devicesBox = new LinearLayout(this);
        devicesBox.setOrientation(LinearLayout.VERTICAL);
        card.addView(devicesBox);

        // --- actions ---
        go = button("Start casting", COL_ACCENT, COL_WHITE, 17, 16);
        go.setOnClickListener(v -> {
            if (!running) {
                showDisclosureThenCapture();
            } else {
                stopService(new Intent(this, CastService.class));
                running = false;
                setRunningUi(false);
                status.setText("stopped");
            }
        });
        root.addView(go, top(dp(16)));

        muteBtn = button("Mute phone", COL_SECONDARY, COL_TEXT, 15, 14);
        muteBtn.setOnClickListener(v -> {
            boolean m = CastService.togglePhoneMute();
            muteBtn.setText(m ? "Unmute phone" : "Mute phone");
            status.setText(m ? "phone muted" : "phone unmuted");
        });
        root.addView(muteBtn, top(dp(10)));

        LinearLayout volRow = new LinearLayout(this);
        volRow.setOrientation(LinearLayout.HORIZONTAL);
        volDownBtn = button("Volume \u2212", COL_SECONDARY, COL_TEXT, 15, 14);
        volDownBtn.setOnClickListener(v -> {
            CastService.adjustRemoteVolume(-5);
            status.setText("volume: " + CastService.getRemoteVolume());
        });
        volUpBtn = button("Volume +", COL_SECONDARY, COL_TEXT, 15, 14);
        volUpBtn.setOnClickListener(v -> {
            CastService.adjustRemoteVolume(5);
            status.setText("volume: " + CastService.getRemoteVolume());
        });
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        hl.setMargins(0, dp(10), dp(5), 0);
        LinearLayout.LayoutParams hr = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        hr.setMargins(dp(5), dp(10), 0, 0);
        volRow.addView(volDownBtn, hl);
        volRow.addView(volUpBtn, hr);
        root.addView(volRow);

        TextView hint = text("While casting, the phone's volume keys control the speaker.",
                12, COL_DIM, false);
        hint.setPadding(0, dp(18), 0, 0);
        root.addView(hint);

        diagLine = text("", 12, COL_DIM, false);
        diagLine.setPadding(0, dp(10), 0, 0);
        root.addView(diagLine);
        ui.postDelayed(diagTick, 1000);

        TextView about = text("About & licenses", 13, COL_ACCENT, false);
        about.setPadding(0, dp(22), 0, 0);
        about.setOnClickListener(v -> showAbout());
        root.addView(about);

        setContentView(scroll);
    }

    // --- mDNS discovery ---------------------------------------------------

    private void startDiscovery() {
        if (discovery != null) return;
        devicesBox.removeAllViews();
        status.setText("searching for AirPlay devices\u2026");
        discovery = new AirplayDiscovery(this, new AirplayDiscovery.Listener() {
            @Override public void onDevice(String name, String host) {
                runOnUiThread(() -> addDeviceRow(name, host));
            }

            @Override public void onError(String message) {
                runOnUiThread(() -> status.setText("discovery error: " + message));
            }
        });
        discovery.start();
        status.postDelayed(this::stopDiscovery, 15000);
    }

    private void addDeviceRow(String name, String host) {
        TextView row = text(name + "  \u2014  " + host, 16, COL_ACCENT, true);
        row.setPadding(0, dp(14), 0, dp(2));
        row.setOnClickListener(v -> {
            hostField.setText(host);
            prefs.edit().putString("host", host).apply();
            status.setText("selected " + name + " (" + host + ")");
            stopDiscovery();
        });
        devicesBox.addView(row);
        int n = devicesBox.getChildCount();
        status.setText(n == 1 ? "found a speaker \u2014 tap it"
                : "found " + n + " speakers \u2014 tap one");
    }

    private void stopDiscovery() {
        if (discovery != null) {
            discovery.stop();
            discovery = null;
        }
    }

    // --- ui helpers --------------------------------------------------------

    private TextView text(String s, float sizeSp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sizeSp);
        t.setTextColor(color);
        if (bold) t.setTypeface(null, Typeface.BOLD);
        return t;
    }

    private void showDisclosureThenCapture() {
        new AlertDialog.Builder(this)
                .setTitle("Before we start")
                .setMessage("CastBridge captures the audio playing on this phone and streams it only to the "
                        + "speaker you pick, over your local Wi-Fi network.\n\nNothing is recorded, stored, "
                        + "or uploaded anywhere.")
                .setPositiveButton("Continue", (d, w) ->
                        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showAbout() {
        String version = "?";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        new AlertDialog.Builder(this)
                .setTitle("CastBridge " + version)
                .setMessage("Streams audio from this phone to AirPlay 2 speakers.\n\n"
                        + "Not affiliated with, or endorsed by, Apple Inc. \"AirPlay\" and \"HomePod\" are "
                        + "trademarks of Apple Inc., used only to describe compatibility.\n\n"
                        + "Open-source components:\n"
                        + "\u2022 airplay2-sender-cpp \u2014 Apache-2.0\n"
                        + "\u2022 mbedTLS \u2014 Apache-2.0\n"
                        + "\u2022 ed25519 (orlp) \u2014 zlib\n\n"
                        + "Audio is processed entirely on-device; nothing is collected.")
                .setPositiveButton("Close", null)
                .show();
    }

    private TextView button(String label, int bg, int fg, float sizeSp, int padV) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setTextSize(sizeSp);
        b.setTextColor(fg);
        b.setTypeface(null, Typeface.BOLD);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(16), dp(padV), dp(16), dp(padV));
        b.setBackground(rounded(bg, 14));
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    private GradientDrawable rounded(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private int dp(float v) {
        return (int) (v * d + 0.5f);
    }

    private LinearLayout.LayoutParams top(int margin) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, margin, 0, 0);
        return p;
    }

    private void setRunningUi(boolean on) {
        go.setText(on ? "Stop casting" : "Start casting");
        go.setBackground(rounded(on ? COL_STOP : COL_ACCENT, 14));
    }

    // --- lifecycle / callbacks ---------------------------------------------

    @Override
    protected void onResume() {
        super.onResume();
        muteBtn.setText(CastService.isPhoneMuted() ? "Unmute phone" : "Mute phone");
    }

    @Override
    protected void onDestroy() {
        stopDiscovery();
        super.onDestroy();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (running && (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            CastService.adjustRemoteVolume(keyCode == KeyEvent.KEYCODE_VOLUME_UP ? 5 : -5);
            status.setText("volume: " + CastService.getRemoteVolume());
            return true; // consume — don't change the phone's own volume
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CAPTURE && resultCode == RESULT_OK && data != null) {
            String host = hostField.getText().toString().trim();
            prefs.edit().putString("host", host).apply();
            Intent i = new Intent(this, CastService.class);
            i.putExtra("resultCode", resultCode);
            i.putExtra("data", data);
            i.putExtra("host", host);
            startForegroundService(i);
            running = true;
            setRunningUi(true);
            status.setText("casting to " + host);
        } else if (requestCode == REQ_CAPTURE) {
            status.setText("capture consent denied");
        }
    }
}

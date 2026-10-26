package org.caremonitor.demo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;

/** Authenticated server views. Bluetooth acquisition and the local demo remain separate. */
public final class AccountActivity extends Activity {
    private static final String[] TABS = {"Home", "Sensors", "Alerts", "Profile"};
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<Runnable> bindings = new ArrayList<>();
    private AccountApiClient api;
    private CarePalette palette;
    private boolean dark, active, loading, settings, networkOkay, authenticating, signingOut;
    private int tab, generation;
    private String selectedDevice = "", endpointDraft = "", usernameDraft = "";
    private String status = "Sign in to your server account.", error = "", classificationError = "", alertError = "";
    private JSONObject account, classification;
    private JSONArray readings = new JSONArray(), alerts = new JSONArray();
    private long readingsFetchedAt, classificationFetchedAt;
    private long alertRevision;
    private boolean alertsHaveMore, alertHistoryLimited;
    private LinearLayout root, content, alertsHost;
    private ScrollView scroll;
    private TextView connectionLabel, loginStatus;
    private Button deviceButton, refreshButton, loginButton;
    private EditText endpointInput, usernameInput, passwordInput;
    private String renderedAlerts = "";
    private Object backCallback;
    private AlertDialog activeDialog;
    private final Runnable poll = () -> { if (active && account != null && !settings && !loading) refresh(); };
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!active) return;
            if (account != null && !signingOut && !api.hasSession()) {
                expire("Your saved session has expired. Sign in again.");
            }
            refreshBindings();
            ui.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        dark = getSharedPreferences("care_monitor_local", MODE_PRIVATE).getBoolean("dark", false);
        palette = new CarePalette(dark);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        api = new AccountApiClient(this);
        endpointDraft = api.endpoint();
        if (endpointDraft == null || endpointDraft.isEmpty()) {
            endpointDraft = new RelaySecureSettings(this).savedEndpoint();
        }
        usernameDraft = api.username();
        if (saved != null) {
            selectedDevice = saved.getString("device", "");
            tab = Math.max(0, Math.min(3, saved.getInt("tab", 0)));
        }
        renderLogin();
        if (Build.VERSION.SDK_INT >= 33) {
            android.window.OnBackInvokedCallback callback = this::back;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
            backCallback = callback;
        }
    }

    @Override protected void onStart() {
        super.onStart(); active = true;
        if (account == null) renderLogin();
        ui.post(tick);
        // Saved role text is never enough to enter a protected screen: recheck /accounts/me.
        if (api.hasSession()) refresh();
    }

    @Override protected void onStop() {
        active = false; generation++; loading = false; authenticating = false; signingOut = false;
        ui.removeCallbacksAndMessages(null);
        if (passwordInput != null) passwordInput.setText("");
        account = null; clearData();
        if (activeDialog != null) { activeDialog.dismiss(); activeDialog = null; }
        bindings.clear(); alertsHost = null; connectionLabel = loginStatus = null;
        content = null; scroll = null;
        deviceButton = refreshButton = loginButton = null;
        endpointInput = usernameInput = passwordInput = null;
        // Remove protected values from the inactive view tree. The next start rechecks /me.
        if (root != null) {
            root.removeAllViews();
            label(root, "Care Monitor", 24, palette.text, true);
            label(root, "Session paused. Account access is checked again when you return.", 14, palette.muted, false);
        }
        super.onStop();
    }

    @Override protected void onDestroy() {
        api.close(); ui.removeCallbacksAndMessages(null);
        if (Build.VERSION.SDK_INT >= 33 && backCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback((android.window.OnBackInvokedCallback) backCallback);
        }
        super.onDestroy();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        out.putString("device", selectedDevice); out.putInt("tab", tab);
        // No password, token, patient data or response body is placed in the Activity state.
        super.onSaveInstanceState(out);
    }

    private void renderLogin() {
        bindings.clear(); settings = false; alertsHost = null; deviceButton = null;
        base("Server account", false);
        label(content, "Care that stays connected", 27, palette.text, true);
        label(content, "Sign in with the account created by your server administrator. Your role and assigned devices come from the server.", 14, palette.muted, false);
        LinearLayout form = card(content);
        endpointInput = field(form, "HTTPS server address", "https://your-server:8000", false);
        endpointInput.setText(endpointDraft == null ? "" : endpointDraft);
        usernameInput = field(form, "Username", "Server account username", false);
        usernameInput.setText(usernameDraft == null ? "" : usernameDraft);
        passwordInput = field(form, "Password", "Account password", true);
        CheckBox show = new CheckBox(this); show.setText("Show password"); show.setTextColor(palette.muted);
        show.setOnCheckedChangeListener((button, checked) -> {
            passwordInput.setInputType(InputType.TYPE_CLASS_TEXT | (checked
                ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD : InputType.TYPE_TEXT_VARIATION_PASSWORD));
            passwordInput.setSelection(passwordInput.length());
        }); form.addView(show);
        loginStatus = label(form, error.isEmpty() ? status : error, 13, error.isEmpty() ? palette.muted : palette.danger, false);
        loginStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        loginButton = action(form, "Sign in", true, v -> login());
        if (api.hasSession()) action(form, "Retry saved session", false, v -> refresh());
        action(content, "Back to Bluetooth / local demo", false, v -> finish());
        label(content, "No public registration or role selector is offered. A server administrator creates accounts and assignments. The local demo does not authenticate a server account.", 12, palette.muted, false);
        refreshBindings();
    }

    private void login() {
        if (loading || authenticating) return;
        endpointDraft = endpointInput.getText().toString().trim();
        usernameDraft = usernameInput.getText().toString().trim();
        String password = passwordInput.getText().toString();
        if (endpointDraft.isEmpty()) { endpointInput.setError("Enter your HTTPS server address."); return; }
        if (usernameDraft.isEmpty()) { usernameInput.setError("Enter your username."); return; }
        if (password.isEmpty()) { passwordInput.setError("Enter your password."); return; }
        hideKeyboard(); passwordInput.setText("");
        authenticating = true; error = ""; status = "Signing in securely…"; refreshBindings();
        final int current = ++generation;
        api.login(endpointDraft, usernameDraft, password, new AccountApiClient.Callback() {
            @Override public void onSuccess(JSONObject result) {
                if (!valid(current)) return;
                authenticating = false; endpointDraft = api.endpoint();
                account = null; clearData(); refresh();
            }
            @Override public void onError(int code, String message) {
                if (!valid(current)) return;
                authenticating = false; loading = false; error = message; status = "Sign-in unsuccessful.";
                refreshBindings();
            }
        });
    }

    private void refresh() {
        if (!active || loading || authenticating) return;
        ui.removeCallbacks(poll);
        if (!api.hasSession()) { expire("Sign in again to view your assigned readings."); return; }
        loading = true; error = ""; status = "Checking account and assigned devices…"; refreshBindings();
        final int current = ++generation;
        api.me(new AccountApiClient.Callback() {
            @Override public void onSuccess(JSONObject result) {
                if (!valid(current)) return;
                String role = result.optString("role", "");
                if (!("patient".equals(role) || "caregiver".equals(role))) {
                    expire("This account role is not supported. Contact the server administrator."); return;
                }
                boolean first = account == null;
                String previousRole = account == null ? "" : account.optString("role", "");
                String previousPatient = selectedPatient(account, selectedDevice);
                account = result;
                JSONArray devices = account.optJSONArray("devices");
                String next = assigned(selectedDevice) ? selectedDevice : firstDevice(devices);
                boolean changed = !next.equals(selectedDevice) || !previousRole.equals(role)
                    || !previousPatient.equals(selectedPatient(account, next));
                if (changed) { selectedDevice = next; clearData(); }
                networkOkay = true;
                if (first || changed) renderShell();
                if (selectedDevice.isEmpty()) { finishRefresh(current, "No device is assigned to this account."); return; }
                status = "Loading stored readings…"; refreshBindings();
                loadReadings(current, selectedDevice);
            }
            @Override public void onError(int code, String message) { failed(current, code, message); }
        });
    }

    private void loadReadings(final int current, final String device) {
        final long requestedAt = SystemClock.elapsedRealtime();
        api.readings(device, 100, new AccountApiClient.Callback() {
            @Override public void onSuccess(JSONObject result) {
                if (!valid(current) || !device.equals(selectedDevice)) return;
                if (!device.equals(result.optString("device_id", ""))) { failed(current, 0, "Server response did not match the selected device."); return; }
                readings = result.optJSONArray("readings"); if (readings == null) readings = new JSONArray();
                // Include request transit time conservatively when ageing server freshness.
                readingsFetchedAt = requestedAt; classification = null; classificationError = "";
                long readingId = latest().optLong("id", 0);
                refreshBindings();
                if (readingId <= 0) { classificationError = "No stored reading is available."; loadAlerts(current, device); return; }
                final long modelRequestedAt = SystemClock.elapsedRealtime();
                api.classification(device, readingId, new AccountApiClient.Callback() {
                    @Override public void onSuccess(JSONObject result) {
                        if (!valid(current) || !device.equals(selectedDevice)) return;
                        if (device.equals(result.optString("device_id", "")) && readingId == result.optLong("reading_id", -1)) {
                            classification = result; classificationFetchedAt = modelRequestedAt;
                        } else classificationError = "Model response did not match the selected reading.";
                        refreshBindings(); loadAlerts(current, device);
                    }
                    @Override public void onError(int code, String message) {
                        if (!valid(current)) return;
                        if (code == 401 || code == 403) { failed(current, code, message); return; }
                        classification = null; classificationError = message; refreshBindings(); loadAlerts(current, device);
                    }
                });
            }
            @Override public void onError(int code, String message) { failed(current, code, message); }
        });
    }

    private void loadAlerts(final int current, final String device) {
        api.alerts(device, alertRevision, new AccountApiClient.Callback() {
            @Override public void onSuccess(JSONObject result) {
                if (!valid(current) || !device.equals(selectedDevice)) return;
                if (!device.equals(result.optString("device_id", ""))) { failed(current, 0, "Alert response did not match the selected device."); return; }
                mergeAlerts(result.optJSONArray("alerts"));
                alertRevision = Math.max(alertRevision, result.optLong("next_revision", alertRevision));
                alertsHaveMore = result.optBoolean("has_more", false); alertError = "";
                finishRefresh(current, "Updated " + new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()));
            }
            @Override public void onError(int code, String message) {
                if (!valid(current)) return;
                if (code == 401 || code == 403) { failed(current, code, message); return; }
                alertError = message; finishRefresh(current, "Readings refreshed; alerts could not be refreshed.");
            }
        });
    }

    private void mergeAlerts(JSONArray next) {
        LinkedHashMap<Long, JSONObject> merged = new LinkedHashMap<>();
        for (int i = 0; i < alerts.length(); i++) {
            JSONObject event = alerts.optJSONObject(i);
            if (event != null && event.optLong("id", 0) > 0) merged.put(event.optLong("id"), event);
        }
        if (next != null) for (int i = 0; i < next.length(); i++) {
            JSONObject event = next.optJSONObject(i);
            if (event == null || event.optLong("id", 0) <= 0) continue;
            long id = event.optLong("id");
            JSONObject previous = merged.get(id);
            if (previous == null || event.optLong("revision", 0) >= previous.optLong("revision", 0)) {
                merged.remove(id); merged.put(id, event);
            }
        }
        while (merged.size() > 500) { merged.remove(merged.keySet().iterator().next()); alertHistoryLimited = true; }
        alerts = new JSONArray(); for (JSONObject event : merged.values()) alerts.put(event);
    }

    private void moreAlerts() {
        if (loading || !alertsHaveMore || selectedDevice.isEmpty()) return;
        ui.removeCallbacks(poll); loading = true; status = "Loading more server alerts…"; refreshBindings();
        loadAlerts(++generation, selectedDevice);
    }

    private void finishRefresh(int current, String message) {
        if (!valid(current)) return;
        loading = false; networkOkay = true; status = message; refreshBindings(); updateAlerts();
        if (account != null && !settings) ui.postDelayed(poll, 5000);
    }

    private void failed(int current, int code, String message) {
        if (!valid(current)) return;
        loading = false; networkOkay = false; classification = null; error = message;
        if (code == 401) { expire("Your session expired or was revoked. Sign in again."); return; }
        if (code == 403) {
            clearData(); account = null; selectedDevice = "";
            error = "Access changed. Retry your saved session to reload assigned devices."; renderLogin(); return;
        }
        status = "Server unavailable. Stored values below may be old.";
        refreshBindings();
        if (account != null && !settings) ui.postDelayed(poll, 10000);
    }

    private void expire(String message) {
        generation++; loading = authenticating = false; api.forgetSession();
        account = null; clearData(); selectedDevice = ""; error = message;
        ui.removeCallbacks(poll); renderLogin();
    }

    private boolean valid(int value) { return active && value == generation && !isFinishing(); }
    private void clearData() {
        readings = new JSONArray(); alerts = new JSONArray(); classification = null;
        readingsFetchedAt = classificationFetchedAt = 0; classificationError = alertError = ""; renderedAlerts = "";
        alertRevision = 0; alertsHaveMore = alertHistoryLimited = false;
    }

    private void renderShell() {
        bindings.clear(); alertsHost = null; loginStatus = null; loginButton = null;
        endpointInput = usernameInput = passwordInput = null;
        base(settings ? "Account settings" : "Care Monitor", true);
        if (settings) settingsContent();
        else {
            deviceButton = action(content, deviceCaption(), false, v -> chooseDevice());
            label(content, "Server account · " + roleCaption(), 12, palette.primary, true);
            connectionLabel = label(content, "", 12, palette.muted, false);
            refreshButton = action(content, "Refresh server data", false, v -> refresh());
            if (tab == 0) home(); else if (tab == 1) sensors(); else if (tab == 2) alertContent(); else profile();
            navigation();
        }
        refreshBindings(); updateAlerts();
    }

    private void home() {
        LinearLayout summary = card(content);
        heading(summary, R.drawable.ic_nav_overview, "Latest stored reading", palette.primary);
        bind(summary, () -> selectedDevice.isEmpty() ? "No assigned device" : "Device ID: " + selectedDevice, 14, palette.text, true);
        bind(summary, () -> latest().optLong("id", 0) > 0 ? "Reading ID: " + latest().optLong("id") : "Reading ID: Unavailable", 12, palette.muted, false);
        bind(summary, () -> "Recorded: " + timestamp(latest().optString("measurement_at", "")), 12, palette.muted, false);
        bind(summary, () -> "Received: " + timestamp(latest().optString("received_at", "")), 12, palette.muted, false);
        bind(summary, this::freshnessCaption, 13, palette.muted, true);
        label(summary, "Server readings remain available when this phone is disconnected from Bluetooth. A recent server record does not prove the sensors are accurate.", 12, palette.muted, false);
        opticalCard("Heart rate", "heart_rate", "bpm", palette.heart, R.drawable.ic_heart);
        opticalCard("Blood oxygen", "oxygen", "%", palette.oxygen, R.drawable.ic_oxygen);
        LinearLayout review = card(content);
        heading(review, R.drawable.ic_nav_alerts, "Server alerts", palette.primary);
        bind(review, () -> alerts.length() + " returned events · " + (caregiver() ? "Caregiver review available" : "Read-only patient view"), 14, palette.text, false);
        action(review, "View alerts", false, v -> navigate(2));
        label(content, "Model output supports a review of the data. It is not a medical diagnosis or an automatically assigned clinical severity.", 12, palette.muted, false);
    }

    private void opticalCard(String title, String channel, String unit, int color, int icon) {
        LinearLayout card = card(content); heading(card, icon, title, color);
        bind(card, () -> measurement(optical(data(latest()), channel), unit), 31, color, true);
        bind(card, () -> "Sensor status: " + object(data(latest()), "max30102").optString("status", "unavailable"), 12, palette.muted, false);
        bind(card, () -> "Classification: " + classificationCaption(channel), 17, palette.text, true);
        bind(card, () -> confidenceCaption(channel), 13, palette.muted, false);
        bind(card, this::classificationSource, 12, palette.muted, false);
        bind(card, () -> classificationDetail(channel), 12, palette.muted, false);
        SensorChartView chart = SensorChartView.line(this, series(channel), color, unit, palette.text, palette.muted, palette.border)
            .timeline("Older", "Record order", "Latest");
        card.addView(chart, new LinearLayout.LayoutParams(-1, -2));
        bindings.add(() -> chart.updateSeries(series(channel)));
        bind(card, this::historyCaption, 11, palette.muted, false);
    }

    private void sensors() {
        label(content, "Stored sensor history", 24, palette.text, true);
        label(content, "Up to 100 records, ordered by server receipt. Gaps mean unavailable sensor values; points are not interpolated across missing values.", 12, palette.muted, false);
        opticalCard("Heart rate", "heart_rate", "bpm", palette.heart, R.drawable.ic_heart);
        opticalCard("Blood oxygen", "oxygen", "%", palette.oxygen, R.drawable.ic_oxygen);
        chartCard("Surface temperature", "surface", "°C", palette.surfaceTemp, R.drawable.ic_temperature);
        chartCard("Ambient temperature", "ambient", "°C", palette.ambientTemp, R.drawable.ic_temperature);
        label(content, "Surface temperature is not core body temperature. It is not submitted to the body-temperature model.", 12, palette.muted, false);
        chartCard("Acceleration X", "accel_x", "g", palette.heart, R.drawable.ic_motion);
        chartCard("Acceleration Y", "accel_y", "g", palette.oxygen, R.drawable.ic_motion);
        chartCard("Acceleration Z", "accel_z", "g", palette.primary, R.drawable.ic_motion);
        chartCard("Light", "light", "%", palette.warning, R.drawable.ic_light);
    }

    private void chartCard(String title, String channel, String unit, int color, int icon) {
        LinearLayout card = card(content); heading(card, icon, title, color);
        bind(card, () -> measurement(sensorValue(data(latest()), channel), unit), 27, color, true);
        SensorChartView chart = SensorChartView.line(this, series(channel), color, unit, palette.text, palette.muted, palette.border)
            .timeline("Older", "Record order", "Latest");
        card.addView(chart, new LinearLayout.LayoutParams(-1, -2)); bindings.add(() -> chart.updateSeries(series(channel)));
        bind(card, this::historyCaption, 11, palette.muted, false);
    }

    private void alertContent() {
        label(content, "Server alert history", 24, palette.text, true);
        label(content, caregiver() ? "Review alerts for assigned patients. Acknowledgement records review; it does not resolve the underlying condition."
            : "You can view your alerts. Acknowledgement is reserved for an assigned caregiver.", 13, palette.muted, false);
        alertsHost = vertical(); content.addView(alertsHost, new LinearLayout.LayoutParams(-1, -2)); renderedAlerts = "";
        label(content, "These are authenticated server events. Push notification delivery is not configured. Model classifications are displayed separately.", 12, palette.muted, false);
    }

    private void updateAlerts() {
        if (alertsHost == null || account == null) return;
        String key = alerts.toString() + alertError + caregiver() + alertsHaveMore + alertHistoryLimited;
        if (key.equals(renderedAlerts)) return;
        renderedAlerts = key; alertsHost.removeAllViews();
        if (!alertError.isEmpty()) label(alertsHost, "Alert refresh: " + alertError, 13, palette.danger, false);
        if (alertHistoryLimited) label(alertsHost, "Showing the 500 most recently loaded or updated events. Earlier history remains on the server.", 12, palette.muted, false);
        if (alertsHaveMore) action(alertsHost, "Load more alerts", false, v -> moreAlerts());
        if (alerts.length() == 0) {
            label(alertsHost, loading ? "Loading alerts…" : "No returned alert events for this device.", 15, palette.muted, false); return;
        }
        for (int i = alerts.length() - 1; i >= 0; i--) {
            JSONObject event = alerts.optJSONObject(i); if (event == null) continue;
            LinearLayout card = card(alertsHost);
            label(card, readable(event.optString("type", "Alert")), 18, palette.text, true);
            label(card, "Severity: " + readable(event.optString("severity", "unavailable")) + " · Status: " + readable(event.optString("status", "unavailable")), 13, palette.muted, false);
            label(card, "Occurred: " + timestamp(event.optString("occurred_at", "")), 12, palette.muted, false);
            String reading = event.optString("reading", ""); if (!reading.isEmpty()) label(card, reading, 15, palette.text, false);
            label(card, "Source: " + readable(event.optString("source", "unavailable")), 12, palette.muted, false);
            final long id = event.optLong("id", 0);
            if (caregiver() && id > 0 && "active".equals(event.optString("status", ""))) {
                action(card, "Acknowledge review", false, v -> acknowledge(id));
            }
        }
    }

    private void acknowledge(final long alertId) {
        if (!caregiver() || loading || selectedDevice.isEmpty()) return;
        activeDialog = new AlertDialog.Builder(themed()).setTitle("Acknowledge this alert?")
            .setMessage("This records that you reviewed the event. It does not resolve the event or confirm that assistance was delivered.")
            .setNegativeButton("Cancel", null).setPositiveButton("Acknowledge", (dialog, which) -> {
                if (!caregiver() || loading) return;
                ui.removeCallbacks(poll); loading = true; status = "Recording caregiver acknowledgement…"; refreshBindings();
                final int current = ++generation;
                api.acknowledge(selectedDevice, alertId, new AccountApiClient.Callback() {
                    @Override public void onSuccess(JSONObject body) { if (valid(current)) { loading = false; refresh(); } }
                    @Override public void onError(int code, String message) { failed(current, code, message); }
                });
            }).show();
    }

    private void profile() {
        LinearLayout card = card(content); heading(card, R.drawable.ic_nav_profile, "Account profile", palette.primary);
        label(card, account.optString("username", ""), 24, palette.text, true);
        label(card, "Role: " + roleCaption(), 16, palette.primary, true);
        label(card, caregiver() ? "Assigned patients and devices are managed by the server administrator."
            : "Only devices assigned to your patient record are available in this account.", 13, palette.muted, false);
        JSONArray devices = account.optJSONArray("devices");
        if (devices != null) for (int i = 0; i < devices.length(); i++) {
            JSONObject device = devices.optJSONObject(i); if (device == null) continue;
            label(card, "Device: " + device.optString("device_id", "") + "\nPatient ID: " + device.optString("patient_id", "Unavailable"), 14, palette.text, false);
        }
        action(content, "Account settings", false, v -> openSettings());
        action(content, "Back to Bluetooth monitor", false, v -> finish());
    }

    private void settingsContent() {
        LinearLayout card = card(content); heading(card, R.drawable.ic_nav_profile, "Signed-in account", palette.primary);
        label(card, account.optString("username", "") + " · " + roleCaption(), 18, palette.text, true);
        label(card, "HTTPS server", 13, palette.muted, true); label(card, api.endpoint(), 14, palette.text, false);
        label(card, "To change servers or accounts, sign out first. Bluetooth upload credentials are separate from your account session.", 13, palette.muted, false);
        connectionLabel = label(card, "", 12, palette.muted, false);
        action(card, "Sign out of server account", true, v -> signOut());
        action(content, "Back to Bluetooth / local settings", false, v -> finish());
        label(content, "The account password is not retained. Signing out clears this account's saved session and displayed data; it does not stop Bluetooth uploads.", 12, palette.muted, false);
    }

    private void signOut() {
        if (loading || authenticating) return;
        ui.removeCallbacks(poll); loading = true; signingOut = true; status = "Signing out…"; refreshBindings();
        final int current = ++generation;
        api.logout(new AccountApiClient.Callback() {
            @Override public void onSuccess(JSONObject body) { finishSignOut(current, "Signed out."); }
            @Override public void onError(int code, String message) {
                finishSignOut(current, "Signed out on this phone. The server could not confirm session revocation; the remote session will expire.");
            }
        });
    }

    private void finishSignOut(int current, String message) {
        if (!valid(current)) return;
        if (!api.sessionRemovalConfirmed()) {
            message = "Account view closed, but saved-session removal could not be confirmed. Check phone storage and ask the server administrator to revoke this session.";
        }
        account = null; clearData(); loading = false; signingOut = false; settings = false; selectedDevice = ""; error = ""; status = message;
        renderLogin();
    }

    private void chooseDevice() {
        JSONArray devices = account == null ? null : account.optJSONArray("devices");
        if (devices == null || devices.length() == 0) return;
        String[] choices = new String[devices.length()];
        for (int i = 0; i < choices.length; i++) {
            JSONObject device = devices.optJSONObject(i);
            choices[i] = device == null ? "Unavailable" : device.optString("device_id", "") + " · " + device.optString("patient_id", "");
        }
        activeDialog = new AlertDialog.Builder(themed()).setTitle(caregiver() ? "Assigned patient devices" : "Your devices")
            .setItems(choices, (dialog, which) -> {
                JSONObject device = devices.optJSONObject(which); if (device == null) return;
                String id = device.optString("device_id", ""); if (id.isEmpty() || id.equals(selectedDevice)) return;
                generation++; loading = false; selectedDevice = id; clearData(); renderShell(); refresh();
            }).setNegativeButton("Cancel", null).show();
    }

    private void navigate(int target) { tab = target; settings = false; renderShell(); }
    private void openSettings() { settings = true; ui.removeCallbacks(poll); renderShell(); }
    private void back() { if (settings && account != null) { settings = false; renderShell(); refresh(); } else finish(); }
    @Override public void onBackPressed() { if (Build.VERSION.SDK_INT < 33) back(); else super.onBackPressed(); }

    private void refreshBindings() {
        if (connectionLabel != null) connectionLabel.setText(status + (error.isEmpty() ? "" : "\n" + error));
        if (refreshButton != null) { refreshButton.setEnabled(!loading); refreshButton.setText(loading ? "Refreshing…" : "Refresh server data"); }
        if (deviceButton != null) deviceButton.setText(deviceCaption());
        if (loginStatus != null) { loginStatus.setText(error.isEmpty() ? status : error); loginStatus.setTextColor(error.isEmpty() ? palette.muted : palette.danger); }
        if (loginButton != null) { loginButton.setEnabled(!loading && !authenticating); loginButton.setText(authenticating ? "Signing in…" : "Sign in"); }
        for (Runnable binding : bindings) binding.run();
    }

    private boolean caregiver() { return account != null && "caregiver".equals(account.optString("role", "")) && account.optBoolean("can_acknowledge_alerts", false); }
    private String roleCaption() { return account == null ? "Not signed in" : readable(account.optString("role", "unavailable")); }
    private boolean assigned(String id) {
        JSONArray devices = account == null ? null : account.optJSONArray("devices");
        if (id == null || id.isEmpty() || devices == null) return false;
        for (int i = 0; i < devices.length(); i++) if (id.equals(object(devices.optJSONObject(i)).optString("device_id", ""))) return true;
        return false;
    }
    private static String firstDevice(JSONArray devices) { return devices == null || devices.length() == 0 ? "" : object(devices.optJSONObject(0)).optString("device_id", ""); }
    private static String selectedPatient(JSONObject account, String deviceId) {
        JSONArray devices = account == null ? null : account.optJSONArray("devices");
        if (devices != null) for (int i = 0; i < devices.length(); i++) {
            JSONObject device = object(devices.optJSONObject(i));
            if (deviceId.equals(device.optString("device_id", ""))) return device.optString("patient_id", "");
        }
        return "";
    }
    private String deviceCaption() { return selectedDevice.isEmpty() ? "No assigned device" : "Device: " + selectedDevice + "  ▾"; }
    private JSONObject latest() { return readings.length() == 0 ? new JSONObject() : object(readings.optJSONObject(0)); }
    private static JSONObject data(JSONObject row) { return object(row, "data"); }
    private static JSONObject object(JSONObject value) { return value == null ? new JSONObject() : value; }
    private static JSONObject object(JSONObject parent, String key) { return parent == null ? new JSONObject() : object(parent.optJSONObject(key)); }

    private boolean fresh(JSONObject metadata, long fetchedAt) {
        if (!networkOkay || fetchedAt == 0 || !"fresh".equals(metadata.optString("status", ""))) return false;
        Double age = number(metadata.opt("age_seconds")), limit = number(metadata.opt("freshness_seconds"));
        double elapsed = Math.max(0, SystemClock.elapsedRealtime() - fetchedAt) / 1000.0;
        return age != null && limit != null && age >= 0 && limit > 0 && age + elapsed <= limit;
    }
    private String freshnessCaption() {
        if (readings.length() == 0) return loading ? "Loading stored readings…" : "No stored readings available";
        if (!networkOkay) return "Connection unavailable · last stored data only";
        JSONObject metadata = object(latest(), "freshness");
        if (fresh(metadata, readingsFetchedAt)) return "Recent timestamp · check each sensor's availability";
        String reason = metadata.optString("reason", "freshness unavailable");
        return "Stored history · " + ("fresh".equals(metadata.optString("status", "")) ? "Freshness window elapsed" : readable(reason));
    }
    private JSONObject model(String channel) { return object(object(classification, "results"), channel); }
    private boolean modelAvailable(String channel) {
        return classification != null && fresh(object(classification, "freshness"), classificationFetchedAt)
            && "available".equals(model(channel).optString("status", ""))
            && classification.optLong("reading_id", -1) == latest().optLong("id", -2)
            && !"Unavailable".equals(AccountPresentationPolicy.label(model(channel).optString("classification", "")));
    }
    private String classificationCaption(String channel) {
        if (!modelAvailable(channel)) return loading && classification == null ? "Loading…" : "Unavailable";
        String value = model(channel).optString("classification", "");
        // Display only the class actually supplied by the server; binary ABNORMAL has no severity.
        if ("ABNORMAL".equals(value)) return "Abnormal (binary model)";
        return AccountPresentationPolicy.label(value);
    }
    private String confidenceCaption(String channel) {
        Double value = modelAvailable(channel) ? number(model(channel).opt("confidence")) : null;
        return "Model confidence: " + AccountPresentationPolicy.confidence(value);
    }
    private String classificationSource() {
        if (classification == null) return "Model source: waiting for server metadata.";
        return classification.optBoolean("synthetic_training", false)
            ? "Model source: experimental classifier trained on synthetic data."
            : "Model source: training-data provenance is not specified by this response.";
    }
    private String classificationDetail(String channel) {
        if (!classificationError.isEmpty()) return classificationError;
        if (!networkOkay) return "No current model result while the server is unavailable.";
        if (classification == null) return loading ? "Waiting for the server model response." : "No model response is available.";
        if (!fresh(object(classification, "freshness"), classificationFetchedAt)) return "The selected reading is stale or has no verified measurement time. Refresh to request a new result.";
        if (!"available".equals(model(channel).optString("status", ""))) return readable(model(channel).optString("reason", "Model output unavailable"));
        return "Uncalibrated training-leaf class proportion; not a probability of disease."
            + (classification.optBoolean("three_class_supported", false) ? "" : " Current binary labels do not distinguish Warning from Critical.");
    }

    private double[] series(String channel) {
        double[] result = new double[readings.length()];
        for (int i = 0; i < readings.length(); i++) {
            JSONObject row = readings.optJSONObject(readings.length() - 1 - i);
            Double value = sensorValue(data(object(row)), channel); result[i] = value == null ? Double.NaN : value;
        }
        return result;
    }
    private Double sensorValue(JSONObject snapshot, String channel) {
        if ("heart_rate".equals(channel) || "oxygen".equals(channel)) return optical(snapshot, channel);
        if ("surface".equals(channel) || "ambient".equals(channel)) {
            JSONObject sensor = object(snapshot, "gy906");
            return Boolean.TRUE.equals(sensor.opt("online")) ? number(sensor.opt("surface".equals(channel) ? "object_temp_c" : "ambient_temp_c")) : null;
        }
        if (channel.startsWith("accel_")) {
            JSONObject sensor = object(snapshot, "mpu6050");
            return Boolean.TRUE.equals(sensor.opt("online")) ? number(object(sensor, "accel_g").opt(channel.substring(6))) : null;
        }
        return "light".equals(channel) ? number(object(snapshot, "photoresistor").opt("percent")) : null;
    }
    private static Double optical(JSONObject snapshot, String channel) {
        JSONObject max = object(snapshot, "max30102");
        if (max.has("online") && !Boolean.TRUE.equals(max.opt("online"))) return null;
        Object state = max.opt("status"), online = max.opt("online"), finger = max.opt("finger_detected");
        return "heart_rate".equals(channel)
            ? OpticalEstimatePolicy.heartRate(state, online, finger, max.opt("heart_rate_valid"), max.opt("heart_rate_bpm"), max.has("sample_age_ms"), max.opt("sample_age_ms"), max.has("result_age_ms"), max.opt("result_age_ms"))
            : OpticalEstimatePolicy.oxygen(state, online, finger, max.opt("spo2_valid"), max.opt("spo2_percent"), max.has("sample_age_ms"), max.opt("sample_age_ms"), max.has("result_age_ms"), max.opt("result_age_ms"));
    }
    private String historyCaption() {
        if (readings.length() == 0) return "No history returned.";
        return readings.length() + " stored records\nFrom " + timestamp(object(readings.optJSONObject(readings.length() - 1)).optString("received_at", ""))
            + "\nTo " + timestamp(latest().optString("received_at", ""));
    }
    private static Double number(Object value) { if (!(value instanceof Number)) return null; double n = ((Number) value).doubleValue(); return Double.isFinite(n) ? n : null; }
    private static String measurement(Double value, String unit) { return value == null ? "Unavailable" : String.format(Locale.US, "%s %s", "g".equals(unit) ? String.format(Locale.US, "%.3f", value) : String.format(Locale.US, "%.1f", value), unit); }
    private static String timestamp(String value) { return value == null || value.isEmpty() || "null".equals(value) ? "Unavailable" : value.replace("T", " ").replace("Z", " UTC"); }
    private static String readable(String value) { if (value == null || value.isEmpty() || "null".equals(value)) return "Unavailable"; String text = value.replace('_', ' '); return Character.toUpperCase(text.charAt(0)) + text.substring(1); }

    private void base(String title, boolean menu) {
        connectionLabel = null; refreshButton = null;
        root = vertical(); root.setBackgroundColor(palette.background);
        getWindow().setStatusBarColor(palette.background); getWindow().setNavigationBarColor(palette.surface);
        if (Build.VERSION.SDK_INT >= 30 && getWindow().getInsetsController() != null) {
            getWindow().getInsetsController().setSystemBarsAppearance(dark ? 0 : android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
        }
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.ime());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        LinearLayout header = horizontal(); header.setGravity(Gravity.CENTER_VERTICAL); header.setPadding(dp(8), dp(5), dp(8), dp(5));
        header.addView(iconButton(R.drawable.ic_back, "Back", v -> back()));
        TextView heading = text(title, 21, palette.text, true); header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        if (menu) header.addView(iconButton(R.drawable.ic_more, "Account options", this::menu));
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));
        scroll = new ScrollView(this); scroll.setFillViewport(true); content = vertical(); content.setPadding(dp(16), dp(10), dp(16), dp(22));
        scroll.addView(content); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1)); setContentView(root);
        root.requestApplyInsets();
    }
    private void menu(View anchor) {
        PopupMenu menu = new PopupMenu(themed(), anchor); menu.getMenu().add("Account settings"); menu.getMenu().add("Bluetooth monitor");
        menu.setOnMenuItemClickListener(item -> { if ("Account settings".contentEquals(item.getTitle())) openSettings(); else finish(); return true; }); menu.show();
    }
    private void navigation() {
        int[] icons = {R.drawable.ic_nav_overview, R.drawable.ic_nav_sensors, R.drawable.ic_nav_alerts, R.drawable.ic_nav_profile};
        LinearLayout bar = horizontal(); bar.setBackgroundColor(palette.surface); bar.setPadding(dp(4), dp(5), dp(4), dp(5));
        for (int i = 0; i < TABS.length; i++) {
            final int destination = i; LinearLayout item = vertical(); item.setGravity(Gravity.CENTER); item.setMinimumHeight(dp(64)); item.setPadding(dp(3), dp(7), dp(3), dp(7));
            item.setBackground(shape(i == tab ? palette.soft : palette.surface, 16, Color.TRANSPARENT));
            item.setContentDescription(TABS[i]); item.setFocusable(true); item.setSelected(i == tab);
            buttonSemantics(item);
            item.addView(icon(icons[i], i == tab ? palette.primary : palette.muted), new LinearLayout.LayoutParams(dp(28), dp(28)));
            TextView caption = text(TABS[i], 12, i == tab ? palette.primary : palette.muted, i == tab); caption.setGravity(Gravity.CENTER); item.addView(caption);
            item.setOnClickListener(v -> navigate(destination)); bar.addView(item, new LinearLayout.LayoutParams(0, -2, 1));
        } root.addView(bar, new LinearLayout.LayoutParams(-1, -2));
    }
    private void bind(LinearLayout parent, java.util.function.Supplier<String> value, float size, int color, boolean bold) {
        TextView text = label(parent, "", size, color, bold);
        Runnable update = () -> { String next = value.get(); if (!next.contentEquals(text.getText())) text.setText(next); }; bindings.add(update); update.run();
    }
    private LinearLayout card(LinearLayout parent) { LinearLayout card = vertical(); card.setPadding(dp(16), dp(15), dp(16), dp(15)); card.setBackground(shape(palette.surface, 22, palette.border)); LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.setMargins(0, dp(6), 0, dp(12)); parent.addView(card, params); return card; }
    private void heading(LinearLayout parent, int drawable, String title, int color) { LinearLayout row = horizontal(); row.setGravity(Gravity.CENTER_VERTICAL); row.addView(icon(drawable, color), new LinearLayout.LayoutParams(dp(25), dp(25))); TextView text = text(title, 16, palette.text, true); text.setPadding(dp(8), 0, 0, 0); row.addView(text, new LinearLayout.LayoutParams(0, -2, 1)); parent.addView(row); }
    private EditText field(LinearLayout parent, String title, String hint, boolean password) {
        label(parent, title, 13, palette.text, true); EditText input = new EditText(this); input.setTextColor(palette.text); input.setHintTextColor(palette.muted); input.setTextSize(16); input.setHint(hint); input.setSingleLine(true); input.setSaveEnabled(false); input.setFreezesText(false); input.setContentDescription(title);
        input.setInputType(InputType.TYPE_CLASS_TEXT | (password ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD));
        if (Build.VERSION.SDK_INT >= 26) input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        input.setPadding(dp(13), dp(12), dp(13), dp(12)); input.setBackground(shape(palette.surface, 12, palette.border)); LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.setMargins(0, dp(4), 0, dp(12)); parent.addView(input, params); return input;
    }
    private Button action(LinearLayout parent, String title, boolean filled, View.OnClickListener listener) {
        Button button = new Button(this); button.setText(title); button.setAllCaps(false); button.setTextSize(14); button.setMinHeight(dp(48)); button.setMinimumHeight(dp(48)); button.setTextColor(filled ? palette.primaryText : palette.primary); button.setPadding(dp(12), dp(10), dp(12), dp(10));
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(Color.argb(35, 18, 108, 103)), shape(filled ? palette.primary : palette.surface, 14, filled ? Color.TRANSPARENT : palette.border), null)); button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.setMargins(0, dp(6), 0, dp(6)); parent.addView(button, params); return button;
    }
    private ImageView icon(int resource, int color) { ImageView image = new ImageView(this); image.setImageResource(resource); image.setImageTintList(ColorStateList.valueOf(color)); image.setScaleType(ImageView.ScaleType.CENTER_INSIDE); image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); return image; }
    private View iconButton(int resource, String description, View.OnClickListener listener) { FrameLayout frame = new FrameLayout(this); frame.setContentDescription(description); frame.setFocusable(true); buttonSemantics(frame); frame.setMinimumWidth(dp(48)); frame.setMinimumHeight(dp(48)); frame.addView(icon(resource, palette.primary), new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER)); frame.setOnClickListener(listener); frame.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48))); return frame; }
    private void buttonSemantics(View view) { view.setAccessibilityDelegate(new View.AccessibilityDelegate() {
        @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) { super.onInitializeAccessibilityNodeInfo(host, info); info.setClassName(Button.class.getName()); }
    }); }
    private TextView text(String value, float size, int color, boolean bold) { TextView text = new TextView(this); text.setText(value); text.setTextSize(size); text.setTextColor(color); text.setLineSpacing(dp(2), 1); if (bold) text.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return text; }
    private TextView label(LinearLayout parent, String value, float size, int color, boolean bold) { TextView text = text(value, size, color, bold); LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.setMargins(0, dp(4), 0, dp(7)); parent.addView(text, params); return text; }
    private LinearLayout vertical() { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); return layout; }
    private LinearLayout horizontal() { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.HORIZONTAL); return layout; }
    private GradientDrawable shape(int fill, int radius, int border) { GradientDrawable shape = new GradientDrawable(); shape.setColor(fill); shape.setCornerRadius(dp(radius)); if (border != Color.TRANSPARENT) shape.setStroke(dp(1), border); return shape; }
    private Context themed() { return new ContextThemeWrapper(this, dark ? android.R.style.Theme_Material_NoActionBar : android.R.style.Theme_Material_Light_NoActionBar); }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void hideKeyboard() { View focus = getCurrentFocus(); android.view.inputmethod.InputMethodManager manager = (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE); if (focus != null && manager != null) manager.hideSoftInputFromWindow(focus.getWindowToken(), 0); }
}

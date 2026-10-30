package org.caremonitor.uitest;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Parcel;
import android.text.Layout;
import android.text.method.PasswordTransformationMethod;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** On-device checks: no authentication, Bluetooth or network operations; endpoint fixtures restored in finally. */
public final class SetupInstrumentation extends Instrumentation {
    private static final String TARGET = "org.caremonitor.demo";
    private static final String PASSWORD = "ui-check-only-Password-4917";
    private Activity activity;
    private boolean safeToReset;
    private int checks;
    private boolean connectionOnly;
    private int originalWindowFlags;
    private boolean windowFlagsCaptured;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        connectionOnly = arguments != null && "connection".equals(arguments.getString("scope"));
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        int code = Activity.RESULT_CANCELED;
        try {
            // Check persisted identity before launching: onStart would otherwise schedule refresh.
            Class<?> sessionType = targetClass("ApiSession");
            Method load = sessionType.getDeclaredMethod("load", Context.class);
            load.setAccessible(true);
            if (load.invoke(null, getTargetContext()) != null) throw new ExistingAccount();
            Map<String, Map<String, ?>> preferences = preferences();
            Map<String, byte[]> files = privateFiles();
            int[] permissions = permissions();
            Intent launch = new Intent(Intent.ACTION_MAIN);
            launch.setClassName(TARGET, TARGET + ".MainActivity");
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity = startActivitySync(launch);
            main(() -> {
                originalWindowFlags = activity.getWindow().getAttributes().flags;
                windowFlagsCaptured = true;
                activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                if (get(activity, "session") != null) throw new ExistingAccount();
                safeToReset = true;
                ((Handler) get(activity, "main")).removeCallbacks((Runnable) get(activity, "poll"));
                set(activity, "visible", false);
                check("secure window remains enabled", (activity.getWindow().getAttributes().flags
                    & WindowManager.LayoutParams.FLAG_SECURE) != 0);
            });
            connectionRecovery();
            if (!connectionOnly) {
                registration();
                accessibleAuthentication();
                wearableKeySetup();
                caregiverGuard();
            }
            check("preferences unchanged", preferences.equals(preferences()));
            Map<String, byte[]> after = privateFiles();
            for (String name : files.keySet()) {
                check("private session/outbox unchanged", Arrays.equals(files.get(name), after.get(name)));
            }
            check("permissions unchanged", Arrays.equals(permissions, permissions()));
            result.putString("result", "PASS");
            result.putString("scope", connectionOnly ? "connection recovery; no layout assertions" : "full onboarding and layout");
            code = Activity.RESULT_OK;
        } catch (ExistingAccount abort) {
            result.putString("result", "ABORTED_EXISTING_SESSION");
            result.putString("detail", "Existing account detected; no test session or form changes applied.");
        } catch (Throwable failure) {
            result.putString("result", "FAIL");
            result.putString("detail", failure instanceof AssertionError
                ? String.valueOf(failure.getMessage()) : failure.getClass().getSimpleName());
        } finally {
            if (activity != null) {
                try {
                    main(() -> {
                        if (safeToReset) {
                            ((Handler) get(activity, "main")).removeCallbacks((Runnable) get(activity, "poll"));
                            set(activity, "session", null);
                            set(activity, "page", "home");
                            set(activity, "registering", false);
                            invoke(activity, "render");
                        }
                        if (windowFlagsCaptured) activity.getWindow().setFlags(
                            originalWindowFlags & WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                        activity.finish();
                    });
                } catch (Throwable cleanupFailure) {
                    result.putString("result", "FAIL");
                    result.putString("detail", "Activity cleanup failed");
                    code = Activity.RESULT_CANCELED;
                }
            }
            result.putInt("checks", checks);
            finish(code, result);
        }
    }

    /** Exercise only the explicit endpoint action, never login or an HTTPS request. */
    private void connectionRecovery() throws Exception {
        final SharedPreferences endpoint = getTargetContext().getSharedPreferences("caremonitor_endpoint", 0);
        final boolean hadBase = endpoint.contains("base"), hadPrevious = endpoint.contains("previous_base");
        final String originalBase = endpoint.getString("base", ""), originalPrevious = endpoint.getString("previous_base", "");
        Field defaultField = targetClass("ApiSession").getDeclaredField("DEFAULT_BASE");
        defaultField.setAccessible(true);
        final String prepared = (String) defaultField.get(null);
        final String previous = prepared.equals("https://192.0.2.1:8443/api/v1")
            ? "https://192.0.2.2:8443/api/v1" : "https://192.0.2.1:8443/api/v1";
        final Map<String, byte[]> files = privateFiles();
        final String originalEmail = (String) get(activity, "loginEmail");
        try {
            main(() -> {
                check("prepared endpoint fixture saved", endpoint.edit().putString("base", prepared).remove("previous_base").commit());
                invoke(activity, "render");
                check("matching connection has no update action", text("Update my connection") == null);
                check("matching connection has no previous action", text("Restore my previous connection") == null);
                check("different endpoint fixture saved", endpoint.edit().putString("base", previous).commit());
                invoke(activity, "render");
                check("mismatched connection offers explicit update", text("Update my connection") != null
                    && text("Update my connection").isEnabled());
                check("connection does not change merely by rendering", previous.equals(endpoint.getString("base", ""))
                    && !endpoint.contains("previous_base"));
                check("connection recovery does not expose a server field", edit("HTTPS server") == null
                    && get(activity, "serverInput") == null);
                check("connection recovery does not expose either address", find(root(), view -> view instanceof TextView
                    && (((TextView)view).getText().toString().contains(prepared)
                        || ((TextView)view).getText().toString().contains(previous))) == null);
                set(activity, "busy", true);
                invoke(activity, "render");
                check("connection action disabled during authentication", !text("Update my connection").isEnabled());
                text("Update my connection").performClick();
                check("busy guard preserves connection", previous.equals(endpoint.getString("base", ""))
                    && !endpoint.contains("previous_base"));
                set(activity, "busy", false);
                invoke(activity, "render");
                edit("Email address").setText("ui-check@example.invalid");
                edit("Password").setText(PASSWORD);
                click("Update my connection");
                check("explicit update selects prepared connection", prepared.equals(endpoint.getString("base", "")));
                check("explicit update retains previous connection", previous.equals(endpoint.getString("previous_base", "")));
                check("updated connection has friendly notice", text("Connection updated. Sign in to continue.") != null);
                check("updated connection hides redundant action", text("Update my connection") == null);
                check("previous connection remains restorable", text("Restore my previous connection") != null);
                check("update preserves login email and password in memory", "ui-check@example.invalid".contentEquals(edit("Email address").getText())
                    && PASSWORD.contentEquals(edit("Password").getText()) && !edit("Password").isSaveEnabled());
                check("update never authenticates or schedules a permission action", get(activity,"session") == null
                    && get(activity,"authentication") == null && !(Boolean)get(activity,"busy")
                    && "".equals(get(activity,"pendingAction")));
                click("Restore my previous connection");
                check("previous connection restored without a session", previous.equals(endpoint.getString("base", ""))
                    && !endpoint.contains("previous_base") && get(activity,"session") == null);
                check("restoring previous connection reoffers explicit update", text("Update my connection") != null);
                check("restoring previous connection preserves login form", "ui-check@example.invalid".contentEquals(edit("Email address").getText())
                    && PASSWORD.contentEquals(edit("Password").getText()));
                secureWindow();
            });
            Map<String, byte[]> after = privateFiles();
            for (String name : files.keySet()) check("recovery leaves private session and outbox unchanged", Arrays.equals(files.get(name),after.get(name)));
        } finally {
            main(() -> {
                SharedPreferences.Editor restore = endpoint.edit();
                if(hadBase)restore.putString("base",originalBase);else restore.remove("base");
                if(hadPrevious)restore.putString("previous_base",originalPrevious);else restore.remove("previous_base");
                check("endpoint fixtures restored", restore.commit());
                if(get(activity,"passwordInput") instanceof EditText)((EditText)get(activity,"passwordInput")).setText("");
                set(activity,"busy",false);set(activity,"loginEmail",originalEmail);set(activity,"notice","");
                invoke(activity,"render");
                check("recovery cleanup retains no session or authentication", get(activity,"session") == null
                    && get(activity,"authentication") == null);
                secureWindow();
            });
        }
    }

    private void registration() throws Exception {
        main(() -> {
            check("login email exists", edit("Email address") != null);
            check("login password exists", edit("Password") != null);
            check("server absent from login", edit("HTTPS server") == null
                && get(activity, "serverInput") == null);
            click("Create an account");
            check("registration requires explicit role", step() == 0 && role().isEmpty());
            check("neither role preselected", !roleView("I'm the older adult").isSelected()
                && !roleView("I'm a caregiver").isSelected());
            click("Continue");
            check("cannot advance without role", step() == 0 && role().isEmpty());
            check("role explanation shown", !((String) get(activity, "notice")).isEmpty());
            roleView("I'm a caregiver").performClick();
            check("caregiver selected explicitly", "caregiver".equals(role())
                && roleView("I'm a caregiver").isSelected()
                && !roleView("I'm the older adult").isSelected());
            click("Continue");
            check("name step", step() == 1 && edit("Your name") != null);
            edit("Your name").setText("Interface check");
            click("Continue");
            check("email step", step() == 2 && edit("Email address") != null);
            edit("Email address").setText("ui-check@example.invalid");
            click("Continue");
            check("final password step", step() == 3 && edit("Choose a password") != null);
            check("profile summary shown", text("Interface check") != null
                && text("ui-check@example.invalid") != null && text("Caregiver") != null);
            EditText password = edit("Choose a password");
            check("password view disables state saving", !password.isSaveEnabled());
            password.setText(PASSWORD);
            Bundle saved = new Bundle();
            callActivityOnSaveInstanceState(activity, saved);
            check("profile state retained without password key", saved.getInt("setup_step") == 3
                && "Interface check".equals(saved.getString("setup_name"))
                && "ui-check@example.invalid".equals(saved.getString("setup_email")));
            for (String key : saved.keySet()) check("no password state key", !key.toLowerCase(java.util.Locale.ROOT).contains("password"));
            Parcel parcel = Parcel.obtain();
            try {
                saved.writeToParcel(parcel, 0);
                byte[] bytes = parcel.marshall();
                check("password absent from saved Bundle UTF-8", !contains(bytes, PASSWORD.getBytes(StandardCharsets.UTF_8)));
                check("password absent from saved Bundle UTF-16LE", !contains(bytes, PASSWORD.getBytes(StandardCharsets.UTF_16LE)));
                check("password absent from saved Bundle UTF-16BE", !contains(bytes, PASSWORD.getBytes(StandardCharsets.UTF_16BE)));
            } finally { parcel.recycle(); }
        });
        main(() -> {
            click("Back");
            check("back preserves email", step() == 2
                && "ui-check@example.invalid".contentEquals(edit("Email address").getText()));
            click("Back");
            check("back preserves name", step() == 1
                && "Interface check".contentEquals(edit("Your name").getText()));
            click("Back");
            check("back preserves caregiver choice", step() == 0 && "caregiver".equals(role()));
            roleView("I'm the older adult").performClick();
            check("adult changes only by explicit selection", "elder".equals(role())
                && roleView("I'm the older adult").isSelected()
                && !roleView("I'm a caregiver").isSelected());
            click("Continue");
            check("name preserved after role change", "Interface check".contentEquals(edit("Your name").getText()));
            click("Continue");
            check("email preserved after role change", "ui-check@example.invalid".contentEquals(edit("Email address").getText()));
            click("Continue");
            check("adult summary and password reentry", step() == 3 && text("Older adult") != null
                && edit("Choose a password").getText().length() == 0);
            check("no authentication request started", !(Boolean) get(activity, "busy") && get(activity, "session") == null);
            // Never click Sign in or Create my account.
            for (int i = 0; i < 4; i++) invoke(activity, "goBack");
            check("returned to login", !(Boolean) get(activity, "registering")
                && edit("Password") != null && edit("HTTPS server") == null);
        });
    }

    /** Exercise real view bounds at enlarged text; never change system font settings. */
    private void accessibleAuthentication() throws Exception {
        final Resources resources = activity.getResources();
        final Configuration original = new Configuration(resources.getConfiguration());
        final DisplayMetrics originalMetrics = new DisplayMetrics();
        originalMetrics.setTo(resources.getDisplayMetrics());
        final int originalInputMode = activity.getWindow().getAttributes().softInputMode;
        try {
            main(() -> {
                Configuration enlarged = new Configuration(original);
                enlarged.fontScale = Math.max(2.0f, original.fontScale);
                resources.updateConfiguration(enlarged, originalMetrics);
                activity.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
                check("200 percent text applied in activity memory", resources.getConfiguration().fontScale >= 2.0f);
                set(activity, "registering", false);
                invoke(activity, "render");
            });
            authenticationLayout("Sign in", "Email address", "Password");
            main(() -> passwordVisibility("Password"));
            main(() -> click("Create an account"));
            authenticationLayout("Continue");
            main(() -> {
                View adult = roleView("I'm the older adult");
                View caregiver = roleView("I'm a caregiver");
                check("role cards are accessible focus targets", adult.isFocusable() && caregiver.isFocusable());
                minimumTarget("adult role card", adult);
                minimumTarget("caregiver role card", caregiver);
                check("explicit role selected for layout flow", caregiver.performClick());
                click("Continue");
            });
            authenticationLayout("Continue", "Your name");
            main(() -> {
                edit("Your name").setText("Interface check");
                click("Continue");
            });
            authenticationLayout("Continue", "Email address");
            main(() -> {
                edit("Email address").setText("ui-check@example.invalid");
                click("Continue");
            });
            authenticationLayout("Create my account", "Choose a password");
            main(() -> {
                passwordVisibility("Choose a password");
                check("layout checks never start authentication", !(Boolean) get(activity, "busy")
                    && get(activity, "session") == null && get(activity, "authentication") == null);
                secureWindow();
            });
        } finally {
            main(() -> {
                if (get(activity, "passwordInput") instanceof EditText)
                    ((EditText) get(activity, "passwordInput")).setText("");
                resources.updateConfiguration(original, originalMetrics);
                activity.getWindow().setSoftInputMode(originalInputMode);
                set(activity, "registering", false);
                set(activity, "page", "home");
                invoke(activity, "render");
                check("font configuration restored", Float.compare(resources.getConfiguration().fontScale,
                    original.fontScale) == 0);
                secureWindow();
            });
        }
    }

    private void authenticationLayout(String action, String... fieldNames) throws Exception {
        main(this::hideKeyboard);
        awaitLayout();
        main(() -> {
            if (Build.VERSION.SDK_INT >= 30) {
                WindowInsets insets = root().getRootWindowInsets();
                check("authentication layout measured without keyboard", insets != null
                    && !insets.isVisible(WindowInsets.Type.ime()));
            }
            primaryAction(action);
        });
        for (String name : fieldNames) {
            main(() -> {
                EditText field = edit(name);
                check("accessible field exists: " + name, field != null && field.isEnabled());
                field.requestRectangleOnScreen(new Rect(0, 0, field.getWidth(), field.getHeight()), true);
            });
            awaitLayout();
            main(() -> {
                EditText field = edit(name);
                fullyVisible("enlarged input: " + name, field);
                minimumTarget("input: " + name, field);
                textNotClipped("input: " + name, field, false);
                primaryAction(action);
            });
        }
        main(() -> ((ScrollView) get(activity, "scroll")).fullScroll(View.FOCUS_DOWN));
        awaitLayout();
        main(() -> {
            primaryAction(action);
            secureWindow();
        });
    }

    private void primaryAction(String label) {
        TextView action = text(label);
        check("primary action is operable: " + label, action != null && action.isEnabled() && action.isClickable());
        fullyVisible("primary action: " + label, action);
        minimumTarget("primary action: " + label, action);
        textNotClipped("primary action: " + label, action, true);
        boolean withinScroll = false;
        for (ViewParent parent = action.getParent(); parent != null; parent = parent.getParent())
            if (parent instanceof ScrollView) withinScroll = true;
        check("primary action remains outside scrolling form", !withinScroll);
    }

    private void passwordVisibility(String hint) {
        EditText password = edit(hint);
        TextView toggle = text("Show password");
        check("password toggle is accessible", toggle != null && toggle.isClickable() && toggle.isEnabled()
            && toggle.getContentDescription() != null
            && "Show password".contentEquals(toggle.getContentDescription()));
        check("password begins masked", password != null
            && password.getTransformationMethod() instanceof PasswordTransformationMethod);
        check("password state saving remains disabled", !password.isSaveEnabled());
        password.setText(PASSWORD);
        toggle.performClick();
        check("password show option is selected", toggle instanceof CompoundButton && ((CompoundButton)toggle).isChecked());
        check("show reveals only existing password", !(password.getTransformationMethod() instanceof PasswordTransformationMethod)
            && PASSWORD.contentEquals(password.getText()));
        check("toggle keeps a readable accessible name", toggle.getText().length() > 0
            && toggle.getContentDescription() != null
            && toggle.getText().toString().contentEquals(toggle.getContentDescription()));
        toggle.performClick();
        check("password hide option is selected", !((CompoundButton)toggle).isChecked());
        check("hide restores masking and preserves text", password.getTransformationMethod() instanceof PasswordTransformationMethod
            && PASSWORD.contentEquals(password.getText()) && !password.isSaveEnabled());
        password.setText("");
        secureWindow();
    }

    private void caregiverGuard() throws Exception {
        main(() -> {
            ((Handler) get(activity, "main")).removeCallbacks((Runnable) get(activity, "poll"));
            Constructor<?> constructor = targetClass("ApiSession").getDeclaredConstructor(String.class, JSONObject.class);
            constructor.setAccessible(true);
            JSONObject user = new JSONObject().put("id", "ui-check").put("role", "caregiver")
                .put("display_name", "Interface check").put("email", "ui-check@example.invalid");
            JSONObject body = new JSONObject().put("access_token", "ui-check-fictional-token-never-persisted-0001")
                .put("expires_at", "2099-01-01T00:00:00Z").put("user", user);
            // Constructor only: never ApiSession.save(), logout(), refresh() or a navigation listener.
            set(activity, "session", constructor.newInstance("https://192.0.2.1:8443/api/v1", body));
            set(activity, "page", "home");
            invoke(activity, "render");
        });
        awaitLayout();
        main(() -> {
            check("caregiver home visible", text("Your care today") != null);
            check("caregiver cannot connect wearable from home", text("Connect my wearable") == null);
            navigationState("Today");
        });
        String[] pages = {"alerts", "care", "account", "connection", "privacy", "notification_settings", "security"};
        String[] selected = {"Updates", "Care", "Settings", "Settings", "Settings", "Settings", "Settings"};
        for (int i = 0; i < pages.length; i++) {
            final String page = pages[i], label = selected[i];
            main(() -> {
                set(activity, "page", page);
                invoke(activity, "render");
            });
            awaitLayout();
            main(() -> {
                navigationState(label);
                if ("account".equals(page)) {
                    View connection = described("Server connection");
                    View heading = find((View) get(activity, "content"), view -> view instanceof TextView
                        && "Settings".contentEquals(((TextView) view).getText()));
                    check("settings exposes grouped server connection", heading != null
                        && connection != null && connection.isClickable());
                    check("server field is not embedded in settings list", edit("HTTPS server") == null
                        && get(activity, "serverInput") == null);
                } else if ("connection".equals(page)) {
                    check("HTTPS server field belongs in connection settings", edit("HTTPS server") != null
                        && get(activity, "serverInput") != null);
                }
                secureWindow();
            });
        }
        main(() -> {
            set(activity, "page", "device");
            invoke(activity, "render");
        });
        awaitLayout();
        main(() -> {
            check("caregiver device page is explanatory", text("Wearable connection") != null
                && get(activity, "deviceList") == null && text("Search for my wearable") == null);
            invoke(activity, "device");
            Method bluetooth = activity.getClass().getDeclaredMethod("bluetooth", String.class);
            bluetooth.setAccessible(true);
            bluetooth.invoke(activity, "scan");
            check("caregiver scan leaves no permission action or device list",
                "".equals(get(activity, "pendingAction")) && get(activity, "deviceList") == null);
            check("caregiver scan keeps the activity focused", activity.hasWindowFocus());
            secureWindow();
            set(activity, "session", null);
            set(activity, "page", "home");
            invoke(activity, "render");
        });
    }

    /** Private-key UI only: fixtures stay in memory; Save/connect is never pressed. */
    private void wearableKeySetup() throws Exception {
        if(Build.VERSION.SDK_INT<29)return; // Public global-window inspection starts at API 29.
        final Resources resources=activity.getResources();
        final Configuration original=new Configuration(resources.getConfiguration());
        final DisplayMetrics metrics=new DisplayMetrics();metrics.setTo(resources.getDisplayMetrics());
        final String key="0123456789abcdef0123456789abcdef";
        final Map<String,byte[]> credentials=wearableFiles();
        try {
            for(float scale:new float[]{1.0f,2.0f}) {
                main(()->{
                    Configuration fixture=new Configuration(original);fixture.fontScale=scale;
                    resources.updateConfiguration(fixture,metrics);
                    set(activity,"session",fixtureSession("elder"));set(activity,"registering",false);
                    set(activity,"page","device");set(activity,"deviceStep",1);invoke(activity,"render");
                    check("wearable pairing no longer requests a computer",find(root(),view->view instanceof TextView
                        && ((TextView)view).getText().toString().contains("USB pairing assistant"))==null);
                    Method method=activity.getClass().getDeclaredMethod("authoriseWearable",String.class);
                    method.setAccessible(true);method.invoke(activity,"02:00:00:00:00:01");
                });
                final View dialog=wearableDialog();
                check("wearable key dialog opened for elder",dialog!=null);
                awaitLayout(dialog);
                final EditText[] field=new EditText[1];
                main(()->{
                    check("dialog uses requested in-memory font scale",Float.compare(dialog.getResources().getConfiguration().fontScale,scale)==0);
                    field[0]=(EditText)find(dialog,view->view instanceof EditText&&"Setup code from your card".contentEquals(view.getContentDescription()));
                    check("setup key field is present and enabled",field[0]!=null&&field[0].isEnabled());
                    check("setup key starts masked",field[0].getTransformationMethod() instanceof PasswordTransformationMethod);
                    check("setup key view state saving disabled",!field[0].isSaveEnabled());
                    check("setup key autofill disabled",field[0].getImportantForAutofill()==View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
                    check("setup dialog has a secure window",dialog.getLayoutParams() instanceof WindowManager.LayoutParams
                        && (((WindowManager.LayoutParams)dialog.getLayoutParams()).flags&WindowManager.LayoutParams.FLAG_SECURE)!=0);
                    fullyVisible("setup key field",field[0]);minimumTarget("setup key field",field[0]);textNotClipped("setup key field",field[0],false);
                    TextView show=(TextView)find(dialog,view->view instanceof TextView&&"Show setup key".contentEquals(((TextView)view).getText()));
                    check("setup key visibility control accessible",show!=null&&show.isClickable()&&show.isEnabled()
                        &&"Show setup key".contentEquals(show.getContentDescription()));
                    fullyVisible("setup key visibility control",show);minimumTarget("setup key visibility control",show);
                    textNotClipped("setup key visibility control",show,true);
                    field[0].setText(key);show.performClick();
                    check("explicit key reveal keeps field text",!(field[0].getTransformationMethod() instanceof PasswordTransformationMethod)&&key.contentEquals(field[0].getText()));
                    show.performClick();
                    check("key can be masked again",field[0].getTransformationMethod() instanceof PasswordTransformationMethod&&key.contentEquals(field[0].getText()));
                    Bundle saved=new Bundle();callActivityOnSaveInstanceState(activity,saved);
                    Parcel parcel=Parcel.obtain();
                    try {
                        saved.writeToParcel(parcel,0);byte[] bytes=parcel.marshall();
                        check("key absent from saved state UTF8",!contains(bytes,key.getBytes(StandardCharsets.UTF_8)));
                        check("key absent from saved state UTF16",!contains(bytes,key.getBytes(StandardCharsets.UTF_16LE))&&!contains(bytes,key.getBytes(StandardCharsets.UTF_16BE)));
                    } finally {parcel.recycle();}
                    for(int id:new int[]{android.R.id.button1,android.R.id.button2}) {
                        TextView action=dialog.findViewById(id);
                        check("setup dialog action operable",action!=null&&action.isEnabled()&&action.isClickable());
                        fullyVisible("setup dialog action",action);textNotClipped("setup dialog action",action,true);
                        check("platform dialog action at least 48dp high",action.getHeight()+1>=48*resources.getDisplayMetrics().density);
                    }
                    check("setup save button labelled", "Connect".contentEquals(((TextView)dialog.findViewById(android.R.id.button1)).getText()));
                    check("setup Cancel click handled",dialog.findViewById(android.R.id.button2).performClick());
                });
                main(()->{
                    check("Cancel dismisses key dialog",!dialog.isAttachedToWindow()&&wearableDialog()==null);
                    check("Cancel clears secret field",field[0].getText().length()==0);
                    check("key UI does not create a persisted account",persistedSession()==null);
                    check("key UI does not start authentication",get(activity,"authentication")==null);
                    secureWindow();
                });
            }
            main(()->{
                set(activity,"session",fixtureSession("caregiver"));set(activity,"page","device");invoke(activity,"render");
                Method method=activity.getClass().getDeclaredMethod("authoriseWearable",String.class);
                method.setAccessible(true);method.invoke(activity,"02:00:00:00:00:01");
                check("caregiver cannot open setup key dialog",wearableDialog()==null);
                check("caregiver key guard requests no permission or transport",get(activity,"deviceList")==null&&"".equals(get(activity,"pendingAction")));
            });
            Map<String,byte[]> after=wearableFiles();check("key UI creates no credential files",credentials.keySet().equals(after.keySet()));
            for(String name:credentials.keySet())check("key UI preserves existing encrypted credential",Arrays.equals(credentials.get(name),after.get(name)));
        } finally {
            main(()->{
                View dialog=wearableDialog();if(dialog!=null&&dialog.findViewById(android.R.id.button2)!=null)dialog.findViewById(android.R.id.button2).performClick();
                resources.updateConfiguration(original,metrics);set(activity,"session",null);set(activity,"page","home");set(activity,"deviceStep",0);invoke(activity,"render");
                check("key UI font restored",Float.compare(resources.getConfiguration().fontScale,original.fontScale)==0);
            });
        }
    }

    private Object fixtureSession(String role) throws Exception {
        Constructor<?> constructor=targetClass("ApiSession").getDeclaredConstructor(String.class,JSONObject.class);constructor.setAccessible(true);
        JSONObject user=new JSONObject().put("id","ui-check").put("role",role).put("display_name","Interface check").put("email","ui-check@example.invalid");
        JSONObject body=new JSONObject().put("access_token","ui-check-fictional-token-never-persisted-0001").put("expires_at","2099-01-01T00:00:00Z").put("user",user);
        return constructor.newInstance("https://192.0.2.1:8443/api/v1",body);
    }
    private Object persistedSession() throws Exception {
        Method load=targetClass("ApiSession").getDeclaredMethod("load",Context.class);load.setAccessible(true);return load.invoke(null,getTargetContext());
    }
    private View wearableDialog() {
        if(Build.VERSION.SDK_INT<29)return null;
        for(View window:android.view.inspector.WindowInspector.getGlobalWindowViews())
            if(window.isAttachedToWindow()&&find(window,view->view instanceof EditText&&"Setup code from your card".contentEquals(view.getContentDescription()))!=null)return window;
        return null;
    }
    private Map<String,byte[]> wearableFiles() throws Exception {
        Map<String,byte[]> result=new HashMap<>();File[] files=getTargetContext().getFilesDir().listFiles();
        if(files!=null)for(File file:files)if(file.getName().startsWith("wearable_")) {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            try(FileInputStream input=new FileInputStream(file)){byte[] bytes=new byte[4096];int count;while((count=input.read(bytes))!=-1)digest.update(bytes,0,count);}
            result.put(file.getName(),digest.digest());
        }
        return result;
    }

    private void navigationState(String selected) throws Exception {
        String[] labels = {"Today", "Updates", "Care", "Settings"};
        // Settings may legitimately contain another Notifications control. Locate
        // destinations only in the bottom navigation appended to the screen root.
        ViewGroup screen = (ViewGroup) get(activity, "root");
        check("navigation is a separate bottom container", screen.getChildCount() > 0
            && screen.getChildAt(screen.getChildCount() - 1) instanceof ViewGroup);
        ViewGroup parent = (ViewGroup) screen.getChildAt(screen.getChildCount() - 1);
        int selectedCount = 0;
        for (String label : labels) {
            View item = find(parent, view -> view.isClickable() && view.getContentDescription() != null
                && label.contentEquals(view.getContentDescription()));
            check("navigation item is accessible: " + label, item != null && item.isClickable() && item.isFocusable());
            check("navigation label visible: " + label, find(item, view -> view instanceof TextView
                && label.contentEquals(((TextView) view).getText())) != null);
            check("navigation includes an icon: " + label, find(item, view -> view instanceof ImageView) != null);
            check("navigation selection matches current destination", item.isSelected() == selected.equals(label));
            if (item.isSelected()) selectedCount++;
            check("navigation destinations share one container", item.getParent() == parent);
            fullyVisible("navigation item: " + label, item);
            minimumTarget("navigation item: " + label, item);
        }
        check("exactly four navigation destinations", parent != null && parent.getChildCount() == 4);
        check("exactly one destination selected", selectedCount == 1);
    }

    private View described(String description) {
        return find(root(), view -> view.isClickable() && view.getContentDescription() != null
            && description.contentEquals(view.getContentDescription()));
    }

    private void secureWindow() {
        check("secure window remains enabled", (activity.getWindow().getAttributes().flags
            & WindowManager.LayoutParams.FLAG_SECURE) != 0);
    }

    private void fullyVisible(String label, View view) {
        Rect visible = new Rect(), viewport = new Rect();
        check(label + " is laid out", view.getWidth() > 0 && view.getHeight() > 0);
        check(label + " is visible", view.getGlobalVisibleRect(visible) && root().getGlobalVisibleRect(viewport));
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        Rect bounds = new Rect(location[0], location[1], location[0] + view.getWidth(), location[1] + view.getHeight());
        check(label + " fits the viewport", viewport.contains(bounds)
            && visible.width() + 1 >= bounds.width() && visible.height() + 1 >= bounds.height());
    }

    private void minimumTarget(String label, View view) {
        float minimum = 56 * activity.getResources().getDisplayMetrics().density;
        check(label + " is at least 56dp high", view.getHeight() + 1 >= minimum);
        check(label + " is at least 56dp wide", view.getWidth() + 1 >= minimum);
    }

    private void textNotClipped(String label, TextView view, boolean checkWidth) {
        Layout layout = view.getLayout();
        check(label + " has measured text", layout != null && layout.getLineCount() > 0);
        int availableHeight = view.getHeight() - view.getCompoundPaddingTop() - view.getCompoundPaddingBottom();
        int availableWidth = view.getWidth() - view.getCompoundPaddingLeft() - view.getCompoundPaddingRight();
        check(label + " text fits vertically", layout.getHeight() <= availableHeight + 1);
        for (int line = 0; line < layout.getLineCount(); line++) {
            check(label + " has no ellipsis", layout.getEllipsisCount(line) == 0);
            if (checkWidth) check(label + " text fits horizontally", layout.getLineWidth(line) <= availableWidth + 1);
        }
    }

    private void hideKeyboard() {
        View focused = activity.getCurrentFocus();
        if (focused != null) focused.clearFocus();
        root().setFocusableInTouchMode(true);
        root().requestFocus();
        InputMethodManager input = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (input != null) input.hideSoftInputFromWindow(root().getWindowToken(), 0);
    }

    /** Wait for an actual traversal, rather than measuring a freshly rebuilt zero-size tree. */
    private void awaitLayout() throws Exception {
        awaitLayout(activity.getWindow().getDecorView());
    }
    private void awaitLayout(View decor) throws Exception {
        final CountDownLatch frame = new CountDownLatch(1);
        final ViewTreeObserver.OnPreDrawListener[] listener = new ViewTreeObserver.OnPreDrawListener[1];
        runOnMainSync(() -> {
            listener[0] = () -> {
                decor.getViewTreeObserver().removeOnPreDrawListener(listener[0]);
                frame.countDown();
                return true;
            };
            decor.getViewTreeObserver().addOnPreDrawListener(listener[0]);
            decor.requestLayout();
            decor.invalidate();
        });
        boolean ready = frame.await(5, TimeUnit.SECONDS);
        runOnMainSync(() -> {
            if (decor.getViewTreeObserver().isAlive())
                decor.getViewTreeObserver().removeOnPreDrawListener(listener[0]);
        });
        check("view tree completed layout", ready);
        waitForIdleSync();
    }

    private interface Action { void run() throws Exception; }
    private void main(Action action) throws Exception {
        final Throwable[] failure = new Throwable[1];
        runOnMainSync(() -> { try { action.run(); } catch (Throwable error) { failure[0] = error; } });
        waitForIdleSync();
        if (failure[0] instanceof ExistingAccount) throw (ExistingAccount) failure[0];
        if (failure[0] instanceof AssertionError) throw (AssertionError) failure[0];
        if (failure[0] != null) throw new Exception(failure[0]);
    }
    private Class<?> targetClass(String simple) throws ClassNotFoundException {
        return Class.forName(TARGET + "." + simple, true, getTargetContext().getClassLoader());
    }
    private static Object get(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
    private static void invoke(Object target, String name) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name); method.setAccessible(true); method.invoke(target);
    }
    private int step() throws Exception { return (Integer) get(get(activity, "setup"), "step"); }
    private String role() throws Exception { return (String) get(get(activity, "setup"), "role"); }
    private View root() { return activity.findViewById(android.R.id.content); }
    private interface Match { boolean accepts(View view); }
    private View find(View view, Match match) {
        if (match.accepts(view)) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View found = find(((ViewGroup) view).getChildAt(i), match); if (found != null) return found;
        }
        return null;
    }
    private TextView text(String value) {
        return (TextView) find(root(), view -> view instanceof TextView
            && value.contentEquals(((TextView) view).getText()));
    }
    private EditText edit(String hint) {
        return (EditText) find(root(), view -> view instanceof EditText
            && hint.contentEquals(view.getContentDescription()));
    }
    private View roleView(String title) {
        View found = find(root(), view -> view.isClickable() && view.getContentDescription() != null
            && view.getContentDescription().toString().startsWith(title + "."));
        check("role choice present", found != null); return found;
    }
    private void click(String label) {
        TextView button = text(label); check("button present: " + label, button != null && button.isClickable());
        check("button click handled: " + label, button.performClick());
    }
    private void check(String label, boolean value) { checks++; if (!value) throw new AssertionError(label); }
    private static boolean contains(byte[] bytes, byte[] sought) {
        outer: for (int i = 0; i <= bytes.length - sought.length; i++) {
            for (int j = 0; j < sought.length; j++) if (bytes[i + j] != sought[j]) continue outer;
            return true;
        }
        return false;
    }
    private Map<String, Map<String, ?>> preferences() {
        Map<String, Map<String, ?>> saved = new HashMap<>();
        for (String name : new String[]{"caremonitor_endpoint", "caremonitor_session_control"}) {
            saved.put(name, new HashMap<>(getTargetContext().getSharedPreferences(name, 0).getAll()));
        }
        return saved;
    }
    private Map<String, byte[]> privateFiles() throws Exception {
        Map<String, byte[]> result = new HashMap<>();
        for (String name : new String[]{"session_v2.enc", "sensor_outbox_v2.enc"}) {
            File file = new File(getTargetContext().getFilesDir(), name);
            if (!file.exists()) { result.put(name, null); continue; }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (FileInputStream stream = new FileInputStream(file)) {
                byte[] buffer = new byte[4096]; int count;
                while ((count = stream.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            result.put(name, digest.digest());
        }
        return result;
    }
    private int[] permissions() throws Exception {
        PackageInfo info = getTargetContext().getPackageManager().getPackageInfo(TARGET, PackageManager.GET_PERMISSIONS);
        return info.requestedPermissionsFlags == null ? new int[0] : info.requestedPermissionsFlags.clone();
    }
    private static final class ExistingAccount extends RuntimeException {}
}

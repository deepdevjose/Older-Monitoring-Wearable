package org.caremonitor.uitest;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
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

/** On-device view-tree checks: no authentication, persistence, Bluetooth or network operations. */
public final class SetupInstrumentation extends Instrumentation {
    private static final String TARGET = "org.caremonitor.demo";
    private static final String PASSWORD = "ui-check-only-Password-4917";
    private Activity activity;
    private boolean safeToReset;
    private int checks;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
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
                if (get(activity, "session") != null) throw new ExistingAccount();
                safeToReset = true;
                ((Handler) get(activity, "main")).removeCallbacks((Runnable) get(activity, "poll"));
                set(activity, "visible", false);
                check("secure window remains enabled", (activity.getWindow().getAttributes().flags
                    & WindowManager.LayoutParams.FLAG_SECURE) != 0);
            });
            registration();
            accessibleAuthentication();
            caregiverGuard();
            check("preferences unchanged", preferences.equals(preferences()));
            Map<String, byte[]> after = privateFiles();
            for (String name : files.keySet()) {
                check("private session/outbox unchanged", Arrays.equals(files.get(name), after.get(name)));
            }
            check("permissions unchanged", Arrays.equals(permissions, permissions()));
            result.putString("result", "PASS");
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

    private void registration() throws Exception {
        main(() -> {
            check("login email exists", edit("Correo electrónico") != null);
            check("login password exists", edit("Contraseña") != null);
            check("server absent from login", edit("Servidor HTTPS") == null
                && get(activity, "serverInput") == null);
            click("Crear una cuenta");
            check("registration requires explicit role", step() == 0 && role().isEmpty());
            check("neither role preselected", !roleView("Soy la persona mayor").isSelected()
                && !roleView("Soy cuidador o cuidadora").isSelected());
            click("Continuar");
            check("cannot advance without role", step() == 0 && role().isEmpty());
            check("role explanation shown", !((String) get(activity, "notice")).isEmpty());
            roleView("Soy cuidador o cuidadora").performClick();
            check("caregiver selected explicitly", "caregiver".equals(role())
                && roleView("Soy cuidador o cuidadora").isSelected()
                && !roleView("Soy la persona mayor").isSelected());
            click("Continuar");
            check("name step", step() == 1 && edit("Tu nombre") != null);
            edit("Tu nombre").setText("Prueba de interfaz");
            click("Continuar");
            check("email step", step() == 2 && edit("Correo electrónico") != null);
            edit("Correo electrónico").setText("ui-check@example.invalid");
            click("Continuar");
            check("final password step", step() == 3 && edit("Elige una contraseña") != null);
            check("profile summary shown", text("Prueba de interfaz") != null
                && text("ui-check@example.invalid") != null && text("Cuidador o cuidadora") != null);
            EditText password = edit("Elige una contraseña");
            check("password view disables state saving", !password.isSaveEnabled());
            password.setText(PASSWORD);
            Bundle saved = new Bundle();
            callActivityOnSaveInstanceState(activity, saved);
            check("profile state retained without password key", saved.getInt("setup_step") == 3
                && "Prueba de interfaz".equals(saved.getString("setup_name"))
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
            click("Atrás");
            check("back preserves email", step() == 2
                && "ui-check@example.invalid".contentEquals(edit("Correo electrónico").getText()));
            click("Atrás");
            check("back preserves name", step() == 1
                && "Prueba de interfaz".contentEquals(edit("Tu nombre").getText()));
            click("Atrás");
            check("back preserves caregiver choice", step() == 0 && "caregiver".equals(role()));
            roleView("Soy la persona mayor").performClick();
            check("adult changes only by explicit selection", "elder".equals(role())
                && roleView("Soy la persona mayor").isSelected()
                && !roleView("Soy cuidador o cuidadora").isSelected());
            click("Continuar");
            check("name preserved after role change", "Prueba de interfaz".contentEquals(edit("Tu nombre").getText()));
            click("Continuar");
            check("email preserved after role change", "ui-check@example.invalid".contentEquals(edit("Correo electrónico").getText()));
            click("Continuar");
            check("adult summary and password reentry", step() == 3 && text("Persona mayor") != null
                && edit("Elige una contraseña").getText().length() == 0);
            check("no authentication request started", !(Boolean) get(activity, "busy") && get(activity, "session") == null);
            // Never click Iniciar sesión or Crear mi cuenta.
            for (int i = 0; i < 4; i++) invoke(activity, "goBack");
            check("returned to login", !(Boolean) get(activity, "registering")
                && edit("Contraseña") != null && edit("Servidor HTTPS") == null);
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
            authenticationLayout("Iniciar sesión", "Correo electrónico", "Contraseña");
            main(() -> passwordVisibility("Contraseña"));
            main(() -> click("Crear una cuenta"));
            authenticationLayout("Continuar");
            main(() -> {
                View adult = roleView("Soy la persona mayor");
                View caregiver = roleView("Soy cuidador o cuidadora");
                check("role cards are accessible focus targets", adult.isFocusable() && caregiver.isFocusable());
                minimumTarget("adult role card", adult);
                minimumTarget("caregiver role card", caregiver);
                check("explicit role selected for layout flow", caregiver.performClick());
                click("Continuar");
            });
            authenticationLayout("Continuar", "Tu nombre");
            main(() -> {
                edit("Tu nombre").setText("Prueba de interfaz");
                click("Continuar");
            });
            authenticationLayout("Continuar", "Correo electrónico");
            main(() -> {
                edit("Correo electrónico").setText("ui-check@example.invalid");
                click("Continuar");
            });
            authenticationLayout("Crear mi cuenta", "Elige una contraseña");
            main(() -> {
                passwordVisibility("Elige una contraseña");
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
        TextView toggle = text("Mostrar contraseña");
        check("password toggle is accessible", toggle != null && toggle.isClickable() && toggle.isEnabled()
            && toggle.getContentDescription() != null
            && "Mostrar contraseña".contentEquals(toggle.getContentDescription()));
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
                .put("display_name", "Prueba de interfaz").put("email", "ui-check@example.invalid");
            JSONObject body = new JSONObject().put("access_token", "ui-check-fictional-token-never-persisted-0001")
                .put("expires_at", "2099-01-01T00:00:00Z").put("user", user);
            // Constructor only: never ApiSession.save(), logout(), refresh() or a navigation listener.
            set(activity, "session", constructor.newInstance("https://192.0.2.1:8443/api/v1", body));
            set(activity, "page", "home");
            invoke(activity, "render");
        });
        awaitLayout();
        main(() -> {
            check("caregiver home visible", text("Cuidar empieza aquí.") != null);
            check("caregiver cannot connect wearable from home", text("Conectar mi wearable") == null);
            navigationState("Hoy");
        });
        String[] pages = {"alerts", "care", "account", "connection", "privacy", "notification_settings", "security"};
        String[] selected = {"Avisos", "Cuidado", "Ajustes", "Ajustes", "Ajustes", "Ajustes", "Ajustes"};
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
                    View connection = described("Conexión con tu servidor");
                    View heading = find((View) get(activity, "content"), view -> view instanceof TextView
                        && "Ajustes".contentEquals(((TextView) view).getText()));
                    check("settings exposes grouped server connection", heading != null
                        && connection != null && connection.isClickable());
                    check("server field is not embedded in settings list", edit("Servidor HTTPS") == null
                        && get(activity, "serverInput") == null);
                } else if ("connection".equals(page)) {
                    check("HTTPS server field belongs in connection settings", edit("Servidor HTTPS") != null
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
            check("caregiver device page is explanatory", text("Conexión del wearable") != null
                && get(activity, "deviceList") == null && text("Buscar mi wearable") == null);
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

    private void navigationState(String selected) {
        String[] labels = {"Hoy", "Avisos", "Cuidado", "Ajustes"};
        ViewGroup parent = null;
        int selectedCount = 0;
        for (String label : labels) {
            View item = described(label);
            check("navigation item is accessible: " + label, item != null && item.isClickable() && item.isFocusable());
            check("navigation label visible: " + label, find(item, view -> view instanceof TextView
                && label.contentEquals(((TextView) view).getText())) != null);
            check("navigation includes an icon: " + label, find(item, view -> view instanceof ImageView) != null);
            check("navigation selection matches current destination", item.isSelected() == selected.equals(label));
            if (item.isSelected()) selectedCount++;
            if (parent == null) {
                check("navigation has a shared container", item.getParent() instanceof ViewGroup);
                parent = (ViewGroup) item.getParent();
            } else check("navigation destinations share one container", item.getParent() == parent);
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
        final CountDownLatch frame = new CountDownLatch(1);
        final View decor = activity.getWindow().getDecorView();
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

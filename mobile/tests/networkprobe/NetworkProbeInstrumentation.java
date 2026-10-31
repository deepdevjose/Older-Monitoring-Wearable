package org.caremonitor.networkprobe;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;

/** Public health by default; existing-account read-only history is explicit opt-in. */
public final class NetworkProbeInstrumentation extends Instrumentation {
    private Bundle arguments;

    @Override public void onCreate(Bundle input) {
        super.onCreate(input);
        arguments = input == null ? new Bundle() : new Bundle(input);
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        int exitCode = Activity.RESULT_CANCELED;
        try {
            String base = arguments.getString("base", "");
            URI endpoint = new URI(base);
            if (!"https".equals(endpoint.getScheme()) || endpoint.getHost() == null
                    || endpoint.getRawUserInfo() != null || endpoint.getRawQuery() != null
                    || endpoint.getRawFragment() != null || !"/api/v1".equals(endpoint.getRawPath())) {
                result.putString("result", "INVALID_ENDPOINT");
                finish(exitCode, result);
                return;
            }
            Context target = getTargetContext();
            Class<?> client = targetClass("ApiClient");
            if ("true".equals(arguments.getString("check_history"))
                    && "true".equals(arguments.getString("check_register_route"))) {
                result.putString("result", "INVALID_READ_ONLY_OPTIONS");
                finish(exitCode, result);
                return;
            }
            if ("true".equals(arguments.getString("check_config"))) {
                // Read only endpoint configuration; never load an account/session/token.
                Class<?> session = targetClass("ApiSession");
                Field defaultBase = session.getDeclaredField("DEFAULT_BASE");
                defaultBase.setAccessible(true);
                Method savedBase = session.getDeclaredMethod("savedBase", Context.class);
                savedBase.setAccessible(true);
                result.putBoolean("default_base_matches", base.equals(defaultBase.get(null)));
                result.putBoolean("saved_base_matches", base.equals(savedBase.invoke(null, target)));
            }
            Method lanAllowed = client.getDeclaredMethod("lanAllowed", Context.class);
            lanAllowed.setAccessible(true);
            if (!Boolean.TRUE.equals(lanAllowed.invoke(null, target))) {
                result.putString("result", "NEEDS_LOCAL_NETWORK_PERMISSION");
                result.putString("detail", "Allow local network access in Android app settings; this probe does not grant permissions.");
                finish(exitCode, result);
                return;
            }
            Method request = client.getDeclaredMethod("request", Context.class, String.class,
                    String.class, String.class, String.class, JSONObject.class);
            request.setAccessible(true);
            // Empty bearer token, GET only, original trust store and hostname checks.
            JSONObject health = (JSONObject) request.invoke(null, target, base, "", "GET", "/health", null);
            if (!"ok".equals(health.optString("status"))) {
                result.putString("result", "UNEXPECTED_HEALTH_RESPONSE");
            } else {
                JSONObject publicHealth = new JSONObject();
                for (String key : new String[]{"status", "mode", "version"}) {
                    Object value = health.opt(key);
                    if (!(value instanceof String) || !((String)value).matches("[A-Za-z0-9_.-]{1,80}")) {
                        throw new IllegalArgumentException("Unexpected public health field");
                    }
                    publicHealth.put(key, value);
                }
                if ("true".equals(arguments.getString("check_register_route"))) {
                    boolean reachable = registrationValidationReached(request, target, base);
                    result.putBoolean("register_validation_route_reachable", reachable);
                    if (!reachable) {
                        result.putString("result", "REGISTER_VALIDATION_ROUTE_FAILED");
                        finish(exitCode, result);
                        return;
                    }
                }
                if ("true".equals(arguments.getString("check_history"))
                        && !checkHistory(target, client, base, result)) {
                    finish(exitCode, result);
                    return;
                }
                result.putString("result", "PASS");
                result.putString("protocol", "HTTPS");
                result.putString("client", "installed ApiClient.request; original CA and hostname validation");
                result.putString("health", publicHealth.toString());
                exitCode = Activity.RESULT_OK;
            }
        } catch (Throwable failure) {
            if (failure instanceof InvocationTargetException && failure.getCause() != null) {
                failure = failure.getCause();
            }
            String message = failure.getMessage();
            result.putString("result", "FAIL");
            try {
                Class<?> failureType = targetClass("ApiClient$Failure");
                if (failureType.isInstance(failure)) {
                    Field status = failureType.getDeclaredField("status");
                    status.setAccessible(true);
                    result.putInt("http_status", status.getInt(failure));
                }
            } catch (ReflectiveOperationException ignored) { }
            // Never emit arbitrary exception messages, response bodies or private state.
            if (message != null && message.contains("certificate or hostname")) {
                result.putString("detail", "CERTIFICATE_OR_HOSTNAME_FAILED");
            } else if (message != null && message.contains("Could not connect securely")) {
                result.putString("detail", "NETWORK_CONNECTION_FAILED");
            } else if (message != null && message.contains("Allow local network access")) {
                result.putString("result", "NEEDS_LOCAL_NETWORK_PERMISSION");
                result.putString("detail", "Local network permission is required; no permission was granted.");
            } else {
                result.putString("detail", failure.getClass().getSimpleName());
            }
        }
        finish(exitCode, result);
    }

    private Class<?> targetClass(String name) throws ClassNotFoundException {
        return Class.forName("org.caremonitor.demo." + name, true, getTargetContext().getClassLoader());
    }

    private boolean checkHistory(Context target, Class<?> client, String base, Bundle result) throws Exception {
        // This is the only call to ApiSession.load, and its caller is gated by
        // check_history. No token, patient ID, reading value or profile is emitted.
        Class<?> sessionClass = targetClass("ApiSession");
        Method load = sessionClass.getDeclaredMethod("load", Context.class);
        load.setAccessible(true);
        Object session = load.invoke(null, target);
        if (session == null) {
            result.putString("result", "NO_VALID_EXISTING_SESSION");
            return false;
        }
        Field sessionBase = sessionClass.getDeclaredField("base");
        sessionBase.setAccessible(true);
        if (!base.equals(sessionBase.get(session))) {
            result.putString("result", "EXISTING_SESSION_ENDPOINT_MISMATCH");
            return false;
        }
        Method call = client.getDeclaredMethod("call", Context.class, sessionClass, String.class, String.class, JSONObject.class);
        call.setAccessible(true);
        JSONObject response = (JSONObject) call.invoke(null, target, session, "GET", "/patients", null);
        JSONArray patients = response.optJSONArray("patients");
        if (patients == null || patients.length() == 0) {
            result.putString("result", "NO_AUTHORIZED_HISTORY_PERSON");
            return false;
        }
        String patient = patients.getJSONObject(0).getString("id");
        if (!patient.matches("[A-Za-z0-9_-]{1,128}")) throw new IllegalArgumentException("Invalid authorized identity");
        Method history = client.getDeclaredMethod("history", Context.class, sessionClass, String.class, String.class, String.class, long.class);
        history.setAccessible(true);
        JSONArray summaries = new JSONArray();
        result.putBoolean("history_existing_session_only", true);
        result.putBoolean("history_read_only", true);
        result.putString("history_patient_scope", "first authorized person returned by GET /patients");
        int queries = 0, nonempty = 0;
        for (String metric : new String[]{"heart_rate_bpm", "spo2_percent", "surface_temp_c", "ambient_temp_c", "acceleration_g"}) {
            for (String period : new String[]{"day", "week", "month"}) {
                long started = SystemClock.elapsedRealtime();
                JSONObject saved = (JSONObject) history.invoke(null, target, session, patient, metric, period, 0L);
                long latency = SystemClock.elapsedRealtime() - started;
                long count = saved.getJSONObject("summary").getLong("count");
                if (count < 0) throw new IllegalArgumentException("Invalid sample count");
                JSONObject coverage = saved.getJSONObject("coverage");
                summaries.put(new JSONObject().put("metric", metric).put("period", period)
                        .put("valid_sample_count", count).put("latency_ms", latency)
                        .put("coverage_complete", coverage.getBoolean("complete")));
                queries++; if (count > 0) nonempty++;
                result.putInt("history_completed_queries", queries);
                result.putInt("history_nonempty_queries", nonempty);
                result.putString("history_summary", summaries.toString());
            }
        }
        result.putString("history_client", "installed ApiClient.history; authorized wearable source only");
        return true;
    }

    private boolean registrationValidationReached(Method request, Context target, String base) throws Exception {
        try {
            // The exact empty object cannot satisfy the canonical registration schema.
            // No identity, password, account token or login operation is supplied.
            request.invoke(null, target, base, "", "POST", "/auth/register", new JSONObject());
            return false;
        } catch (InvocationTargetException wrapper) {
            Throwable failure = wrapper.getCause();
            Class<?> failureType = targetClass("ApiClient$Failure");
            if (!failureType.isInstance(failure)) return false;
            Field status = failureType.getDeclaredField("status");
            status.setAccessible(true);
            return status.getInt(failure) == 422;
        }
    }
}

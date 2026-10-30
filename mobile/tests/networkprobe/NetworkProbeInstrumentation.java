package org.caremonitor.networkprobe;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import org.json.JSONObject;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;

/** Public health request through the installed target's real HTTPS client. */
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

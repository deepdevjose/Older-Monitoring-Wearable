package org.caremonitor.demo;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocketFactory;
import org.json.JSONObject;

/** HTTPS account API, separate from the existing Bluetooth/ingest service. No secrets in logs/errors. */
final class AccountApiClient {
    interface Callback { void onSuccess(JSONObject body); void onError(int status, String message); }
    private final Context context;
    private final AccountSessionStore store;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicInteger generation = new AtomicInteger();
    private volatile boolean closed;
    private volatile boolean localClearFailed;
    private volatile AccountSessionStore.Session session;
    private volatile String endpoint;
    private SSLSocketFactory tls;

    AccountApiClient(Context context) {
        this.context = context.getApplicationContext(); store = new AccountSessionStore(this.context);
        endpoint = store.endpoint();
        try { session = store.load(); } catch (Exception e) { localClearFailed = !store.clear(); session = null; }
        if (endpoint.isEmpty()) endpoint = new RelaySecureSettings(this.context).savedEndpoint();
    }
    boolean hasSession() { AccountSessionStore.Session s = session; return s != null && AccountPresentationPolicy.sessionValid(s.expires, System.currentTimeMillis()); }
    String endpoint() { return endpoint; }
    String username() { AccountSessionStore.Session s = session; return s == null ? "" : s.username; }
    String role() { AccountSessionStore.Session s = session; return s == null ? "" : s.role; }
    boolean sessionRemovalConfirmed() { return !localClearFailed; }
    synchronized void forgetSession() { generation.incrementAndGet(); session = null; localClearFailed = !store.clear(); }
    synchronized void close() { closed = true; generation.incrementAndGet(); worker.shutdownNow(); }

    synchronized void login(String address, String username, String password, Callback callback) {
        final String origin;
        try { origin = AccountPresentationPolicy.origin(address); }
        catch (IllegalArgumentException e) { callback.onError(400, e.getMessage()); return; }
        if (username == null || username.trim().isEmpty() || username.trim().length() > 64 || password == null || password.isEmpty() || password.length() > 128) {
            callback.onError(400, "Enter a username and password (maximum 64 and 128 characters)."); return;
        }
        forgetSession(); endpoint = origin;
        if (localClearFailed) { callback.onError(0, "Cannot securely remove the previous saved session. Check device storage before signing in again."); return; }
        try {
            JSONObject body = new JSONObject().put("username", username.trim()).put("password", password);
            submit(origin, "/accounts/login", "POST", body, false, false, callback);
        } catch (Exception e) { callback.onError(400, "Unable to prepare login."); }
    }
    void me(Callback callback) { request("/accounts/me", "GET", null, callback); }
    void readings(String device, int limit, Callback callback) {
        request("/accounts/readings?device_id=" + query(device) + "&limit=" + Math.max(1, Math.min(100, limit)), "GET", null, callback);
    }
    void classification(String device, long readingId, Callback callback) {
        request("/accounts/classification?device_id=" + query(device) + (readingId > 0 ? "&reading_id=" + readingId : ""), "GET", null, callback);
    }
    void alerts(String device, Callback callback) { alerts(device, 0, callback); }
    void alerts(String device, long afterRevision, Callback callback) {
        request("/accounts/alerts?device_id=" + query(device) + "&limit=100&after_revision=" + Math.max(0, afterRevision), "GET", null, callback);
    }
    void acknowledge(String device, long alertId, Callback callback) {
        if (alertId <= 0) { callback.onError(400, "Select a valid alert."); return; }
        try { request("/accounts/alerts/" + alertId + "/acknowledge", "POST", new JSONObject().put("device_id", device), callback); }
        catch (Exception e) { callback.onError(400, "Invalid alert request."); }
    }
    synchronized void logout(Callback callback) {
        AccountSessionStore.Session s = session;
        if (s == null) { forgetSession(); callback.onSuccess(new JSONObject()); return; }
        generation.incrementAndGet();
        // Immediately invalidate local use; the captured token is used only to revoke remotely.
        session = null; localClearFailed = !store.clear();
        submit(s.origin, "/accounts/logout", "POST", new JSONObject(), true, true, callback, s);
    }
    private synchronized void request(String path, String method, JSONObject body, Callback callback) {
        AccountSessionStore.Session s = session;
        if (s == null || !hasSession()) { forgetSession(); callback.onError(401, AccountPresentationPolicy.error(401)); return; }
        submit(s.origin, path, method, body, true, false, callback, s);
    }
    private static String query(String value) {
        try { return URLEncoder.encode(value == null ? "" : value, "UTF-8"); }
        catch (Exception e) { throw new IllegalArgumentException("Invalid device identifier."); }
    }
    private boolean current(int epoch) { return !closed && epoch == generation.get(); }
    private void success(int epoch, Callback callback, JSONObject body) { main.post(() -> { if (current(epoch)) callback.onSuccess(body); }); }
    private void failure(int epoch, Callback callback, int status, String message) { main.post(() -> { if (current(epoch)) callback.onError(status, message); }); }

    private void submit(String origin, String path, String method, JSONObject body, boolean authenticated, boolean signOut, Callback callback) {
        submit(origin, path, method, body, authenticated, signOut, callback, session);
    }
    private void submit(String origin, String path, String method, JSONObject body, boolean authenticated, boolean signOut, Callback callback, AccountSessionStore.Session selected) {
        if (closed) return;
        int epoch = generation.get();
        worker.execute(() -> {
            if (!current(epoch)) return;
            HttpsURLConnection connection = null;
            try {
                if (authenticated && (selected == null || !AccountPresentationPolicy.sessionValid(selected.expires, System.currentTimeMillis()))) {
                    clearIfCurrent(epoch); failure(epoch, callback, 401, AccountPresentationPolicy.error(401)); return;
                }
                if (tls == null) tls = ServerRelay.createTlsFactory(context);
                ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
                Network wifi = null;
                if (manager != null) for (Network network : manager.getAllNetworks()) {
                    NetworkCapabilities caps = manager.getNetworkCapabilities(network);
                    if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) { wifi = network; break; }
                }
                if (wifi == null) throw new java.io.IOException("Wi-Fi unavailable");
                connection = (HttpsURLConnection) wifi.openConnection(new URL(origin + path));
                connection.setSSLSocketFactory(tls); // Retain the default hostname verifier.
                connection.setConnectTimeout(5000); connection.setReadTimeout(5000);
                connection.setInstanceFollowRedirects(false); connection.setUseCaches(false);
                connection.setRequestMethod(method); connection.setRequestProperty("Accept", "application/json");
                if (authenticated) connection.setRequestProperty("Authorization", "Bearer " + selected.token);
                if (body != null) {
                    byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    connection.setDoOutput(true); connection.setFixedLengthStreamingMode(bytes.length);
                    try (OutputStream out = connection.getOutputStream()) { out.write(bytes); }
                }
                int status = connection.getResponseCode();
                if (!current(epoch)) return;
                if (status < 200 || status >= 300) {
                    if (status == 401) clearIfCurrent(epoch);
                    failure(epoch, callback, status, AccountPresentationPolicy.error(status)); return;
                }
                JSONObject result;
                try (InputStream input = connection.getInputStream()) { result = ServerAuthWire.response(input, 2 * 1024 * 1024); }
                if (!current(epoch)) return;
                if ("/accounts/login".equals(path)) {
                    if (!"Bearer".equals(ServerAuthWire.text(result, "token_type", 20))) throw new IllegalArgumentException();
                    long ttl = ServerAuthWire.integer(result, "expires_in");
                    if (ttl < 1 || ttl > 86400) throw new IllegalArgumentException();
                    AccountSessionStore.Session next = new AccountSessionStore.Session(origin, ServerAuthWire.text(result, "access_token", 100),
                            ServerAuthWire.text(result, "username", 64), ServerAuthWire.text(result, "role", 20), System.currentTimeMillis() + ttl * 1000);
                    if (!saveIfCurrent(epoch, next)) return;
                    // UI does not receive or display the bearer credential.
                    result.remove("access_token");
                } else if ("/accounts/me".equals(path)) {
                    String verifiedUser = ServerAuthWire.text(result, "username", 64);
                    String verifiedRole = ServerAuthWire.text(result, "role", 20);
                    if (!selected.username.equals(verifiedUser) || !AccountPresentationPolicy.role(verifiedRole)
                            || result.optJSONArray("devices") == null) throw new IllegalArgumentException();
                    AccountSessionStore.Session next = new AccountSessionStore.Session(origin, selected.token, verifiedUser, verifiedRole, selected.expires);
                    if (!saveIfCurrent(epoch, next)) return;
                }
                success(epoch, callback, result);
            } catch (SSLException e) {
                failure(epoch, callback, 0, "HTTPS verification failed. Check the server certificate, address and phone date.");
            } catch (java.io.IOException e) {
                failure(epoch, callback, 0, "Server unreachable. Connect to Wi-Fi and check the server address. Previously stored readings are not live.");
            } catch (Exception e) {
                failure(epoch, callback, 0, "Cannot verify the server response or save the encrypted session. No classification is assumed.");
            } finally {
                if (connection != null) connection.disconnect();
                if (signOut) clearIfCurrent(epoch);
            }
        });
    }
    private synchronized boolean saveIfCurrent(int epoch, AccountSessionStore.Session next) throws Exception {
        if (!current(epoch)) return false;
        store.save(next); session = next; localClearFailed = false; return true;
    }
    private synchronized void clearIfCurrent(int epoch) {
        if (current(epoch)) { session = null; localClearFailed = !store.clear(); }
    }
}

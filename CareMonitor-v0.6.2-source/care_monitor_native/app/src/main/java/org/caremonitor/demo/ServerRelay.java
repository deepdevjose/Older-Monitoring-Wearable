package org.caremonitor.demo;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AtomicFile;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

/**
 * Foreground-service-owned, Wi-Fi-only HTTPS outbox for real BLE snapshots.
 *
 * The caller controls start/pause and must never enqueue simulated readings.
 * All queue, credential, TLS, and HTTP work happens on one bounded-input worker.
 * Delivery is at least once: a lost acknowledgement can cause a repeated POST.
 */
public final class ServerRelay {
    public static final String DEFAULT_ENDPOINT = "https://10.238.118.117:8000/sensor-data";

    public interface Listener {
        /** Always delivered on the main thread; status contains no payload or API key. */
        void onStatus(String status, int queued, long lastSuccessMs);
    }

    private static final int MAX_QUEUE_FILE_BYTES = 4 * 1024 * 1024;
    private static final int TIMEOUT_MS = 5000;
    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ScheduledThreadPoolExecutor worker;
    private final ExecutorService cancellationWorker;
    private final Object cancellationLock = new Object();
    private HttpsURLConnection lastCancellationTarget;
    private final ArrayDeque<String> queue = new ArrayDeque<>();
    private final AtomicLong generation = new AtomicLong();
    private final AtomicLong configurationGeneration = new AtomicLong();
    private final AtomicInteger pendingEnqueues = new AtomicInteger();
    private final AtomicLong pendingDrops = new AtomicLong();
    private final AtomicBoolean notificationPosted = new AtomicBoolean();
    private volatile boolean ready;
    private volatile boolean running;
    private volatile boolean closed;
    private volatile boolean configured;
    private volatile int queued;
    private volatile long lastSuccess;
    private volatile long dropped;
    private volatile String endpoint = "";
    private volatile String status = "Loading saved server settings…";
    private volatile HttpsURLConnection activeConnection;
    // The following fields belong exclusively to the worker.
    private String apiKey = "";
    private volatile String queueEndpoint = "";
    private String blockedReason = "";
    private boolean storageBlocked;
    private boolean callbackRegistered;
    private int failures;
    private long nextAllowedMs;
    private long lastAttemptMs;
    private AtomicFile queueFile;
    private RelaySecureSettings secureSettings;
    private SSLSocketFactory tlsFactory;
    private ConnectivityManager connectivity;
    private ScheduledFuture<?> nextPump;

    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network network) { wake(); }
        @Override public void onLost(Network network) { wake(); }
        @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) { wake(); }
    };

    public ServerRelay(Context context, Listener listener) {
        if (context == null || listener == null) throw new IllegalArgumentException("Context and listener are required.");
        this.context = context.getApplicationContext();
        this.listener = listener;
        worker = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "CareMonitor-HttpsRelay");
            thread.setDaemon(true);
            return thread;
        });
        worker.setRemoveOnCancelPolicy(true);
        worker.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        cancellationWorker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "CareMonitor-RelayCancellation");
            thread.setDaemon(true);
            return thread;
        });
        execute(this::initialise);
        notifyMain();
    }

    /**
     * Validates immediately, then saves the API key using AndroidKeyStore AES-GCM.
     * A queue belongs to one server. Clear it explicitly before choosing a new server.
     * Saving is asynchronous; wait for onStatus / isConfigured before reporting success.
     */
    public void configure(String newEndpoint, String newApiKey) throws IllegalArgumentException {
        final String canonical = RelayPolicy.endpoint(newEndpoint);
        RelayPolicy.apiKey(newApiKey);
        if (closed) throw new IllegalArgumentException("The relay is closed.");
        if (!ready) throw new IllegalArgumentException("Server settings are still loading. Please try again shortly.");
        if ((queued > 0 && !canonical.equals(queueEndpoint))
                || (pendingEnqueues.get() > 0 && !canonical.equals(endpoint))) {
            throw new IllegalArgumentException("Queued samples belong to the current server. Send them or explicitly clear the queue before changing server.");
        }
        final long configurationEpoch = configurationGeneration.incrementAndGet();
        generation.incrementAndGet();
        configured = false;
        cancelConnection();
        publish("Saving encrypted server settings…");
        execute(() -> {
            if (closed || configurationEpoch != configurationGeneration.get()) return;
            cancelPump();
            if (!queue.isEmpty() && !canonical.equals(queueEndpoint)) {
                publish("Queued samples belong to another server. Clear the queue before changing server.");
                return;
            }
            try {
                secureSettings.save(canonical, newApiKey);
                endpoint = canonical;
                apiKey = newApiKey;
                if (queue.isEmpty()) {
                    queueEndpoint = canonical;
                    if (!storageBlocked) saveQueue();
                }
                configured = true;
                blockedReason = "";
                failures = 0;
                nextAllowedMs = 0;
                if (storageBlocked) publish("Queue storage needs attention. Clear the queue explicitly to recover.");
                else if (running) schedulePump(0);
                else publish("Server settings saved · forwarding paused");
            } catch (Exception exception) {
                // Never display exception text; network / security libraries may include sensitive details.
                configured = false;
                publish("Could not save server settings securely. Check phone storage and try again.");
            }
        });
    }

    /** Start sending only from a real-data foreground session. Does not start a service itself. */
    public void start() {
        if (closed) return;
        running = true;
        execute(() -> { if (running && !closed) schedulePump(0); });
    }

    /** Pause forwarding but preserve encrypted settings and the durable pending queue. */
    public void pause() {
        if (closed) return;
        running = false;
        generation.incrementAndGet();
        cancelConnection();
        execute(() -> {
            cancelPump();
            publish("Forwarding paused · pending samples kept on this phone");
        });
    }

    /** Accept one complete real JSON snapshot, preserving sample_id and boot_nonce if provided. */
    public void enqueue(String rawJson) {
        if (closed) return;
        if (!RelayPolicy.bodyFits(rawJson)) {
            publish("Snapshot rejected · JSON must be no larger than 16 KiB");
            return;
        }
        if (pendingEnqueues.incrementAndGet() > RelayPolicy.MAX_PENDING_ENQUEUES) {
            pendingEnqueues.decrementAndGet();
            pendingDrops.incrementAndGet();
            publish("Incoming queue busy · newest sample dropped");
            return;
        }
        if (!execute(() -> {
            try { enqueueOnWorker(rawJson); }
            finally { pendingEnqueues.decrementAndGet(); }
        })) pendingEnqueues.decrementAndGet();
    }

    /** Explicit user action: permanently discard queued samples, keeping server credentials. */
    public void clearQueue() {
        if (closed) return;
        generation.incrementAndGet();
        cancelConnection();
        execute(() -> {
            cancelPump();
            ArrayDeque<String> previous = new ArrayDeque<>(queue);
            String previousEndpoint = queueEndpoint;
            long previousDropped = dropped;
            queue.clear();
            queueEndpoint = endpoint;
            dropped = 0;
            pendingDrops.set(0);
            try {
                saveQueue();
                queued = 0;
                storageBlocked = false;
                publish("Pending queue cleared");
                if (running) schedulePump(0);
            } catch (IOException exception) {
                queue.addAll(previous);
                queueEndpoint = previousEndpoint;
                dropped = previousDropped;
                queued = queue.size();
                storageBlocked = true;
                publish("Could not clear queue storage. Forwarding stopped; existing samples retained.");
            }
        });
    }

    /** Cancels HTTP, unregisters connectivity callbacks, and drains accepted persistence work. */
    public void close() {
        if (closed) return;
        closed = true;
        running = false;
        generation.incrementAndGet();
        cancelConnection();
        execute(() -> {
            cancelPump();
            if (callbackRegistered && connectivity != null) {
                try { connectivity.unregisterNetworkCallback(networkCallback); }
                catch (RuntimeException ignored) { /* Already removed by the platform. */ }
            }
            callbackRegistered = false;
            apiKey = "";
            synchronized (cancellationLock) { lastCancellationTarget = null; }
            publish("Forwarding stopped · pending samples saved on this phone");
            cancellationWorker.shutdown();
            worker.shutdown();
        });
    }

    public String endpoint() { return ready ? endpoint : ""; }
    /** Endpoint to which pending samples belong, useful for an explicit clear-queue confirmation. */
    public String queuedEndpoint() { return ready ? queueEndpoint : ""; }
    public boolean isConfigured() { return ready && configured; }
    public int queuedCount() { return queued; }
    public String status() { return status; }
    /** Wall-clock milliseconds of the last acknowledged POST; zero means none recorded. */
    public long lastSuccessMs() { return lastSuccess; }

    private void initialise() {
        endpoint = DEFAULT_ENDPOINT;
        secureSettings = new RelaySecureSettings(context);
        queueFile = new AtomicFile(new File(context.getFilesDir(), "real_sensor_relay_queue_v1.json"));
        connectivity = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        String settingsIssue = "";
        try {
            RelaySecureSettings.Saved saved = secureSettings.load();
            if (!saved.endpoint.isEmpty()) endpoint = RelayPolicy.endpoint(saved.endpoint);
            if (!saved.apiKey.isEmpty()) {
                RelayPolicy.apiKey(saved.apiKey);
                apiKey = saved.apiKey;
                configured = true;
            }
        } catch (Exception exception) {
            // Recover the endpoint for queue binding even when a restored / invalidated key cannot be decrypted.
            try {
                String saved = secureSettings.savedEndpoint();
                if (!saved.isEmpty()) endpoint = RelayPolicy.endpoint(saved);
            } catch (RuntimeException ignored) { /* Keep the documented default. */ }
            settingsIssue = "Saved API key is unavailable. Enter the server key again.";
        }
        try { loadQueue(); }
        catch (Exception exception) { storageBlocked = true; }
        try { tlsFactory = createTlsFactory(); }
        catch (Exception exception) { tlsFactory = null; }
        if (connectivity != null) {
            try {
                connectivity.registerNetworkCallback(new NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), networkCallback);
                callbackRegistered = true;
            } catch (RuntimeException ignored) { /* Polling is still used while a real session runs. */ }
        }
        ready = true;
        if (storageBlocked) publish("Saved queue could not be read. Forwarding paused; clear the queue explicitly to recover.");
        else if (!settingsIssue.isEmpty()) publish(settingsIssue);
        else if (!configured) publish("Set up an HTTPS endpoint and API key to forward real readings");
        else if (running) schedulePump(0);
        else publish("Forwarding paused · saved queue ready");
    }

    private void enqueueOnWorker(String rawJson) {
        absorbPendingDrops();
        if (storageBlocked) {
            dropped++;
            publish("Queue storage unavailable · newest sample dropped; clear the queue explicitly to recover");
            return;
        }
        final String canonical;
        try { canonical = canonicalSnapshot(rawJson); }
        catch (JSONException | IllegalArgumentException exception) {
            publish("Snapshot rejected · require a complete JSON object for device elder-demo-01");
            return;
        }
        if (queue.size() >= RelayPolicy.MAX_QUEUE_SAMPLES) {
            dropped++;
            try { saveQueue(); }
            catch (IOException exception) { storageBlocked = true; }
            publish("Queue full (200 samples) · newest sample dropped");
            return;
        }
        if (!queue.isEmpty() && !queueEndpoint.equals(endpoint)) {
            dropped++;
            publish("Queue belongs to a different server · new sample rejected until the queue is explicitly cleared");
            return;
        }
        String previousEndpoint = queueEndpoint;
        if (queue.isEmpty()) queueEndpoint = endpoint;
        queue.addLast(canonical);
        try {
            saveQueue();
            queued = queue.size();
            if (running && blockedReason.isEmpty()) schedulePump(0);
            else publish(blockedReason.isEmpty() ? "Forwarding paused · real sample saved" : blockedReason);
        } catch (IOException exception) {
            queue.removeLast();
            queueEndpoint = previousEndpoint;
            queued = queue.size();
            dropped++;
            storageBlocked = true;
            publish("Could not save the newest sample. Forwarding stopped; previous queue retained.");
        }
    }

    private static String canonicalSnapshot(String raw) throws JSONException {
        if (!RelayPolicy.bodyFits(raw)) throw new IllegalArgumentException("Oversized sample.");
        JSONTokener tokens = new JSONTokener(raw);
        Object parsed = tokens.nextValue();
        if (!(parsed instanceof JSONObject) || tokens.nextClean() != 0) throw new JSONException("Not one JSON object.");
        JSONObject object = (JSONObject) parsed;
        if (!RelayPolicy.EXPECTED_DEVICE_ID.equals(object.opt("device_id"))) throw new JSONException("Device mismatch.");
        // JSONObject serialization produces a valid, compact JSON body without changing fields.
        String result = object.toString();
        if (!RelayPolicy.bodyFits(result)) throw new IllegalArgumentException("Oversized sample.");
        return result;
    }

    private void loadQueue() throws IOException, JSONException {
        if (!queueFile.getBaseFile().exists() && !new File(queueFile.getBaseFile().getPath() + ".bak").exists()) {
            queueEndpoint = endpoint;
            return;
        }
        JSONObject saved;
        try (FileInputStream input = queueFile.openRead()) {
            saved = new JSONObject(new String(readLimited(input, MAX_QUEUE_FILE_BYTES), StandardCharsets.UTF_8));
        }
        if (saved.optInt("version", 0) != 1) throw new IOException("Unknown queue version.");
        String bound = RelayPolicy.endpoint(saved.getString("endpoint"));
        JSONArray samples = saved.getJSONArray("samples");
        if (samples.length() > RelayPolicy.MAX_QUEUE_SAMPLES) throw new IOException("Queue too large.");
        List<String> validated = new ArrayList<>();
        for (int i = 0; i < samples.length(); i++) validated.add(canonicalSnapshot(samples.getJSONObject(i).toString()));
        queueEndpoint = bound;
        queue.addAll(validated);
        queued = queue.size();
        dropped = Math.max(0, saved.optLong("dropped", 0));
        lastSuccess = Math.max(0, saved.optLong("last_success_ms", 0));
    }

    private void saveQueue() throws IOException {
        absorbPendingDrops();
        FileOutputStream output = null;
        try {
            JSONObject saved = new JSONObject();
            saved.put("version", 1);
            saved.put("endpoint", queueEndpoint.isEmpty() ? endpoint : queueEndpoint);
            saved.put("dropped", dropped);
            saved.put("last_success_ms", lastSuccess);
            JSONArray samples = new JSONArray();
            for (String sample : queue) samples.put(new JSONObject(sample));
            saved.put("samples", samples);
            byte[] bytes = saved.toString().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_QUEUE_FILE_BYTES) throw new IOException("Queue exceeds storage limit.");
            output = queueFile.startWrite();
            output.write(bytes);
            output.flush();
            output.getFD().sync();
            queueFile.finishWrite(output);
        } catch (Exception exception) {
            if (output != null) queueFile.failWrite(output);
            throw new IOException("Cannot update private queue.");
        }
    }

    private void pump() {
        nextPump = null;
        if (closed || !running) return;
        if (storageBlocked) { publish("Queue storage unavailable · forwarding paused; explicitly clear the queue to recover"); return; }
        if (!configured) { publish("Server setup required · real samples kept on this phone"); return; }
        if (!blockedReason.isEmpty()) { publish(blockedReason); return; }
        if (tlsFactory == null) { publish("TLS certificate setup unavailable. Reinstall the complete app package."); return; }
        if (queue.isEmpty()) { publish("Ready · waiting for real sensor samples"); return; }
        if (!endpoint.equals(queueEndpoint)) {
            publish("Queue belongs to another server. Restore its endpoint or explicitly clear the queue.");
            return;
        }
        long now = SystemClock.elapsedRealtime();
        long wait = Math.max(nextAllowedMs - now, lastAttemptMs == 0 ? 0
                : RelayPolicy.MIN_SEND_INTERVAL_MS - (now - lastAttemptMs));
        if (wait > 0) { schedulePump(wait); return; }
        Network wifi;
        try { wifi = wifiNetwork(); }
        catch (RuntimeException exception) { wifi = null; }
        if (wifi == null) {
            publish("Waiting for Wi-Fi · real samples saved on this phone");
            schedulePump(3000);
            return;
        }
        long epoch = generation.get();
        String head = queue.peekFirst();
        lastAttemptMs = SystemClock.elapsedRealtime();
        publish("Sending real sample over Wi-Fi…");
        HttpsURLConnection connection = null;
        try {
            // Binding this connection to an actual Wi-Fi Network prevents cellular fallback.
            connection = (HttpsURLConnection) wifi.openConnection(new URL(endpoint));
            connection.setSSLSocketFactory(tlsFactory);
            // Keep Android's default HostnameVerifier. Never accept mismatched hosts or arbitrary certificates.
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("X-API-Key", apiKey);
            byte[] bytes = head.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            activeConnection = connection;
            if (!current(epoch)) return;
            try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            int code = connection.getResponseCode();
            if (!current(epoch)) return;
            if (code >= 200 && code < 300) {
                JSONObject response;
                try (InputStream input = connection.getInputStream()) {
                    JSONTokener tokens = new JSONTokener(new String(
                            readLimited(input, RelayPolicy.MAX_RESPONSE_BYTES), StandardCharsets.UTF_8));
                    Object value = tokens.nextValue();
                    if (!(value instanceof JSONObject) || tokens.nextClean() != 0) throw new JSONException("Invalid acknowledgement.");
                    response = (JSONObject) value;
                }
                if (!current(epoch)) return;
                if (!RelayPolicy.isAcknowledgement(response.optString("status", ""), response.opt("record_id"))) {
                    retry("Server response did not confirm storage · sample retained; retry may duplicate it", null);
                    return;
                }
                long previousSuccess = lastSuccess;
                lastSuccess = System.currentTimeMillis();
                queue.removeFirst();
                try { saveQueue(); }
                catch (IOException exception) {
                    queue.addFirst(head);
                    lastSuccess = previousSuccess;
                    storageBlocked = true;
                    publish("Server acknowledged the sample, but phone queue could not be updated. Paused to avoid repeated sends.");
                    return;
                }
                queued = queue.size();
                failures = 0;
                nextAllowedMs = 0;
                publish("Server acknowledged real sample");
                if (!queue.isEmpty()) schedulePump(RelayPolicy.MIN_SEND_INTERVAL_MS);
            } else if (RelayPolicy.shouldPauseForHttp(code)) {
                blockedReason = code == 401 || code == 403
                        ? "Server rejected the API key (HTTP " + code + "). Save corrected server settings to resume."
                        : "Server rejected this POST (HTTP " + code + "). Queue retained; correct server settings or payload, then save settings to resume.";
                publish(blockedReason);
            } else {
                retry("Server unavailable (HTTP " + code + ") · sample retained", connection.getHeaderField("Retry-After"));
            }
        } catch (SSLException exception) {
            if (current(epoch)) retry("TLS verification failed · check server certificate, hostname, and phone date", null);
        } catch (Exception exception) {
            if (current(epoch)) retry("Wi-Fi delivery unconfirmed · sample retained; a retry may duplicate a stored sample", null);
        } finally {
            if (activeConnection == connection) activeConnection = null;
            if (connection != null) connection.disconnect();
        }
    }

    private void retry(String reason, String retryAfter) {
        failures = Math.min(1000, failures + 1);
        long delay = RelayPolicy.retryAfterMs(retryAfter, RelayPolicy.retryDelayMs(failures));
        nextAllowedMs = SystemClock.elapsedRealtime() + delay;
        publish(reason + " · retry in " + (delay / 1000) + "s");
        schedulePump(delay);
    }

    private boolean current(long epoch) { return !closed && running && epoch == generation.get(); }

    private Network wifiNetwork() {
        if (connectivity == null) return null;
        for (Network network : connectivity.getAllNetworks()) {
            NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(network);
            if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    && !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return network;
        }
        return null;
    }

    private void wake() { if (!closed) execute(() -> { if (running) schedulePump(0); }); }

    private void schedulePump(long delay) {
        if (closed || !running) return;
        cancelPump();
        nextPump = worker.schedule(this::pump, Math.max(0, delay), TimeUnit.MILLISECONDS);
    }

    private void cancelPump() {
        if (nextPump != null) { nextPump.cancel(false); nextPump = null; }
    }

    private void cancelConnection() {
        final HttpsURLConnection connection = activeConnection;
        if (connection == null) return;
        synchronized (cancellationLock) {
            if (lastCancellationTarget == connection) return;
            lastCancellationTarget = connection;
        }
        // disconnect() can wait on a platform socket lock. Never call it on an Activity's thread.
        try { cancellationWorker.execute(connection::disconnect); }
        catch (RejectedExecutionException ignored) { /* Worker finalization also disconnects its connection. */ }
    }

    private boolean execute(Runnable task) {
        try { worker.execute(task); return true; }
        catch (RejectedExecutionException exception) { return false; }
    }

    private void absorbPendingDrops() {
        long rejected = pendingDrops.getAndSet(0);
        dropped = Math.min(Long.MAX_VALUE - rejected, dropped) + rejected;
    }

    private void publish(String message) {
        long lost = dropped + pendingDrops.get();
        status = lost > 0 ? message + " · " + lost + " dropped in this queue" : message;
        notifyMain();
    }

    private void notifyMain() {
        if (notificationPosted.compareAndSet(false, true)) main.post(() -> {
            notificationPosted.set(false);
            listener.onStatus(status, queued, lastSuccess);
        });
    }

    private static byte[] readLimited(InputStream input, int maximum) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (output.size() + count > maximum) throw new IOException("Response exceeds limit.");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private SSLSocketFactory createTlsFactory() throws Exception {
        TrustManagerFactory platform = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        platform.init((KeyStore) null);
        KeyStore local = KeyStore.getInstance(KeyStore.getDefaultType());
        local.load(null, null);
        try (InputStream input = context.getAssets().open("server_ca.crt")) {
            Certificate certificate = CertificateFactory.getInstance("X.509").generateCertificate(input);
            local.setCertificateEntry("teammate-server-public-certificate", certificate);
        }
        TrustManagerFactory project = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        project.init(local);
        final X509TrustManager system = x509(platform.getTrustManagers());
        final X509TrustManager bundled = x509(project.getTrustManagers());
        X509TrustManager trust = new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String type) throws CertificateException {
                system.checkClientTrusted(chain, type);
            }
            @Override public void checkServerTrusted(X509Certificate[] chain, String type) throws CertificateException {
                try { system.checkServerTrusted(chain, type); }
                catch (CertificateException exception) {
                    // The supplied certificate can be a self-signed leaf trust anchor;
                    // explicitly enforce its dates as some trust stores omit anchor expiry checks.
                    for (X509Certificate certificate : chain) certificate.checkValidity();
                    bundled.checkServerTrusted(chain, type);
                }
            }
            @Override public X509Certificate[] getAcceptedIssuers() {
                X509Certificate[] first = system.getAcceptedIssuers(), second = bundled.getAcceptedIssuers();
                X509Certificate[] all = new X509Certificate[first.length + second.length];
                System.arraycopy(first, 0, all, 0, first.length);
                System.arraycopy(second, 0, all, first.length, second.length);
                return all;
            }
        };
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(null, new TrustManager[] { trust }, null);
        return tls.getSocketFactory();
    }

    private static X509TrustManager x509(TrustManager[] managers) throws CertificateException {
        for (TrustManager manager : managers) if (manager instanceof X509TrustManager) return (X509TrustManager) manager;
        throw new CertificateException("No X509 trust manager.");
    }
}

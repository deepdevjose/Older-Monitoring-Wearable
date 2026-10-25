package org.caremonitor.demo;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;

/** Transport limits shared by the Android relay and its plain JVM checks. */
public final class RelayPolicy {
    public static final int MAX_BODY_BYTES = 16 * 1024;
    public static final int MAX_QUEUE_SAMPLES = 200;
    public static final int MAX_PENDING_ENQUEUES = 32;
    public static final int MAX_RESPONSE_BYTES = 64 * 1024;
    public static final long MIN_SEND_INTERVAL_MS = 250;
    public static final String EXPECTED_DEVICE_ID = "elder-demo-01";

    private RelayPolicy() {}

    public static String endpoint(String input) {
        if (input == null || input.trim().isEmpty()) {
            throw new IllegalArgumentException("Enter the full HTTPS sensor-data endpoint.");
        }
        try {
            URI uri = new URI(input.trim()).normalize();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getHost().isEmpty() || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || uri.getPort() == 0 || uri.getPort() < -1 || uri.getPort() > 65535) {
                throw new IllegalArgumentException("Use an HTTPS URL with a host, without credentials, query, or fragment.");
            }
            String path = uri.getRawPath();
            if (path == null || path.isEmpty() || "/".equals(path)) {
                throw new IllegalArgumentException("Include the POST path, normally /sensor-data.");
            }
            // Canonical authority prevents spelling changes from rebinding a queue.
            String host = uri.getHost().toLowerCase(java.util.Locale.ROOT);
            if (host.indexOf(':') >= 0 && !host.startsWith("[")) host = "[" + host + "]";
            int port = uri.getPort();
            String authority = host + (port == -1 || port == 443 ? "" : ":" + port);
            return "https://" + authority + path;
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("Enter a valid HTTPS sensor-data endpoint.");
        }
    }

    public static void apiKey(String key) {
        if (key == null || key.length() < 16 || key.length() > 1024) {
            throw new IllegalArgumentException("The API key must contain 16 to 1024 characters.");
        }
        for (int i = 0; i < key.length(); i++) {
            char value = key.charAt(i);
            if (value < 33 || value > 126) {
                throw new IllegalArgumentException("Use the exact printable API key, without spaces or line breaks.");
            }
        }
    }

    public static boolean bodyFits(String body) {
        // Checking characters first also avoids allocating a huge UTF-8 buffer.
        return body != null && body.length() <= MAX_BODY_BYTES
                && body.getBytes(StandardCharsets.UTF_8).length <= MAX_BODY_BYTES;
    }

    public static long retryDelayMs(int failures) {
        int exponent = failures <= 1 ? 0 : Math.min(failures - 1, 5);
        return Math.min(30000L, 1000L << exponent);
    }

    public static long retryAfterMs(String header, long fallback) {
        if (header == null) return fallback;
        try {
            long seconds = Long.parseLong(header.trim());
            return seconds >= 0 ? Math.max(1000L, Math.min(seconds, 60L) * 1000L) : fallback;
        } catch (NumberFormatException exception) {
            // Date-format Retry-After is deliberately not interpreted using an unreliable phone clock.
            return fallback;
        }
    }

    public static boolean shouldPauseForHttp(int code) {
        return (code >= 300 && code < 400)
                || (code >= 400 && code < 500 && code != 408 && code != 425 && code != 429);
    }

    public static boolean isAcknowledgement(String status, Object recordId) {
        if (!"received".equals(status) || !(recordId instanceof Number)) return false;
        double value = ((Number) recordId).doubleValue();
        return !Double.isNaN(value) && !Double.isInfinite(value) && value > 0
                && value == Math.rint(value);
    }
}

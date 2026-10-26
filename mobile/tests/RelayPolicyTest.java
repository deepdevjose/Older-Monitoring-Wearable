import java.util.Locale;
import org.caremonitor.demo.RelayPolicy;

/** Plain-JVM checks for endpoint, HTTP acknowledgement, queue/body, and retry policy. */
public final class RelayPolicyTest {
    private static int checks;
    private static void check(boolean condition, String label) {
        checks++;
        if (!condition) throw new AssertionError(label);
    }
    private static void badUrl(String value) {
        boolean rejected = false;
        try { RelayPolicy.endpoint(value); } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "Reject unsafe endpoint: " + value);
    }
    private static void badKey(String value) {
        boolean rejected = false;
        try { RelayPolicy.apiKey(value); } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "Reject malformed credential");
    }
    private static String repeat(String value, int count) {
        StringBuilder result = new StringBuilder(value.length() * count);
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }
    public static void main(String[] arguments) {
        check(RelayPolicy.endpoint("  HTTPS://Example.COM:443/sensor-data  ")
                .equals("https://example.com/sensor-data"), "Canonical HTTPS authority");
        check(RelayPolicy.endpoint("https://10.238.118.117:8000/sensor-data")
                .equals("https://10.238.118.117:8000/sensor-data"), "LAN endpoint with port");
        check(RelayPolicy.endpoint("https://[::1]:8000/sensor-data")
                .equals("https://[::1]:8000/sensor-data"), "IPv6 preserved");
        check(RelayPolicy.endpoint("https://example.com/v1/../sensor-data")
                .equals("https://example.com/sensor-data"), "Path normalized for queue binding");
        Locale previous = Locale.getDefault();
        Locale.setDefault(new Locale("tr", "TR"));
        try {
            check(RelayPolicy.endpoint("https://IOT.EXAMPLE.COM/sensor-data")
                    .equals("https://iot.example.com/sensor-data"), "Canonicalization independent of phone locale");
        } finally { Locale.setDefault(previous); }
        badUrl(null); badUrl(""); badUrl("http://example.com/sensor-data");
        badUrl("https://example.com"); badUrl("https://example.com/");
        badUrl("https://user:password@example.com/sensor-data");
        badUrl("https://example.com/sensor-data?key=secret");
        badUrl("https://example.com/sensor-data#fragment");
        badUrl("https://example.com:0/sensor-data");
        badUrl("https://example.com:65536/sensor-data");
        badUrl("https:///sensor-data"); badUrl("file:///sensor-data");
        badUrl("https://example.com/sensor\ndata");
        badKey(null); badKey("too-short"); badKey("123456789012345\n");
        badKey("123456789012345 "); badKey("123456789012345\u00e9");
        badKey(repeat("x", 1025));
        RelayPolicy.apiKey(repeat("x", 16));
        check(true, "Minimum valid key accepted");
        RelayPolicy.apiKey(repeat("x", 1024));
        check(true, "Maximum valid key accepted");
        check(!RelayPolicy.bodyFits(null), "Null body rejected");
        check(RelayPolicy.bodyFits(repeat("x", 16384)), "Exact 16 KiB accepted");
        check(!RelayPolicy.bodyFits(repeat("x", 16385)), "Oversized body rejected");
        check(RelayPolicy.bodyFits(repeat("\u4e2d", 5461)), "UTF-8 limit below boundary");
        check(!RelayPolicy.bodyFits(repeat("\u4e2d", 5462)), "UTF-8 bytes, not character count");
        check(RelayPolicy.MAX_QUEUE_SAMPLES == 200, "Bounded 200-sample durable queue");
        check(RelayPolicy.MAX_PENDING_ENQUEUES <= 32, "Bounded transient producer input");
        check(RelayPolicy.MIN_SEND_INTERVAL_MS >= 100, "Shared server rate limit respected");
        check(RelayPolicy.retryDelayMs(0) == 1000, "Initial retry delay");
        check(RelayPolicy.retryDelayMs(1) == 1000, "First failure delay");
        check(RelayPolicy.retryDelayMs(3) == 4000, "Exponential retry");
        check(RelayPolicy.retryDelayMs(1000) == 30000, "Retry upper bound");
        check(RelayPolicy.retryDelayMs(Integer.MIN_VALUE) == 1000, "Negative retry count safe");
        check(RelayPolicy.retryAfterMs("5", 3000) == 5000, "Retry-After seconds");
        check(RelayPolicy.retryAfterMs("0", 3000) == 1000, "Retry-After lower bound");
        check(RelayPolicy.retryAfterMs("999999999", 3000) == 60000, "Retry-After upper bound");
        check(RelayPolicy.retryAfterMs("-10", 3000) == 3000, "Negative Retry-After ignored");
        check(RelayPolicy.retryAfterMs(null, 3000) == 3000, "Missing Retry-After fallback");
        check(RelayPolicy.retryAfterMs("Tue, 20 Oct 2026 10:00:00 GMT", 3000) == 3000,
                "Date-format Retry-After safe fallback");
        for (int code : new int[] {301, 302, 307, 308, 400, 401, 403, 404, 413, 422})
            check(RelayPolicy.shouldPauseForHttp(code), "Pause rejected HTTP " + code);
        for (int code : new int[] {200, 201, 204, 408, 425, 429, 500, 503})
            check(!RelayPolicy.shouldPauseForHttp(code), "Retry / ACK handling HTTP " + code);
        check(RelayPolicy.isAcknowledgement("received", 1), "Expected acknowledgement accepted");
        check(RelayPolicy.isAcknowledgement("received", Long.MAX_VALUE), "Large record ID accepted");
        check(!RelayPolicy.isAcknowledgement("ok", 1), "Other success label insufficient");
        check(!RelayPolicy.isAcknowledgement("received", null), "Missing record ID insufficient");
        check(!RelayPolicy.isAcknowledgement("received", "1"), "String record ID insufficient");
        check(!RelayPolicy.isAcknowledgement("received", 0), "Zero record ID rejected");
        check(!RelayPolicy.isAcknowledgement("received", -1), "Negative record ID rejected");
        check(!RelayPolicy.isAcknowledgement("received", 1.5), "Non-integer record ID rejected");
        check(!RelayPolicy.isAcknowledgement("received", Double.NaN), "NaN record ID rejected");
        check(!RelayPolicy.isAcknowledgement("received", Double.POSITIVE_INFINITY), "Infinite record ID rejected");
        System.out.println("RelayPolicyTest: " + checks + " checks passed");
    }
}

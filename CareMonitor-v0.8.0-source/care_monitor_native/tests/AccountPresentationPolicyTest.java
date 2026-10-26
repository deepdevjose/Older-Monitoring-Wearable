package org.caremonitor.demo;

import java.time.Instant;
import java.util.Locale;

/** Plain-JVM transport/presentation checks; not Android/Keystore/network tests. */
public final class AccountPresentationPolicyTest {
    private static int checked;

    public static void main(String[] args) {
        testOrigins();
        testLabelsAndConfidence();
        testSessionsAndRoles();
        testTimestamps();
        testErrors();
        System.out.println("PASS: " + checked + " AccountPresentationPolicy checks");
    }

    private static void testOrigins() {
        equal("HTTPS root", "https://example.com", AccountPresentationPolicy.origin("https://example.com"));
        equal("canonical host/scheme/default port", "https://example.com", AccountPresentationPolicy.origin(" HTTPS://EXAMPLE.COM:443/ "));
        equal("legacy upload address", "https://example.com:8000", AccountPresentationPolicy.origin("https://example.com:8000/sensor-data"));
        equal("LAN IPv4", "https://192.168.1.20:8000", AccountPresentationPolicy.origin("https://192.168.1.20:8000/"));
        equal("IPv6 host retains brackets", "https://[2001:db8::1]:8000", AccountPresentationPolicy.origin("https://[2001:db8::1]:8000/sensor-data"));
        equal("IPv6 localhost", "https://[::1]", AccountPresentationPolicy.origin("https://[::1]:443"));
        equal("lowest valid port", "https://localhost:1", AccountPresentationPolicy.origin("https://localhost:1"));
        equal("highest valid port", "https://localhost:65535", AccountPresentationPolicy.origin("https://localhost:65535"));
        String[] rejected = {
            null, "", "example.com", "http://example.com", "ftp://example.com", "//example.com",
            "https:///missing-host", "https://example.com:0", "https://example.com:65536", "https://example.com:-1",
            "https://example.com:", "https://user:private-marker-452@example.com",
            "https://user@example.com", "https://example.com?token=private-marker-452",
            "https://example.com#private-marker-452", "https://example.com/accounts/login",
            "https://example.com/sensor-data/", "https://example.com//sensor-data",
            "https://example.com/%73ensor-data", "https://example.com\\evil", "https://ex ample.com",
            "https://example.com\r\nAuthorization: private-marker-452", repeat('a', 2049)
        };
        for (int i = 0; i < rejected.length; i++) rejectOrigin("invalid origin " + i, rejected[i]);
    }

    private static void testLabelsAndConfidence() {
        equal("actual NORMAL label", "Normal", AccountPresentationPolicy.label("NORMAL"));
        equal("ABNORMAL is not renamed Warning", "Abnormal", AccountPresentationPolicy.label("ABNORMAL"));
        equal("explicit future WARNING remains distinct", "Warning", AccountPresentationPolicy.label("WARNING"));
        equal("explicit future CRITICAL remains distinct", "Critical", AccountPresentationPolicy.label("CRITICAL"));
        for (String value : new String[] {null, "", "normal", " NORMAL", "OK", "NAN", "false", "0"}) {
            equal("unknown label does not become Normal", "Unavailable", AccountPresentationPolicy.label(value));
        }
        equal("missing confidence", "Unavailable", AccountPresentationPolicy.confidence(null));
        equal("NaN confidence", "Unavailable", AccountPresentationPolicy.confidence(Double.NaN));
        equal("positive infinity confidence", "Unavailable", AccountPresentationPolicy.confidence(Double.POSITIVE_INFINITY));
        equal("negative infinity confidence", "Unavailable", AccountPresentationPolicy.confidence(Double.NEGATIVE_INFINITY));
        equal("below range confidence", "Unavailable", AccountPresentationPolicy.confidence(-0.0001));
        equal("above range confidence", "Unavailable", AccountPresentationPolicy.confidence(1.0001));
        equal("zero confidence remains zero", "0.0%", AccountPresentationPolicy.confidence(0.0));
        equal("unit probability", "100.0%", AccountPresentationPolicy.confidence(1.0));
        equal("probability percentage", "87.0%", AccountPresentationPolicy.confidence(0.87));
        equal("confidence rounding", "87.7%", AccountPresentationPolicy.confidence(0.87654));
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.FRANCE);
            equal("UI confidence locale is stable", "87.0%", AccountPresentationPolicy.confidence(0.87));
        } finally { Locale.setDefault(original); }
    }

    private static void testSessionsAndRoles() {
        yes("patient role", AccountPresentationPolicy.role("patient"));
        yes("caregiver role", AccountPresentationPolicy.role("caregiver"));
        for (String role : new String[] {null, "", "admin", "visitor", "Caregiver", "patient ", "patient\ncaregiver"}) {
            no("unknown role is rejected", AccountPresentationPolicy.role(role));
        }
        String token = "cmu_" + repeat('a', 43);
        yes("account token shape", AccountPresentationPolicy.token(token));
        yes("URL-safe token characters", AccountPresentationPolicy.token("cmu_" + repeat('-', 21) + repeat('_', 22)));
        for (String value : new String[] {null, "", "cmu_" + repeat('a', 42), "cmu_" + repeat('a', 44),
                "key_" + repeat('a', 43), "cmu_" + repeat('a', 42) + "+", token + "\n", "Bearer " + token}) {
            no("invalid account token", AccountPresentationPolicy.token(value));
        }
        yes("session just before expiry", AccountPresentationPolicy.sessionValid(1000, 999));
        no("session exact expiry", AccountPresentationPolicy.sessionValid(1000, 1000));
        no("session after expiry", AccountPresentationPolicy.sessionValid(1000, 1001));
        no("missing expiry", AccountPresentationPolicy.sessionValid(0, 1));
        no("negative expiry", AccountPresentationPolicy.sessionValid(-1, 1));
        no("invalid zero clock", AccountPresentationPolicy.sessionValid(1000, 0));
        no("negative clock", AccountPresentationPolicy.sessionValid(1000, -1));
        no("maximum exact expiry", AccountPresentationPolicy.sessionValid(Long.MAX_VALUE, Long.MAX_VALUE));
        yes("maximum future expiry compares without overflow", AccountPresentationPolicy.sessionValid(Long.MAX_VALUE, Long.MAX_VALUE - 1));
    }

    private static void testTimestamps() {
        long base = Instant.parse("2026-10-26T08:00:00Z").toEpochMilli();
        number("UTC timestamp", base, AccountPresentationPolicy.timestamp("2026-10-26T08:00:00Z"));
        number("positive offset equivalence", base, AccountPresentationPolicy.timestamp("2026-10-26T16:00:00+08:00"));
        number("negative offset equivalence", base, AccountPresentationPolicy.timestamp("2026-10-26T03:00:00-05:00"));
        number("tenths padded to milliseconds", base + 100, AccountPresentationPolicy.timestamp("2026-10-26T08:00:00.1Z"));
        number("hundredths padded to milliseconds", base + 120, AccountPresentationPolicy.timestamp("2026-10-26T08:00:00.12Z"));
        number("milliseconds exact", base + 123, AccountPresentationPolicy.timestamp("2026-10-26T08:00:00.123Z"));
        number("microseconds conservatively truncate", base + 123, AccountPresentationPolicy.timestamp("2026-10-26T08:00:00.123999Z"));
        number("nanoseconds conservatively truncate", base + 123, AccountPresentationPolicy.timestamp("2026-10-26T08:00:00.123999999Z"));
        number("fraction with timezone", base + 123, AccountPresentationPolicy.timestamp("2026-10-26T16:00:00.123456+08:00"));
        number("valid leap day", Instant.parse("2024-02-29T12:00:00Z").toEpochMilli(), AccountPresentationPolicy.timestamp("2024-02-29T12:00:00Z"));
        String[] invalid = {null, "", "not-a-date", "2026-10-26T08:00:00", "2026-10-26 08:00:00Z",
            "2026-02-29T08:00:00Z", "2026-02-30T08:00:00Z", "2026-13-01T08:00:00Z",
            "2026-10-26T24:00:00Z", "2026-10-26T08:60:00Z", "2026-10-26T08:00:60Z",
            "2026-10-26T08:00:00.Z", "2026-10-26T08:00:00.1234567890Z",
            "2026-10-26T08:00:00+24:00", "2026-10-26T08:00:00+00:60",
            "2026-10-26T08:00:00Zgarbage", "2026-10-26T08:00:00Z ",
            "1970-01-01T00:00:00Z", "1969-12-31T23:59:59Z"};
        for (String value : invalid) number("invalid timestamp stays unavailable", 0, AccountPresentationPolicy.timestamp(value));
    }

    private static void testErrors() {
        yes("401 asks to sign in", AccountPresentationPolicy.error(401).contains("Sign in"));
        yes("403 describes permission boundary", AccountPresentationPolicy.error(403).contains("not permitted"));
        yes("429 asks to wait", AccountPresentationPolicy.error(429).contains("Wait"));
        yes("server error does not expose details", AccountPresentationPolicy.error(503).contains("temporarily unavailable"));
        for (int status : new int[] {0, 301, 302, 400, 401, 403, 404, 409, 422, 429, 500, 503}) {
            String message = AccountPresentationPolicy.error(status);
            yes("error is explanatory", message != null && !message.isEmpty());
            no("error does not expose token", message.contains("cmu_"));
        }
        yes("unknown response makes no Normal assumption", AccountPresentationPolicy.error(302).contains("No reading has been classified as Normal"));
    }

    private static void rejectOrigin(String label, String value) {
        checked++;
        try {
            AccountPresentationPolicy.origin(value);
            throw new AssertionError(label + " unexpectedly accepted");
        } catch (IllegalArgumentException expected) {
            if (expected.getMessage() != null && expected.getMessage().contains("private-marker-452")) {
                throw new AssertionError(label + " exposed credential input");
            }
        }
    }
    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }
    private static void equal(String label, String expected, String actual) {
        checked++;
        if (!expected.equals(actual)) throw new AssertionError(label + ": expected " + expected + ", got " + actual);
    }
    private static void number(String label, long expected, long actual) {
        checked++;
        if (expected != actual) throw new AssertionError(label + ": expected " + expected + ", got " + actual);
    }
    private static void yes(String label, boolean actual) { checked++; if (!actual) throw new AssertionError(label); }
    private static void no(String label, boolean actual) { checked++; if (actual) throw new AssertionError(label); }
}

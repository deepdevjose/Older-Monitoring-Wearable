package org.caremonitor.demo;

import java.net.URI;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Pure presentation/transport rules. No clinical severity thresholds are inferred. */
final class AccountPresentationPolicy {
    static String origin(String value) {
        if (value == null || value.length() > 2048) throw new IllegalArgumentException("Enter the HTTPS server address.");
        try {
            URI uri = new URI(value.trim());
            String path = uri.getRawPath();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || uri.getPort() == 0 || uri.getPort() > 65535 || uri.getRawAuthority().endsWith(":"))
                throw new IllegalArgumentException("Use an HTTPS server address without credentials, query or fragment.");
            if (!(path == null || path.isEmpty() || "/".equals(path) || "/sensor-data".equals(path)))
                throw new IllegalArgumentException("Use the server root address or its /sensor-data address.");
            // URI host retains the brackets of IPv6 literals. Never concatenate user information.
            return "https://" + uri.getHost().toLowerCase(Locale.US)
                    + (uri.getPort() < 0 || uri.getPort() == 443 ? "" : ":" + uri.getPort());
        } catch (java.net.URISyntaxException e) {
            throw new IllegalArgumentException("Invalid HTTPS server address.");
        }
    }

    static String label(String value) {
        if ("NORMAL".equals(value)) return "Normal";
        if ("ABNORMAL".equals(value)) return "Abnormal";
        if ("WARNING".equals(value)) return "Warning";
        if ("CRITICAL".equals(value)) return "Critical";
        return "Unavailable";
    }

    static String confidence(Double value) {
        if (value == null || !Double.isFinite(value) || value < 0 || value > 1) return "Unavailable";
        return String.format(Locale.US, "%.1f%%", value * 100);
    }

    static boolean role(String value) { return "patient".equals(value) || "caregiver".equals(value); }
    static boolean token(String value) { return value != null && value.matches("cmu_[A-Za-z0-9_-]{43}"); }
    static boolean sessionValid(long expires, long now) { return now > 0 && expires > now; }

    static String error(int status) {
        switch (status) {
            case 400: case 422: return "The server rejected the request. Check the supplied details.";
            case 401: return "Sign in again. The credentials are incorrect or the session has expired.";
            case 403: return "This account is not permitted to access this patient or action.";
            case 404: return "The requested record or account API is unavailable on this server.";
            case 409: return "The record changed. Refresh before trying again.";
            case 429: return "Too many requests. Wait a minute before trying again.";
            default: return status >= 500 ? "The server is temporarily unavailable. Try again later."
                    : "Unexpected server response. No reading has been classified as Normal.";
        }
    }

    static long timestamp(String value) {
        if (value == null || !value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]{1,9})?(Z|[+-][0-9]{2}:[0-9]{2})")) return 0;
        String adjusted = value;
        int dot = value.indexOf('.');
        if (dot >= 0) {
            int end = dot + 1;
            while (end < value.length() && Character.isDigit(value.charAt(end))) end++;
            adjusted = value.substring(0, dot) + "." + (value.substring(dot + 1, end) + "000").substring(0, 3) + value.substring(end);
        }
        SimpleDateFormat format = new SimpleDateFormat(dot < 0 ? "yyyy-MM-dd'T'HH:mm:ssXXX" : "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US);
        format.setLenient(false);
        ParsePosition pos = new ParsePosition(0);
        Date date = format.parse(adjusted, pos);
        return date == null || pos.getIndex() != adjusted.length() || date.getTime() <= 0 ? 0 : date.getTime();
    }
}

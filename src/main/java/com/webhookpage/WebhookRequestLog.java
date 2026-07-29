package com.webhookpage;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Immutable log entry for a captured webhook hit.
 */
public final class WebhookRequestLog {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final Instant timestamp;
    private final String method;
    private final String url;
    private final String clientIp;
    private final String userAgent;
    private final String bodySummary;

    public WebhookRequestLog(Instant timestamp, String method, String url,
                             String clientIp, String userAgent, String bodySummary) {
        this.timestamp = timestamp == null ? Instant.now() : timestamp;
        this.method = method == null ? "" : method;
        this.url = url == null ? "" : url;
        this.clientIp = clientIp == null ? "" : clientIp;
        this.userAgent = userAgent == null ? "" : userAgent;
        this.bodySummary = bodySummary == null ? "" : bodySummary;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public String getFormattedTime() {
        return TIME_FORMAT.format(timestamp);
    }

    public String getMethod() {
        return method;
    }

    public String getUrl() {
        return url;
    }

    public String getClientIp() {
        return clientIp;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public String getBodySummary() {
        return bodySummary;
    }

    public static String summarizeBody(String body, int maxLen) {
        if (body == null || body.isBlank()) {
            return "(empty)";
        }
        String compact = body.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ').trim();
        if (compact.length() <= maxLen) {
            return compact;
        }
        return compact.substring(0, maxLen) + "...";
    }
}

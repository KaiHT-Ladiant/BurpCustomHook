package com.webhookpage;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Thread-safe configuration shared between the UI and HTTP handler.
 */
public final class WebhookConfig {

    public static final String DEFAULT_REQUIRED_STRING = "kai_ht";
    public static final String DEFAULT_PATH = "/kai_ht/webhook";

    public static final String DEFAULT_HTML = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <title>Webhook Received</title>
                <style>
                    body { font-family: Arial, sans-serif; background: #1a1a1a; color: #eee; display: flex; justify-content: center; align-items: center; height: 100vh; margin: 0; }
                    .box { background: #2d2d2d; padding: 40px; border-radius: 12px; text-align: center; box-shadow: 0 0 20px rgba(0,0,0,0.5); }
                    h1 { color: #4CAF50; }
                </style>
            </head>
            <body>
                <div class="box">
                    <h1>Webhook Hit!</h1>
                    <p>The request was received successfully.</p>
                    <p id="time"></p>
                </div>
                <script>
                    document.getElementById('time').innerText = new Date().toLocaleString();
                </script>
            </body>
            </html>
            """;

    private final Object lock = new Object();
    private final CopyOnWriteArrayList<Consumer<WebhookConfig>> listeners = new CopyOnWriteArrayList<>();

    /** Default OFF until the user enables Public Webhook. */
    private volatile boolean enabled = false;
    private volatile String requiredString = DEFAULT_REQUIRED_STRING;
    private volatile String webhookPath = DEFAULT_PATH;
    private volatile String htmlContent = DEFAULT_HTML;
    /** Auto-set by PublicUrlService when Public Webhook is ON (scheme://host[:port]). */
    private volatile String publicAddress = "";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        synchronized (lock) {
            this.enabled = enabled;
        }
        notifyListeners();
    }

    public String getRequiredString() {
        return requiredString;
    }

    public String getWebhookPath() {
        return webhookPath;
    }

    public String getHtmlContent() {
        return htmlContent;
    }

    public String getPublicAddress() {
        return publicAddress;
    }

    /**
     * Sets the auto-discovered public base URL (not a user-typed field).
     */
    public void setPublicAddress(String publicAddress) {
        synchronized (lock) {
            this.publicAddress = publicAddress == null ? "" : publicAddress.trim();
        }
        notifyListeners();
    }

    /**
     * Applies settings after validating and normalizing the path.
     * Does not change {@link #isEnabled()} — that is controlled by the Public Webhook toggle.
     *
     * @return normalized path that was applied
     * @throws IllegalArgumentException if required string is blank
     */
    public String apply(String requiredString, String webhookPath, String htmlContent) {
        String required = requiredString == null ? "" : requiredString.trim();
        if (required.isEmpty()) {
            throw new IllegalArgumentException("Required token cannot be empty.");
        }

        String normalizedPath = ensureRequiredInPath(normalizePath(webhookPath), required);
        String html = (htmlContent == null || htmlContent.isBlank()) ? DEFAULT_HTML : htmlContent;

        synchronized (lock) {
            this.requiredString = required;
            this.webhookPath = normalizedPath;
            this.htmlContent = html;
        }
        notifyListeners();
        return normalizedPath;
    }

    public void resetToDefaults() {
        synchronized (lock) {
            this.requiredString = DEFAULT_REQUIRED_STRING;
            this.webhookPath = DEFAULT_PATH;
            this.htmlContent = DEFAULT_HTML;
            // Keep current enabled / publicAddress — controlled by Public Webhook toggle
        }
        notifyListeners();
    }

    /**
     * Path match including enabled check. Used for serving success responses.
     */
    public boolean matchesPath(String requestPath) {
        return enabled && pathMatches(requestPath);
    }

    /**
     * Path match ignoring enabled flag (for 503 when Public Webhook is OFF).
     */
    public boolean pathMatches(String requestPath) {
        if (requestPath == null) {
            return false;
        }
        String normalizedRequest = stripQuery(requestPath);
        String configured = webhookPath;
        return normalizedRequest.equals(configured)
                || normalizedRequest.equals(configured + "/")
                || (configured.endsWith("/") && normalizedRequest.equals(configured.substring(0, configured.length() - 1)));
    }

    public void addChangeListener(Consumer<WebhookConfig> listener) {
        listeners.add(Objects.requireNonNull(listener));
    }

    private void notifyListeners() {
        for (Consumer<WebhookConfig> listener : listeners) {
            try {
                listener.accept(this);
            } catch (RuntimeException ignored) {
                // UI listeners should not break config updates
            }
        }
    }

    public static String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        String trimmed = path.trim();
        if (!trimmed.startsWith("/")) {
            trimmed = "/" + trimmed;
        }
        // collapse duplicate slashes except after scheme (paths only)
        while (trimmed.contains("//")) {
            trimmed = trimmed.replace("//", "/");
        }
        if (trimmed.length() > 1 && trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    /**
     * Ensures the required token appears as a path segment.
     * If missing, inserts {@code /{required}} at the beginning of the path.
     */
    public static String ensureRequiredInPath(String path, String required) {
        String normalized = normalizePath(path);
        String token = required.trim();
        if (token.isEmpty()) {
            throw new IllegalArgumentException("Required token cannot be empty.");
        }

        // Accept either as path segment or substring in path (user may embed it)
        if (pathContainsRequired(normalized, token)) {
            return normalized;
        }

        String insertion = normalizePath("/" + token);
        if ("/".equals(normalized)) {
            return insertion;
        }
        return insertion + normalized;
    }

    public static boolean pathContainsRequired(String path, String required) {
        if (path == null || required == null || required.isBlank()) {
            return false;
        }
        String normalized = normalizePath(path).toLowerCase();
        String token = required.trim().toLowerCase();
        // Match as path segment to avoid accidental partial matches being insufficient;
        // also allow direct substring so /kai_ht_webhook style still works if user wants.
        String[] segments = normalized.split("/");
        for (String segment : segments) {
            if (segment.equals(token) || segment.contains(token)) {
                return true;
            }
        }
        return normalized.contains(token);
    }

    private static String stripQuery(String path) {
        int q = path.indexOf('?');
        return q >= 0 ? path.substring(0, q) : path;
    }
}

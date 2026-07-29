package com.webhookpage;

import burp.api.montoya.MontoyaApi;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the deployable Webhook URL from an auto-discovered public base
 * plus the required-token webhook path.
 */
public final class WebhookUrlBuilder {

    private static final long CACHE_TTL_MS = 5_000L;

    private final MontoyaApi api;
    private final WebhookConfig config;
    private final AtomicReference<CachedListeners> cache = new AtomicReference<>();
    private volatile int extensionPort = -1;

    public WebhookUrlBuilder(MontoyaApi api, WebhookConfig config) {
        this.api = api;
        this.config = config;
    }

    public void setExtensionPort(int extensionPort) {
        this.extensionPort = extensionPort;
    }

    public int getExtensionPort() {
        return extensionPort;
    }

    /** Local listen base only (no webhook path / required token). */
    public String getExtensionBaseUrl() {
        if (extensionPort <= 0) {
            return "http://127.0.0.1:(starting...)";
        }
        return "http://127.0.0.1:" + extensionPort;
    }

    /**
     * Deployable webhook URL using applied config and auto public address.
     */
    public String buildWebhookUrl() {
        return buildDeployableUrl(
                config.getPublicAddress(),
                config.getRequiredString(),
                config.getWebhookPath()
        );
    }

    /**
     * Deployable webhook URL from draft UI values (live preview).
     * Required token is always embedded in the path.
     */
    public String buildDeployableUrl(String publicAddress, String requiredToken, String webhookPath) {
        String required = requiredToken == null ? "" : requiredToken.trim();
        if (required.isEmpty()) {
            required = config.getRequiredString();
        }
        String pathSource = (webhookPath == null || webhookPath.isBlank())
                ? config.getWebhookPath()
                : webhookPath;
        String path = WebhookConfig.ensureRequiredInPath(
                WebhookConfig.normalizePath(pathSource),
                required
        );

        if (publicAddress != null && !publicAddress.isBlank()) {
            return combineBaseAndPath(extractPublicBase(publicAddress), path);
        }

        // No public base yet — temporary local fallback (listen URL + path)
        return combineBaseAndPath(getExtensionBaseUrl(), path);
    }

    public boolean hasPublicAddress() {
        String publicAddress = config.getPublicAddress();
        return publicAddress != null && !publicAddress.isBlank();
    }

    public List<ListenerEndpoint> listActiveListeners() {
        return findActiveListeners();
    }

    public void invalidateCache() {
        cache.set(null);
    }

    private List<ListenerEndpoint> findActiveListeners() {
        long now = System.currentTimeMillis();
        CachedListeners cached = cache.get();
        if (cached != null && (now - cached.fetchedAtMs) < CACHE_TTL_MS) {
            return cached.listeners;
        }

        List<ListenerEndpoint> result = new ArrayList<>();
        try {
            String json = api.burpSuite().exportProjectOptionsAsJson("proxy");
            if (json != null && !json.isBlank()) {
                result.addAll(parseListeners(json));
            }
        } catch (Exception e) {
            api.logging().logToError("[Webhook Page] Failed to read proxy listeners: " + e.getMessage());
        }

        cache.set(new CachedListeners(List.copyOf(result), now));
        return result;
    }

    static List<ListenerEndpoint> parseListeners(String json) {
        List<ListenerEndpoint> listeners = new ArrayList<>();

        Pattern blockPattern = Pattern.compile(
                "\\{([^{}]*\"listener_port\"\\s*:\\s*\\d+[^{}]*)\\}",
                Pattern.DOTALL
        );
        Matcher blockMatcher = blockPattern.matcher(json);
        while (blockMatcher.find()) {
            String block = blockMatcher.group(1);

            Boolean running = extractBoolean(block, "running");
            if (running != null && !running) {
                continue;
            }

            Integer port = extractInt(block, "listener_port");
            if (port == null) {
                continue;
            }

            String address = extractString(block, "listen_address");
            if (address == null || address.isBlank()) {
                address = "127.0.0.1";
            }
            if ("0.0.0.0".equals(address) || "::".equals(address) || "*".equals(address)) {
                address = "127.0.0.1";
            }

            boolean useTls = false;
            String certMode = extractString(block, "certificate_mode");
            if (certMode != null) {
                useTls = certMode.toLowerCase().contains("tls") || certMode.equalsIgnoreCase("own_certificate");
            }
            Boolean enableTls = extractBoolean(block, "enable_tls");
            if (enableTls != null) {
                useTls = enableTls;
            }

            listeners.add(new ListenerEndpoint(address, port, useTls));
        }

        return listeners;
    }

    private static Integer extractInt(String block, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(\\d+)").matcher(block);
        return m.find() ? Integer.parseInt(m.group(1)) : null;
    }

    private static Boolean extractBoolean(String block, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(true|false)", Pattern.CASE_INSENSITIVE)
                .matcher(block);
        return m.find() ? Boolean.parseBoolean(m.group(1)) : null;
    }

    private static String extractString(String block, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"").matcher(block);
        return m.find() ? m.group(1) : null;
    }

    /**
     * Normalize public base to scheme://host[:port] only.
     */
    static String extractPublicBase(String publicAddress) {
        String value = publicAddress.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        if (!value.matches("(?i)^https?://.*")) {
            value = "https://" + value;
        }

        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme() == null ? "https" : uri.getScheme();
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return normalizePublicBaseFallback(value);
            }
            int port = uri.getPort();
            boolean defaultPort = port < 0
                    || ("https".equalsIgnoreCase(scheme) && port == 443)
                    || ("http".equalsIgnoreCase(scheme) && port == 80);
            return defaultPort ? scheme + "://" + host : scheme + "://" + host + ":" + port;
        } catch (Exception e) {
            return normalizePublicBaseFallback(value);
        }
    }

    private static String normalizePublicBaseFallback(String value) {
        int pathStart = -1;
        int schemeIdx = value.indexOf("://");
        if (schemeIdx >= 0) {
            pathStart = value.indexOf('/', schemeIdx + 3);
        } else {
            pathStart = value.indexOf('/');
        }
        if (pathStart >= 0) {
            value = value.substring(0, pathStart);
        }
        int q = value.indexOf('?');
        if (q >= 0) {
            value = value.substring(0, q);
        }
        int h = value.indexOf('#');
        if (h >= 0) {
            value = value.substring(0, h);
        }
        return value;
    }

    static String combineBaseAndPath(String base, String path) {
        String b = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        String p = path.startsWith("/") ? path : "/" + path;
        return b + p;
    }

    public record ListenerEndpoint(String host, int port, boolean tls) {
    }

    private record CachedListeners(List<ListenerEndpoint> listeners, long fetchedAtMs) {
    }
}

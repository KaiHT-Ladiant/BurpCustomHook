package com.webhookpage;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Discovers or creates a public base URL for the local webhook server.
 * <p>
 * Priority when Public Webhook is ON:
 * <ol>
 *   <li>cloudflared Quick Tunnel ({@code *.trycloudflare.com})</li>
 *   <li>ngrok local API ({@code http://127.0.0.1:4040/api/tunnels})</li>
 *   <li>LAN IPv4 + extension port</li>
 * </ol>
 */
public final class PublicUrlService implements AutoCloseable {

    public enum Source {
        NONE,
        CLOUDFLARED,
        NGROK,
        LAN
    }

    public record DiscoveryResult(String publicBaseUrl, Source source, String hint) {
    }

    private static final Pattern TRY_CLOUDFLARE = Pattern.compile(
            "https://[a-zA-Z0-9](?:[a-zA-Z0-9.-]*[a-zA-Z0-9])?\\.(?:trycloudflare\\.com|cfargotunnel\\.com)"
    );
    private static final Pattern TRY_CLOUDFLARE_HOST = Pattern.compile(
            "(?i)\\b([a-z0-9](?:[a-z0-9.-]*[a-z0-9])?\\.(?:trycloudflare\\.com|cfargotunnel\\.com))\\b"
    );
    private static final Pattern NGROK_PUBLIC_URL = Pattern.compile(
            "\"public_url\"\\s*:\\s*\"(https?://[^\"]+)\""
    );
    private static final Pattern ANSI_ESCAPE = Pattern.compile("\\u001B\\[[0-9;?]*[a-zA-Z]");
    private static final long CLOUDFLARED_WAIT_MS = 45_000L;

    private final Object lock = new Object();
    private final AtomicReference<Process> cloudflaredProcess = new AtomicReference<>();
    private volatile String publicBaseUrl = "";
    private volatile Source source = Source.NONE;
    private volatile String hint = "";
    private volatile String lastTunnelFailure = "";

    public String getPublicBaseUrl() {
        return publicBaseUrl;
    }

    public Source getSource() {
        return source;
    }

    public String getHint() {
        return hint;
    }

    /**
     * Starts discovery asynchronously. Invokes {@code onComplete} on a background thread.
     */
    public void discoverAsync(int extensionPort, Consumer<DiscoveryResult> onComplete) {
        Thread t = new Thread(() -> {
            DiscoveryResult result = discover(extensionPort);
            if (onComplete != null) {
                onComplete.accept(result);
            }
        }, "webhook-public-url-discover");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Discovers a public base URL (blocking). Stops any previously started cloudflared first.
     */
    public DiscoveryResult discover(int extensionPort) {
        stopCloudflared();

        if (extensionPort <= 0) {
            DiscoveryResult fail = new DiscoveryResult("", Source.NONE, "Extension port not ready.");
            applyResult(fail);
            return fail;
        }

        DiscoveryResult cloudflared = tryCloudflared(extensionPort);
        if (cloudflared != null) {
            applyResult(cloudflared);
            return cloudflared;
        }

        DiscoveryResult ngrok = tryNgrok();
        if (ngrok != null) {
            applyResult(ngrok);
            return ngrok;
        }

        DiscoveryResult lan = tryLan(extensionPort);
        applyResult(lan);
        return lan;
    }

    public void clear() {
        stopCloudflared();
        synchronized (lock) {
            publicBaseUrl = "";
            source = Source.NONE;
            hint = "";
            lastTunnelFailure = "";
        }
    }

    public String getLastTunnelFailure() {
        return lastTunnelFailure;
    }

    private void applyResult(DiscoveryResult result) {
        synchronized (lock) {
            this.publicBaseUrl = result.publicBaseUrl() == null ? "" : result.publicBaseUrl();
            this.source = result.source() == null ? Source.NONE : result.source();
            this.hint = result.hint() == null ? "" : result.hint();
        }
    }

    private DiscoveryResult tryCloudflared(int extensionPort) {
        StringBuilder resolveStatus = new StringBuilder();
        String cloudflaredBin = CloudflaredResolver.resolveExecutable(resolveStatus);
        if (cloudflaredBin == null || cloudflaredBin.isBlank()) {
            lastTunnelFailure = "cloudflared unavailable: " + resolveStatus;
            return null;
        }

        ProcessBuilder pb = new ProcessBuilder(
                cloudflaredBin, "tunnel", "--url", "http://127.0.0.1:" + extensionPort
        );
        pb.redirectErrorStream(true);

        Process process;
        try {
            process = pb.start();
        } catch (Exception e) {
            lastTunnelFailure = "cloudflared start failed: " + e.getMessage();
            return null;
        }
        cloudflaredProcess.set(process);

        StringBuilder recentOutput = new StringBuilder();
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));

        // Blocking readLine (do NOT use InputStream.ready()) — on Windows, ready() often
        // stays false while cloudflared prints the trycloudflare.com URL, so the old loop
        // timed out and fell back to LAN with no separate tunnel domain.
        ExecutorService waitPool = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "webhook-cloudflared-url-wait");
            t.setDaemon(true);
            return t;
        });
        String foundUrl = null;
        try {
            Future<String> waitFuture = waitPool.submit(() -> {
                String line;
                while ((line = reader.readLine()) != null) {
                    String cleaned = ANSI_ESCAPE.matcher(line).replaceAll("").trim();
                    if (recentOutput.length() < 8_000) {
                        recentOutput.append(cleaned).append('\n');
                    }
                    Matcher m = TRY_CLOUDFLARE.matcher(cleaned);
                    if (m.find()) {
                        return m.group();
                    }
                    Matcher hostOnly = TRY_CLOUDFLARE_HOST.matcher(cleaned);
                    if (hostOnly.find()) {
                        return "https://" + hostOnly.group(1);
                    }
                }
                return null;
            });
            try {
                foundUrl = waitFuture.get(CLOUDFLARED_WAIT_MS, TimeUnit.MILLISECONDS);
            } catch (Exception timeoutOrFail) {
                waitFuture.cancel(true);
                foundUrl = null;
            }
        } finally {
            waitPool.shutdownNow();
        }

        // Keep draining stdout so the process does not block on a full pipe
        Thread drain = new Thread(() -> {
            try {
                drainQuietly(reader);
            } finally {
                try {
                    reader.close();
                } catch (Exception ignored) {
                    // ignore
                }
            }
        }, "webhook-cloudflared-drain");
        drain.setDaemon(true);
        drain.start();

        if (foundUrl == null || foundUrl.isBlank()) {
            stopCloudflared();
            String detail = recentOutput.isEmpty()
                    ? "no URL in cloudflared output within " + (CLOUDFLARED_WAIT_MS / 1000) + "s"
                    : recentOutput.toString().trim();
            if (detail.length() > 400) {
                detail = detail.substring(detail.length() - 400);
            }
            lastTunnelFailure = "cloudflared ran but tunnel domain was not parsed. " + detail;
            System.err.println("[Webhook Page] " + lastTunnelFailure);
            return null;
        }

        lastTunnelFailure = "";
        String okHint = "Cloudflare Quick Tunnel started — random tunnel domain is ready.";
        if (!resolveStatus.isEmpty()) {
            okHint = okHint + " " + resolveStatus;
        }
        return new DiscoveryResult(
                stripTrailingSlash(foundUrl),
                Source.CLOUDFLARED,
                okHint
        );
    }

    private DiscoveryResult tryNgrok() {
        try {
            URL url = URI.create("http://127.0.0.1:4040/api/tunnels").toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(1_500);
            conn.setReadTimeout(2_500);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                return null;
            }
            String body;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                body = sb.toString();
            }

            String https = null;
            String http = null;
            Matcher m = NGROK_PUBLIC_URL.matcher(body);
            while (m.find()) {
                String publicUrl = m.group(1);
                if (publicUrl.startsWith("https://") && https == null) {
                    https = publicUrl;
                } else if (publicUrl.startsWith("http://") && http == null) {
                    http = publicUrl;
                }
            }
            String chosen = https != null ? https : http;
            if (chosen == null || chosen.isBlank()) {
                return null;
            }
            return new DiscoveryResult(
                    stripTrailingSlash(chosen),
                    Source.NGROK,
                    "Using ngrok tunnel from local API (127.0.0.1:4040)."
            );
        } catch (Exception e) {
            return null;
        }
    }

    private DiscoveryResult tryLan(int extensionPort) {
        String lanIp = detectLanIpv4();
        if (lanIp == null || lanIp.isBlank()) {
            lanIp = "127.0.0.1";
        }
        String base = "http://" + lanIp + ":" + extensionPort;
        String failure = lastTunnelFailure == null || lastTunnelFailure.isBlank()
                ? "cloudflared/ngrok unavailable"
                : lastTunnelFailure;
        return new DiscoveryResult(
                base,
                Source.LAN,
                "No tunnel domain (Internet hostname). LAN only — not a public domain. "
                        + failure
                        + " Local listen port=" + extensionPort + "."
        );
    }

    static String detectLanIpv4() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface nif = interfaces.nextElement();
                if (!nif.isUp() || nif.isLoopback() || nif.isVirtual()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = nif.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
            // fall through
        }
        return null;
    }

    private void stopCloudflared() {
        Process process = cloudflaredProcess.getAndSet(null);
        if (process == null) {
            return;
        }
        try {
            process.destroy();
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (Exception ignored) {
            try {
                process.destroyForcibly();
            } catch (Exception ignored2) {
                // ignore
            }
        }
    }

    private static void drainQuietly(BufferedReader reader) {
        try {
            while (reader.readLine() != null) {
                // discard
            }
        } catch (Exception ignored) {
            // process ended
        }
    }

    private static String stripTrailingSlash(String url) {
        String value = url.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    @Override
    public void close() {
        clear();
    }
}

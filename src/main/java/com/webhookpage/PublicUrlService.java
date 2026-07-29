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
            "https://[a-zA-Z0-9.-]+\\.trycloudflare\\.com"
    );
    private static final Pattern NGROK_PUBLIC_URL = Pattern.compile(
            "\"public_url\"\\s*:\\s*\"(https?://[^\"]+)\""
    );
    private static final long CLOUDFLARED_WAIT_MS = 25_000L;

    private final Object lock = new Object();
    private final AtomicReference<Process> cloudflaredProcess = new AtomicReference<>();
    private volatile String publicBaseUrl = "";
    private volatile Source source = Source.NONE;
    private volatile String hint = "";

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
        }
    }

    private void applyResult(DiscoveryResult result) {
        synchronized (lock) {
            this.publicBaseUrl = result.publicBaseUrl() == null ? "" : result.publicBaseUrl();
            this.source = result.source() == null ? Source.NONE : result.source();
            this.hint = result.hint() == null ? "" : result.hint();
        }
    }

    private DiscoveryResult tryCloudflared(int extensionPort) {
        if (!isCommandOnPath("cloudflared")) {
            return null;
        }

        ProcessBuilder pb = new ProcessBuilder(
                "cloudflared", "tunnel", "--url", "http://127.0.0.1:" + extensionPort
        );
        pb.redirectErrorStream(true);

        Process process;
        try {
            process = pb.start();
        } catch (Exception e) {
            return null;
        }
        cloudflaredProcess.set(process);

        String foundUrl = null;
        long deadline = System.currentTimeMillis() + CLOUDFLARED_WAIT_MS;
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while (System.currentTimeMillis() < deadline) {
                if (!process.isAlive() && !reader.ready()) {
                    break;
                }
                if (reader.ready()) {
                    line = reader.readLine();
                    if (line == null) {
                        break;
                    }
                    Matcher m = TRY_CLOUDFLARE.matcher(line);
                    if (m.find()) {
                        foundUrl = m.group();
                        break;
                    }
                } else {
                    try {
                        Thread.sleep(150);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            // Keep draining stdout so the process does not block on a full pipe
            final BufferedReader drainReader = reader;
            reader = null; // ownership transferred to drain thread
            Thread drain = new Thread(() -> {
                try {
                    drainQuietly(drainReader);
                } finally {
                    try {
                        drainReader.close();
                    } catch (Exception ignored) {
                        // ignore
                    }
                }
            }, "webhook-cloudflared-drain");
            drain.setDaemon(true);
            drain.start();
        } catch (Exception e) {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignored) {
                    // ignore
                }
            }
            stopCloudflared();
            return null;
        }

        if (foundUrl == null || foundUrl.isBlank()) {
            stopCloudflared();
            return null;
        }

        return new DiscoveryResult(
                stripTrailingSlash(foundUrl),
                Source.CLOUDFLARED,
                "Cloudflare Quick Tunnel started."
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
        return new DiscoveryResult(
                base,
                Source.LAN,
                "No cloudflared/ngrok found — using LAN address. "
                        + "For an Internet public URL, install cloudflared or run ngrok http "
                        + extensionPort + "."
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

    private static boolean isCommandOnPath(String command) {
        String os = System.getProperty("os.name", "").toLowerCase();
        boolean windows = os.contains("win");
        ProcessBuilder pb = windows
                ? new ProcessBuilder("where", command)
                : new ProcessBuilder("which", command);
        pb.redirectErrorStream(true);
        try {
            Process p = pb.start();
            boolean finished = p.waitFor(3, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
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

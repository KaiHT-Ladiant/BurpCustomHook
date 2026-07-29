package com.webhookpage;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 *   <li>LAN IPv4 + extension port (fallback only — not a public tunnel domain)</li>
 * </ol>
 * LAN fallback is used only when tunnel creation fails; it is not the intended public URL.
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

    /** Loose match — cloudflared wraps the URL in box-drawing / JSON message fields. */
    private static final Pattern TRY_CLOUDFLARE = Pattern.compile(
            "https://[a-zA-Z0-9][a-zA-Z0-9.-]*\\.(?:trycloudflare\\.com|cfargotunnel\\.com)"
    );
    private static final Pattern NGROK_PUBLIC_URL = Pattern.compile(
            "\"public_url\"\\s*:\\s*\"(https?://[^\"]+)\""
    );
    private static final long CLOUDFLARED_WAIT_MS = 60_000L;
    private static final long LOG_POLL_MS = 200L;

    private final Object lock = new Object();
    private final AtomicReference<Process> cloudflaredProcess = new AtomicReference<>();
    private volatile Path cloudflaredLogFile;
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

    public String getLastTunnelFailure() {
        return lastTunnelFailure;
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
            lastTunnelFailure = "Extension listen port not ready (port=" + extensionPort + ").";
            DiscoveryResult fail = new DiscoveryResult("", Source.NONE, lastTunnelFailure);
            applyResult(fail);
            return fail;
        }

        System.out.println("[Webhook Page] Discovering public URL for 127.0.0.1:" + extensionPort);

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
            System.err.println("[Webhook Page] " + lastTunnelFailure);
            return null;
        }
        System.out.println("[Webhook Page] cloudflared binary: " + cloudflaredBin + " (" + resolveStatus + ")");

        Path logFile;
        try {
            Path logDir = Path.of(System.getProperty("user.home"), ".webhook-page", "logs");
            Files.createDirectories(logDir);
            logFile = logDir.resolve("quick-tunnel.log");
            Files.deleteIfExists(logFile);
        } catch (Exception e) {
            lastTunnelFailure = "could not prepare cloudflared log file: " + e.getMessage();
            System.err.println("[Webhook Page] " + lastTunnelFailure);
            return null;
        }

        // --logfile is required on Windows: Go fully buffers stdout when not a TTY,
        // so piping ProcessBuilder stdout often never yields the trycloudflare.com URL.
        ProcessBuilder pb = new ProcessBuilder(
                cloudflaredBin,
                "tunnel",
                "--url", "http://127.0.0.1:" + extensionPort,
                "--logfile", logFile.toAbsolutePath().toString(),
                "--loglevel", "info"
        );
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);

        Process process;
        try {
            process = pb.start();
        } catch (Exception e) {
            lastTunnelFailure = "cloudflared start failed: " + e.getMessage();
            System.err.println("[Webhook Page] " + lastTunnelFailure);
            return null;
        }
        cloudflaredProcess.set(process);
        cloudflaredLogFile = logFile;

        String foundUrl = null;
        long deadline = System.currentTimeMillis() + CLOUDFLARED_WAIT_MS;
        String lastSnippet = "";
        int readErrors = 0;
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive()) {
                foundUrl = extractUrlFromLog(logFile);
                if (foundUrl == null) {
                    lastSnippet = readLogTail(logFile, 600);
                    lastTunnelFailure = "cloudflared exited early (code="
                            + safeExitCode(process) + "). " + lastSnippet;
                }
                break;
            }
            foundUrl = extractUrlFromLog(logFile);
            if (foundUrl != null) {
                break;
            }
            try {
                lastSnippet = readLogTail(logFile, 600);
                Thread.sleep(LOG_POLL_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        if (foundUrl == null || foundUrl.isBlank()) {
            // One more read after a short settle (Windows may delay flush)
            try {
                Thread.sleep(400);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            foundUrl = extractUrlFromLog(logFile);
        }

        if (foundUrl == null || foundUrl.isBlank()) {
            stopCloudflared();
            if (lastTunnelFailure == null || lastTunnelFailure.isBlank()
                    || lastTunnelFailure.startsWith("cloudflared unavailable")
                    || lastTunnelFailure.startsWith("cloudflared start")) {
                String detail = lastSnippet.isBlank()
                        ? ("no URL in " + logFile + " within " + (CLOUDFLARED_WAIT_MS / 1000) + "s")
                        : lastSnippet;
                lastTunnelFailure = "cloudflared ran but tunnel domain was not parsed. " + detail;
            }
            System.err.println("[Webhook Page] " + lastTunnelFailure);
            return null;
        }

        lastTunnelFailure = "";
        String okHint = "Tunnel OK (cloudflared Quick Tunnel). Domain is random *.trycloudflare.com — "
                + "not Burp Collaborator.";
        if (!resolveStatus.isEmpty()) {
            okHint = okHint + " " + resolveStatus;
        }
        System.out.println("[Webhook Page] Tunnel domain ready: " + foundUrl);
        return new DiscoveryResult(
                stripTrailingSlash(foundUrl),
                Source.CLOUDFLARED,
                okHint
        );
    }

    private static int safeExitCode(Process process) {
        try {
            return process.exitValue();
        } catch (Exception e) {
            return -1;
        }
    }

    private static String extractUrlFromLog(Path logFile) {
        String content = readLogShared(logFile);
        if (content == null || content.isBlank()) {
            return null;
        }
        Matcher m = TRY_CLOUDFLARE.matcher(content);
        if (m.find()) {
            return m.group();
        }
        return null;
    }

    /**
     * Shared read — avoids exclusive lock issues while cloudflared appends the log on Windows.
     */
    private static String readLogShared(Path logFile) {
        if (logFile == null || !Files.isRegularFile(logFile)) {
            return null;
        }
        try {
            if (Files.size(logFile) <= 0) {
                return "";
            }
        } catch (Exception e) {
            return null;
        }
        try (FileInputStream in = new FileInputStream(logFile.toFile())) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            try {
                return Files.readString(logFile, StandardCharsets.UTF_8);
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    private static String readLogTail(Path logFile, int maxChars) {
        String content = readLogShared(logFile);
        if (content == null) {
            return "";
        }
        content = content.trim();
        if (content.length() <= maxChars) {
            return content;
        }
        return content.substring(content.length() - maxChars);
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
            lastTunnelFailure = "";
            return new DiscoveryResult(
                    stripTrailingSlash(chosen),
                    Source.NGROK,
                    "Tunnel OK (ngrok local API). Not Burp Collaborator."
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
                "FALLBACK ONLY — no Internet tunnel domain. Reason: " + failure
                        + " | Full Webhook URL below is LAN (not public). "
                        + "Check Extender output / ~/.webhook-page/logs/quick-tunnel.log then Refresh URL."
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
        if (process != null) {
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
        // Keep last log for user diagnosis; do not delete quick-tunnel.log here.
        cloudflaredLogFile = null;
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

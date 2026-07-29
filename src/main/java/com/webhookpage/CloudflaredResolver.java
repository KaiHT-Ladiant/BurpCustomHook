package com.webhookpage;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Resolves a usable {@code cloudflared} executable without requiring a separate install step.
 * <p>
 * Order:
 * <ol>
 *   <li>{@code PATH}</li>
 *   <li>cached binary under {@code ~/.webhook-page/bin/}</li>
 *   <li>extract official binary bundled inside this extension JAR (Apache-2.0)</li>
 *   <li>download from GitHub releases (fallback)</li>
 * </ol>
 * ngrok is <strong>not</strong> redistributed (proprietary).
 * Unrelated to PortSwigger Burp Collaborator.
 */
public final class CloudflaredResolver {

    private static final String CACHE_DIR_NAME = ".webhook-page";
    private static final String BIN_DIR_NAME = "bin";
    private static final String BUNDLE_RESOURCE_PREFIX = "/cloudflared/";
    private static final String PINNED_TAG = "2025.2.0";
    private static final long MIN_VALID_BYTES = 5_000_000L;
    private static final int MAX_DOWNLOAD_ATTEMPTS = 2;

    private CloudflaredResolver() {
    }

    /**
     * @return absolute path to cloudflared, or {@code null} if unavailable
     */
    public static String resolveExecutable(StringBuilder statusOut) {
        if (isCommandOnPath("cloudflared")) {
            if (statusOut != null) {
                statusOut.append("Using cloudflared from PATH.");
            }
            return "cloudflared";
        }

        Path cached = cachedBinaryPath();
        cleanupStaleTemp(cached);
        try {
            if (isValidBinary(cached)) {
                if (statusOut != null) {
                    statusOut.append("Using cached cloudflared: ").append(cached);
                }
                return cached.toAbsolutePath().toString();
            }
        } catch (IOException ignored) {
            // extract / download
        }

        try {
            if (extractBundledBinary(cached)) {
                if (statusOut != null) {
                    statusOut.append("Extracted bundled cloudflared to ").append(cached);
                }
                return cached.toAbsolutePath().toString();
            }
        } catch (Exception e) {
            System.err.println("[Webhook Page] Bundled cloudflared extract failed: " + rootMessage(e));
        }

        Exception lastError = null;
        for (int attempt = 1; attempt <= MAX_DOWNLOAD_ATTEMPTS; attempt++) {
            try {
                Path downloaded = downloadOfficialBinary(cached);
                if (statusOut != null) {
                    statusOut.append("Downloaded cloudflared to ").append(downloaded);
                }
                return downloaded.toAbsolutePath().toString();
            } catch (Exception e) {
                lastError = e;
                System.err.println("[Webhook Page] cloudflared download attempt "
                        + attempt + "/" + MAX_DOWNLOAD_ATTEMPTS + " failed: " + e);
                try {
                    Thread.sleep(600L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        String detail = lastError == null ? "unknown error" : rootMessage(lastError);
        if (statusOut != null) {
            statusOut.append("cloudflared unavailable (bundle+download failed): ").append(detail)
                    .append(" | ").append(manualInstallHint());
        }
        System.err.println("[Webhook Page] cloudflared unavailable: " + detail);
        System.err.println("[Webhook Page] " + manualInstallHint());
        return null;
    }

    public static String manualInstallHint() {
        Path dest = cachedBinaryPath();
        String asset = officialAssetName();
        String url = asset == null
                ? "https://github.com/cloudflare/cloudflared/releases"
                : "https://github.com/cloudflare/cloudflared/releases/latest/download/" + asset;
        return "Optional manual place: save official cloudflared as "
                + dest.toAbsolutePath()
                + " from " + url
                + " (Apache-2.0 tunnel client — not Burp Collaborator).";
    }

    public static Path cachedBinaryPath() {
        String fileName = isWindows() ? "cloudflared.exe" : "cloudflared";
        return Path.of(System.getProperty("user.home"), CACHE_DIR_NAME, BIN_DIR_NAME, fileName);
    }

    /**
     * Extracts the platform binary packaged under {@code /cloudflared/} in the JAR.
     */
    static boolean extractBundledBinary(Path destination) throws IOException {
        String asset = officialAssetName();
        if (asset == null) {
            return false;
        }
        String resource = BUNDLE_RESOURCE_PREFIX + asset;
        try (InputStream in = CloudflaredResolver.class.getResourceAsStream(resource)) {
            if (in == null) {
                System.err.println("[Webhook Page] No bundled resource " + resource
                        + " (rebuild JAR with generate-resources download).");
                return false;
            }
            Files.createDirectories(destination.getParent());
            Path temp = Path.of(
                    System.getProperty("java.io.tmpdir"),
                    "webhook-page-bundled-cf-" + UUID.randomUUID() + (isWindows() ? ".exe" : ".bin")
            );
            try (OutputStream out = Files.newOutputStream(temp)) {
                in.transferTo(out);
            }
            if (Files.size(temp) < MIN_VALID_BYTES) {
                Files.deleteIfExists(temp);
                throw new IOException("Bundled cloudflared too small: " + resource);
            }
            installFromTemp(temp, destination);
            makeExecutable(destination);
            return isValidBinary(destination);
        }
    }

    static Path downloadOfficialBinary(Path destination) throws Exception {
        String asset = officialAssetName();
        if (asset == null) {
            throw new IOException("Unsupported OS/arch for cloudflared download.");
        }

        Files.createDirectories(destination.getParent());
        cleanupStaleTemp(destination);

        Path temp = Path.of(
                System.getProperty("java.io.tmpdir"),
                "webhook-page-cloudflared-" + UUID.randomUUID() + (isWindows() ? ".exe" : ".bin")
        );

        Exception last = null;
        for (URI uri : candidateDownloadUris(asset)) {
            try {
                System.out.println("[Webhook Page] Downloading cloudflared from " + uri);
                downloadToFile(uri, temp);
                if (Files.size(temp) < MIN_VALID_BYTES) {
                    Files.deleteIfExists(temp);
                    throw new IOException("Downloaded file too small from " + uri);
                }
                installFromTemp(temp, destination);
                makeExecutable(destination);
                return destination;
            } catch (Exception e) {
                last = e;
                System.err.println("[Webhook Page] Download source failed (" + uri + "): " + rootMessage(e));
                try {
                    Files.deleteIfExists(temp);
                } catch (Exception ignored) {
                    // continue
                }
            }
        }
        throw last != null ? last : new IOException("All cloudflared download URLs failed.");
    }

    private static List<URI> candidateDownloadUris(String asset) {
        List<URI> uris = new ArrayList<>();
        uris.add(URI.create(
                "https://github.com/cloudflare/cloudflared/releases/download/"
                        + PINNED_TAG + "/" + asset));
        uris.add(URI.create(
                "https://github.com/cloudflare/cloudflared/releases/latest/download/" + asset));
        return uris;
    }

    private static void downloadToFile(URI uri, Path destination) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .sslContext(jvmCacertsSslContext())
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(30))
                .proxy(ProxySelector.getDefault())
                .build();

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMinutes(4))
                .header("User-Agent", "Mozilla/5.0 (compatible; WebhookPage-BurpExtension/1.1)")
                .header("Accept", "application/octet-stream,*/*")
                .GET()
                .build();

        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        int code = response.statusCode();
        if (code < 200 || code >= 300) {
            try (InputStream ignored = response.body()) {
                // drain
            }
            throw new IOException("HTTP " + code + " from " + uri);
        }

        try (InputStream in = response.body();
             OutputStream out = Files.newOutputStream(destination)) {
            in.transferTo(out);
        }
    }

    private static SSLContext jvmCacertsSslContext() throws Exception {
        Path cacerts = Path.of(System.getProperty("java.home"), "lib", "security", "cacerts");
        if (!Files.isRegularFile(cacerts)) {
            return SSLContext.getDefault();
        }
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        try (InputStream in = Files.newInputStream(cacerts)) {
            ks.load(in, "changeit".toCharArray());
        }
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tmf.getTrustManagers(), null);
        return ctx;
    }

    private static void installFromTemp(Path temp, Path destination) throws IOException {
        Files.createDirectories(destination.getParent());
        try {
            Files.copy(temp, destination, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.deleteIfExists(destination);
            Files.copy(temp, destination, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            try {
                Files.deleteIfExists(temp);
            } catch (Exception ignored) {
                temp.toFile().deleteOnExit();
            }
        }
        if (!isValidBinary(destination)) {
            throw new IOException("Failed to install cloudflared into " + destination);
        }
    }

    private static boolean isValidBinary(Path path) throws IOException {
        return Files.isRegularFile(path) && Files.size(path) > MIN_VALID_BYTES;
    }

    private static void cleanupStaleTemp(Path cached) {
        try {
            Files.deleteIfExists(cached.resolveSibling(cached.getFileName() + ".tmp"));
        } catch (Exception ignored) {
            // best-effort
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        String best = t.getMessage();
        while (cur != null) {
            if (cur.getMessage() != null && !cur.getMessage().isBlank()) {
                best = cur.getClass().getSimpleName() + ": " + cur.getMessage();
            }
            cur = cur.getCause();
        }
        return (best == null || best.isBlank()) ? t.getClass().getSimpleName() : best;
    }

    static String officialAssetName() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean arm = arch.contains("aarch64") || arch.contains("arm64");
        boolean x64 = arch.contains("amd64") || arch.equals("x86_64") || arch.equals("x64");

        if (os.contains("win")) {
            return x64 || arch.contains("amd") ? "cloudflared-windows-amd64.exe" : null;
        }
        if (os.contains("mac") || os.contains("darwin")) {
            if (arm) {
                return "cloudflared-darwin-arm64";
            }
            if (x64) {
                return "cloudflared-darwin-amd64";
            }
            return null;
        }
        if (os.contains("nux") || os.contains("nix")) {
            if (arm) {
                return "cloudflared-linux-arm64";
            }
            if (x64) {
                return "cloudflared-linux-amd64";
            }
            return null;
        }
        return null;
    }

    private static void makeExecutable(Path path) {
        try {
            Set<PosixFilePermission> perms = EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ,
                    PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ,
                    PosixFilePermission.OTHERS_EXECUTE
            );
            Files.setPosixFilePermissions(path, perms);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows or FS without POSIX perms
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static boolean isCommandOnPath(String command) {
        boolean windows = isWindows();
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
}

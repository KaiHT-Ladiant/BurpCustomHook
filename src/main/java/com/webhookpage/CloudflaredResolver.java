package com.webhookpage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Resolves a usable {@code cloudflared} executable.
 * <p>
 * Order: {@code PATH} → cached binary under {@code ~/.webhook-page/bin/} →
 * download from the official Cloudflare GitHub release (Apache-2.0).
 * <p>
 * ngrok binaries are <strong>not</strong> downloaded or redistributed here
 * (ngrok is proprietary; users must install/run ngrok themselves).
 */
public final class CloudflaredResolver {

    private static final String CACHE_DIR_NAME = ".webhook-page";
    private static final String BIN_DIR_NAME = "bin";
    private static final String DOWNLOAD_BASE =
            "https://github.com/cloudflare/cloudflared/releases/latest/download/";

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
        if (Files.isRegularFile(cached) && Files.isExecutable(cached)) {
            if (statusOut != null) {
                statusOut.append("Using cached cloudflared: ").append(cached);
            }
            return cached.toAbsolutePath().toString();
        }

        try {
            Path downloaded = downloadOfficialBinary(cached);
            if (statusOut != null) {
                statusOut.append("Downloaded cloudflared to ").append(downloaded);
            }
            return downloaded.toAbsolutePath().toString();
        } catch (Exception e) {
            if (statusOut != null) {
                statusOut.append("cloudflared download failed: ").append(e.getMessage());
            }
            return null;
        }
    }

    static Path cachedBinaryPath() {
        String fileName = isWindows() ? "cloudflared.exe" : "cloudflared";
        return Path.of(System.getProperty("user.home"), CACHE_DIR_NAME, BIN_DIR_NAME, fileName);
    }

    static Path downloadOfficialBinary(Path destination) throws IOException {
        String asset = officialAssetName();
        if (asset == null) {
            throw new IOException("Unsupported OS/arch for bundled cloudflared download.");
        }

        Files.createDirectories(destination.getParent());
        Path temp = destination.resolveSibling(destination.getFileName() + ".tmp");

        URI uri = URI.create(DOWNLOAD_BASE + asset);
        HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(120_000);
        conn.setRequestProperty("User-Agent", "WebhookPage-BurpExtension");
        int code = conn.getResponseCode();
        // Handle manual redirect if needed
        if (code == HttpURLConnection.HTTP_MOVED_PERM
                || code == HttpURLConnection.HTTP_MOVED_TEMP
                || code == HttpURLConnection.HTTP_SEE_OTHER
                || code == 307 || code == 308) {
            String location = conn.getHeaderField("Location");
            conn.disconnect();
            if (location == null || location.isBlank()) {
                throw new IOException("cloudflared download redirect without Location.");
            }
            conn = (HttpURLConnection) URI.create(location).toURL().openConnection();
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(120_000);
            conn.setRequestProperty("User-Agent", "WebhookPage-BurpExtension");
            code = conn.getResponseCode();
        }
        if (code < 200 || code >= 300) {
            conn.disconnect();
            throw new IOException("HTTP " + code + " downloading " + asset);
        }

        try (InputStream in = conn.getInputStream();
             OutputStream out = Files.newOutputStream(temp)) {
            in.transferTo(out);
        } finally {
            conn.disconnect();
        }

        Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        makeExecutable(destination);
        return destination;
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

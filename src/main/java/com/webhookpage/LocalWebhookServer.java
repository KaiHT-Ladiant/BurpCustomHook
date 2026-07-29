package com.webhookpage;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Dedicated webhook HTTP server. Completely independent from Burp's proxy pipeline
 * to avoid deadlocks caused by proxy request rewriting.
 */
public final class LocalWebhookServer implements AutoCloseable {

    private final WebhookConfig config;
    private final Consumer<WebhookRequestLog> logConsumer;
    private final ServerSocket serverSocket;
    private final ExecutorService acceptPool;
    private final ExecutorService workerPool;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final int port;

    public LocalWebhookServer(WebhookConfig config, Consumer<WebhookRequestLog> logConsumer) throws IOException {
        this.config = config;
        this.logConsumer = logConsumer;
        // Loopback only — ngrok/local tunnels forward to 127.0.0.1
        this.serverSocket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        this.port = serverSocket.getLocalPort();

        ThreadFactory acceptFactory = namedDaemonFactory("webhook-page-accept");
        ThreadFactory workerFactory = namedDaemonFactory("webhook-page-worker");

        this.acceptPool = Executors.newSingleThreadExecutor(acceptFactory);
        // Bounded pool — never starve the JVM / Burp with unbounded threads
        this.workerPool = Executors.newFixedThreadPool(8, workerFactory);

        acceptPool.execute(this::acceptLoop);
    }

    public int getPort() {
        return port;
    }

    public String getLocalBaseUrl() {
        return "http://127.0.0.1:" + port;
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket socket = serverSocket.accept();
                socket.setSoTimeout(10_000);
                try {
                    workerPool.execute(() -> handleClient(socket));
                } catch (Exception ex) {
                    closeQuietly(socket);
                }
            } catch (SocketException e) {
                break;
            } catch (IOException e) {
                if (!running.get()) {
                    break;
                }
            }
        }
    }

    private void handleClient(Socket socket) {
        try (Socket s = socket;
             InputStream rawIn = new BufferedInputStream(s.getInputStream());
             OutputStream out = s.getOutputStream()) {

            ParsedRequest parsed = readRequest(rawIn);
            if (parsed == null) {
                return;
            }

            if (config.pathMatches(parsed.path)) {
                if (!config.isEnabled()) {
                    writeHtml(out, 503, "Service Unavailable",
                            "<!DOCTYPE html><html><body><h1>Webhook disabled</h1>"
                                    + "<p>Public Webhook is OFF.</p></body></html>");
                } else {
                    logConsumer.accept(new WebhookRequestLog(
                            Instant.now(),
                            parsed.method,
                            buildUrl(parsed),
                            clientIp(s),
                            parsed.userAgent,
                            WebhookRequestLog.summarizeBody(parsed.body, 200)
                    ));
                    writeHtml(out, 200, "OK", config.getHtmlContent());
                }
            } else {
                writeHtml(out, 404, "Not Found",
                        "<!DOCTYPE html><html><body><h1>404</h1><p>Webhook path mismatch.</p></body></html>");
            }
        } catch (SocketTimeoutException ignored) {
            // slow / incomplete client
        } catch (IOException ignored) {
            // disconnected
        }
    }

    private String buildUrl(ParsedRequest parsed) {
        if (parsed.host != null && !parsed.host.isBlank()) {
            return "http://" + parsed.host + parsed.rawTarget;
        }
        return getLocalBaseUrl() + parsed.rawTarget;
    }

    private static String clientIp(Socket socket) {
        try {
            InetAddress addr = socket.getInetAddress();
            return addr == null ? "(unknown)" : addr.getHostAddress();
        } catch (Exception e) {
            return "(unknown)";
        }
    }

    private static void writeHtml(OutputStream out, int status, String reason, String html) throws IOException {
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        String header = "HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Content-Type: text/html; charset=UTF-8\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "\r\n";
        out.write(header.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private static ParsedRequest readRequest(InputStream in) throws IOException {
        byte[] headerBytes = readUntilHeaderEnd(in);
        if (headerBytes.length == 0) {
            return null;
        }
        String headers = new String(headerBytes, StandardCharsets.ISO_8859_1);
        String[] lines = headers.split("\r\n");
        if (lines.length == 0 || lines[0].isBlank()) {
            return null;
        }

        String[] parts = lines[0].split(" ", 3);
        if (parts.length < 2) {
            return null;
        }
        String method = parts[0];
        String target = parts[1];

        String path = target;
        int q = path.indexOf('?');
        if (q >= 0) {
            path = path.substring(0, q);
        }
        // Absolute-form proxy URL: http://host/path
        if (path.startsWith("http://") || path.startsWith("https://")) {
            try {
                int schemeEnd = path.indexOf("://");
                int pathStart = path.indexOf('/', schemeEnd + 3);
                path = pathStart >= 0 ? path.substring(pathStart) : "/";
            } catch (Exception ignored) {
                path = "/";
            }
        }

        String host = headerValue(lines, "Host");
        String userAgent = headerValue(lines, "User-Agent");
        String body = drainBody(in, lines);

        return new ParsedRequest(method, WebhookConfig.normalizePath(path), target, host, userAgent, body);
    }

    private static String headerValue(String[] lines, String name) {
        String prefix = name.toLowerCase() + ":";
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.toLowerCase().startsWith(prefix)) {
                return line.substring(prefix.length()).trim();
            }
        }
        return "";
    }

    private static String drainBody(InputStream in, String[] headerLines) throws IOException {
        long contentLength = -1;
        boolean chunked = false;
        for (int i = 1; i < headerLines.length; i++) {
            String lower = headerLines[i].toLowerCase();
            if (lower.startsWith("content-length:")) {
                try {
                    contentLength = Long.parseLong(lower.substring("content-length:".length()).trim());
                } catch (NumberFormatException ignored) {
                    contentLength = -1;
                }
            } else if (lower.startsWith("transfer-encoding:") && lower.contains("chunked")) {
                chunked = true;
            }
        }

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        if (contentLength > 0) {
            long remaining = Math.min(contentLength, 64_000); // cap stored body for log summary
            byte[] buf = new byte[4096];
            long skippedExtra = contentLength - remaining;
            while (remaining > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, remaining));
                if (n < 0) {
                    break;
                }
                body.write(buf, 0, n);
                remaining -= n;
            }
            long left = skippedExtra;
            while (left > 0) {
                long skipped = in.skip(left);
                if (skipped <= 0) {
                    if (in.read() < 0) {
                        break;
                    }
                    left--;
                } else {
                    left -= skipped;
                }
            }
        } else if (chunked) {
            // Best-effort: read a little for logging, then discard rest until timeout/close
            byte[] buf = new byte[2048];
            in.mark(1);
            int n = in.read(buf);
            if (n > 0) {
                body.write(buf, 0, Math.min(n, 512));
            }
        }
        return body.toString(StandardCharsets.UTF_8);
    }

    private static byte[] readUntilHeaderEnd(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(512);
        int state = 0;
        int b;
        while ((b = in.read()) != -1) {
            buf.write(b);
            if (b == '\r') {
                state = (state == 2) ? 3 : 1;
            } else if (b == '\n') {
                if (state == 1) {
                    state = 2;
                } else if (state == 3 || state == 2) {
                    return buf.toByteArray();
                } else {
                    byte[] bytes = buf.toByteArray();
                    if (bytes.length >= 2 && bytes[bytes.length - 2] == '\n') {
                        return bytes;
                    }
                    state = 0;
                }
            } else {
                state = 0;
            }
            if (buf.size() > 64 * 1024) {
                throw new IOException("Request headers too large");
            }
        }
        return buf.toByteArray();
    }

    private static ThreadFactory namedDaemonFactory(String prefix) {
        AtomicInteger idx = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + "-" + idx.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    @Override
    public void close() {
        running.set(false);
        try {
            serverSocket.close();
        } catch (IOException ignored) {
        }
        acceptPool.shutdownNow();
        workerPool.shutdownNow();
        try {
            acceptPool.awaitTermination(1, TimeUnit.SECONDS);
            workerPool.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record ParsedRequest(
            String method,
            String path,
            String rawTarget,
            String host,
            String userAgent,
            String body
    ) {
    }
}

# Webhook Page — Burp Suite Extension

![Burp Suite](https://img.shields.io/badge/Burp_Suite-Montoya_API-FF6633?logo=burpsuite&logoColor=white)
![Java](https://img.shields.io/badge/Java-17+-F89820?logo=openjdk&logoColor=white)
![Maven](https://img.shields.io/badge/Maven-3.8+-C71A36?logo=apachemaven&logoColor=white)
![Platform](https://img.shields.io/badge/Platform-Cross--Platform-lightgrey)

Serves user-defined HTML with `200 OK` for requests to a configured webhook path.

This extension does **not** intercept Burp Proxy traffic. A dedicated local HTTP server handles webhook requests so Burp stays responsive.

> **Korean documentation:** [README_KO.md](README_KO.md)

## Disclaimer (Burp Collaborator)

This project is **not** Burp Collaborator, is **not** affiliated with or endorsed by PortSwigger, and is **not** a Collaborator replacement. It does **not** use Collaborator infrastructure or Collaborator hostnames.

When **Public Webhook** is ON, a **tunnel you control** (cloudflared Quick Tunnel, or ngrok if you already run it) forwards HTTPS traffic to **your** Extension listen port. The hostname (e.g. `*.trycloudflare.com`) belongs to that tunnel vendor, not to Collaborator.

## Ports: Extension listen port ≠ Proxy

| What | Where to look | Use for webhook? |
|------|----------------|------------------|
| **Extension listen port** | **Webhook Page** tab → *Local listen (127.0.0.1)* | **Yes** — browser, cloudflared, ngrok |
| Burp **Proxy** listener | Proxy → Options (often `8080`) | **No** — proxy only |

Do **not** open `http://127.0.0.1:8080/...` as the webhook URL. That hits Burp’s proxy, not this extension, and typically yields:

```text
Invalid client request received: First line of request did not contain an absolute URL
- try enabling invisible proxy support.
```

Correct local check (example — use the port shown in the tab):

```text
http://127.0.0.1:<Extension-listen-port>/kai_ht/webhook
```

## Public Webhook ON/OFF

Default is **OFF**. Matching path returns **503** until you enable the toggle.

When **ON**:

1. Matching requests receive your HTML
2. A public base is discovered in order:
   1. **cloudflared** Quick Tunnel → random hostname under `*.trycloudflare.com` (shown as **Tunnel domain**)
      - Uses `cloudflared` on `PATH`, or cache under `~/.webhook-page/bin/`
      - If missing, downloads the official Cloudflare binary once ([Apache-2.0](https://github.com/cloudflare/cloudflared))
   2. Else **ngrok** — only if **you** already run ngrok; reads `http://127.0.0.1:4040/api/tunnels`
   3. Else **LAN IPv4** + Extension listen port (local network only — **not** a tunnel domain)
3. UI shows:
   - **Tunnel domain** — hostname only (empty / “LAN only” when no tunnel)
   - **Full Webhook URL** — base + required-token path (e.g. `/kai_ht/webhook`)

When **OFF**: **503** on the path; cloudflared started by this extension is stopped.

### Tunnel packaging

| Tool | Redistributed? | Notes |
|------|----------------|-------|
| **cloudflared** | Auto-download + cache (not inside the JAR) | Apache-2.0 — see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) |
| **ngrok** | **No** | Proprietary — install/run yourself if you prefer it |

Anyone who knows the Full Webhook URL can hit the path while Public Webhook is ON. Treat it as sensitive and turn the toggle OFF when finished.

### Manual cloudflared install (if auto-download fails)

Corporate TLS inspection / Burp JVM SSL quirks can cause:

```text
Remote host terminated the handshake
```

or a failed install into `~/.webhook-page/bin/cloudflared.exe.tmp`.

1. Download the official binary from [cloudflared releases](https://github.com/cloudflare/cloudflared/releases) (Apache-2.0)
2. Save as `~/.webhook-page/bin/cloudflared.exe` (Windows) or `~/.webhook-page/bin/cloudflared` (macOS/Linux)
3. In the extension tab, click **Refresh URL**

This is only the Cloudflare tunnel client — **not** Burp Collaborator.

## Features

- **Webhook Page** suite tab
- **Public Webhook** ON/OFF (default OFF)
- Separate **Tunnel domain** + **Full Webhook URL**
- Auto public base (cloudflared → optional ngrok → LAN)
- Required path token (default: `kai_ht`)
- Custom HTML response + request log

## Requirements

- Burp Suite (Montoya API)
- JDK 17+ (build) / Burp’s bundled JRE (runtime)
- Maven 3.8+ (build)
- Outbound HTTPS on first Public Webhook use (to fetch cloudflared, unless already installed)

## Build

```bash
mvn clean package
```

Output: `target/webhook-page-extension-1.0.6.jar`

## Install

1. Burp → **Extensions** → **Installed** → **Add**
2. Extension type: **Java**
3. Select the JAR from [Releases](https://github.com/KaiHT-Ladiant/BurpCustomHook/releases)
4. Open the **Webhook Page** tab

## Usage

1. Set **Required token**, **Webhook path**, **Response HTML** → **Apply**
2. Note **Local listen (127.0.0.1)** — that port is the tunnel target (not Proxy `8080`)
3. Check **Public Webhook** (first run may download cloudflared; wait up to ~45s)
4. Confirm **Tunnel domain** shows a hostname (e.g. `….trycloudflare.com`) → **Copy URL** or **Copy domain**
5. If domain stays “(none — LAN only…)”, check Extender output for cloudflared errors, then **Refresh URL**
6. Uncheck **Public Webhook** when done

## Implementation note

Loopback `ServerSocket` HTTP server only — no Burp Proxy destination rewriting (avoids UI freezes).

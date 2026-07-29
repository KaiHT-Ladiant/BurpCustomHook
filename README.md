# Webhook Page — Burp Suite Extension

![Burp Suite](https://img.shields.io/badge/Burp_Suite-Montoya_API-FF6633?logo=burpsuite&logoColor=white)
![Java](https://img.shields.io/badge/Java-17+-F89820?logo=openjdk&logoColor=white)
![Maven](https://img.shields.io/badge/Maven-3.8+-C71A36?logo=apachemaven&logoColor=white)
![Platform](https://img.shields.io/badge/Platform-Cross--Platform-lightgrey)

Serves user-defined HTML with `200 OK` for requests to a configured webhook path.

This extension does **not** intercept Burp Proxy traffic. A dedicated local HTTP server handles webhook requests so Burp stays responsive.

> **Korean documentation:** [README_KO.md](README_KO.md)

> **Not Burp Collaborator.** This project is **not** affiliated with, endorsed by, or a substitute for PortSwigger **Burp Collaborator**. It does not use Collaborator infrastructure. When Public Webhook is ON, traffic is delivered to **your** local Extension server through a tunnel you control (or a LAN address).

## Important: Do not confuse ports

| Target | Port | Purpose |
|--------|------|---------|
| **Extension webhook server** | **Extension listen port** shown in the tab | Browser / tunnel target |
| Burp Proxy Listener | Usually `8080` | Proxy only — **not** your webhook URL |

Accessing `http://127.0.0.1:8080/kai_ht/webhook` (Burp Proxy port) produces:

```text
Invalid client request received: First line of request did not contain an absolute URL
- try enabling invisible proxy support.
```

## Public Webhook ON/OFF + auto public URL

Public Webhook defaults to **OFF**. When you enable **Public Webhook**:

1. Matching requests receive your HTML (`enabled = true`)
2. A ready-to-use public base URL is generated automatically:
   1. **cloudflared** Quick Tunnel → random `https://….trycloudflare.com`
      - Uses `cloudflared` on `PATH`, or a cached copy under `~/.webhook-page/bin/`
      - If missing, downloads the official Cloudflare binary once (Apache-2.0)
   2. Else **ngrok** (optional) — only if **you** already run ngrok; reads `http://127.0.0.1:4040/api/tunnels`
   3. Else **LAN IPv4** + extension port (local network only)
3. Deployable URL = public base + required-token path (e.g. `/kai_ht/webhook`)

When **OFF**: matching requests return **503**, and any cloudflared process started by the extension is stopped.

### Tunnel packaging notes

| Tool | Bundled / redistributed? | Why |
|------|--------------------------|-----|
| **cloudflared** | Yes (auto-download + cache, not inside the JAR) | Official binaries are [Apache-2.0](https://github.com/cloudflare/cloudflared); see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) |
| **ngrok** | **No** | Proprietary — do **not** redistribute the ngrok binary; install/run ngrok yourself if you prefer it |

Anyone who learns the generated URL can reach the webhook path while Public Webhook is ON. Treat the URL as a secret and turn the toggle OFF when finished.

## Features

- **Webhook Page** suite tab
- **Public Webhook** ON/OFF (default OFF)
- Auto public URL (cloudflared → optional ngrok → LAN)
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

Output: `target/webhook-page-extension-1.0.2.jar`

## Install

1. Burp → **Extensions** → **Installed** → **Add**
2. Extension type: **Java**
3. Select the JAR from [Releases](https://github.com/KaiHT-Ladiant/BurpCustomHook/releases)
4. Open the **Webhook Page** tab

## Usage

1. Set **Required token**, **Webhook path**, **Response HTML** → **Apply**
2. Check **Public Webhook** (first run may download cloudflared)
3. Wait until the URL is ready → **Copy URL**
4. Uncheck **Public Webhook** when done

## Implementation note

Loopback `ServerSocket` HTTP server only — no Burp Proxy destination rewriting (avoids UI freezes).

# Webhook Page — Burp Suite Extension

![Burp Suite](https://img.shields.io/badge/Burp_Suite-Montoya_API-FF6633?logo=burpsuite&logoColor=white)
![Java](https://img.shields.io/badge/Java-17+-F89820?logo=openjdk&logoColor=white)
![Maven](https://img.shields.io/badge/Maven-3.8+-C71A36?logo=apachemaven&logoColor=white)
![Platform](https://img.shields.io/badge/Platform-Cross--Platform-lightgrey)

Serves user-defined HTML with `200 OK` for requests to a configured webhook path.

This extension does **not** intercept Burp Proxy traffic. A dedicated local HTTP server handles webhook requests so Burp stays responsive.

> **Korean documentation:** [README_KO.md](README_KO.md)

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

Local listen example (port changes each time the extension loads):

```text
http://127.0.0.1:51234
```

## Public Webhook ON/OFF + auto public URL

Public Webhook defaults to **OFF** (safer). When you enable the **Public Webhook** checkbox:

1. `config.enabled = true` — matching requests receive your HTML
2. The extension **auto-generates** a ready-to-use public base URL (priority order):
   1. **cloudflared** Quick Tunnel (`cloudflared tunnel --url http://127.0.0.1:<port>`) if `cloudflared` is on `PATH` → `https://….trycloudflare.com`
   2. Else **ngrok** local API at `http://127.0.0.1:4040/api/tunnels` → first `https` (or `http`) `public_url`
   3. Else **LAN IPv4** + extension port → `http://<lan-ip>:<port>`
3. The deployable Webhook URL = public base + required-token path (e.g. `/kai_ht/webhook`)

When Public Webhook is **OFF**:

- Matching path requests return **503** with “Webhook disabled”
- Any cloudflared process started by the extension is stopped
- The big URL field shows `(Webhook OFF)`

There is **no manual public-address text field** — the URL is always auto-generated.

Optional helpers for Internet exposure:

```bash
# Prefer: install cloudflared and leave PATH detection to the extension
# Or run ngrok yourself against the Extension listen port:
ngrok http 51234
```

## Features

- **Webhook Page** suite tab
- **Public Webhook** ON/OFF toggle (default OFF)
- Auto public URL (cloudflared → ngrok → LAN)
- Deployable Webhook URL display / copy (public base + required token path)
- **Required path token** enforcement (default: `kai_ht`)
- Custom HTML response
- Request log table

## Requirements

- Burp Suite (Montoya API)
- JDK 17+
- Maven 3.8+
- Optional: [cloudflared](https://developers.cloudflare.com/cloudflare-one/connections/connect-apps/install-and-setup/installation/) or [ngrok](https://ngrok.com/) for Internet-reachable URLs

## Build

```bash
mvn clean package
```

Output: `target/webhook-page-extension-1.0.1.jar`

## Install

1. Burp → **Extensions** → **Installed** → **Add**
2. Extension type: **Java**
3. Select the built JAR
4. Open the **Webhook Page** tab and note the Extension listen port

## Usage

1. Set **Required token**, **Webhook path**, and **Response HTML**, then click **Apply**
2. Check **Public Webhook** to turn availability ON and auto-generate the public URL
3. Wait until the URL field leaves `Generating...`, then use **Copy URL**
4. Uncheck **Public Webhook** to disable (503) and tear down tunnels started by the extension

## Implementation note

The extension runs a lightweight `ServerSocket`-based HTTP server on loopback. It does not rewrite Burp Proxy destinations, avoiding UI freezes caused by in-process proxy loops.

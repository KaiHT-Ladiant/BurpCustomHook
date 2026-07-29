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

When **Public Webhook** is ON, traffic reaches **your** Extension listen port through a tunnel helper you control (bundled cloudflared, optional ngrok you already run, or system OpenSSH). Random hostnames such as `*.trycloudflare.com` belong to that tunnel vendor — not Collaborator.

## Zero-config Public Webhook

You do **not** need to install cloudflared separately.

From **v1.1.0**, the release JAR **bundles** official Cloudflare `cloudflared` binaries (Apache-2.0). On first Public Webhook ON, the matching OS binary is extracted to `~/.webhook-page/bin/` and a Quick Tunnel is started automatically.

Priority:

1. **Bundled / cached cloudflared** Quick Tunnel → `*.trycloudflare.com`
2. **ngrok** — only if **you** already run it (`127.0.0.1:4040`) — binary is **not** redistributed
3. **OpenSSH** reverse tunnel (system `ssh`, e.g. Pinggy / localhost.run) — no extra proprietary binary
4. **LAN** address only (last resort; not a public tunnel domain)

### Why cloudflared (and not only “another binary”)?

| Option | Free public HTTPS hostname? | Can we ship it in the JAR? |
|--------|-----------------------------|----------------------------|
| **cloudflared** Quick Tunnel | Yes (`*.trycloudflare.com`) | Yes (Apache-2.0) |
| **ngrok** | Yes | **No** (proprietary) |
| **OpenSSH** to a free relay | Often yes | Uses OS `ssh` (nothing to ship) |
| Self-hosted frp/bore/etc. | Yes | Needs **your** server |

cloudflared is used because it is one of the few **redistributable** clients that gives a random public HTTPS URL **without** you running a VPS. The JAR also tries OpenSSH as a second path. This is still **not** Burp Collaborator.

## Ports: Extension listen port ≠ Proxy

| What | Where to look | Use for webhook? |
|------|----------------|------------------|
| **Extension listen port** | **Webhook Page** tab → *Local listen* | **Yes** |
| Burp **Proxy** listener | Proxy → Options (often `8080`) | **No** |

## Usage

1. Install the JAR from [Releases](https://github.com/KaiHT-Ladiant/BurpCustomHook/releases)
2. Open **Webhook Page** (confirm version **1.1.0+**)
3. Edit **Response HTML** / path → **Apply**
4. Enable **Public Webhook** → wait for **Tunnel domain** → **Copy URL**
5. Disable **Public Webhook** when finished

## Build

```bash
mvn clean package
```

Build downloads official cloudflared assets into the JAR (`generate-resources`).  
Output: `target/webhook-page-extension-1.1.0.jar` (larger than earlier releases because binaries are embedded).

## Third-party

See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

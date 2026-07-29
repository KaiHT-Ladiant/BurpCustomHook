# Third-party notices

## cloudflared (Cloudflare Tunnel client)

- Project: https://github.com/cloudflare/cloudflared
- License: Apache License 2.0
- Official release binaries for major OS/arch may be **bundled inside the extension JAR**
  (build-time download) and extracted on first use to `~/.webhook-page/bin/`.
- If missing, the extension may also download the same official binary at runtime.
- Cloudflare / cloudflared are trademarks of their respective owners.
- This project is not affiliated with Cloudflare.
- Bundled tunnel helpers are **not** PortSwigger Burp Collaborator.

## ngrok

- The **ngrok binary is not redistributed** with this project.
- If you already run ngrok locally, the extension may read tunnel URLs from the
  local ngrok API (`http://127.0.0.1:4040/api/tunnels`) only.
- ngrok is proprietary software; follow ngrok’s terms when installing it yourself.

## OpenSSH / third-party SSH relays

- The extension may invoke the system `ssh` client for optional reverse tunnels
  (e.g. community free relays). No SSH server binary is redistributed.
- Relay services have their own terms; use at your own risk.

## PortSwigger / Burp Suite

- Burp Suite and Burp Collaborator are trademarks of PortSwigger Ltd.
- This extension uses the Burp Montoya API (`provided` scope) and is **not**
  Burp Collaborator, nor a PortSwigger product.

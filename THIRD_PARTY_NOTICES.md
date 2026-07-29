# Third-party notices

## cloudflared (Cloudflare Tunnel client)

- Project: https://github.com/cloudflare/cloudflared
- License: Apache License 2.0
- This extension may download the official release binary into
  `~/.webhook-page/bin/` when Public Webhook is enabled and `cloudflared` is not
  already available on `PATH`.
- Cloudflare / cloudflared are trademarks of their respective owners.
- This project is not affiliated with Cloudflare.

## ngrok

- The **ngrok binary is not redistributed** with this project.
- If you already run ngrok locally, the extension may read tunnel URLs from the
  local ngrok API (`http://127.0.0.1:4040/api/tunnels`) only.
- ngrok is proprietary software; follow ngrok’s terms when installing it yourself.

## PortSwigger / Burp Suite

- Burp Suite and Burp Collaborator are trademarks of PortSwigger Ltd.
- This extension uses the Burp Montoya API (`provided` scope) and is **not**
  Burp Collaborator, nor a PortSwigger product.

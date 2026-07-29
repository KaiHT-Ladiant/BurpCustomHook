# Webhook Page — Burp Suite Extension (한국어)

![Burp Suite](https://img.shields.io/badge/Burp_Suite-Montoya_API-FF6633?logo=burpsuite&logoColor=white)
![Java](https://img.shields.io/badge/Java-17+-F89820?logo=openjdk&logoColor=white)
![Maven](https://img.shields.io/badge/Maven-3.8+-C71A36?logo=apachemaven&logoColor=white)
![Platform](https://img.shields.io/badge/Platform-Cross--Platform-lightgrey)

지정한 Webhook 경로로 들어온 요청에 사용자가 작성한 HTML을 `200 OK`로 응답합니다.

Burp Proxy를 가로채지 않습니다. Extension 전용 HTTP 서버가 응답하므로 Burp UI가 멈추지 않습니다.

> **English documentation:** [README.md](README.md)

## 고지 (Burp Collaborator)

이 프로젝트는 **Burp Collaborator가 아니며**, PortSwigger와 무관하고 Collaborator를 대체하지 않습니다. Collaborator 인프라·호스트명을 사용하지 않습니다.

**Public Webhook** ON 시 트래픽은 **사용자 PC의 Extension 수신 포트**로 전달됩니다. `*.trycloudflare.com` 등은 터널 제공자 호스트명이며 Collaborator가 아닙니다.

## 별도 설치 없이 동작 (v1.1.0+)

cloudflared를 **따로 설치할 필요 없습니다.**

릴리스 JAR에 공식 `cloudflared` 바이너리(Apache-2.0)가 **포함**됩니다. Public Webhook을 켜면 OS에 맞는 바이너리를 `~/.webhook-page/bin/`에 풀고 Quick Tunnel을 자동 시작합니다.

우선순위:

1. **번들/캐시 cloudflared** → `*.trycloudflare.com`
2. **ngrok** — 사용자가 이미 실행 중일 때만 (바이너리 재배포 안 함)
3. **OpenSSH** (`ssh`) 리버스 터널 — 추가 독점 바이너리 없음
4. **LAN** (최후 수단, 공개 터널 도메인 아님)

### 왜 Cloudflare 바이너리인가?

| 옵션 | 무료 공개 HTTPS 호스트 | JAR에 실을 수 있나 |
|------|------------------------|-------------------|
| **cloudflared** | 가능 | 가능 (Apache-2.0) |
| **ngrok** | 가능 | **불가** (독점) |
| **OpenSSH** 릴레이 | 가능한 경우 많음 | OS `ssh` 사용 |
| 자체 frp/bore 등 | 가능 | **본인 서버** 필요 |

VPS 없이 공개 HTTPS를 쓰면서 **재배포 가능한** 클라이언트가 cloudflared라서 기본으로 씁니다. 보조로 OpenSSH도 시도합니다. 여전히 **Burp Collaborator가 아닙니다.**

## 포트

| 구분 | 확인 | Webhook? |
|------|------|----------|
| Extension 수신 포트 | 탭 *Local listen* | **예** |
| Burp Proxy (흔히 8080) | Proxy Options | **아니오** |

## 사용

1. [Releases](https://github.com/KaiHT-Ladiant/BurpCustomHook/releases) JAR 설치  
2. **Webhook Page 1.1.0+** 확인  
3. HTML/경로 **Apply** → **Public Webhook** ON → **Tunnel domain** 확인 → **Copy URL**

## 빌드

```bash
mvn clean package
```

산출물: `target/webhook-page-extension-1.1.0.jar` (바이너리 포함으로 용량이 큼)

# Webhook Page — Burp Suite Extension (한국어)

![Burp Suite](https://img.shields.io/badge/Burp_Suite-Montoya_API-FF6633?logo=burpsuite&logoColor=white)
![Java](https://img.shields.io/badge/Java-17+-F89820?logo=openjdk&logoColor=white)
![Maven](https://img.shields.io/badge/Maven-3.8+-C71A36?logo=apachemaven&logoColor=white)
![Platform](https://img.shields.io/badge/Platform-Cross--Platform-lightgrey)

지정한 Webhook 경로로 들어온 요청에 사용자가 작성한 HTML을 `200 OK`로 응답합니다.

Burp Proxy를 가로채지 않습니다. Extension 전용 HTTP 서버가 응답하므로 Burp UI가 멈추지 않습니다.

> **English documentation:** [README.md](README.md)

> **Burp Collaborator가 아닙니다.** 이 프로젝트는 PortSwigger **Burp Collaborator**와 무관하며, Collaborator 인프라를 사용하지 않습니다. Public Webhook이 ON이면 트래픽은 터널(또는 LAN)을 통해 **사용자 PC의 Extension 서버**로 전달됩니다.

## 중요: 포트를 혼동하지 마세요

| 대상 | 포트 | 용도 |
|------|------|------|
| **Extension Webhook 서버** | 탭의 **Extension 수신 포트** | 브라우저 / 터널 대상 |
| Burp Proxy Listener | 보통 `8080` | 프록시 전용 — Webhook URL 아님 |

## Public Webhook ON/OFF + 공개 URL 자동 생성

기본값은 **OFF**입니다. **Public Webhook**을 켜면:

1. 매칭 요청에 HTML 응답 (`enabled = true`)
2. 바로 쓸 수 있는 공개 base URL 자동 생성:
   1. **cloudflared** Quick Tunnel → `https://….trycloudflare.com` (임의 도메인)
      - PATH의 `cloudflared`, 또는 `~/.webhook-page/bin/` 캐시
      - 없으면 Cloudflare 공식 바이너리를 1회 다운로드 (Apache-2.0)
   2. 없으면 **ngrok** — **사용자가 직접 실행 중일 때만** `127.0.0.1:4040` API 사용
   3. 없으면 **LAN IPv4** + 포트 (로컬망)
3. 배포 URL = 공개 base + Required token 경로

**OFF**면 매칭 요청은 **503**, Extension이 띄운 cloudflared는 종료됩니다.

### 패키징 / 재배포

| 도구 | JAR/릴리스에 바이너리 포함? | 이유 |
|------|------------------------------|------|
| **cloudflared** | JAR 안에 넣지 않고 **자동 다운로드·캐시** | 공식 바이너리 [Apache-2.0](https://github.com/cloudflare/cloudflared) — [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) |
| **ngrok** | **포함/재배포 안 함** | 독점 소프트웨어 — 사용자가 직접 설치·실행 |

생성된 URL을 아는 사람은 Public Webhook ON 동안 웹훅 경로에 접근할 수 있습니다. URL은 비밀처럼 다루고, 사용 후 OFF 하세요.

## 기능

- **Public Webhook** ON/OFF (기본 OFF)
- 공개 URL 자동 생성 (cloudflared → 선택적 ngrok → LAN)
- Required token 경로 강제
- 커스텀 HTML + 요청 로그

## 빌드 / 설치

```bash
mvn clean package
```

산출물: `target/webhook-page-extension-1.0.2.jar`  
릴리스: https://github.com/KaiHT-Ladiant/BurpCustomHook/releases

1. Burp → Extensions → Add → Java → JAR 선택  
2. **Webhook Page** 탭 → 설정 **Apply** → **Public Webhook** ON → **Copy URL**

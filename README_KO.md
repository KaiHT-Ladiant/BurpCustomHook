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

**Public Webhook**이 ON이면, **사용자가 쓰는 터널**(cloudflared Quick Tunnel, 또는 이미 실행 중인 ngrok)이 HTTPS를 **본인 PC의 Extension 수신 포트**로 전달합니다. 호스트명(예: `*.trycloudflare.com`)은 해당 터널 제공자 쪽이며 Collaborator가 아닙니다.

## 포트: Extension 수신 포트 ≠ Proxy

| 구분 | 확인 위치 | Webhook으로 쓰는가 |
|------|-----------|-------------------|
| **Extension 수신 포트** | **Webhook Page** 탭 → *Local listen (127.0.0.1)* | **예** — 브라우저·cloudflared·ngrok 대상 |
| Burp **Proxy** 리스너 | Proxy → Options (흔히 `8080`) | **아니오** — 프록시 전용 |

`http://127.0.0.1:8080/...` 을 Webhook URL로 열지 마세요. Proxy로 들어가며 보통 아래 오류가 납니다.

```text
Invalid client request received: First line of request did not contain an absolute URL
- try enabling invisible proxy support.
```

로컬 확인 예 (탭에 표시된 포트 사용):

```text
http://127.0.0.1:<Extension-수신-포트>/kai_ht/webhook
```

## Public Webhook ON/OFF

기본값은 **OFF**입니다. OFF면 매칭 경로는 **503**입니다.

**ON**이면:

1. 매칭 요청에 HTML 응답
2. 공개 base 자동 탐색 순서:
   1. **cloudflared** Quick Tunnel → `*.trycloudflare.com` 임의 호스트 (**Tunnel domain**에 표시)
      - PATH의 `cloudflared` 또는 `~/.webhook-page/bin/` 캐시
      - 없으면 Cloudflare 공식 바이너리 1회 다운로드 ([Apache-2.0](https://github.com/cloudflare/cloudflared))
   2. 없으면 **ngrok** — **사용자가 이미 실행 중일 때만** `127.0.0.1:4040` API
   3. 없으면 **LAN IPv4** + Extension 포트 (로컬망만 — **터널 도메인 아님**)
3. UI:
   - **Tunnel domain** — 호스트명만 (터널 없으면 “LAN only”)
   - **Full Webhook URL** — base + Required token 경로

**OFF**면 경로 **503**, Extension이 띄운 cloudflared는 종료됩니다.

### 패키징

| 도구 | 재배포 | 비고 |
|------|--------|------|
| **cloudflared** | JAR 밖 자동 다운로드·캐시 | Apache-2.0 — [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) |
| **ngrok** | **포함/재배포 안 함** | 독점 — 직접 설치·실행 |

Full Webhook URL을 아는 사람은 ON 동안 경로에 접근할 수 있습니다. 사용 후 OFF 하세요.

## 기능

- **Public Webhook** ON/OFF (기본 OFF)
- **Tunnel domain** / **Full Webhook URL** 분리 표시
- 공개 base 자동 (cloudflared → 선택적 ngrok → LAN)
- Required token 경로 강제
- 커스텀 HTML + 요청 로그

## 빌드 / 설치

```bash
mvn clean package
```

산출물: `target/webhook-page-extension-1.0.3.jar`  
릴리스: https://github.com/KaiHT-Ladiant/BurpCustomHook/releases

1. Burp → Extensions → Add → Java → JAR 선택  
2. **Webhook Page** → 설정 **Apply**  
3. **Local listen** 포트 확인 (**Proxy 8080 아님**)  
4. **Public Webhook** ON → **Tunnel domain**에 호스트가 뜨면 **Copy URL** / **Copy domain**  
5. 도메인이 “(none — LAN only…)”이면 Extender 로그의 cloudflared 오류를 확인한 뒤 **Refresh URL**

# Webhook Page — Burp Suite Extension (한국어)

![Burp Suite](https://img.shields.io/badge/Burp_Suite-Montoya_API-FF6633?logo=burpsuite&logoColor=white)
![Java](https://img.shields.io/badge/Java-17+-F89820?logo=openjdk&logoColor=white)
![Maven](https://img.shields.io/badge/Maven-3.8+-C71A36?logo=apachemaven&logoColor=white)
![Platform](https://img.shields.io/badge/Platform-Cross--Platform-lightgrey)

지정한 Webhook 경로로 들어온 요청에 사용자가 작성한 HTML을 `200 OK`로 응답합니다.

Burp Proxy를 가로채지 않습니다. Extension 전용 HTTP 서버가 응답하므로 Burp UI가 멈추지 않습니다.

> **English documentation:** [README.md](README.md)

## 중요: 포트를 혼동하지 마세요

| 대상 | 포트 | 용도 |
|------|------|------|
| **Extension Webhook 서버** | 탭에 표시되는 **Extension 수신 포트** | 브라우저 / 터널 연결 대상 |
| Burp Proxy Listener | 보통 `8080` | 프록시 전용 — Webhook URL로 사용하지 말 것 |

`http://127.0.0.1:8080/kai_ht/webhook` 처럼 Burp Proxy 포트로 접속하면 아래 오류가 납니다.

```text
Invalid client request received: First line of request did not contain an absolute URL
- try enabling invisible proxy support.
```

로컬 수신 예 (포트 값은 로드 시마다 다를 수 있음):

```text
http://127.0.0.1:51234
```

## Public Webhook ON/OFF + 공개 URL 자동 생성

Public Webhook 기본값은 **OFF**(안전)입니다. **Public Webhook** 체크박스를 켜면:

1. `config.enabled = true` — 경로가 일치하면 HTML 응답
2. Extension이 **바로 쓸 수 있는** 공개 base URL을 **자동 생성**합니다 (우선순위):
   1. **cloudflared** Quick Tunnel (`cloudflared tunnel --url http://127.0.0.1:<port>`) — PATH에 있으면 → `https://….trycloudflare.com`
   2. 없으면 **ngrok** 로컬 API `http://127.0.0.1:4040/api/tunnels` → 첫 `https`(또는 `http`) `public_url`
   3. 둘 다 없으면 **LAN IPv4** + Extension 포트 → `http://<lan-ip>:<port>`
3. 배포용 Webhook URL = 공개 base + Required token 경로 (예: `/kai_ht/webhook`)

Public Webhook가 **OFF**이면:

- 경로가 맞아도 **503** (“Webhook disabled”)
- Extension이 띄운 cloudflared 프로세스는 종료
- URL 필드는 `(Webhook OFF)` 표시

외부 공개 주소를 **직접 입력하는 필드는 없습니다** — 항상 자동 생성됩니다.

인터넷 공개가 필요할 때:

```bash
# 권장: cloudflared 설치 후 PATH에 두면 Extension이 자동 탐지
# 또는 Extension 수신 포트로 ngrok을 직접 실행:
ngrok http 51234
```

## 기능

- **Webhook Page** 스위트 탭
- **Public Webhook** ON/OFF 토글 (기본 OFF)
- 공개 URL 자동 생성 (cloudflared → ngrok → LAN)
- 배포용 Webhook URL 표시 / 복사 (공개 base + Required token 경로)
- **Required token**을 URL 경로에 강제 (기본: `kai_ht`)
- 커스텀 HTML 응답
- 요청 로그 테이블

## 요구 사항

- Burp Suite (Montoya API 지원)
- JDK 17+
- Maven 3.8+
- 선택: 인터넷 공개용 [cloudflared](https://developers.cloudflare.com/cloudflare-one/connections/connect-apps/install-and-setup/installation/) 또는 [ngrok](https://ngrok.com/)

## 빌드

```bash
mvn clean package
```

생성물: `target/webhook-page-extension-1.0.1.jar`

## 설치

1. Burp → **Extensions** → **Installed** → **Add**
2. Extension type: **Java**
3. 빌드한 JAR 선택
4. **Webhook Page** 탭에서 Extension 수신 포트 확인

## 사용 방법

1. **Required token**, **Webhook path**, **Response HTML**을 설정한 뒤 **Apply**
2. **Public Webhook**를 체크해 가용성을 ON으로 두고 공개 URL을 자동 생성
3. URL 필드가 `Generating...`에서 실제 주소로 바뀌면 **Copy URL**
4. **Public Webhook** 체크 해제로 비활성(503) 및 Extension이 띄운 터널 정리

## 구현 참고

Extension은 루프백 `ServerSocket` 기반 HTTP 서버를 사용합니다. Burp Proxy destination을 바꾸지 않아, 프록시 루프로 인한 UI 멈춤을 피합니다.

# AlphaAdopter

> 관심 종목·키워드에 대한 뉴스를 실시간으로 감지해 가장 먼저 알려주는 실시간 정보 알림 플랫폼

![Kotlin](https://img.shields.io/badge/Kotlin-2.3-7F52FF?logo=kotlin&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1-6DB33F?logo=springboot&logoColor=white)
![React](https://img.shields.io/badge/React-18-61DAFB?logo=react&logoColor=white)
![TypeScript](https://img.shields.io/badge/TypeScript-5-3178C6?logo=typescript&logoColor=white)

> 이 프로젝트를 처음부터 끝까지 어떻게 진행했는지(실제로 겪은 문제와 해결 과정 포함)는
> [`docs/project-story.md`](docs/project-story.md)에 정리돼 있습니다.

## 왜 만드는가

정보가 넘치는 시대일수록, "내가 원하는 정보를 얼마나 빠르게 받아보는가"가 중요해지고 있습니다. AlphaAdopter는 사용자가 등록한 키워드/관심 종목과 관련된 뉴스가 발생하면, 노이즈를 걸러내고 실시간으로 알림을 전달하는 서비스입니다.

## 핵심 기능

- 키워드/관심 종목 구독 등록·조회·삭제
- 뉴스 소스 실시간 수집 (NAVER API HUB)
- 구독 키워드 기반 매칭 + Claude(Haiku)를 이용한 AI 관련도 판단으로 노이즈 필터링 ([실측 결과](docs/phase6-ai-relevance-filtering.md): 실제 수집 데이터 기준 노이즈 60% 감소)
- 관련 뉴스 발생 시 실시간 알림 전달 (SSE + Redis Pub/Sub)
- 비회원은 일일 다이제스트 이메일, 회원은 실시간 SSE로 차등 전달 (가입 시 기본 회원 처리라 현재는 사실상 전원 실시간 수신, 비회원 등급은 결제 연동 시 재도입 예정)
- 알림 읽음/클릭 참여도 추적 (향후 개인화 필터링을 위한 데이터 수집 단계, [future-ideas](docs/future-ideas.md) 참고)
- 이메일+비밀번호 인증 (Spring Security + JWT, BCrypt 해시). SSE는 커스텀 헤더를 못 보내 토큰을 쿼리 파라미터로도 허용
- React + TypeScript 프론트엔드 (다크 테마, 사이드바/본문 레이아웃): 회원가입/로그인, 구독 관리, 실시간 알림 피드, 알림 히스토리
- 관리자 화면: `app.admin.emails` 화이트리스트에 등록된 이메일로 로그인하면 전체 사용자/구독/키워드 통계, 최근 7일 알림 추이, 최근 알림 목록을 볼 수 있음 (로그인은 JWT로 하지만 "관리자 역할" 자체는 별도 Role 테이블 없이 배포 환경변수로만 판단)

투자 조언·매매 시그널 등 자본시장법상 유사투자자문업으로 해석될 수 있는 기능은 스코프에서 명시적으로 제외합니다. 뉴스 원문 전체를 저장·재배포하지 않고 제목·요약·링크 위주로 다뤄 저작권 이슈를 피합니다. `ANTHROPIC_API_KEY`가 없는 환경(예: 기여자 로컬)에서는 AI 필터가 자동으로 비활성화되고 기존 키워드 문자열 매칭 결과를 그대로 신뢰합니다.

## 아키텍처

```
[뉴스 소스 (NAVER API HUB)]
        │
        ▼
  [수집기] ──▶ Kafka(news.raw) ──▶ [MongoDB: 원본 저장]
                                          │
                                          ▼
                              [매칭 엔진: Spring Boot + JPA]
                              (구독 키워드 ↔ 뉴스, 문자열 매칭 + Claude 관련도 판단)
                                          │
                                          ▼
                              Kafka(news.matched)
                                          │
                                          ▼
                         [알림 서비스: SSE + Redis Pub/Sub]
                                          │
                                          ▼
                              [React 클라이언트]
```

## 기술 스택

| 영역 | 선택 | 비고 |
|---|---|---|
| 언어/프레임워크(백엔드) | Kotlin + Spring Boot | |
| ORM | JPA/Hibernate | |
| 프론트엔드 | React 18 + TypeScript + Vite | 빌드 산출물을 `core-service`의 정적 리소스로 직접 서빙 |
| 이벤트 스트리밍 | Kafka (EKS 위 Strimzi Operator) | |
| 원본 저장 | MongoDB | 소스별 포맷이 달라 스키마 유연성 필요 |
| 정본 저장 | PostgreSQL (AWS RDS) | HikariCP 풀 튜닝, 핫 컬럼 인덱싱 |
| 캐시 | Redis (AWS ElastiCache) | SSE Pub/Sub 브로커로도 사용 |
| 배포 | AWS EKS (Helm, HPA) | Docker 멀티스테이지 빌드(프론트엔드+백엔드) |
| IaC | Terraform | |
| 관측성 | Prometheus, Grafana (Micrometer) | 분산 트레이싱(OpenTelemetry)은 현재 범위 밖 — 향후 과제 |
| 부하테스트 | k6 | |
| 인증 | Spring Security + JWT | 세션/쿠키 없는 stateless API, 관리자 역할은 별도 Role 테이블 없이 이메일 화이트리스트로 판단 |
| AI | Claude Haiku (Anthropic API) | 키워드 문자열 매칭 위에 얹는 2차 관련도 필터. API 키 없으면 자동 비활성화 |
| 테스트 | Testcontainers(Kafka/Postgres/Mongo/Redis/Mailpit), vitest | 로컬 인프라 없이 CI에서 실제 컨테이너로 통합테스트 |

## 프로젝트 구조

```
alpha-adopter/
├── docker-compose.yml          # 로컬 개발용 PostgreSQL·MongoDB·Kafka·Redis·Mailpit
├── docs/                       # 검증 결과·설계 기록
│   ├── project-story.md         # 프로젝트 전체 진행 과정 총정리 (이력서/면접용)
│   ├── phase0-news-source-validation.md
│   ├── phase5-load-test-observability.md
│   ├── phase6-ai-relevance-filtering.md
│   └── future-ideas.md
├── infra/
│   ├── terraform/               # AWS 인프라(VPC/RDS/ElastiCache/EKS/CI·CD용 IAM) IaC
│   └── k8s/                     # core-service/Kafka(Strimzi)/MongoDB/모니터링 매니페스트
├── loadtest/                    # k6 부하테스트 시나리오
├── frontend/                    # React + TypeScript + Vite 프론트엔드
│   └── src/
│       ├── api/                 # 타입 안전 API 클라이언트 (백엔드 DTO와 1:1 대응)
│       ├── auth/                # 세션 상태 관리 (AuthContext)
│       ├── components/          # Sidebar, Logo 등 공용 컴포넌트
│       ├── hooks/                # useNotificationStream (SSE 훅)
│       ├── icons/                # 스트로크 기반 SVG 아이콘 세트
│       ├── pages/                # AuthPage, UserDashboard, AdminDashboard
│       └── styles/               # 다크 테마 전역 스타일
└── core-service/                # 핵심 백엔드 서비스 (Kotlin + Spring Boot)
    └── src/main
        ├── kotlin/com/alphaadopter/core/
        │   ├── domain/          # User, Subscription, NewsArticle, Notification
        │   ├── collector/       # NAVER 뉴스 수집 (NaverNewsClient, 스케줄러)
        │   ├── pipeline/        # Kafka 컨슈머, MongoDB 원본 저장, 매칭 엔진, 구독 캐시 기반 매칭
        │   ├── ai/              # Claude 기반 관련도 판단 (2차 노이즈 필터)
        │   ├── notification/    # 실시간 알림 전달(SSE + Redis Pub/Sub), 일일 다이제스트 이메일, 읽음/클릭 참여도 추적, 알림 히스토리 조회
        │   ├── subscription/    # 구독 등록/조회/삭제 REST API, 메모리 구독 캐시 (SubscriptionCache)
        │   ├── auth/            # 회원가입/로그인(JWT 발급), Spring Security 설정, JWT 인증 필터
        │   ├── user/            # 관리자 이메일 화이트리스트 판별
        │   ├── admin/           # 관리자 전용 통계/데이터 API (사용자·키워드·일별 추이·최근 알림, 화이트리스트 기반 접근 제어)
        │   └── config/          # Kafka 토픽 등 설정
        └── resources/static/    # 빌드 산출물 전용 디렉터리 (git에는 커밋되지 않음, frontend/의 `npm run build`가 채움)
```

## 로컬 개발 환경

```bash
# 1. 로컬 PostgreSQL·MongoDB·Kafka·Redis·Mailpit 실행
docker compose up -d

# 2. NAVER API HUB 인증정보 등록 (Client ID/Secret은 콘솔에서 발급)
export NAVER_CLIENT_ID=발급받은_CLIENT_ID
export NAVER_CLIENT_SECRET=발급받은_CLIENT_SECRET

# (선택) AI 관련도 필터를 쓰려면 Anthropic API 키 등록 — 없으면 자동 비활성화되고
# 문자열 매칭 결과를 그대로 신뢰하므로 기여자 전원이 가질 필요는 없음
export ANTHROPIC_API_KEY=발급받은_API_KEY

# 3. 프론트엔드 빌드 (core-service/src/main/resources/static/으로 직접 출력됨)
cd frontend
npm install
npm run build      # 또는 npm run dev로 Vite 개발 서버(핫 리로드, /api·/actuator는 :8090으로 프록시)

# 4. core-service 실행
cd ../core-service
./gradlew bootRun
```

포트는 이 개발 환경에 이미 떠 있는 다른 프로젝트들과 겹치지 않도록 기본값 대신 아래 포트를 사용합니다.

| 서비스 | 기본 포트 대신 | 환경변수 |
|---|---|---|
| PostgreSQL | 5434 | `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD` |
| MongoDB | 27018 | `MONGODB_URI` |
| Kafka | 9095 | `KAFKA_BOOTSTRAP_SERVERS` |
| Redis | 6380 | `REDIS_HOST`, `REDIS_PORT` |
| Mailpit (SMTP/웹 UI) | 1025 / 8025 | `MAIL_HOST`, `MAIL_PORT` (다이제스트 이메일은 http://localhost:8025 에서 확인) |
| core-service | 8090 | `SERVER_PORT` |

## 브랜치 전략

`main`은 항상 정상 동작하는 상태를 유지하고, 기능 단위로 `feature/*` 브랜치를 파서 PR로 병합합니다(GitHub Flow). `main`은 브랜치 보호가 걸려 있어 PR 없이 직접 push할 수 없습니다.

## 로드맵

- [x] 0단계 — 뉴스 데이터 소스 검증, MVP 스코프 확정 ([검증 결과](docs/phase0-news-source-validation.md))
- [x] 1단계 — 핵심 도메인 설계 (Spring Boot + JPA, 로컬 PostgreSQL) — User/Subscription/NewsArticle/Notification 엔티티 설계 완료
- [x] 2단계 — 뉴스 수집 파이프라인 (Kafka + MongoDB + 매칭 엔진) — 스케줄러 수집 → news.raw → MongoDB 원본 저장 → 구독 매칭 → news.matched 발행까지 구현
- [x] 3단계 — 실시간 알림 전달 (SSE + Redis) — news.matched 컨슈머 → Redis Pub/Sub → 인스턴스별 SSE 커넥션으로 전달, 하트비트로 유휴 커넥션 방지
- [x] 4단계 — AWS 인프라 전환 (RDS, ElastiCache, EKS, Terraform) — VPC/RDS/ElastiCache/EKS Terraform으로 구성, core-service+Kafka(Strimzi)+MongoDB를 EKS에 배포, HPA 적용, GitHub Actions(OIDC) CI/CD로 자동 배포
- [x] 5단계 — 실측 부하테스트 및 관측성 구축 — Prometheus+Grafana(kube-prometheus-stack)로 관측성 구축, k6로 구독 API 부하테스트 실시 ([결과](docs/phase5-load-test-observability.md))
- [x] 6단계 — 인증 + AI 기반 관련도 판단 — 이메일+비밀번호 JWT 인증 도입, Claude Haiku로 문자열 매칭 위 2차 노이즈 필터링, 실제 수집 데이터로 노이즈 60% 감소 실측 ([결과](docs/phase6-ai-relevance-filtering.md))
- [x] 7단계 — 프론트엔드 React 전환 + 백엔드 성능 개선 — 바닐라 JS 프론트엔드를 다크 테마 재설계 거쳐 React 18+TypeScript+Vite로 전면 재작성, Docker 멀티스테이지 빌드로 CI/CD 통합, SSE 인증/플러시 버그 2건 수정. 다른 실무 프로젝트와 비교 조사해 백엔드 N+1 쿼리 2건·트랜잭션 스코프·누락 인덱스 6개·커넥션 풀 미설정 수정

전체 진행 과정과 각 단계에서 실제로 겪은 문제·해결 방법은 [`docs/project-story.md`](docs/project-story.md)에 상세히 정리돼 있습니다.


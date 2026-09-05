# AlphaAdopter

> 관심 종목·키워드에 대한 뉴스를 실시간으로 감지해 가장 먼저 알려주는 실시간 정보 알림 플랫폼

![Kotlin](https://img.shields.io/badge/Kotlin-2.3-7F52FF?logo=kotlin&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1-6DB33F?logo=springboot&logoColor=white)

백엔드 API 서버(REST + SSE)만 구현된 상태이며, 브라우저에서 쓰는 프론트엔드는 별도 범위입니다.

## 왜 만드는가

정보가 넘치는 시대일수록, "내가 원하는 정보를 얼마나 빠르게 받아보는가"가 중요해지고 있습니다. AlphaAdopter는 사용자가 등록한 키워드/관심 종목과 관련된 뉴스가 발생하면, 노이즈를 걸러내고 실시간으로 알림을 전달하는 서비스입니다.

## 핵심 기능 (MVP)

- 키워드/관심 종목 구독 등록·조회·삭제
- 뉴스 소스 실시간 수집 (NAVER API HUB)
- 구독 키워드 기반 매칭 및 알림 발행
- 관련 뉴스 발생 시 실시간 알림 전달 (SSE + Redis Pub/Sub)
- 비회원은 일일 다이제스트 이메일, 회원은 실시간 SSE로 차등 전달
- 알림 읽음/클릭 참여도 추적 (향후 개인화 필터링을 위한 데이터 수집 단계, [future-ideas](docs/future-ideas.md) 참고)

투자 조언·매매 시그널 등 자본시장법상 유사투자자문업으로 해석될 수 있는 기능은 스코프에서 명시적으로 제외합니다. 뉴스 원문 전체를 저장·재배포하지 않고 제목·요약·링크 위주로 다뤄 저작권 이슈를 피합니다. 현재 매칭은 키워드 문자열 포함 여부 기반이며, AI 기반 관련도 판단·노이즈 필터링은 아직 구현 전입니다.

## 아키텍처

```
[뉴스 소스 (NAVER API HUB)]
        │
        ▼
  [수집기] ──▶ Kafka(news.raw) ──▶ [MongoDB: 원본 저장]
                                          │
                                          ▼
                              [매칭 엔진: Spring Boot + JPA]
                              (구독 키워드 ↔ 뉴스, 문자열 매칭)
                                          │
                                          ▼
                              Kafka(news.matched)
                                          │
                                          ▼
                         [알림 서비스: SSE + Redis Pub/Sub]
                                          │
                                          ▼
                                    [클라이언트]
```

## 기술 스택

| 영역 | 선택 | 비고 |
|---|---|---|
| 언어/프레임워크 | Kotlin + Spring Boot | |
| ORM | JPA/Hibernate | |
| 이벤트 스트리밍 | Kafka (EKS 위 Strimzi Operator) | |
| 원본 저장 | MongoDB | 소스별 포맷이 달라 스키마 유연성 필요 |
| 정본 저장 | PostgreSQL (AWS RDS) | |
| 캐시 | Redis (AWS ElastiCache) | |
| 배포 | AWS EKS (Helm, HPA) | |
| IaC | Terraform | |
| 관측성 | Prometheus, Grafana (Micrometer) | 분산 트레이싱(OpenTelemetry)은 현재 범위 밖 — 향후 과제 |
| 부하테스트 | k6 | |

## 프로젝트 구조

```
alpha-adopter/
├── docker-compose.yml          # 로컬 개발용 PostgreSQL·MongoDB·Kafka
├── docs/                       # 검증 결과·설계 기록
│   ├── phase0-news-source-validation.md
│   ├── phase5-load-test-observability.md
│   └── future-ideas.md
├── infra/
│   ├── terraform/               # AWS 인프라(VPC/RDS/ElastiCache/EKS/CI·CD용 IAM) IaC
│   └── k8s/                     # core-service/Kafka(Strimzi)/MongoDB/모니터링 매니페스트
├── loadtest/                    # k6 부하테스트 시나리오
└── core-service/                # 핵심 백엔드 서비스 (Kotlin + Spring Boot)
    └── src/main/kotlin/com/alphaadopter/core/
        ├── domain/              # User, Subscription, NewsArticle, Notification
        ├── collector/           # NAVER 뉴스 수집 (NaverNewsClient, 스케줄러)
        ├── pipeline/            # Kafka 컨슈머, MongoDB 원본 저장, 매칭 엔진
        ├── notification/        # 실시간 알림 전달(SSE + Redis Pub/Sub), 일일 다이제스트 이메일, 읽음/클릭 참여도 추적
        ├── subscription/        # 구독 등록/조회/삭제 REST API (인증 도입 전까지 email로 사용자 식별, NAVER API 할당량 보호를 위한 신규 키워드 총량 제한)
        ├── user/                # 회원 전환 REST API (결제/인증 없이 email 기반)
        └── config/              # Kafka 토픽 등 설정
```

## 로컬 개발 환경

```bash
# 1. 로컬 PostgreSQL·MongoDB·Kafka 실행
docker compose up -d

# 2. NAVER API HUB 인증정보 등록 (Client ID/Secret은 콘솔에서 발급)
export NAVER_CLIENT_ID=발급받은_CLIENT_ID
export NAVER_CLIENT_SECRET=발급받은_CLIENT_SECRET

# 3. core-service 실행
cd core-service
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

## 참고

컨셉 검증은 이미 완료된 상태입니다. 이전에 Python 기반 프로토타입(`stock-alert`)으로 "뉴스 감지 → AI 중요도 스코어링 → 알림 전달" 흐름 자체는 동작을 확인했고, 이번 프로젝트는 그 컨셉을 Kotlin·Spring·Kafka·AWS 기반으로 서비스 규모에 맞게 재설계·구현하는 것이 목표입니다.

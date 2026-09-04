# AlphaAdopter

> 관심 종목·키워드에 대한 뉴스를 실시간으로 감지해, AI로 중요도를 판단하고 가장 먼저 알려주는 실시간 정보 알림 플랫폼

🚧 **개발 진행 중 (MVP 단계)**

## 왜 만드는가

정보가 넘치는 시대일수록, "내가 원하는 정보를 얼마나 빠르게 받아보는가"가 중요해지고 있습니다. AlphaAdopter는 사용자가 등록한 키워드/관심 종목과 관련된 뉴스가 발생하면, AI로 관련도를 판단해 노이즈를 걸러내고 실시간으로 알림을 전달하는 서비스입니다.

이 프로젝트는 단순한 사이드 프로젝트가 아니라, 지금까지의 커리어(Node.js/NestJS 기반 백엔드)에서 다루지 못했던 기술 스택을 실제로 서비스 규모로 증명하기 위한 목적을 가지고 시작했습니다. 특히 대용량 트래픽 처리 경험을 "산정치"가 아니라 실제 배포된 인프라 위에서의 **실측치**로 확보하는 것을 핵심 목표로 삼고 있습니다.

## 핵심 기능 (MVP)

- 키워드/관심 종목 구독 등록
- 뉴스 소스 실시간 수집 (RSS/공식 API)
- AI 기반 관련도 판단 및 노이즈 필터링
- 관련 뉴스 발생 시 실시간 알림 전달 (SSE/WebSocket)

투자 조언·매매 시그널 등 자본시장법상 유사투자자문업으로 해석될 수 있는 기능은 스코프에서 명시적으로 제외합니다. 뉴스 원문 전체를 저장·재배포하지 않고 제목·요약·링크 위주로 다뤄 저작권 이슈를 피합니다.

## 아키텍처

```
[뉴스 소스 (RSS/API)]
        │
        ▼
  [수집기] ──▶ Kafka(news.raw) ──▶ [MongoDB: 원본 저장]
                                          │
                                          ▼
                              [매칭 엔진: Spring Boot + JPA]
                              (구독 키워드 ↔ 뉴스, AI 관련도 판단)
                                          │
                                          ▼
                              Kafka(news.matched)
                                          │
                                          ▼
                         [알림 서비스: SSE/WebSocket + Redis]
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
| 관측성 | Prometheus, Grafana, OpenTelemetry | |
| 부하테스트 | k6 | |

## 로컬 개발 환경

```bash
# 1. 로컬 PostgreSQL 실행
docker compose up -d

# 2. core-service 실행
cd core-service
./gradlew bootRun
```

## 로드맵

- [x] 0단계 — 뉴스 데이터 소스 검증, MVP 스코프 확정 ([검증 결과](docs/phase0-news-source-validation.md))
- [x] 1단계 — 핵심 도메인 설계 (Spring Boot + JPA, 로컬 PostgreSQL) — User/Subscription/NewsArticle/Notification 엔티티 설계 완료
- [ ] 2단계 — 뉴스 수집 파이프라인 (Kafka + MongoDB + 매칭 엔진)
- [ ] 3단계 — 실시간 알림 전달 (SSE/WebSocket + Redis)
- [ ] 4단계 — AWS 인프라 전환 (RDS, ElastiCache, EKS, Terraform)
- [ ] 5단계 — 실측 부하테스트 및 관측성 구축

## 참고

컨셉 검증은 이미 완료된 상태입니다. 이전에 Python 기반 프로토타입(`stock-alert`)으로 "뉴스 감지 → AI 중요도 스코어링 → 알림 전달" 흐름 자체는 동작을 확인했고, 이번 프로젝트는 그 컨셉을 Java/Kotlin·Spring·Kafka·AWS 기반으로 서비스 규모에 맞게 재설계·구현하는 것이 목표입니다.

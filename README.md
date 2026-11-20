# AlphaAdopter

> 관심 종목·키워드의 뉴스를 실시간으로 수집하고, AI로 노이즈를 걸러 필요한 소식만 알려주는 알림 서비스

[![Test core-service](https://github.com/zeroway3/alpha-adopter/actions/workflows/test-core-service.yml/badge.svg)](https://github.com/zeroway3/alpha-adopter/actions/workflows/test-core-service.yml)
[![Test frontend](https://github.com/zeroway3/alpha-adopter/actions/workflows/test-frontend.yml/badge.svg)](https://github.com/zeroway3/alpha-adopter/actions/workflows/test-frontend.yml)
[![CodeQL](https://github.com/zeroway3/alpha-adopter/actions/workflows/codeql.yml/badge.svg)](https://github.com/zeroway3/alpha-adopter/actions/workflows/codeql.yml)
![Kotlin](https://img.shields.io/badge/Kotlin-2.4-7F52FF?logo=kotlin&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1-6DB33F?logo=springboot&logoColor=white)
![React](https://img.shields.io/badge/React-19-61DAFB?logo=react&logoColor=white)
![TypeScript](https://img.shields.io/badge/TypeScript-7-3178C6?logo=typescript&logoColor=white)

설계 결정의 이유와 운영하며 찾고 고친 문제들은 [`docs/project-story.md`](docs/project-story.md)에 정리했습니다.

## 왜 만들었나

키워드 문자열 매칭만으로 뉴스 알림을 보내면 노이즈가 많습니다. 실제로 수집한 "카카오" 기사 9건 중
8건이 초콜릿 원료나 카카오TV 드라마 기사였습니다. AlphaAdopter는 빠르게 수집하는 것에 더해,
**받을 가치가 있는 기사만, 빠짐없이, 한 번만** 전달하는 것을 목표로 합니다.

## 주요 기능

**수집과 정확도**
- NAVER 뉴스 검색 API로 구독 키워드를 최신순 수집. 키워드별 수집 지점까지 페이지를 따라가 기사가 몰려도
  빠지지 않게 하고, 누락 의심·호출 실패·발행 실패를 기록
- Claude Haiku가 기사와 키워드의 관련도를 점수로 판정하는 2차 필터
  (실제 알림 후보 30건 중 18건 제거, [결과](docs/phase6-ai-relevance-filtering.md))
- Voyage AI 임베딩의 코사인 유사도로 같은 사건의 중복 보도를 하나로 묶음 ([설계](docs/phase8-embedding-dedup.md))
- 직접 라벨링한 평가셋으로 정밀도·재현율을 측정하며 프롬프트 개선

**전달**
- Kafka → Redis Pub/Sub → SSE 실시간 알림 (여러 파드로 확장해도 사용자 연결을 가진 파드가 전달)
- 일일 다이제스트 메일, 알림 읽음·클릭 기반 우선순위 정렬

**운영과 안정성**
- Redis 분산 락으로 여러 파드 중 한 곳에서만 스케줄 작업 실행 (다이제스트 메일 중복 발송 방지)
- 원본 기사 link 유니크 저장으로 재수집·Kafka 재전달에도 한 건만 저장
- AI·임베딩·rate limit은 장애 시 통과(fail-open), 되돌릴 수 없는 메일 발송은 장애 시 보류(fail-closed)
- Prometheus·Grafana 지표, 관리자 화면의 수집 현황·이상 기록

**보안**
- JWT를 httpOnly + SameSite=Strict 쿠키로 전달, refresh token 교체·폐기
- DB 기반 RBAC와 권한 변경 감사 로그, 로그인·가입·구독 API rate limit
- Dependabot·dependency-review·CodeQL로 의존성과 코드 취약점 스캔
  (운영 서버 내장 Tomcat critical CVE 3건 발견·패치)

**AI 에이전트**
- 관리자가 지목한 사용자의 가입·구독·알림 열람 데이터를 모델이 스스로 골라 조회해 이상 사용 여부를
  위험도와 근거로 보고하는 조사 에이전트. 조회만 하고 조치는 사람이 결정하며, 최대 5회 반복 후 판단 불가로 종료

투자 조언·매매 신호처럼 유사투자자문업으로 해석될 수 있는 기능은 제외했고, 뉴스 본문은 저장하지 않고
제목·요약·링크만 다룹니다.

## 아키텍처

```
[NAVER 뉴스 검색 API]
        │  최신순 수집 + 수집 지점 추적 + 분산 락
        ▼
  [수집기] ──▶ Kafka(news.raw) ──▶ [MongoDB: 원본 저장]
                                          │
                                          ▼
                 [매칭: 문자열 매칭 → 임베딩 중복 제거 → Claude 관련도 판단]
                                          │
                                          ▼
                       [PostgreSQL 저장] ──▶ Kafka(news.matched)
                                          │
                                          ▼
                   [알림: Redis Pub/Sub → SSE / 일일 다이제스트 메일]
                                          │
                                          ▼
                                 [React 클라이언트]
```

AWS EKS 위에 core-service·Kafka(Strimzi)·MongoDB를, RDS(PostgreSQL)·ElastiCache(Redis)를 관리형으로 두고
Terraform으로 관리합니다. GitHub Actions가 OIDC로 인증해 테스트 통과 후 배포합니다.

## 실측 결과

| 항목 | 결과 | 조건 |
|---|---|---|
| AI 관련도 필터 | 알림 대상 30건 → 12건 (60% 감소) | 실제 수집된 알림 후보 30건 |
| 관련도 판정 | 정밀도 78% → 100%, 재현율 100% | 라벨링 평가셋 20건(튜닝 데이터와 동일, 확대 예정) |
| API 부하테스트 | 12,278건 요청, 실패율 0%, p95 18ms, HPA 1→3 파드 | k6, t3.small ×4 ([결과](docs/phase5-load-test-observability.md)) |
| 알림 지연 | 수집→알림 평균 9.8초, 게시→알림 평균 약 3.6분 | Micrometer+Prometheus 실측, 2026-11-19 ([상세](docs/project-story.md#12-2026-11-19-aws-재배포--처음부터-끝까지-실측-검증)) |
| 실제 노드 장애 복구 | 메모리 부족으로 EKS 노드 1대 NotReady → EC2 종료 → ASG 자동 교체로 정상화 | 2026-11-19 재배포 중 실제 발생 |

## 기술 스택

| 영역 | 기술 |
|---|---|
| 백엔드 | Kotlin, Spring Boot 4, Spring Security, JPA, Flyway |
| 메시징·저장소 | Kafka (Strimzi), PostgreSQL, MongoDB, Redis |
| AI | Claude Haiku (tool use 구조화 출력), Voyage AI 임베딩 |
| 프론트엔드 | React 19, TypeScript, Vite |
| 인프라 | AWS EKS·RDS·ElastiCache, Terraform, Docker, GitHub Actions (OIDC) |
| 관측·테스트 | Prometheus, Grafana, Micrometer, k6, Testcontainers, vitest |

## 프로젝트 구조

```
alpha-adopter/
├── core-service/               # 백엔드 (Kotlin + Spring Boot)
│   └── src/main/kotlin/com/alphaadopter/core/
│       ├── collector/          # 뉴스 수집 (수집 지점 추적, 재시도, 누락 감지)
│       ├── pipeline/           # Kafka 컨슈머, 원본 저장, 매칭
│       ├── ai/                 # 관련도 판단, 임베딩 중복 제거, agent/ 조사 에이전트
│       ├── notification/       # SSE, Redis Pub/Sub, 일일 다이제스트
│       ├── scheduling/         # 분산 스케줄 락
│       ├── subscription/       # 구독 API, 구독 캐시
│       ├── auth/               # JWT 쿠키 인증, rate limit, 보안 설정
│       ├── admin/              # 관리자 통계·수집 현황·조사 API
│       ├── personalization/    # 참여도 점수
│       └── domain/             # 엔티티와 리포지토리
├── frontend/                   # React + TypeScript + Vite
├── infra/terraform/            # AWS 인프라 (VPC, EKS, RDS, ElastiCache, IAM)
├── infra/k8s/                  # Kubernetes 매니페스트 (core-service, Kafka, MongoDB, 모니터링)
├── loadtest/                   # k6 시나리오
└── docs/                       # 설계·실측 기록
```

## 로컬 실행

```bash
# 1. PostgreSQL·MongoDB·Kafka·Redis·Mailpit 실행
docker compose up -d

# 2. 환경변수 (NAVER는 수집에 필요, AI 키는 선택 — 없으면 해당 기능이 자동으로 꺼짐)
export JWT_SECRET=$(openssl rand -base64 32)   # 필수: 토큰 서명 키, 없으면 기동 실패
export NAVER_CLIENT_ID=...
export NAVER_CLIENT_SECRET=...
export ANTHROPIC_API_KEY=...   # 선택: AI 관련도 필터
export VOYAGE_API_KEY=...      # 선택: 임베딩 중복 제거

# 3. 프론트엔드 빌드 (core-service 정적 리소스로 출력)
cd frontend && npm install && npm run build

# 4. 백엔드 실행
cd ../core-service && ./gradlew bootRun
```

다른 로컬 서비스와 충돌하지 않도록 기본 포트 대신 아래 포트를 씁니다.

| 서비스 | 포트 | 환경변수 |
|---|---|---|
| PostgreSQL | 5434 | `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD` |
| MongoDB | 27018 | `MONGODB_URI` |
| Kafka | 9095 | `KAFKA_BOOTSTRAP_SERVERS` |
| Redis | 6380 | `REDIS_HOST`, `REDIS_PORT` |
| Mailpit (SMTP / 웹) | 1025 / 8025 | `MAIL_HOST`, `MAIL_PORT` |
| core-service | 8090 | `SERVER_PORT` |

테스트는 Testcontainers로 실제 컨테이너를 띄우므로 Docker만 있으면 됩니다.

```bash
cd core-service && ./gradlew test
cd frontend && npm test
```

## 개발 방식

`main`은 브랜치 보호가 걸려 있어 기능별 브랜치에서 PR로만 병합합니다. PR마다 백엔드·프론트엔드 테스트,
의존성 취약점 검사, CodeQL 스캔이 돌고, main에 병합되면 EKS로 배포됩니다.

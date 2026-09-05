# AlphaAdopter — 프로젝트 진행 과정 총정리

> 이 문서는 이력서·포트폴리오·면접에서 이 프로젝트를 설명할 때 참고할 단일 레퍼런스입니다.
> 무엇을 만들었는지뿐 아니라 **왜 그렇게 만들었는지, 어떤 문제를 실제로 겪고 어떻게 고쳤는지**를
> 중심으로 정리했습니다. 날짜·수치·PR 내용은 전부 이 저장소의 실제 커밋/문서에서 가져온
> 것이며 지어낸 내용은 없습니다.

## 1. 한눈에 보기

**AlphaAdopter**는 사용자가 등록한 키워드·관심 종목 관련 뉴스가 발생하면, 노이즈를 걸러내고
실시간으로 알림을 전달하는 개인 프로젝트입니다. 약 33개의 PR을 거쳐 아래 흐름으로 완성됐습니다.

```
뉴스 소스 검증 → 도메인 설계 → 수집 파이프라인(Kafka/Mongo) → 실시간 알림(SSE/Redis)
→ AWS 인프라 전환(EKS/RDS/Terraform) → 관측성/부하테스트(Prometheus/k6)
→ 인증(JWT) → AI 노이즈 필터링(Claude) → 프론트엔드 재설계 → React 전환 → 백엔드 성능 개선
```

**핵심 수치 (전부 실측)**
- AI 2차 필터링으로 뉴스 알림 노이즈 **60% 감소** (실제 수집 데이터 30건 재검증)
- k6 부하테스트: 12,278건 요청, 실패율 0%, p95 응답시간 18ms 이하, HPA가 1→3 파드로 정상 스케일
- GitHub Actions OIDC로 AWS 정적 액세스키 없이 CI/CD 구성
- 백엔드 성능 개선 5건(N+1 쿼리 2건, 트랜잭션 스코프, 인덱스 6개, 커넥션 풀 튜닝)을 다른 실무
  프로젝트와 비교 조사한 뒤 실제로 적용

## 2. 왜 만들었는가

정보가 넘치는 시대일수록 "원하는 정보를 얼마나 빠르게 받아보는가"가 중요해집니다.
이전에 Python 기반 프로토타입(`stock-alert`)으로 "뉴스 감지 → AI 중요도 스코어링 → 알림
전달" 흐름 자체는 동작을 확인했고, 이 프로젝트는 그 컨셉을 **Kotlin·Spring·Kafka·AWS
기반으로 서비스 규모에 맞게 재설계·구현**하는 것이 목표였습니다. 즉 "아이디어 검증"이
아니라 "실제로 트래픽을 받는 시스템을 만들면 무엇이 필요한가"를 배우는 것이 이 프로젝트의
진짜 동기입니다.

## 3. 아키텍처

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

인프라는 EKS(Kubernetes) 위에 core-service·Kafka(Strimzi)·MongoDB를 올리고, RDS(PostgreSQL)·
ElastiCache(Redis)는 관리형 서비스로 분리했습니다. 전부 Terraform으로 관리하고, GitHub
Actions(OIDC)가 테스트 통과 후 자동 배포합니다.

## 4. 기술적 의사결정과 그 이유

의도적으로 선택한 것들이고, 각각 "왜 그걸 골랐는지" 설명할 수 있어야 면접에서 힘을 받습니다.

| 결정 | 이유 |
|---|---|
| Kafka + MongoDB(원본) + PostgreSQL(정규화) 이원화 | 뉴스 소스마다 포맷이 달라 원본은 스키마 유연성이 필요하고, 매칭/조회는 관계형 쿼리가 유리해서 저장 계층을 분리 |
| SSE (WebSocket 아님) | 알림은 서버→클라이언트 단방향 푸시만 필요. 양방향성이 불필요한데 WebSocket을 쓰면 복잡도만 늘어남. SSE는 일반 HTTP라 인프라 전환(EKS+ALB) 시에도 별도 프로토콜 처리가 필요 없음 |
| Kafka 컨슈머 → Redis Pub/Sub → SSE (직접 전달 아님) | `news.matched`를 소비하는 인스턴스와 특정 사용자의 SSE 커넥션을 들고 있는 인스턴스가 다를 수 있음(멀티 파드 환경 전제). Redis를 매개로 분리해두면 4단계(EKS+HPA) 전환 시 구조 변경 없이 그대로 스케일됨 — 실제로 그렇게 됐음 |
| JWT(stateless) 인증, Role 테이블 없이 관리자 이메일 화이트리스트 | 세션/쿠키 없는 순수 API 서버 지향. "관리자인가"는 배포 환경변수(`ADMIN_EMAILS`)로만 판단해 재배포만으로 조정 가능하게 함 — 규모에 비해 적절한 단순화 |
| 문자열 매칭 위에 Claude Haiku 2차 필터 | 키워드 문자열 포함만으로는 "카카오"처럼 일반명사성 키워드에서 노이즈가 심함(실측 89%). 1차 필터로 후보를 줄여둔 뒤 AI로 재판단해 API 호출 비용을 통제 |
| AI 판단 fail-open 설계 | API 키 없음/레이트리밋/네트워크 오류 시 "판단 실패"가 아니라 "통과"로 처리. AI 인프라 장애가 알림 전달 자체를 막는 단일 장애점이 되지 않도록 |
| GitHub Actions OIDC (정적 액세스키 없음) | 장기 액세스 키 유출 리스크 제거. repo/브랜치 범위로 신뢰 관계를 제한(`zeroway3/alpha-adopter`, `main`만) |
| 프론트엔드: 바닐라 HTML/JS → React/TS로 전환 | 초기엔 빌드 도구 없이 빠르게 화면을 붙이는 게 우선이었지만, 규모가 커지면서(사이드바·아이콘·상태 관리) 유지보수 비용이 커져 정식 React+TypeScript+Vite로 재작성 — "왜 처음부터 React로 안 했는가"에 대한 답이기도 함 |

## 5. 단계별 진행 과정

로드맵 기준 0~6단계는 `README.md`에 요약돼 있고, 이 문서에서는 **각 단계에서 실제로 무엇을
구현했는지**를 조금 더 구체적으로 남깁니다.

### 0단계 — 뉴스 데이터 소스 검증
NAVER API HUB(Search News API)로 데이터 수집이 가능함을 확인. 할당량(25,000회/일,
775,000회/월), 인증 헤더, 응답 포맷을 검증 ([`docs/phase0-news-source-validation.md`](phase0-news-source-validation.md)).

### 1단계 — 핵심 도메인 설계
`User`(최소 정보, 인증은 다음 단계로 미룸), `Subscription`(KEYWORD/STOCK_CODE),
`NewsArticle`(link 기준 중복 방지), `Notification`(MATCHED/SENT/FAILED 상태 머신) 설계.

### 2단계 — 뉴스 수집 파이프라인
`NaverNewsClient` → `NewsCollectorScheduler`(주기적 수집) → Kafka(`news.raw`) →
`NewsRawConsumer`(MongoDB 원본 저장 → PostgreSQL 정규화 → 구독 매칭) → Kafka(`news.matched`).
이 단계에서 **파이프라인이 아예 부팅도 안 되던 상태**를 처음으로 실행해보고 발견 — 자세한
내용은 6절 참고.

### 3단계 — 실시간 알림 전달 (SSE + Redis)
`news.matched` 컨슈머가 Redis Pub/Sub으로 릴레이하고, 해당 사용자의 SSE 커넥션을 들고 있는
인스턴스가 실제로 전달. 프록시/유휴 타임아웃 대비 하트비트 핑 포함.

### 4단계 — AWS 인프라 전환
VPC/RDS/ElastiCache/EKS를 Terraform으로 구성하고, core-service+Kafka(Strimzi)+MongoDB를
EKS에 배포. GitHub Actions(OIDC)로 CI/CD 자동화. 이 단계에서 OIDC 인증 실패를 근본 원인까지
추적해 해결 — 6절 참고.

### 5단계 — 관측성 + 실측 부하테스트
`kube-prometheus-stack`(Prometheus+Grafana)을 `monitoring` 네임스페이스에 구성, core-service를
`ServiceMonitor`로 15초 간격 스크랩. k6로 구독 등록/조회 흐름 부하테스트 — 결과는 위 1절 참고,
상세는 [`docs/phase5-load-test-observability.md`](phase5-load-test-observability.md).

### 6단계 — 인증 + AI 관련도 판단
이메일+비밀번호 JWT 인증(Spring Security, BCrypt) 도입, 관리자 통계/유저/키워드 API 추가.
Claude Haiku 기반 2차 노이즈 필터 추가, 노이즈 60% 감소 실측 —
상세는 [`docs/phase6-ai-relevance-filtering.md`](phase6-ai-relevance-filtering.md).

### 7단계 — 프론트엔드 재설계 + React 전환 + 백엔드 성능 개선
KOSMOPROJECT/front(Next.js+Tailwind+Radix) 디자인 언어를 참고해 라이트 테마로 재작성 →
사용자 피드백("Claude 기본 디자인 같다")을 받아 다크 테마 + 사이드바 레이아웃 + 커스텀 SVG
로고 + 아이콘 세트로 전면 재설계 → 최종적으로 바닐라 JS의 유지보수 한계를 넘기 위해
React 18 + TypeScript + Vite로 재작성. 이 과정에서 SSE 관련 실제 버그 2건을 발견해 수정
(9절). 이어서 다른 실무 프로젝트와 비교 조사해 백엔드 성능 개선 5건 적용 — 8절 참고.

## 6. 실제로 겪고 해결한 문제들 (가장 중요한 섹션)

면접에서 "어떤 문제를 겪었고 어떻게 해결했나"라는 질문에 구체적으로 답할 수 있는 근거입니다.
전부 실제로 발생했고, 실제로 고쳤습니다.

### 인프라/배포

**GitHub Actions OIDC 인증 실패 (`AssumeRoleWithWebIdentity`, Not authorized)**
저장소/조직 이름이 과거에 바뀐 이력이 있어, GitHub이 발급하는 JWT의 `sub` 클레임이
`repo:owner/repo:ref:...` 형태가 아니라 `repo:owner@orgId/repo@repoId:ref:...`처럼 이름 재사용
위조를 막기 위한 **불변 숫자 ID 포함 형태**로 나온다는 걸 GitHub이 실제로 발급한 토큰을
디버그 스텝으로 직접 출력해서 확인. IAM 신뢰 정책의 `sub` 조건을 이름이 아니라 ID 기반
패턴으로 수정해 해결 (이후 이름이 또 바뀌어도 안전).

**Kafka 파이프라인이 애초에 한 번도 동작한 적 없었음**
로컬 인프라까지 직접 띄워 실행해보고서야 발견. `spring-kafka` raw 라이브러리만 추가돼
있어 Spring Boot 오토컨피그(KafkaTemplate 빈 생성)가 전혀 동작하지 않았고, Jackson 3
프로젝트인데 Kafka 직렬화 설정은 Jackson 2 기반 클래스를 가리켜 `ClassNotFoundException`이
발생하고 있었음. 의존성과 직렬화 설정을 모두 교체해 해결.

**NAVER API 응답을 실제 키로 처음 검증하며 발견한 버그 2건**
NAVER API HUB가 JSON 본문을 `Content-Type: text/plain`으로 내려줘서 매번 파싱이
거부되고 있었고(메시지 컨버터가 text/plain도 처리하도록 등록), Spring Boot 4.1에서
MongoDB 설정 prefix가 `spring.data.mongodb`→`spring.mongodb`로 바뀐 걸 놓쳐서 조용히
기본값(localhost:27017)으로 접속을 시도하며 파이프라인 전체가 멈춰 있었음.

**CI/CD가 매니페스트 변경분을 실제로 반영하지 않던 문제**
`kubectl set image`로 이미지 태그만 바꾸고 `deployment.yaml` 자체는 `apply`하지 않아서,
Service/ServiceMonitor 변경이 클러스터에 전혀 반영되지 않고 있었음 (관측성 작업 중
ServiceMonitor가 안 만들어져 있는 걸 보고 발견).

**EKS 배포 역할에 Prometheus Operator CRD 권한 누락**
`AmazonEKSEditPolicy`는 `ServiceMonitor` 같은 커스텀 리소스까지는 커버하지 않아
`servicemonitors is forbidden` 에러 발생 → 해당 CRD에 대해서만 권한을 주는 별도
ClusterRole/ClusterRoleBinding 추가.

**Grafana Helm 업그레이드 시 Multi-Attach 에러**
RWO(gp3) PVC를 쓰는 단일 레플리카를 기본 RollingUpdate 전략으로 갱신하면 새 파드가
볼륨을 얻지 못해 멈춤 → `strategy.type: Recreate`로 고정.

### 테스트 인프라

**Testcontainers 도입 중 겪은 문제들**: 2.x부터 모듈 아티팩트명이 `testcontainers-<module>`로
변경된 것, `apache/kafka` 이미지가 `KafkaContainer`의 로그 기반 wait strategy와 안 맞아
`confluentinc/cp-kafka`로 교체한 것, `MongoDBContainer.replicaSetUrl`에 DB 이름을
이어붙이면 깨져서 URI를 직접 구성한 것, 테스트 메서드에 `@Transactional`을 걸면 그 안에서
만든 데이터가 커밋되지 않아 별도 스레드의 Kafka 컨슈머가 아예 못 보는 문제(제거 후 전용
조회 쿼리로 대체), 여러 테스트 클래스가 컨테이너를 공유해야 하는데 `@Testcontainers`의
클래스별 시작/종료 방식이 이를 깨버려 싱글턴 패턴(companion object)으로 전환한 것.

**AI 판단 테스트가 개발자 로컬 환경변수에 따라 결과가 달라지던 문제**
로컬에 `ANTHROPIC_API_KEY`가 export돼 있으면, 기존 파이프라인 통합테스트의 합성 문구를
실제 Claude가 낮은 점수로 평가해 매칭이 걸러지는 걸 발견. "테스트 결과가 개발자 환경변수에
좌우되면 안 된다"는 원칙에 따라 해당 테스트는 `@TestPropertySource`로 AI를 명시적으로
비활성화하도록 고정.

### 애플리케이션 로직

**Spring Security 도입 후 모든 에러 응답이 403으로 가려짐**
컨트롤러의 `ResponseStatusException`이 서블릿 `sendError()`를 거쳐 `/error`로 내부
포워딩되는데, 이 경로가 `permitAll`이 아니면 인증 없는 요청에서는 실제 상태코드(409/401 등)가
전부 403으로 덮여버림. `/error`를 permitAll에 추가해 해결.

**LazyInitializationException**
관리자/히스토리 조회에서 지연 로딩 연관관계(`Subscription.user`, `NewsArticle`)에 세션
밖에서 접근하며 발생 — `@Transactional(readOnly = true)`로 세션을 열어둔 채 DTO 변환까지
끝내도록 수정.

**SSE가 특정 조건에서 영원히 "연결 중"에 멈추는 버그 (React 전환 중 발견, 2건)**
1. `JwtAuthFilter`(Spring Security)가 SSE의 서블릿 Async 재디스패치에서는 기본적으로
   건너뛰도록 설계돼 있는데, `SecurityContext`는 스레드 로컬이라 재디스패치가 다른 워커
   스레드에서 실행되면 인증 정보가 사라져 있었음 — 이 시점에 `anyRequest().authenticated()`가
   재평가되며 `AuthorizationDeniedException`이 터지고, 이미 응답이 커밋된 뒤라 에러도
   못 보내고 커넥션만 조용히 죽었음. `shouldNotFilterAsyncDispatch() = false`로 수정.
2. 위 버그를 고친 뒤에도 여전히 재현되는 경우가 있어 재현 조건을 좁혀보니, **EventSource
   연결과 동시에 인증된 fetch 요청이 같이 나가는 흔한 페이지 진입 패턴**에서 Tomcat이 SSE
   응답의 초기 플러시를 미루고 있었음. 에미터 생성 직후 초기 comment 이벤트를 보내 강제로
   플러시하도록 수정. 재현 스크립트로 수정 전/후 동작 차이를 직접 확인함.

**구독 캐시 도입 후 통합테스트가 실패해 추적한 진짜 레이스 컨디션 (백엔드 성능 개선 중 발견)**
뉴스 1건마다 구독 테이블 전체를 읽던 걸(N+1성 부하) 메모리 캐시로 바꾸자, "구독 생성 →
캐시가 아직 갱신되기 전 → 마침 매칭되는 뉴스가 먼저 소비됨 → 매칭을 영구히 놓침"이라는
레이스가 생겼음. 실제 서비스는 컨트롤러가 저장 직후 캐시를 즉시 갱신해서 문제없지만, 테스트가
컨트롤러를 거치지 않고 리포지토리에 직접 저장해서 이 레이스가 노출됐음을 확인 — 테스트도
동일한 즉시 갱신 패턴으로 맞춰 해결.

## 7. 프론트엔드 여정

1. **1차**: 빌드 도구 없는 순수 HTML/CSS/JS, `core-service`가 정적 리소스로 직접 서빙.
   회원가입/로그인, 구독 관리, SSE 실시간 피드, 관리자 통계 화면.
2. **2차**: KOSMOPROJECT/front(Next.js+Tailwind+Radix)의 실제 UI 컴포넌트 구현체를 참고해
   색상 팔레트·둥근 정도·그림자·상태 표현을 정확히 이식.
3. **3차**: 사용자 피드백("이거 최선이야? KOSMOPROJECT 참고한 거 맞아?")에 렌더링을 실제로
   검증한 적이 없다는 한계를 인정하고, 다크 테마 + 사이드바/본문 레이아웃 + 커스텀 SVG
   로고로 전면 재설계. 이후 "Claude 기본 디자인 느낌이 난다"는 추가 피드백에 이모지 아이콘
   17곳을 전부 일관된 스트로크 SVG 아이콘 세트로 교체하고, 근거 없이 넣었던 "데모입니다"
   문구·관리자 화면에 노출돼 있던 내부 `kubectl` 명령어를 정리.
4. **4차(최종)**: React 18 + TypeScript + Vite로 전면 재작성. 컴포넌트 단위 구조(AuthPage/
   UserDashboard/AdminDashboard/Sidebar), 타입 안전한 API 클라이언트, vitest 테스트 3종.
   Docker 멀티스테이지 빌드로 프론트엔드 빌드까지 CI/CD 파이프라인에 통합.

이 과정에서 배운 것: **디자인은 "그럴듯해 보이는 코드"와 "실제로 렌더링해본 결과"가 다르다.**
브라우저 도구가 없어 렌더링을 검증하지 못했던 한계를, playwright-core로 헤드리스 크로미움을
직접 설치해 실제 스크린샷으로 검증하는 방식으로 극복했습니다.

## 8. 백엔드 성능 개선 (다른 프로젝트와 비교 조사 후 적용)

본인의 다른 실무 프로젝트(si_backend, si_connect, fms-api — 전부 NestJS/TypeScript)를
조사해 DB 커넥션 풀 설정, 캐싱, N+1 처리, 인덱싱 전략을 비교했고, 그 결과를 바탕으로
alpha-adopter에 실제로 존재하던 문제 5건을 수정했습니다.

1. `NewsRawConsumer`가 뉴스 1건마다 구독 테이블 전체를 읽던 것을 메모리 캐시
   (`SubscriptionCache`, 30초 주기 + 쓰기 시 즉시 갱신)로 교체
2. AI 관련도 판단(외부 API 호출)을 `@Transactional` 스코프 밖으로 분리 — 느린 외부 호출
   때문에 DB 커넥션을 계속 붙잡고 있지 않도록
3. `/api/admin/users`의 유저별 개별 COUNT 쿼리(N+1)를 `GROUP BY` 일괄 집계로 교체
4. `Subscription.keyword/user_id`, `Notification.status/created_at/subscription_id/
   news_article_id`에 명시적 인덱스 추가 (Postgres는 FK 컬럼을 자동으로 인덱싱하지 않음)
5. HikariCP 풀 설정 추가 (기존엔 설정 자체가 없어 스프링 기본값에 의존)

흥미로운 점: 비교 대상이었던 si_backend에도 정확히 같은 유형의 N+1 버그가 있었음 —
"유저 목록 조회 후 유저마다 별도 COUNT 쿼리"라는, 본인이 여러 프로젝트에서 반복하던
패턴이라는 뜻이라 앞으로 코드 리뷰 체크리스트에 넣을 만한 교훈.

## 9. 의도적으로 하지 않은 것 / 알고 있는 한계

정직하게 말할 수 있어야 신뢰를 얻습니다. 이 프로젝트가 "숨기는 약점"이 아니라 "스코프
판단"임을 보여주는 목록입니다.

- **투자 조언/매매 시그널 기능 제외**: 자본시장법상 유사투자자문업으로 해석될 수 있어
  스코프에서 명시적으로 배제. 뉴스 원문 전체도 저장·재배포하지 않고 제목/요약/링크 위주로만
  다뤄 저작권 이슈를 피함.
- **Refresh Token 없음**: JWT 7일 고정 만료, 로그아웃해도 토큰 자체는 만료 전까지 유효.
  `localStorage` 저장이라 XSS 노출 표면도 있음 — 다음에 붙일 만한 항목으로 인지하고 있음.
- **Role 기반 인가 없음**: 관리자 여부가 DB가 아니라 배포 환경변수 화이트리스트. 지금
  규모엔 합리적이지만 "RBAC 설계"라고는 말할 수 없음.
- **rate limiting 없음**: 회원가입/로그인/구독 API에 무차별 대입 방어가 없음.
- **알림 목록 무제한 페이지네이션**: `/api/notifications`가 유저당 알림이 계속 쌓이는데
  페이지네이션이 없음 — 지금 규모에선 괜찮지만 스케일 대비 필요.
- **관리자 통계 캐시 없음**: `/api/admin/stats`가 호출마다 집계 쿼리 8개를 매번 새로 계산.
- **부하테스트가 실제 사용자 트래픽이 아님**: k6로 만든 합성 트래픽이지 실사용자 트래픽으로
  검증한 게 아니라는 점을 분명히 인지.

## 10. 이력서/자소서/면접에 바로 쓸 수 있는 요약

아래는 실제 사실에 기반한 요약 문장입니다. 뭉뚱그리지 말고 구체적인 숫자·용어를 그대로
써야 설득력이 생깁니다.

- "Kafka 기반 이벤트 파이프라인(수집→매칭→실시간 알림)에 Claude Haiku AI 2차 필터를
  결합해, 실제 수집 데이터 기준 뉴스 알림 노이즈를 60% 감소시켰다."
- "GitHub Actions OIDC로 정적 액세스키 없는 CI/CD를 구축했고, 조직/저장소 이름 변경으로
  발생한 OIDC 신뢰 정책 불일치를 실제 JWT 클레임을 디버깅해 근본 원인까지 추적·해결했다."
- "Spring Security의 Servlet Async 디스패치에서 SecurityContext가 스레드 로컬이라 유실되는
  문제로 SSE 연결이 인증 단계에서 조용히 끊기는 버그를 재현·진단·수정했다."
- "다른 실무 프로젝트와 DB 접근 패턴을 비교 조사해, N+1 쿼리 2건·트랜잭션 스코프 문제·
  누락된 인덱스 6개·커넥션 풀 미설정을 찾아 수정했다."
- "k6로 부하테스트를 실시해 HPA가 1→3 파드로 정상 스케일하는 것과 p95 응답시간 18ms
  이하를 실측으로 확인했다."
- "바닐라 JS로 시작한 프론트엔드를 React+TypeScript+Vite로 전면 재작성하고, Docker
  멀티스테이지 빌드로 프론트엔드 빌드까지 CI/CD에 통합했다."
- "Testcontainers 기반 통합테스트를 도입해 Kafka/PostgreSQL/MongoDB/Redis를 실제로 띄우는
  CI 테스트 게이트를 구축했다."

## 11. 남은 과제 (백로그)

- `/api/notifications` 커서 기반 페이지네이션
- `/api/admin/stats` Redis 캐싱 (30~60초 TTL)
- Refresh Token 도입, JWT `localStorage` → httpOnly 쿠키 전환 검토
- Role 기반 인가(RBAC)로 전환
- Rate limiting (회원가입/로그인/구독 API)
- Flyway 마이그레이션 도입 (`ddl-auto: update` → `validate` 전환)
- 알림 개인화 필터링 (읽음/클릭 참여도 데이터는 이미 수집 중, 스코어링 로직은 미구현 —
  [`docs/future-ideas.md`](future-ideas.md) 참고)

# 향후 아이디어 (지금 구현 안 함, 잊지 않게 기록만)

## ~~회원/비회원 차등 알림~~ (2026-09-04 논의 → 2026-09-04 구현 완료)

- **비회원**: 실시간 알림 대신, 매일 아침 9시에 그때까지 쌓인 `MATCHED` 상태 알림을 사용자별로 모아 다이제스트로 발송 (`notification/DailyDigestScheduler`)
- **회원**: 실시간으로 알림 수신 (`GET /api/notifications/stream/{userId}`, `User.isMember`가 false면 403)

다이제스트는 `spring-boot-starter-mail`로 실제 이메일 발송(`POST /api/users/membership`으로 회원 전환 가능, 결제 연동은 아직 없음). 로컬은 Mailpit으로 캡처, 실제 SMTP는 `MAIL_HOST`/`MAIL_PORT`/`MAIL_USERNAME`/`MAIL_PASSWORD`로 교체. 발송 실패한 사용자의 알림은 SENT로 바뀌지 않고 다음 주기에 재시도됨. 결제 연동한 프리미엄 전환 유인은 여전히 미구현.

## ~~참여도 기반 개인화 필터링~~ (2026-09-04 논의 → 2026-09-13 구현 완료)

사용자가 구독 시스템으로 받은 정보 중 실제로 어떤 걸 클릭·조회하는지(참여도)를 추적해서, "더 필요로 하는 정보"는 우선 노출하는 개인화 시스템.

- `Notification`에 `readAt`/`clickedAt` 필드 추가. `POST /api/notifications/{id}/read`로 읽음 처리, `GET /api/notifications/{id}/click`은 클릭 시각을 기록하고 원문 기사로 302 리다이렉트
- 다이제스트 이메일의 기사 링크는 이 클릭 추적 리다이렉트를 거쳐간다(`DailyDigestScheduler`). 실시간 SSE 쪽도 `UserDashboard`가 피드 아이템 클릭 시 `/read`를 호출하도록 이미 연결되어 있음
- `PersonalizationScorer`: 구독(키워드)별로 전달된 알림 대비 읽은 알림 비율(0.0~1.0)을 계산. 전달 이력이 `app.personalization.min-sample-size`(기본 5건) 미만이면 콜드스타트로 보고 `null`(판단 보류)을 반환 — 표본이 적을 때 우연히 나온 0%/100%를 신뢰하지 않기 위함
- AI 관련도 필터를 통과해 실제로 알림을 만드는 시점(`NewsMatchPersister`)에 이 점수를 함께 계산해 `Notification.personalizationScore`에 남기고, SSE 페이로드(`NewsMatchedMessage`)·알림 히스토리·관리자 대시보드에도 그대로 노출
- 걸러내는 게 아니라 순서만 바꾸는 방식(fail-open 원칙 유지): `DailyDigestScheduler`가 다이제스트 발송 시 이 점수가 높은 알림을 상단에 배치하고, 콜드스타트(`null`)는 중간값(0.5) 취급해 너무 아래로 밀리지 않게 함. 회원 전체는 언제나 그대로 전달됨 — 하드 필터링/배제는 하지 않음

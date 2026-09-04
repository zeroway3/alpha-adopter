# 향후 아이디어 (지금 구현 안 함, 잊지 않게 기록만)

## ~~회원/비회원 차등 알림~~ (2026-09-04 논의 → 2026-09-04 구현 완료)

- **비회원**: 실시간 알림 대신, 매일 아침 9시에 그때까지 쌓인 `MATCHED` 상태 알림을 사용자별로 모아 다이제스트로 발송 (`notification/DailyDigestScheduler`)
- **회원**: 실시간으로 알림 수신 (`GET /api/notifications/stream/{userId}`, `User.isMember`가 false면 403)

다이제스트는 `spring-boot-starter-mail`로 실제 이메일 발송(`POST /api/users/membership`으로 회원 전환 가능, 결제 연동은 아직 없음). 로컬은 Mailpit으로 캡처, 실제 SMTP는 `MAIL_HOST`/`MAIL_PORT`/`MAIL_USERNAME`/`MAIL_PASSWORD`로 교체. 발송 실패한 사용자의 알림은 SENT로 바뀌지 않고 다음 주기에 재시도됨. 결제 연동한 프리미엄 전환 유인은 여전히 미구현.

## 참여도 기반 개인화 필터링 (2026-09-04 논의 → 데이터 수집만 2026-09-04 구현)

사용자가 구독 시스템으로 받은 정보 중 실제로 어떤 걸 클릭·조회하는지(참여도)를 추적해서, AI/데이터 분석으로 "더 필요로 하는 정보"는 우선 노출하고 "잘 안 보는 것"은 배제하는 개인화 시스템.

- `Notification`에 `readAt`/`clickedAt` 필드 추가. `POST /api/notifications/{id}/read`로 읽음 처리, `GET /api/notifications/{id}/click`은 클릭 시각을 기록하고 원문 기사로 302 리다이렉트
- 다이제스트 이메일의 기사 링크는 이제 이 클릭 추적 리다이렉트를 거쳐가도록 바뀜(`DailyDigestScheduler`). 실시간 SSE 쪽은 아직 프런트엔드가 없어서 `/read` 호출 지점이 없음
- 콜드스타트 문제 때문에 키워드/카테고리별 관심도 스코어링 → 알림 우선순위·필터링 로직 자체는 여전히 미구현. 지금은 데이터만 쌓기 시작한 단계

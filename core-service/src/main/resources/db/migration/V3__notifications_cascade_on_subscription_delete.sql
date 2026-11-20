-- 구독을 해지하면 그 구독으로 받은 알림 이력도 함께 지운다.
--
-- 기존 FK에는 ON DELETE 동작이 없어서, 알림을 한 건이라도 받은 구독은 삭제할 때 FK 위반으로 500이
-- 났다(사용자가 구독 해지를 할 수 없는 상태). 테스트는 알림이 없는 구독만 지우고 있어 드러나지 않았고,
-- AWS 실측 중 부하테스트 계정의 구독을 정리하다가 발견했다. 알림은 구독에 딸린 데이터라 구독과 수명을
-- 같이 하는 것이 맞으므로 DB 레벨에서 연쇄 삭제한다(애플리케이션에서 지우면 동시 수집과 경합할 수 있다).
ALTER TABLE notifications DROP CONSTRAINT fke782fmwh46b2q4f9gm1wpr7w4;
ALTER TABLE notifications
    ADD CONSTRAINT fk_notifications_subscription
    FOREIGN KEY (subscription_id) REFERENCES subscriptions(id) ON DELETE CASCADE;

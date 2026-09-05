// core-service의 핵심 사용자 흐름(구독 등록 → 조회 → 해제)을 시뮬레이션하는 부하테스트.
//
// 실행:
//   BASE_URL=http://<core-service LoadBalancer>/  k6 run loadtest/subscription-flow.js
//
// 주의: app.subscription.max-distinct-keywords(기본 15) 한도 때문에 VU마다 새 키워드를
// 무한정 만들면 429가 터진다. 실제 서비스에서도 "인기 키워드에 여러 사용자가 몰리는" 게
// 자연스러운 패턴이라, KEYWORDS 풀을 고정해두고 VU들이 그 안에서 나눠 갖게 한다.
//
// SSE 알림 스트림(GET /api/notifications/stream?token=...)은 k6 기본 기능으로는 검증할 수 없어
// (long-lived streaming 응답) 이 스크립트 범위에서 제외. SSE 자체의 동작은 3단계 구현 때
// 수동으로(curl -N) 이미 확인된 상태 — 이번 부하테스트는 그 앞단인 구독 API의 처리량/지연에 집중한다.

import http from "k6/http";
import { check, sleep } from "k6";
import { Counter } from "k6/metrics";

const BASE_URL = __ENV.BASE_URL || "http://localhost:8090";
const KEYWORDS = ["삼성전자", "반도체", "금리", "AI", "이차전지"];

export const subscriptionConflicts = new Counter("subscription_conflicts");
export const subscriptionRateLimited = new Counter("subscription_rate_limited");

export const options = {
  scenarios: {
    steady_ramp: {
      executor: "ramping-vus",
      startVUs: 0,
      stages: [
        { duration: "30s", target: 20 },
        { duration: "1m", target: 20 },
        { duration: "30s", target: 50 },
        { duration: "1m", target: 50 },
        { duration: "30s", target: 0 },
      ],
    },
  },
  thresholds: {
    http_req_failed: ["rate<0.01"],
    "http_req_duration{endpoint:create_subscription}": ["p(95)<500"],
    "http_req_duration{endpoint:list_subscriptions}": ["p(95)<300"],
  },
};

// k6는 VU마다 스크립트 전역 스코프를 독립적으로 유지하면서 iteration 사이에 값을 보존하므로,
// 매 iteration마다 로그인하지 않고 VU당 한 번만 인증해서 토큰을 재사용한다.
let authToken = null;

function authHeaders() {
  return { "Content-Type": "application/json", Authorization: `Bearer ${authToken}` };
}

function ensureAuthenticated() {
  if (authToken) return;

  const email = `loadtest-vu${__VU}@alphaadopter.local`;
  const password = "loadtest-password-1234";

  let res = http.post(
    `${BASE_URL}/api/auth/signup`,
    JSON.stringify({ email, password }),
    { headers: { "Content-Type": "application/json" }, tags: { endpoint: "auth" }, responseCallback: http.expectedStatuses(201, 409) },
  );
  if (res.status === 409) {
    // 이전 실행에서 이미 가입된 VU 계정 — 로그인으로 대체
    res = http.post(
      `${BASE_URL}/api/auth/login`,
      JSON.stringify({ email, password }),
      { headers: { "Content-Type": "application/json" }, tags: { endpoint: "auth" } },
    );
  }
  authToken = JSON.parse(res.body).token;
}

export default function () {
  ensureAuthenticated();
  const keyword = KEYWORDS[__VU % KEYWORDS.length];

  const createRes = http.post(
    `${BASE_URL}/api/subscriptions`,
    JSON.stringify({ keyword, type: "KEYWORD" }),
    {
      headers: authHeaders(),
      tags: { endpoint: "create_subscription" },
      // 이미 구독한 키워드에 재요청하는 409는 정상적인 애플리케이션 흐름(멱등 재시도)이라
      // http_req_failed 실패율에 안 잡히게 명시적으로 "기대하는 상태"로 등록
      responseCallback: http.expectedStatuses(201, 409, 429),
    },
  );

  check(createRes, {
    "create: 201/409 중 하나": (r) => r.status === 201 || r.status === 409,
  });
  if (createRes.status === 409) subscriptionConflicts.add(1);
  if (createRes.status === 429) subscriptionRateLimited.add(1);

  const listRes = http.get(`${BASE_URL}/api/subscriptions`, {
    headers: authHeaders(),
    tags: { endpoint: "list_subscriptions" },
  });
  check(listRes, {
    "list: 200": (r) => r.status === 200,
    "list: 최소 1개 구독 포함": (r) => JSON.parse(r.body).length >= 1,
  });

  sleep(1);
}

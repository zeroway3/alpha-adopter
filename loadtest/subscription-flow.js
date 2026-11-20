// core-service의 핵심 사용자 흐름(구독 조회·알림 이력 조회, 가끔 구독 등록)을 시뮬레이션하는 부하테스트.
//
// 실행:
//   BASE_URL=http://<core-service LoadBalancer>  k6 run loadtest/subscription-flow.js
//
// 인증: 로그인 응답은 토큰을 본문이 아니라 httpOnly 쿠키로 내려준다. 예전 스크립트는 본문의 token을
// 읽으려다 항상 비어 있는 값을 얻었고, 그 결과 매 iteration마다 다시 가입·로그인하다가 로그인 rate
// limit(429)에 막혀 실패율이 66%까지 치솟았다(서버 성능 문제가 아니라 스크립트 결함). 지금은 setup()에서
// 몇 개 계정만 한 번 로그인해 access_token 쿠키 값을 받아 두고, VU들이 Bearer 헤더로 나눠 쓴다.
// 부하 발생기가 IP 하나라 VU마다 로그인하면 IP 기준 로그인 한도(분당 10회)에 걸리기 때문이다.
//
// 구독 등록에도 IP 기준 한도(분당 30회)가 있어, 한 대에서 쏘는 부하는 대부분 429로 정상 방어된다.
// 429는 필터에서 바로 끊겨 DB를 거치지 않으므로, 지연 측정에서 빼고 별도 카운터로만 센다.
//
// SSE 스트림은 k6 기본 기능으로 검증할 수 없어 범위에서 제외(알림 지연은 Micrometer 지표로 측정).

import http from "k6/http";
import { check, sleep } from "k6";
import { Counter, Trend } from "k6/metrics";

const BASE_URL = (__ENV.BASE_URL || "http://localhost:8090").replace(/\/$/, "");
const KEYWORDS = ["삼성전자", "반도체", "금리", "AI", "이차전지"];
const ACCOUNTS = 8; // 로그인 한도(분당 10회) 안에서 setup이 끝나도록

const createAccepted = new Trend("create_subscription_accepted_duration", true);
const rateLimited = new Counter("rate_limited");

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
    // 429(정상적인 방어)는 기대 응답으로 등록해 실패율에서 제외한다
    http_req_failed: ["rate<0.01"],
    "http_req_duration{endpoint:list_subscriptions}": ["p(95)<300"],
    "http_req_duration{endpoint:notification_history}": ["p(95)<300"],
    create_subscription_accepted_duration: ["p(95)<500"],
  },
};

function json() {
  return { headers: { "Content-Type": "application/json" }, tags: { endpoint: "auth" } };
}

export function setup() {
  const tokens = [];
  for (let i = 0; i < ACCOUNTS; i++) {
    const email = `loadtest-${i}@alphaadopter.local`;
    const body = JSON.stringify({ email, password: "loadtest-password-1234" });
    let res = http.post(`${BASE_URL}/api/auth/signup`, body, {
      ...json(),
      responseCallback: http.expectedStatuses(201, 409),
    });
    if (res.status === 409) {
      res = http.post(`${BASE_URL}/api/auth/login`, body, json());
    }
    const cookie = res.cookies.access_token && res.cookies.access_token[0];
    if (!cookie) throw new Error(`계정 ${email} 로그인 실패: HTTP ${res.status}`);
    tokens.push(cookie.value);
  }
  return { tokens };
}

export default function (data) {
  const token = data.tokens[__VU % data.tokens.length];
  const auth = { Authorization: `Bearer ${token}` };

  const listRes = http.get(`${BASE_URL}/api/subscriptions`, {
    headers: auth,
    tags: { endpoint: "list_subscriptions" },
  });
  check(listRes, { "list: 200": (r) => r.status === 200 });

  const historyRes = http.get(`${BASE_URL}/api/notifications?limit=20`, {
    headers: auth,
    tags: { endpoint: "notification_history" },
  });
  check(historyRes, { "history: 200": (r) => r.status === 200 });

  // 실제 사용 패턴처럼 등록은 조회보다 드물게
  if (__ITER % 5 === 0) {
    const keyword = KEYWORDS[__VU % KEYWORDS.length];
    const createRes = http.post(
      `${BASE_URL}/api/subscriptions`,
      JSON.stringify({ keyword, type: "KEYWORD" }),
      {
        headers: { ...auth, "Content-Type": "application/json" },
        tags: { endpoint: "create_subscription" },
        // 409(이미 구독함)와 429(한도 초과)는 정상적인 애플리케이션 흐름
        responseCallback: http.expectedStatuses(201, 409, 429),
      },
    );
    if (createRes.status === 429) {
      rateLimited.add(1);
    } else {
      createAccepted.add(createRes.timings.duration);
      check(createRes, { "create: 201/409": (r) => r.status === 201 || r.status === 409 });
    }
  }

  sleep(1);
}

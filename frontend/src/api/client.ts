import type {
  AdminDailyCount,
  AdminKeywordSummary,
  AdminStatsResponse,
  AdminUserSummary,
  NotificationHistoryPage,
  Session,
  Subscription,
  SubscriptionType,
} from "./types";

export class SessionExpiredError extends Error {
  constructor() {
    super("세션이 만료되었습니다. 다시 로그인해주세요.");
  }
}

interface ApiErrorBody {
  detail?: string;
  message?: string;
}

async function readErrorMessage(res: Response, fallback: string): Promise<string> {
  const body = (await res.json().catch(() => ({}))) as ApiErrorBody;
  return body.detail || body.message || `${fallback} (${res.status})`;
}

// access token이 httpOnly 쿠키라 만료 여부를 JS가 미리 알 수 없다. Spring Security가
// STATELESS+익명 인증 기본 설정이라, 인증 자체가 없는(또는 만료된) 요청은 401이 아니라
// 403으로 응답한다(AuthenticationEntryPoint가 아니라 AccessDeniedHandler 경로 — 익명
// Authentication은 있지만 authenticated()를 통과 못 해서 생기는 403). 그래서 401뿐 아니라
// 403도 "세션이 끊겼을 수 있다"로 보고 refresh 쿠키로 한 번 재발급을 시도한 뒤 재시도한다.
// (권한 자체가 없어 나는 정상적인 403 — 예: 비회원의 SSE 접근 — 은 refresh해도 여전히
// 403이라 재시도 이후 그대로 반환되므로 잘못된 동작은 아니다.) AuthContext가 세션 유지 중
// 주기적으로도 refresh를 호출하지만(장시간 열어둔 SSE 탭 대비), 이 재시도 로직이 있어야
// 그 주기 사이의 빈틈도 메워진다.
async function authFetch(path: string, options: RequestInit = {}): Promise<Response> {
  const res = await fetch(path, { ...options, credentials: "same-origin" });
  if (res.status !== 401 && res.status !== 403) return res;

  const refreshed = await refresh().catch(() => null);
  if (!refreshed) throw new SessionExpiredError();

  const retry = await fetch(path, { ...options, credentials: "same-origin" });
  if (retry.status === 401) throw new SessionExpiredError();
  return retry;
}

export async function signup(email: string, password: string): Promise<Session> {
  const res = await fetch("/api/auth/signup", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    credentials: "same-origin",
    body: JSON.stringify({ email, password }),
  });
  if (!res.ok) throw new Error(await readErrorMessage(res, "회원가입 실패"));
  return res.json();
}

export async function login(email: string, password: string): Promise<Session> {
  const res = await fetch("/api/auth/login", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    credentials: "same-origin",
    body: JSON.stringify({ email, password }),
  });
  if (!res.ok) throw new Error(await readErrorMessage(res, "로그인 실패"));
  return res.json();
}

// 페이지 로드 시 "이미 로그인돼 있는가"를 확인한다. access_token 쿠키가 없거나 만료됐으면
// 401 — 로그아웃 상태로 취급한다.
export async function me(): Promise<Session | null> {
  const res = await fetch("/api/auth/me", { credentials: "same-origin" });
  if (!res.ok) return null;
  return res.json();
}

export async function refresh(): Promise<Session | null> {
  const res = await fetch("/api/auth/refresh", { method: "POST", credentials: "same-origin" });
  if (!res.ok) return null;
  return res.json();
}

export async function logout(): Promise<void> {
  await fetch("/api/auth/logout", { method: "POST", credentials: "same-origin" });
}

export async function loadSubscriptions(): Promise<Subscription[]> {
  const res = await authFetch("/api/subscriptions");
  return res.json();
}

export async function createSubscription(keyword: string, type: SubscriptionType): Promise<Subscription> {
  const res = await authFetch("/api/subscriptions", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ keyword, type }),
  });
  if (!res.ok) throw new Error(await readErrorMessage(res, "구독 실패"));
  return res.json();
}

export async function deleteSubscription(id: number): Promise<void> {
  const res = await authFetch(`/api/subscriptions/${id}`, { method: "DELETE" });
  if (!res.ok && res.status !== 204) throw new Error("구독 해제 실패");
}

export async function markNotificationRead(notificationId: number): Promise<void> {
  await authFetch(`/api/notifications/${notificationId}/read`, { method: "POST" });
}

// cursor를 넘기면 그 지점부터 다음 페이지를 최신순으로 받는다. 첫 페이지는 cursor 생략.
export async function loadHistory(cursor?: string, limit = 20): Promise<NotificationHistoryPage> {
  const params = new URLSearchParams({ limit: String(limit) });
  if (cursor) params.set("cursor", cursor);
  const res = await authFetch(`/api/notifications?${params}`);
  if (!res.ok) throw new Error(await readErrorMessage(res, "알림 히스토리 조회 실패"));
  return res.json();
}

export async function loadAdminStats(): Promise<AdminStatsResponse> {
  const res = await authFetch("/api/admin/stats");
  if (!res.ok) throw new Error(`관리자 통계 조회 실패 (${res.status})`);
  return res.json();
}

export async function loadAdminUsers(): Promise<AdminUserSummary[]> {
  const res = await authFetch("/api/admin/users");
  return res.json();
}

export async function loadAdminKeywords(): Promise<AdminKeywordSummary[]> {
  const res = await authFetch("/api/admin/keywords");
  return res.json();
}

export async function loadAdminDaily(): Promise<AdminDailyCount[]> {
  const res = await authFetch("/api/admin/stats/daily");
  return res.json();
}

export async function checkServerHealth(): Promise<boolean> {
  try {
    const res = await fetch("/actuator/health");
    return res.ok;
  } catch {
    return false;
  }
}

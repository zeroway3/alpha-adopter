import type {
  AdminDailyCount,
  AdminKeywordSummary,
  AdminStatsResponse,
  AdminUserSummary,
  NotificationHistoryItem,
  Session,
  Subscription,
  SubscriptionType,
} from "./types";

const SESSION_KEY = "alphaadopter_session";

export function getSession(): Session | null {
  try {
    const raw = localStorage.getItem(SESSION_KEY);
    return raw ? (JSON.parse(raw) as Session) : null;
  } catch {
    return null;
  }
}

export function setSession(session: Session): void {
  localStorage.setItem(SESSION_KEY, JSON.stringify(session));
}

export function clearSession(): void {
  localStorage.removeItem(SESSION_KEY);
}

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

async function authFetch(path: string, options: RequestInit = {}): Promise<Response> {
  const session = getSession();
  const headers = new Headers(options.headers);
  if (session) headers.set("Authorization", `Bearer ${session.token}`);

  const res = await fetch(path, { ...options, headers });
  if (res.status === 401) {
    clearSession();
    throw new SessionExpiredError();
  }
  return res;
}

export async function signup(email: string, password: string): Promise<Session> {
  const res = await fetch("/api/auth/signup", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, password }),
  });
  if (!res.ok) throw new Error(await readErrorMessage(res, "회원가입 실패"));
  return res.json();
}

export async function login(email: string, password: string): Promise<Session> {
  const res = await fetch("/api/auth/login", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, password }),
  });
  if (!res.ok) throw new Error(await readErrorMessage(res, "로그인 실패"));
  return res.json();
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

export async function loadHistory(): Promise<NotificationHistoryItem[]> {
  const res = await authFetch("/api/notifications");
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

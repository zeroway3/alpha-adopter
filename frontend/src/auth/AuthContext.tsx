import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import * as api from "../api/client";
import type { Session } from "../api/types";

// access token(쿠키) 기본 유효기간(백엔드 app.jwt.access-validity-minutes, 기본 60분)보다
// 충분히 짧은 주기로 미리 갱신해, 오래 열어둔 탭의 SSE 연결이 만료로 끊기지 않게 한다.
const PROACTIVE_REFRESH_INTERVAL_MS = 10 * 60 * 1000;

interface AuthContextValue {
  session: Session | null;
  /** 부팅 시 세션 확인(GET /api/auth/me)이 아직 끝나지 않은 동안 true */
  initializing: boolean;
  login: (email: string, password: string) => Promise<void>;
  signup: (email: string, password: string) => Promise<void>;
  logout: () => void;
  /** authFetch가 401로 세션 만료를 감지했을 때 호출 — 화면 전체를 로그인 화면으로 되돌린다 */
  handleSessionExpired: () => void;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSessionState] = useState<Session | null>(null);
  const [initializing, setInitializing] = useState(true);

  useEffect(() => {
    let cancelled = false;
    api
      .me()
      .then((s) => {
        if (!cancelled) setSessionState(s);
      })
      .catch(() => {
        if (!cancelled) setSessionState(null);
      })
      .finally(() => {
        if (!cancelled) setInitializing(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    if (!session) return;
    const id = setInterval(() => {
      api.refresh().catch(() => {});
    }, PROACTIVE_REFRESH_INTERVAL_MS);
    return () => clearInterval(id);
  }, [session]);

  const login = useCallback(async (email: string, password: string) => {
    const s = await api.login(email, password);
    setSessionState(s);
  }, []);

  const signup = useCallback(async (email: string, password: string) => {
    const s = await api.signup(email, password);
    setSessionState(s);
  }, []);

  const logout = useCallback(() => {
    api.logout().finally(() => setSessionState(null));
  }, []);

  const handleSessionExpired = useCallback(() => {
    setSessionState(null);
  }, []);

  const value = useMemo(
    () => ({ session, initializing, login, signup, logout, handleSessionExpired }),
    [session, initializing, login, signup, logout, handleSessionExpired],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth는 AuthProvider 내부에서만 사용할 수 있습니다.");
  return ctx;
}

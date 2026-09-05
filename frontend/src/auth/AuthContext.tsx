import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from "react";
import * as api from "../api/client";
import type { Session } from "../api/types";

interface AuthContextValue {
  session: Session | null;
  login: (email: string, password: string) => Promise<void>;
  signup: (email: string, password: string) => Promise<void>;
  logout: () => void;
  /** authFetch가 401로 세션 만료를 감지했을 때 호출 — 화면 전체를 로그인 화면으로 되돌린다 */
  handleSessionExpired: () => void;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSessionState] = useState<Session | null>(() => api.getSession());

  const login = useCallback(async (email: string, password: string) => {
    const s = await api.login(email, password);
    api.setSession(s);
    setSessionState(s);
  }, []);

  const signup = useCallback(async (email: string, password: string) => {
    const s = await api.signup(email, password);
    api.setSession(s);
    setSessionState(s);
  }, []);

  const logout = useCallback(() => {
    api.clearSession();
    setSessionState(null);
  }, []);

  const handleSessionExpired = useCallback(() => {
    api.clearSession();
    setSessionState(null);
  }, []);

  const value = useMemo(
    () => ({ session, login, signup, logout, handleSessionExpired }),
    [session, login, signup, logout, handleSessionExpired],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth는 AuthProvider 내부에서만 사용할 수 있습니다.");
  return ctx;
}

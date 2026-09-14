import { useState } from "react";
import { useAuth } from "./auth/AuthContext";
import { AuthPage } from "./pages/AuthPage";
import { UserDashboard } from "./pages/UserDashboard";
import { AdminDashboard } from "./pages/AdminDashboard";
import { Sidebar, type ViewName } from "./components/Sidebar";

export function App() {
  const { session, initializing, logout, handleSessionExpired } = useAuth();
  const [view, setView] = useState<ViewName>("user");

  // 부팅 시 GET /api/auth/me로 세션을 확인하는 동안(비동기) — 이 판단이 끝나기 전에
  // AuthPage를 먼저 보여주면 로그인돼 있어도 한순간 로그인 화면이 깜빡인다.
  if (initializing) return null;

  if (!session) return <AuthPage />;

  const activeView = session.isAdmin ? view : "user";

  return (
    <div className="app-shell">
      <Sidebar session={session} activeView={activeView} onChangeView={setView} onLogout={logout} />
      <div className="main-area">
        {activeView === "admin" ? (
          <AdminDashboard onSessionExpired={handleSessionExpired} />
        ) : (
          <UserDashboard onSessionExpired={handleSessionExpired} />
        )}
      </div>
    </div>
  );
}

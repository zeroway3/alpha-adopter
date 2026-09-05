import { useState } from "react";
import { useAuth } from "./auth/AuthContext";
import { AuthPage } from "./pages/AuthPage";
import { UserDashboard } from "./pages/UserDashboard";
import { AdminDashboard } from "./pages/AdminDashboard";
import { Sidebar, type ViewName } from "./components/Sidebar";

export function App() {
  const { session, logout, handleSessionExpired } = useAuth();
  const [view, setView] = useState<ViewName>("user");

  if (!session) return <AuthPage />;

  const activeView = session.isAdmin ? view : "user";

  return (
    <div className="app-shell">
      <Sidebar session={session} activeView={activeView} onChangeView={setView} onLogout={logout} />
      <div className="main-area">
        {activeView === "admin" ? (
          <AdminDashboard onSessionExpired={handleSessionExpired} />
        ) : (
          <UserDashboard token={session.token} onSessionExpired={handleSessionExpired} />
        )}
      </div>
    </div>
  );
}

import { Icon } from "../icons/Icon";
import { Logo } from "./Logo";
import type { Session } from "../api/types";

export type ViewName = "user" | "admin";

interface SidebarProps {
  session: Session;
  activeView: ViewName;
  onChangeView: (view: ViewName) => void;
  onLogout: () => void;
}

export function Sidebar({ session, activeView, onChangeView, onLogout }: SidebarProps) {
  return (
    <aside className="sidebar">
      <div className="sidebar-header">
        <Logo size={34} />
        <div>
          <div className="logo-wordmark">AlphaAdopter</div>
          <div className="sub">뉴스 알림 플랫폼</div>
        </div>
      </div>

      <nav className="sidebar-nav">
        {session.isAdmin && (
          <>
            <div className="sidebar-nav-label">메뉴</div>
            <button
              type="button"
              className={`sidebar-nav-item${activeView === "user" ? " active" : ""}`}
              onClick={() => onChangeView("user")}
            >
              <span className="icon">
                <Icon name="home" size={16} />
              </span>
              내 화면
            </button>
            <button
              type="button"
              className={`sidebar-nav-item${activeView === "admin" ? " active" : ""}`}
              onClick={() => onChangeView("admin")}
            >
              <span className="icon">
                <Icon name="grid" size={16} />
              </span>
              관리자
            </button>
          </>
        )}
      </nav>

      <div className="sidebar-footer">
        <div className="sidebar-user">
          <span className="sidebar-user-avatar">{session.email.charAt(0).toUpperCase()}</span>
          <div className="sidebar-user-info">
            <div className="sidebar-user-email">{session.email}</div>
            <div className="sidebar-user-role">
              {session.isAdmin ? <span className="badge badge-primary">ADMIN</span> : "일반 회원"}
            </div>
          </div>
        </div>
        <button type="button" className="btn btn-outline btn-sm btn-full" onClick={onLogout}>
          로그아웃
        </button>
      </div>
    </aside>
  );
}

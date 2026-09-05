import { useCallback, useEffect, useState } from "react";
import * as api from "../api/client";
import { SessionExpiredError } from "../api/client";
import type {
  AdminDailyCount,
  AdminKeywordSummary,
  AdminStatsResponse,
  AdminUserSummary,
  NotificationStatus,
} from "../api/types";
import { Icon, type IconName } from "../icons/Icon";

function formatTime(iso: string | null): string {
  if (!iso) return "-";
  return new Date(iso).toLocaleString("ko-KR");
}

function StatusBadge({ status }: { status: NotificationStatus }) {
  const map: Record<NotificationStatus, string> = {
    MATCHED: "badge-primary",
    SENT: "badge-success",
    FAILED: "badge-danger",
  };
  return <span className={`badge ${map[status]}`}>{status}</span>;
}

function RelevanceBadge({ score }: { score: number | null }) {
  if (score === null) return <span className="badge badge-default">미판단</span>;
  const variant = score >= 70 ? "badge-success" : score >= 50 ? "badge-warning" : "badge-danger";
  return <span className={`badge ${variant}`}>{score}점</span>;
}

interface StatBox {
  icon: IconName;
  color: string;
  label: string;
  value: string | number;
}

function buildStatBoxes(stats: AdminStatsResponse): StatBox[] {
  return [
    { icon: "users", color: "blue", label: "전체 사용자", value: stats.totalUsers },
    { icon: "search", color: "violet", label: "전체 구독", value: stats.totalSubscriptions },
    { icon: "newspaper", color: "cyan", label: "수집된 뉴스", value: stats.totalNewsArticles },
    { icon: "checkCircle", color: "emerald", label: "매칭됨", value: stats.notificationsMatched },
    { icon: "send", color: "amber", label: "전송됨", value: stats.notificationsSent },
    { icon: "alertTriangle", color: "rose", label: "실패", value: stats.notificationsFailed },
    {
      icon: "cpu",
      color: stats.aiFilterEnabled ? "emerald" : "rose",
      label: "AI 필터",
      value: stats.aiFilterEnabled ? "ON" : "OFF",
    },
    {
      icon: "barChart",
      color: "cyan",
      label: "평균 관련도",
      value: stats.averageRelevanceScore != null ? `${Math.round(stats.averageRelevanceScore)}점` : "-",
    },
  ];
}

interface Props {
  onSessionExpired: () => void;
}

export function AdminDashboard({ onSessionExpired }: Props) {
  const [stats, setStats] = useState<AdminStatsResponse | null>(null);
  const [users, setUsers] = useState<AdminUserSummary[]>([]);
  const [keywords, setKeywords] = useState<AdminKeywordSummary[]>([]);
  const [daily, setDaily] = useState<AdminDailyCount[]>([]);
  const [serverUp, setServerUp] = useState<boolean | null>(null);
  const [now, setNow] = useState(() => new Date());

  useEffect(() => {
    const timer = setInterval(() => setNow(new Date()), 1000);
    return () => clearInterval(timer);
  }, []);

  useEffect(() => {
    let cancelled = false;
    const poll = async () => {
      const up = await api.checkServerHealth();
      if (!cancelled) setServerUp(up);
    };
    poll();
    const timer = setInterval(poll, 15000);
    return () => {
      cancelled = true;
      clearInterval(timer);
    };
  }, []);

  const refresh = useCallback(async () => {
    try {
      const [s, u, k, d] = await Promise.all([
        api.loadAdminStats(),
        api.loadAdminUsers(),
        api.loadAdminKeywords(),
        api.loadAdminDaily(),
      ]);
      setStats(s);
      setUsers(u);
      setKeywords(k);
      setDaily(d);
    } catch (err) {
      if (err instanceof SessionExpiredError) onSessionExpired();
      else console.error(err);
    }
  }, [onSessionExpired]);

  useEffect(() => {
    refresh();
  }, [refresh]);

  const maxDaily = Math.max(...daily.map((d) => d.total), 1);

  return (
    <main className="content">
      <div className="admin-header">
        <h2>관리자 대시보드</h2>
        <div style={{ display: "flex", alignItems: "center", gap: "1.1rem" }}>
          <span className="clock">{now.toLocaleString("ko-KR")}</span>
          <span
            className={`status-pill${serverUp === true ? " status-up" : serverUp === false ? " status-down" : ""}`}
          >
            <span className="status-dot-wrap">
              <span className="status-dot-ping" />
              <span className="status-dot" />
            </span>
            <span className="status-text">{serverUp === null ? "확인 중" : serverUp ? "정상" : "오류"}</span>
          </span>
          <button type="button" className="btn btn-outline btn-sm" onClick={refresh}>
            새로고침
          </button>
        </div>
      </div>

      <div className="stat-grid">
        {stats &&
          buildStatBoxes(stats).map((box) => (
            <div className="stat-card" key={box.label}>
              <span className={`stat-icon-box icon-${box.color}`}>
                <Icon name={box.icon} size={22} />
              </span>
              <div>
                <div className="stat-value">{box.value}</div>
                <div className="stat-label">{box.label}</div>
              </div>
            </div>
          ))}
      </div>

      <section className="card">
        <div className="card-header">
          <span className="card-icon tint-cyan">
            <Icon name="trendingUp" />
          </span>
          <div>
            <div className="card-title">최근 7일 알림 발생 추이</div>
            <div className="card-subtitle">일자별 매칭·발송된 알림 총량</div>
          </div>
        </div>
        <div className="chart-wrap">
          {daily.length === 0 && (
            <p style={{ color: "var(--text-faint)", fontSize: "0.85rem" }}>최근 7일간 데이터가 없습니다.</p>
          )}
          {daily.map((d) => (
            <div className="chart-bar-col" key={d.day}>
              <span className="chart-bar-value">{d.total}</span>
              <div className="chart-bar" style={{ height: `${Math.max((d.total / maxDaily) * 100, 4)}%` }} />
              <span className="chart-bar-label">
                {new Date(d.day).toLocaleDateString("ko-KR", { month: "numeric", day: "numeric" })}
              </span>
            </div>
          ))}
        </div>
        <p className="ops-note">
          상세 인프라 메트릭(CPU·메모리·처리량)은 별도 모니터링 대시보드에서 제공되며, 보안을 위해 외부에는
          공개하지 않습니다.
        </p>
      </section>

      <section className="card">
        <div className="card-header">
          <span className="card-icon tint-amber">
            <Icon name="tag" />
          </span>
          <div className="card-title">인기 키워드 TOP 10</div>
        </div>
        <div className="table-card">
          <div className="table-scroll">
            <table className="data-table">
              <thead>
                <tr>
                  <th>키워드</th>
                  <th>구독자 수</th>
                </tr>
              </thead>
              <tbody>
                {keywords.length === 0 && (
                  <tr>
                    <td colSpan={2} style={{ textAlign: "center", color: "var(--text-faint)", padding: "2rem 1rem" }}>
                      데이터가 없습니다.
                    </td>
                  </tr>
                )}
                {keywords.map((k) => (
                  <tr key={k.keyword}>
                    <td>{k.keyword}</td>
                    <td>{k.subscriberCount}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      </section>

      <section className="card">
        <div className="card-header">
          <span className="card-icon tint-violet">
            <Icon name="users" />
          </span>
          <div className="card-title">전체 사용자</div>
        </div>
        <div className="table-card">
          <div className="table-scroll">
            <table className="data-table">
              <thead>
                <tr>
                  <th>이메일</th>
                  <th>회원</th>
                  <th>관리자</th>
                  <th>구독 수</th>
                  <th>가입일</th>
                </tr>
              </thead>
              <tbody>
                {users.map((u) => (
                  <tr key={u.id}>
                    <td>{u.email}</td>
                    <td>{u.isMember ? "O" : "-"}</td>
                    <td>{u.isAdmin ? "O" : "-"}</td>
                    <td>{u.subscriptionCount}</td>
                    <td>{formatTime(u.createdAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      </section>

      <section className="card">
        <div className="card-header">
          <span className="card-icon tint-rose">
            <Icon name="bell" />
          </span>
          <div className="card-title">전체 최근 알림</div>
        </div>
        <div className="table-card">
          <div className="table-scroll">
            <table className="data-table">
              <thead>
                <tr>
                  <th>사용자</th>
                  <th>키워드</th>
                  <th>기사</th>
                  <th>AI 관련도</th>
                  <th>상태</th>
                  <th>발생 시각</th>
                </tr>
              </thead>
              <tbody>
                {stats?.recentNotifications.map((n) => (
                  <tr key={n.id}>
                    <td>{n.userEmail}</td>
                    <td>{n.keyword}</td>
                    <td>{n.articleTitle}</td>
                    <td>
                      <RelevanceBadge score={n.relevanceScore} />
                    </td>
                    <td>
                      <StatusBadge status={n.status} />
                    </td>
                    <td>{formatTime(n.createdAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      </section>
    </main>
  );
}

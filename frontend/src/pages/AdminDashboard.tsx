import { useCallback, useEffect, useState } from "react";
import * as api from "../api/client";
import { SessionExpiredError } from "../api/client";
import type {
  AdminAuditLogSummary,
  AdminDailyCount,
  AdminKeywordSummary,
  AdminStatsResponse,
  AdminUserSummary,
  InvestigationResponse,
  NotificationStatus,
  RiskLevel,
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

function PersonalizationBadge({ score }: { score: number | null }) {
  if (score === null) return <span className="badge badge-default">데이터 부족</span>;
  const pct = Math.round(score * 100);
  const variant = pct >= 70 ? "badge-success" : pct >= 40 ? "badge-warning" : "badge-danger";
  return <span className={`badge ${variant}`}>{pct}%</span>;
}

function RiskBadge({ riskLevel }: { riskLevel: RiskLevel }) {
  const map: Record<RiskLevel, string> = {
    LOW: "badge-success",
    MEDIUM: "badge-warning",
    HIGH: "badge-danger",
    INCONCLUSIVE: "badge-default",
  };
  const label: Record<RiskLevel, string> = {
    LOW: "낮음",
    MEDIUM: "보통",
    HIGH: "높음",
    INCONCLUSIVE: "조사 불가",
  };
  return <span className={`badge ${map[riskLevel]}`}>{label[riskLevel]}</span>;
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
  const [auditLogs, setAuditLogs] = useState<AdminAuditLogSummary[]>([]);
  const [serverUp, setServerUp] = useState<boolean | null>(null);
  const [now, setNow] = useState(() => new Date());

  // 조사 중인 userId(로딩 상태 표시용)와, 가장 최근 조사 결과를 함께 보관한다 — 여러 유저를
  // 연달아 조사할 수 있으므로 결과에 대상 이메일을 같이 담아둔다.
  const [investigatingUserId, setInvestigatingUserId] = useState<number | null>(null);
  const [investigation, setInvestigation] = useState<{ email: string; result: InvestigationResponse } | null>(null);
  const [investigationError, setInvestigationError] = useState<string | null>(null);

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
      const [s, u, k, d, a] = await Promise.all([
        api.loadAdminStats(),
        api.loadAdminUsers(),
        api.loadAdminKeywords(),
        api.loadAdminDaily(),
        api.loadAdminAuditLogs(),
      ]);
      setStats(s);
      setUsers(u);
      setKeywords(k);
      setDaily(d);
      setAuditLogs(a);
    } catch (err) {
      if (err instanceof SessionExpiredError) onSessionExpired();
      else console.error(err);
    }
  }, [onSessionExpired]);

  useEffect(() => {
    refresh();
  }, [refresh]);

  async function handleInvestigate(user: AdminUserSummary) {
    setInvestigatingUserId(user.id);
    setInvestigationError(null);
    try {
      const result = await api.investigateUser(user.id);
      setInvestigation({ email: user.email, result });
    } catch (err) {
      if (err instanceof SessionExpiredError) {
        onSessionExpired();
        return;
      }
      setInvestigationError(err instanceof Error ? err.message : String(err));
      setInvestigation(null);
    } finally {
      setInvestigatingUserId(null);
    }
  }

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
          <div>
            <div className="card-title">전체 사용자</div>
            <div className="card-subtitle">
              "조사" 버튼을 누르면 AI 에이전트가 가입 정보·구독 활동·참여도를 스스로 조사해 위험도를 보고합니다
              — 계정 조치는 직접 실행하지 않고 결과만 보고합니다.
            </div>
          </div>
        </div>

        {investigationError && <p className="form-error">{investigationError}</p>}

        {investigation && (
          <div className="card" style={{ background: "var(--paper-2)", marginBottom: "1rem" }}>
            <div className="card-header" style={{ justifyContent: "space-between" }}>
              <div style={{ display: "flex", alignItems: "center", gap: "0.6rem" }}>
                <span className="card-title">{investigation.email} 조사 결과</span>
                <RiskBadge riskLevel={investigation.result.riskLevel} />
              </div>
              <button type="button" className="btn btn-ghost btn-sm" onClick={() => setInvestigation(null)}>
                닫기
              </button>
            </div>
            <p>
              <b>근거</b> — {investigation.result.reasoning || "(근거 없음)"}
            </p>
            <p>
              <b>권장 조치</b> — {investigation.result.recommendedAction || "-"}
            </p>
            <p className="ops-note">도구 호출 {investigation.result.toolCallsUsed}회 후 보고됨</p>
          </div>
        )}

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
                  <th>AI 조사</th>
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
                    <td>
                      <button
                        type="button"
                        className="btn btn-outline btn-sm"
                        disabled={investigatingUserId === u.id}
                        onClick={() => handleInvestigate(u)}
                      >
                        {investigatingUserId === u.id ? "조사 중..." : "조사"}
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      </section>

      <section className="card">
        <div className="card-header">
          <span className="card-icon tint-emerald">
            <Icon name="lock" />
          </span>
          <div>
            <div className="card-title">관리자 권한 변경 감사 로그</div>
            <div className="card-subtitle">누가·언제·어떤 이유로 관리자 권한을 받았는지의 전체 이력</div>
          </div>
        </div>
        <div className="table-card">
          <div className="table-scroll">
            <table className="data-table">
              <thead>
                <tr>
                  <th>대상</th>
                  <th>이전 역할</th>
                  <th>이후 역할</th>
                  <th>변경 주체</th>
                  <th>이유</th>
                  <th>시각</th>
                </tr>
              </thead>
              <tbody>
                {auditLogs.length === 0 && (
                  <tr>
                    <td colSpan={6} style={{ textAlign: "center", color: "var(--text-faint)", padding: "2rem 1rem" }}>
                      권한 변경 이력이 없습니다.
                    </td>
                  </tr>
                )}
                {auditLogs.map((log) => (
                  <tr key={log.id}>
                    <td>{log.targetEmail}</td>
                    <td>{log.previousRole}</td>
                    <td>{log.newRole}</td>
                    <td>{log.changedBy}</td>
                    <td>{log.reason}</td>
                    <td>{formatTime(log.createdAt)}</td>
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
                  <th>참여도</th>
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
                      <PersonalizationBadge score={n.personalizationScore} />
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

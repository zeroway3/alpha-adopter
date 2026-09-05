import { useCallback, useEffect, useState, type FormEvent } from "react";
import * as api from "../api/client";
import { SessionExpiredError } from "../api/client";
import type { NotificationHistoryItem, NotificationStatus, Subscription, SubscriptionType } from "../api/types";
import { Icon } from "../icons/Icon";
import { useNotificationStream } from "../hooks/useNotificationStream";

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

// Claude가 판단한 관련도 점수. AI 필터 비활성화/판단 실패 시 null이라 "미판단"으로
// 표시한다 — 0점(무관)과 혼동되지 않게.
function RelevanceBadge({ score }: { score: number | null }) {
  if (score === null) return <span className="badge badge-default">미판단</span>;
  const variant = score >= 70 ? "badge-success" : score >= 50 ? "badge-warning" : "badge-danger";
  return <span className={`badge ${variant}`}>{score}점</span>;
}

interface Props {
  token: string;
  onSessionExpired: () => void;
}

export function UserDashboard({ token, onSessionExpired }: Props) {
  const [subscriptions, setSubscriptions] = useState<Subscription[]>([]);
  const [history, setHistory] = useState<NotificationHistoryItem[]>([]);
  const [keyword, setKeyword] = useState("");
  const [type, setType] = useState<SubscriptionType>("KEYWORD");
  const [subscribeError, setSubscribeError] = useState<string | null>(null);
  const { status: sseStatus, feed } = useNotificationStream(token);

  const withSessionGuard = useCallback(
    async <T,>(fn: () => Promise<T>): Promise<T | undefined> => {
      try {
        return await fn();
      } catch (err) {
        if (err instanceof SessionExpiredError) {
          onSessionExpired();
          return undefined;
        }
        throw err;
      }
    },
    [onSessionExpired],
  );

  const refreshSubscriptions = useCallback(async () => {
    const subs = await withSessionGuard(api.loadSubscriptions);
    if (subs) setSubscriptions(subs);
  }, [withSessionGuard]);

  const refreshHistory = useCallback(async () => {
    const items = await withSessionGuard(api.loadHistory);
    if (items) setHistory(items);
  }, [withSessionGuard]);

  useEffect(() => {
    refreshSubscriptions();
    refreshHistory();
  }, [refreshSubscriptions, refreshHistory]);

  async function handleSubscribe(e: FormEvent) {
    e.preventDefault();
    setSubscribeError(null);
    try {
      await api.createSubscription(keyword, type);
      setKeyword("");
      await refreshSubscriptions();
    } catch (err) {
      setSubscribeError(err instanceof Error ? err.message : String(err));
    }
  }

  async function handleUnsubscribe(id: number) {
    await withSessionGuard(() => api.deleteSubscription(id));
    await refreshSubscriptions();
  }

  const sseLabel =
    sseStatus === "connected" ? "연결됨" : sseStatus === "connecting" ? "연결 중..." : "연결 끊김 (재시도 중...)";

  return (
    <main className="content">
      <section className="card">
        <div className="card-header">
          <span className="card-icon tint-blue">
            <Icon name="search" />
          </span>
          <div>
            <div className="card-title">키워드/종목 구독</div>
            <div className="card-subtitle">관심 있는 키워드를 등록하면 뉴스가 뜰 때 알려드려요</div>
          </div>
        </div>
        <form className="inline-form" onSubmit={handleSubscribe}>
          <div className="field-input-wrap">
            <input
              type="text"
              className="field-input"
              style={{ paddingLeft: "1rem" }}
              placeholder="예: 삼성전자"
              value={keyword}
              onChange={(e) => setKeyword(e.target.value)}
              required
            />
          </div>
          <select className="field-input" value={type} onChange={(e) => setType(e.target.value as SubscriptionType)}>
            <option value="KEYWORD">키워드</option>
            <option value="STOCK_CODE">종목코드</option>
          </select>
          <button type="submit" className="btn btn-primary">
            구독
          </button>
        </form>
        {subscribeError && <p className="form-error">{subscribeError}</p>}
        <ul className="item-list">
          {subscriptions.length === 0 && (
            <li style={{ color: "var(--text-faint)", fontSize: "0.85rem" }}>아직 구독한 키워드가 없습니다.</li>
          )}
          {subscriptions.map((s) => (
            <li key={s.id}>
              <span>
                {s.keyword} <span className="badge badge-default">{s.type}</span>
              </span>
              <button type="button" className="btn btn-ghost btn-sm" style={{ color: "var(--rose-500)" }} onClick={() => handleUnsubscribe(s.id)}>
                해제
              </button>
            </li>
          ))}
        </ul>
      </section>

      <section className="card">
        <div className="card-header">
          <span className="card-icon tint-emerald">
            <Icon name="zap" />
          </span>
          <div>
            <div className="card-title">실시간 알림</div>
            <div className="card-subtitle">
              <span
                className={`status-pill${
                  sseStatus === "connected" ? " status-up" : sseStatus === "disconnected" ? " status-down" : ""
                }`}
              >
                <span className="status-dot-wrap">
                  <span className="status-dot-ping" />
                  <span className="status-dot" />
                </span>
                <span className="status-text">{sseLabel}</span>
              </span>
            </div>
          </div>
        </div>
        <ul className="feed-list">
          {feed.map((item) => (
            <li
              className="feed-item"
              key={`${item.notificationId}-${item.receivedAt}`}
              onClick={() => api.markNotificationRead(item.notificationId)}
            >
              <a href={/^https?:\/\//i.test(item.link) ? item.link : "#"} target="_blank" rel="noopener noreferrer">
                {item.title}
              </a>
              <span className="meta">
                키워드: {item.subscriptionKeyword} · {new Date(item.receivedAt).toLocaleTimeString("ko-KR")}
              </span>
            </li>
          ))}
        </ul>
      </section>

      <section className="card">
        <div className="card-header" style={{ justifyContent: "space-between" }}>
          <div style={{ display: "flex", alignItems: "center", gap: "0.75rem" }}>
            <span className="card-icon tint-violet">
              <Icon name="clock" />
            </span>
            <div className="card-title">알림 히스토리</div>
          </div>
          <button type="button" className="btn btn-outline btn-sm" onClick={refreshHistory}>
            새로고침
          </button>
        </div>
        <div className="table-card">
          <div className="table-scroll">
            <table className="data-table">
              <thead>
                <tr>
                  <th>키워드</th>
                  <th>기사</th>
                  <th>AI 관련도</th>
                  <th>상태</th>
                  <th>수신</th>
                  <th>읽음</th>
                  <th>클릭</th>
                </tr>
              </thead>
              <tbody>
                {history.length === 0 && (
                  <tr>
                    <td colSpan={7} style={{ textAlign: "center", color: "var(--text-faint)", padding: "2rem 1rem" }}>
                      아직 받은 알림이 없습니다.
                    </td>
                  </tr>
                )}
                {history.map((n) => (
                  <tr key={n.id}>
                    <td>{n.keyword}</td>
                    <td>
                      <a href={/^https?:\/\//i.test(n.articleLink) ? n.articleLink : "#"} target="_blank" rel="noopener noreferrer">
                        {n.articleTitle}
                      </a>
                    </td>
                    <td>
                      <RelevanceBadge score={n.relevanceScore} />
                    </td>
                    <td>
                      <StatusBadge status={n.status} />
                    </td>
                    <td>{formatTime(n.createdAt)}</td>
                    <td>{formatTime(n.readAt)}</td>
                    <td>{formatTime(n.clickedAt)}</td>
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

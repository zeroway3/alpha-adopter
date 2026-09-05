import { useState, type FormEvent } from "react";
import { useAuth } from "../auth/AuthContext";
import { Icon } from "../icons/Icon";
import { Logo } from "../components/Logo";

const FEATURES = [
  {
    icon: "newspaper",
    title: "실시간 뉴스 감지",
    desc: "키워드 매칭 + AI 관련도 필터링 기반 자동 수집·알림",
  },
  { icon: "zap", title: "즉시 알림 전송", desc: "접속 중이면 즉시, 아니면 일일 다이제스트" },
  { icon: "barChart", title: "운영 대시보드", desc: "전체 사용자·구독·알림 현황을 한눈에" },
] as const;

type Tab = "login" | "signup";

export function AuthPage() {
  const { login, signup } = useAuth();
  const [tab, setTab] = useState<Tab>("login");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  function switchTab(next: Tab) {
    setTab(next);
    setError(null);
    setEmail("");
    setPassword("");
  }

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      if (tab === "login") {
        await login(email, password);
      } else {
        await signup(email, password);
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <section id="auth-view">
      <div className="auth-decor">
        <div className="auth-decor-content">
          <Logo size={52} />
          <h1>
            AlphaAdopter
            <br />
            <span style={{ opacity: 0.75 }}>뉴스 알림 플랫폼</span>
          </h1>
          <p className="tagline">관심 키워드·종목 뉴스를 실시간으로 감지해 가장 먼저 알려드립니다.</p>
          <div className="auth-feature-list">
            {FEATURES.map((f) => (
              <div className="auth-feature" key={f.title}>
                <span className="auth-feature-icon">
                  <Icon name={f.icon} size={18} />
                </span>
                <div>
                  <div className="title">{f.title}</div>
                  <div className="desc">{f.desc}</div>
                </div>
              </div>
            ))}
          </div>
        </div>
      </div>

      <div className="auth-form-side">
        <div className="auth-card">
          <div className="auth-mobile-logo">
            <Logo size={40} />
            <h1>AlphaAdopter</h1>
          </div>

          <div className="auth-tabs">
            <button
              type="button"
              className={`auth-tab${tab === "login" ? " active" : ""}`}
              onClick={() => switchTab("login")}
            >
              로그인
            </button>
            <button
              type="button"
              className={`auth-tab${tab === "signup" ? " active" : ""}`}
              onClick={() => switchTab("signup")}
            >
              회원가입
            </button>
          </div>

          {error && <p className="auth-error">{error}</p>}

          <form onSubmit={handleSubmit}>
            <div className="field-group">
              <label className="field-label">이메일</label>
              <div className="field-input-wrap">
                <span className="icon">
                  <Icon name="mail" size={16} />
                </span>
                <input
                  type="email"
                  className="field-input"
                  placeholder="you@example.com"
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  required
                />
              </div>
            </div>
            <div className="field-group">
              <label className="field-label">비밀번호</label>
              <div className="field-input-wrap">
                <span className="icon">
                  <Icon name="lock" size={16} />
                </span>
                <input
                  type="password"
                  className="field-input"
                  placeholder={tab === "signup" ? "8자 이상" : "비밀번호"}
                  minLength={tab === "signup" ? 8 : undefined}
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  required
                />
              </div>
            </div>
            <button type="submit" className="btn btn-primary btn-full" disabled={submitting}>
              {tab === "login" ? "로그인" : "회원가입"}
            </button>
          </form>
        </div>
      </div>
    </section>
  );
}

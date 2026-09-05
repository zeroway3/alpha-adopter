// 이메일+비밀번호 인증(JWT). 서버가 내려준 토큰을 localStorage에 저장하고,
// 이후 모든 API 호출에 Authorization: Bearer 헤더로 실어 보낸다.

const SESSION_KEY = "alphaadopter_session";

function getSession() {
  try {
    return JSON.parse(localStorage.getItem(SESSION_KEY) || "null");
  } catch {
    return null;
  }
}

function setSession(session) {
  localStorage.setItem(SESSION_KEY, JSON.stringify(session));
}

function clearSession() {
  localStorage.removeItem(SESSION_KEY);
}

let eventSource = null;
let adminClockTimer = null;
let serverStatusTimer = null;

function formatTime(iso) {
  if (!iso) return "-";
  return new Date(iso).toLocaleString("ko-KR");
}

// 구독 키워드·뉴스 제목 등은 사용자 입력/외부 API 값이라 innerHTML로 직접 꽂으면 XSS가
// 가능하다. textContent만 쓰는 DOM 생성 헬퍼로 항상 이스케이프되게 한다.
function el(tag, { text, className, children } = {}) {
  const node = document.createElement(tag);
  if (text !== undefined) node.textContent = text;
  if (className) node.className = className;
  (children || []).forEach((c) => node.appendChild(c));
  return node;
}

// 이모지 대신 쓰는 라인 아이콘 세트. 여기 들어가는 문자열은 전부 고정된 상수(외부/사용자
// 입력 없음)라 innerHTML로 꽂아도 XSS 위험이 없다 — el()의 textContent 원칙과는 별개.
const ICON_PATHS = {
  search: '<circle cx="10.5" cy="10.5" r="6.5"/><line x1="15.5" y1="15.5" x2="21" y2="21"/>',
  zap: '<path d="M13 2 4 14h6l-1 8 9-12h-6l1-8z" fill="currentColor" stroke="none"/>',
  clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7.5v5l3.5 2"/>',
  trendingUp: '<polyline points="3 16 9 10 13 14 21 5"/><polyline points="14 5 21 5 21 12"/>',
  tag: '<path d="M11.5 3H5a2 2 0 0 0-2 2v6.5L13.5 22 21 14.5 11.5 3z"/><circle cx="8" cy="8" r="1.4" fill="currentColor" stroke="none"/>',
  users: '<circle cx="9" cy="8" r="3.2"/><path d="M3.5 20c0-3.3 2.5-6 5.5-6s5.5 2.7 5.5 6"/><circle cx="17" cy="9.5" r="2.3"/><path d="M15.5 14.2c2.5.5 4.5 2.7 4.5 5.8"/>',
  bell: '<path d="M6 9a6 6 0 0 1 12 0c0 5 2 6.5 2 6.5H4S6 14 6 9z"/><path d="M10 19a2 2 0 0 0 4 0"/>',
  newspaper: '<rect x="4" y="3" width="16" height="18" rx="1.5"/><line x1="7.5" y1="8" x2="16.5" y2="8"/><line x1="7.5" y1="12" x2="16.5" y2="12"/><line x1="7.5" y1="16" x2="13" y2="16"/>',
  barChart: '<line x1="6" y1="20" x2="6" y2="13"/><line x1="12" y1="20" x2="12" y2="5"/><line x1="18" y1="20" x2="18" y2="16"/>',
  checkCircle: '<circle cx="12" cy="12" r="9"/><path d="M8 12.3l2.6 2.6 5-5.6"/>',
  send: '<path d="M21 3 10.5 13.5"/><path d="M21 3 14 21l-3.5-8L3 9z"/>',
  alertTriangle: '<path d="M12 3 2.5 20h19z"/><line x1="12" y1="9.5" x2="12" y2="14.5"/><circle cx="12" cy="17.3" r="0.9" fill="currentColor" stroke="none"/>',
  cpu: '<rect x="6" y="6" width="12" height="12" rx="1.5"/><rect x="10" y="10" width="4" height="4"/><line x1="12" y1="2" x2="12" y2="6"/><line x1="12" y1="18" x2="12" y2="22"/><line x1="2" y1="12" x2="6" y2="12"/><line x1="18" y1="12" x2="22" y2="12"/>',
  home: '<path d="M4 11 12 4l8 7"/><path d="M6 10v10h5v-6h2v6h5V10"/>',
  grid: '<rect x="4" y="4" width="7" height="7" rx="1.2"/><rect x="13" y="4" width="7" height="7" rx="1.2"/><rect x="4" y="13" width="7" height="7" rx="1.2"/><rect x="13" y="13" width="7" height="7" rx="1.2"/>',
  mail: '<rect x="3" y="5" width="18" height="14" rx="1.5"/><path d="M4 7l8 6 8-6"/>',
  lock: '<rect x="5" y="11" width="14" height="9" rx="1.5"/><path d="M8 11V8a4 4 0 0 1 8 0v3"/>',
};

function iconSvg(name, size = 18) {
  const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  svg.setAttribute("viewBox", "0 0 24 24");
  svg.setAttribute("width", String(size));
  svg.setAttribute("height", String(size));
  svg.setAttribute("fill", "none");
  svg.setAttribute("stroke", "currentColor");
  svg.setAttribute("stroke-width", "1.8");
  svg.setAttribute("stroke-linecap", "round");
  svg.setAttribute("stroke-linejoin", "round");
  svg.innerHTML = ICON_PATHS[name] || "";
  return svg;
}

// index.html에 미리 박아둔 <span data-icon="...">에 실제 svg를 채워 넣는다
function applyStaticIcons() {
  document.querySelectorAll("[data-icon]").forEach((node) => {
    node.appendChild(iconSvg(node.dataset.icon, node.classList.contains("auth-feature-icon") ? 18 : 16));
  });
}

function linkEl(href, text) {
  const a = el("a", { text });
  a.href = /^https?:\/\//i.test(href) ? href : "#";
  a.target = "_blank";
  a.rel = "noopener";
  return a;
}

function statusTag(status) {
  const map = { MATCHED: "badge-primary", SENT: "badge-success", FAILED: "badge-danger" };
  return el("span", { className: "badge " + (map[status] || "badge-default"), text: status });
}

// Claude가 판단한 관련도 점수. AI 필터가 비활성화됐거나(키 없음) 판단 실패 시 null이라
// "판단 안 함"으로 표시한다 — 0점(무관)과 혼동되지 않게
function relevanceBadge(score) {
  if (score === null || score === undefined) {
    return el("span", { className: "badge badge-default", text: "미판단" });
  }
  const variant = score >= 70 ? "badge-success" : score >= 50 ? "badge-warning" : "badge-danger";
  return el("span", { className: "badge " + variant, text: score + "점" });
}

async function authFetch(path, options = {}) {
  const session = getSession();
  const headers = Object.assign({}, options.headers || {});
  if (session) headers["Authorization"] = "Bearer " + session.token;

  const res = await fetch(path, { ...options, headers });
  if (res.status === 401) {
    clearSession();
    showAuth();
    throw new Error("세션이 만료되었습니다. 다시 로그인해주세요.");
  }
  return res;
}

async function signup(email, password) {
  const res = await fetch("/api/auth/signup", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, password }),
  });
  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    throw new Error(body.detail || body.message || "회원가입 실패 (" + res.status + ")");
  }
  return res.json();
}

async function login(email, password) {
  const res = await fetch("/api/auth/login", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, password }),
  });
  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    throw new Error(body.detail || body.message || "로그인 실패 (" + res.status + ")");
  }
  return res.json();
}

async function loadSubscriptions() {
  return (await authFetch("/api/subscriptions")).json();
}

async function createSubscription(keyword, type) {
  const res = await authFetch("/api/subscriptions", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ keyword, type }),
  });
  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    throw new Error(body.detail || body.message || "구독 실패 (" + res.status + ")");
  }
  return res.json();
}

async function deleteSubscription(id) {
  const res = await authFetch("/api/subscriptions/" + id, { method: "DELETE" });
  if (!res.ok && res.status !== 204) throw new Error("구독 해제 실패");
}

async function loadHistory() {
  return (await authFetch("/api/notifications")).json();
}

async function loadAdminStats() {
  const res = await authFetch("/api/admin/stats");
  if (!res.ok) throw new Error("관리자 통계 조회 실패 (" + res.status + ")");
  return res.json();
}

async function loadAdminUsers() {
  return (await authFetch("/api/admin/users")).json();
}

async function loadAdminKeywords() {
  return (await authFetch("/api/admin/keywords")).json();
}

async function loadAdminDaily() {
  return (await authFetch("/api/admin/stats/daily")).json();
}

/* ---------------- 렌더링 ---------------- */

function renderSubscriptions(subs) {
  const list = document.getElementById("subscription-list");
  list.innerHTML = "";
  if (subs.length === 0) {
    const empty = el("li", { text: "아직 구독한 키워드가 없습니다." });
    empty.style.color = "var(--gray-400)";
    empty.style.fontSize = "0.85rem";
    list.appendChild(empty);
    return;
  }
  subs.forEach((s) => {
    const removeBtn = el("button", { className: "btn btn-ghost btn-sm", text: "해제" });
    removeBtn.style.color = "var(--rose-600)";
    removeBtn.addEventListener("click", async () => {
      await deleteSubscription(s.id);
      renderSubscriptions(await loadSubscriptions());
    });
    const keywordSpan = el("span", { text: s.keyword + " " });
    keywordSpan.appendChild(el("span", { className: "badge badge-default", text: s.type }));
    list.appendChild(el("li", { children: [keywordSpan, removeBtn] }));
  });
}

function renderHistory(items) {
  const body = document.getElementById("history-body");
  body.innerHTML = "";
  if (items.length === 0) {
    const td = el("td", { text: "아직 받은 알림이 없습니다." });
    td.colSpan = 6;
    td.style.textAlign = "center";
    td.style.color = "var(--gray-400)";
    td.style.padding = "2rem 1rem";
    body.appendChild(el("tr", { children: [td] }));
    return;
  }
  items.forEach((n) => {
    body.appendChild(
      el("tr", {
        children: [
          el("td", { text: n.keyword }),
          el("td", { children: [linkEl(n.articleLink, n.articleTitle)] }),
          el("td", { children: [relevanceBadge(n.relevanceScore)] }),
          el("td", { children: [statusTag(n.status)] }),
          el("td", { text: formatTime(n.createdAt) }),
          el("td", { text: formatTime(n.readAt) }),
          el("td", { text: formatTime(n.clickedAt) }),
        ],
      }),
    );
  });
}

function prependLiveNotification(matched) {
  const feed = document.getElementById("live-feed");
  const meta = el("span", { className: "meta", text: "키워드: " + matched.subscriptionKeyword + " · " + new Date().toLocaleTimeString("ko-KR") });
  const li = el("li", { className: "feed-item", children: [linkEl(matched.link, matched.title), meta] });
  li.addEventListener("click", () => authFetch("/api/notifications/" + matched.notificationId + "/read", { method: "POST" }), { once: true });
  feed.prepend(li);
}

function setStatusPill(pillEl, up, upLabel, downLabel) {
  pillEl.classList.toggle("status-up", up);
  pillEl.classList.toggle("status-down", !up);
  pillEl.querySelector(".status-text").textContent = up ? upLabel : downLabel;
}

function connectSse(token) {
  if (eventSource) eventSource.close();
  const statusEl = document.getElementById("sse-status");
  eventSource = new EventSource("/api/notifications/stream?token=" + encodeURIComponent(token));
  eventSource.onopen = () => setStatusPill(statusEl, true, "연결됨", "");
  eventSource.onerror = () => setStatusPill(statusEl, false, "", "연결 끊김 (재시도 중...)");
  eventSource.addEventListener("news-matched", (event) => prependLiveNotification(JSON.parse(event.data)));
}

function renderStatGrid(stats) {
  const grid = document.getElementById("stat-grid");
  const boxes = [
    ["users", "blue", "전체 사용자", stats.totalUsers],
    ["search", "violet", "전체 구독", stats.totalSubscriptions],
    ["newspaper", "cyan", "수집된 뉴스", stats.totalNewsArticles],
    ["checkCircle", "emerald", "매칭됨", stats.notificationsMatched],
    ["send", "amber", "전송됨", stats.notificationsSent],
    ["alertTriangle", "rose", "실패", stats.notificationsFailed],
    [
      "cpu",
      stats.aiFilterEnabled ? "emerald" : "rose",
      "AI 필터",
      stats.aiFilterEnabled ? "ON" : "OFF",
    ],
    [
      "barChart",
      "cyan",
      "평균 관련도",
      stats.averageRelevanceScore != null ? Math.round(stats.averageRelevanceScore) + "점" : "-",
    ],
  ];
  grid.innerHTML = "";
  boxes.forEach(([icon, color, label, value]) => {
    const iconBox = el("span", { className: "stat-icon-box icon-" + color });
    iconBox.appendChild(iconSvg(icon, 22));
    grid.appendChild(
      el("div", {
        className: "stat-card",
        children: [
          iconBox,
          el("div", { children: [el("div", { className: "stat-value", text: String(value) }), el("div", { className: "stat-label", text: label })] }),
        ],
      }),
    );
  });
}

function renderDailyChart(days) {
  const wrap = document.getElementById("daily-chart");
  wrap.innerHTML = "";
  if (days.length === 0) {
    const p = el("p", { text: "최근 7일간 데이터가 없습니다." });
    p.style.color = "var(--gray-400)";
    p.style.fontSize = "0.85rem";
    wrap.appendChild(p);
    return;
  }
  const max = Math.max(...days.map((d) => d.total), 1);
  days.forEach((d) => {
    const heightPct = Math.max((d.total / max) * 100, 4);
    const bar = el("div", { className: "chart-bar" });
    bar.style.height = heightPct + "%";
    const label = new Date(d.day).toLocaleDateString("ko-KR", { month: "numeric", day: "numeric" });
    wrap.appendChild(
      el("div", {
        className: "chart-bar-col",
        children: [el("span", { className: "chart-bar-value", text: String(d.total) }), bar, el("span", { className: "chart-bar-label", text: label })],
      }),
    );
  });
}

function renderKeywords(keywords) {
  const body = document.getElementById("keywords-body");
  body.innerHTML = "";
  if (keywords.length === 0) {
    const td = el("td", { text: "데이터가 없습니다." });
    td.colSpan = 2;
    td.style.textAlign = "center";
    td.style.color = "var(--gray-400)";
    td.style.padding = "2rem 1rem";
    body.appendChild(el("tr", { children: [td] }));
    return;
  }
  keywords.forEach((k) => {
    body.appendChild(el("tr", { children: [el("td", { text: k.keyword }), el("td", { text: String(k.subscriberCount) })] }));
  });
}

function renderUsers(users) {
  const body = document.getElementById("users-body");
  body.innerHTML = "";
  users.forEach((u) => {
    body.appendChild(
      el("tr", {
        children: [
          el("td", { text: u.email }),
          el("td", { text: u.isMember ? "O" : "-" }),
          el("td", { text: u.isAdmin ? "O" : "-" }),
          el("td", { text: String(u.subscriptionCount) }),
          el("td", { text: formatTime(u.createdAt) }),
        ],
      }),
    );
  });
}

function renderAdminRecent(items) {
  const body = document.getElementById("admin-recent-body");
  body.innerHTML = "";
  items.forEach((n) => {
    body.appendChild(
      el("tr", {
        children: [
          el("td", { text: n.userEmail }),
          el("td", { text: n.keyword }),
          el("td", { text: n.articleTitle }),
          el("td", { children: [relevanceBadge(n.relevanceScore)] }),
          el("td", { children: [statusTag(n.status)] }),
          el("td", { text: formatTime(n.createdAt) }),
        ],
      }),
    );
  });
}

async function loadAdminView() {
  const [stats, users, keywords, daily] = await Promise.all([
    loadAdminStats(),
    loadAdminUsers(),
    loadAdminKeywords(),
    loadAdminDaily(),
  ]);
  renderStatGrid(stats);
  renderAdminRecent(stats.recentNotifications);
  renderUsers(users);
  renderKeywords(keywords);
  renderDailyChart(daily);
}

function startAdminClock() {
  const clockEl = document.getElementById("admin-clock");
  const tick = () => (clockEl.textContent = new Date().toLocaleString("ko-KR"));
  tick();
  adminClockTimer = setInterval(tick, 1000);
}

function startServerStatusPoll() {
  const statusEl = document.getElementById("server-status");
  const check = async () => {
    try {
      const res = await fetch("/actuator/health");
      setStatusPill(statusEl, res.ok, "정상", "오류");
    } catch {
      setStatusPill(statusEl, false, "", "오류");
    }
  };
  check();
  serverStatusTimer = setInterval(check, 15000);
}

function stopAdminTimers() {
  if (adminClockTimer) clearInterval(adminClockTimer);
  if (serverStatusTimer) clearInterval(serverStatusTimer);
}

function switchView(view) {
  const isAdmin = view === "admin";
  document.getElementById("user-content").hidden = isAdmin;
  document.getElementById("admin-content").hidden = !isAdmin;
  document.querySelectorAll("#view-toggle button").forEach((btn) => {
    btn.classList.toggle("active", btn.dataset.view === view);
  });
  if (isAdmin) {
    startAdminClock();
    startServerStatusPoll();
    loadAdminView().catch((err) => console.error(err));
  } else {
    stopAdminTimers();
  }
}

async function showApp(session) {
  document.getElementById("auth-view").hidden = true;
  document.getElementById("app-view").hidden = false;
  document.getElementById("user-email").textContent = session.email;
  document.getElementById("user-avatar").textContent = session.email.charAt(0).toUpperCase();
  document.getElementById("admin-badge").hidden = !session.isAdmin;
  document.getElementById("member-role-label").hidden = session.isAdmin;
  document.getElementById("view-toggle").hidden = !session.isAdmin;
  document.getElementById("nav-menu-label").hidden = !session.isAdmin;

  renderSubscriptions(await loadSubscriptions());
  renderHistory(await loadHistory());
  connectSse(session.token);
  switchView("user");
}

function showAuth() {
  stopAdminTimers();
  document.getElementById("auth-view").hidden = false;
  document.getElementById("app-view").hidden = true;
  if (eventSource) {
    eventSource.close();
    eventSource = null;
  }
}

/* ---------------- 이벤트 바인딩 ---------------- */

document.querySelectorAll(".auth-tab").forEach((tab) => {
  tab.addEventListener("click", () => {
    document.querySelectorAll(".auth-tab").forEach((t) => t.classList.remove("active"));
    tab.classList.add("active");
    const isSignup = tab.dataset.tab === "signup";
    document.getElementById("login-form").hidden = isSignup;
    document.getElementById("signup-form").hidden = !isSignup;
    document.getElementById("auth-error").hidden = true;
  });
});

function showAuthError(message) {
  const el = document.getElementById("auth-error");
  el.textContent = message;
  el.hidden = false;
}

document.getElementById("login-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  document.getElementById("auth-error").hidden = true;
  try {
    const session = await login(
      document.getElementById("login-email").value.trim(),
      document.getElementById("login-password").value,
    );
    setSession(session);
    await showApp(session);
  } catch (err) {
    showAuthError(err.message);
  }
});

document.getElementById("signup-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  document.getElementById("auth-error").hidden = true;
  try {
    const session = await signup(
      document.getElementById("signup-email").value.trim(),
      document.getElementById("signup-password").value,
    );
    setSession(session);
    await showApp(session);
  } catch (err) {
    showAuthError(err.message);
  }
});

document.getElementById("logout-btn").addEventListener("click", () => {
  clearSession();
  showAuth();
});

document.getElementById("subscribe-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  const keyword = document.getElementById("subscribe-keyword").value.trim();
  const type = document.getElementById("subscribe-type").value;
  const errorEl = document.getElementById("subscribe-error");
  errorEl.hidden = true;
  try {
    await createSubscription(keyword, type);
    document.getElementById("subscribe-keyword").value = "";
    renderSubscriptions(await loadSubscriptions());
  } catch (err) {
    errorEl.textContent = err.message;
    errorEl.hidden = false;
  }
});

document.getElementById("refresh-history-btn").addEventListener("click", async () => {
  renderHistory(await loadHistory());
});

document.getElementById("refresh-admin-btn").addEventListener("click", () => loadAdminView().catch((err) => console.error(err)));

document.querySelectorAll("#view-toggle button").forEach((btn) => {
  btn.addEventListener("click", () => switchView(btn.dataset.view));
});

(async function init() {
  applyStaticIcons();
  const session = getSession();
  if (session) {
    try {
      await showApp(session);
      return;
    } catch {
      clearSession();
    }
  }
  showAuth();
})();

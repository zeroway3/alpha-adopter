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

function linkEl(href, text) {
  const a = el("a", { text });
  a.href = /^https?:\/\//i.test(href) ? href : "#";
  a.target = "_blank";
  a.rel = "noopener";
  return a;
}

function statusTag(status) {
  const map = { MATCHED: "matched", SENT: "sent", FAILED: "failed" };
  return el("span", { className: "tag-pill " + (map[status] || ""), text: status });
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
    list.appendChild(el("li", { className: "empty-note", text: "아직 구독한 키워드가 없습니다." }));
    return;
  }
  subs.forEach((s) => {
    const removeBtn = el("button", { className: "remove-btn", text: "해제" });
    removeBtn.addEventListener("click", async () => {
      await deleteSubscription(s.id);
      renderSubscriptions(await loadSubscriptions());
    });
    list.appendChild(
      el("li", { children: [el("span", { text: s.keyword + " (" + s.type + ")" }), removeBtn] }),
    );
  });
}

function renderHistory(items) {
  const body = document.getElementById("history-body");
  body.innerHTML = "";
  if (items.length === 0) {
    body.appendChild(el("tr", { children: [el("td", { text: "아직 받은 알림이 없습니다.", className: "empty-note" })] }));
    return;
  }
  items.forEach((n) => {
    body.appendChild(
      el("tr", {
        children: [
          el("td", { text: n.keyword }),
          el("td", { children: [linkEl(n.articleLink, n.articleTitle)] }),
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

function connectSse(token) {
  if (eventSource) eventSource.close();
  const statusEl = document.getElementById("sse-status");
  const dot = statusEl.querySelector(".status-dot");
  eventSource = new EventSource("/api/notifications/stream?token=" + encodeURIComponent(token));
  eventSource.onopen = () => {
    statusEl.lastChild.textContent = "연결됨";
    dot.className = "status-dot up";
  };
  eventSource.onerror = () => {
    statusEl.lastChild.textContent = "연결 끊김 (재시도 중...)";
    dot.className = "status-dot down";
  };
  eventSource.addEventListener("news-matched", (event) => prependLiveNotification(JSON.parse(event.data)));
}

function renderStatGrid(stats) {
  const grid = document.getElementById("stat-grid");
  const boxes = [
    ["👥", "accent-blue", "전체 사용자", stats.totalUsers],
    ["🔎", "accent-violet", "전체 구독", stats.totalSubscriptions],
    ["📰", "accent-cyan", "수집된 뉴스", stats.totalNewsArticles],
    ["✅", "accent-emerald", "매칭됨", stats.notificationsMatched],
    ["📤", "accent-amber", "전송됨", stats.notificationsSent],
    ["⚠️", "accent-rose", "실패", stats.notificationsFailed],
  ];
  grid.innerHTML = "";
  boxes.forEach(([icon, accent, label, value]) => {
    grid.appendChild(
      el("div", {
        className: "stat-box",
        children: [
          el("span", { className: "icon-badge " + accent, text: icon }),
          el("div", { children: [el("div", { className: "value", text: String(value) }), el("div", { className: "label", text: label })] }),
        ],
      }),
    );
  });
}

function renderDailyChart(days) {
  const wrap = document.getElementById("daily-chart");
  wrap.innerHTML = "";
  if (days.length === 0) {
    wrap.appendChild(el("p", { className: "empty-note", text: "최근 7일간 데이터가 없습니다." }));
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
    body.appendChild(el("tr", { children: [el("td", { text: "데이터가 없습니다.", className: "empty-note" })] }));
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
  const dot = statusEl.querySelector(".status-dot");
  const check = async () => {
    try {
      const res = await fetch("/actuator/health");
      const ok = res.ok;
      dot.className = "status-dot " + (ok ? "up" : "down");
      statusEl.lastChild.textContent = ok ? "정상" : "오류";
    } catch {
      dot.className = "status-dot down";
      statusEl.lastChild.textContent = "오류";
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
  document.getElementById("admin-badge").hidden = !session.isAdmin;
  document.getElementById("view-toggle").hidden = !session.isAdmin;

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

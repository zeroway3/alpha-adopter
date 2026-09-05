// 인증 시스템이 없는 데모 단계라, "로그인"은 이메일을 서버에 등록/회원 전환하고
// 그 결과(userId, isAdmin)를 localStorage에 저장하는 것으로 대신한다.

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
  // href도 사용자가 통제하지 못하는 값(NAVER API 기사 링크)이지만, 혹시 모를
  // javascript: 스킴 주입을 막기 위해 http(s)만 허용한다.
  a.href = /^https?:\/\//i.test(href) ? href : "#";
  a.target = "_blank";
  a.rel = "noopener";
  return a;
}

async function login(email) {
  const res = await fetch("/api/users/membership", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email }),
  });
  if (!res.ok) throw new Error("로그인 실패 (" + res.status + ")");
  return res.json();
}

async function loadSubscriptions(email) {
  const res = await fetch("/api/subscriptions?email=" + encodeURIComponent(email));
  if (!res.ok) throw new Error("구독 목록 조회 실패");
  return res.json();
}

async function createSubscription(email, keyword, type) {
  const res = await fetch("/api/subscriptions", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, keyword, type }),
  });
  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    throw new Error(body.detail || body.message || "구독 실패 (" + res.status + ")");
  }
  return res.json();
}

async function deleteSubscription(id, email) {
  const res = await fetch("/api/subscriptions/" + id + "?email=" + encodeURIComponent(email), {
    method: "DELETE",
  });
  if (!res.ok && res.status !== 204) throw new Error("구독 해제 실패");
}

async function loadHistory(email) {
  const res = await fetch("/api/notifications?email=" + encodeURIComponent(email));
  if (!res.ok) throw new Error("알림 히스토리 조회 실패");
  return res.json();
}

async function loadAdminStats(email) {
  const res = await fetch("/api/admin/stats?email=" + encodeURIComponent(email));
  if (!res.ok) throw new Error("관리자 통계 조회 실패 (" + res.status + ")");
  return res.json();
}

function renderSubscriptions(subs, email) {
  const list = document.getElementById("subscription-list");
  list.innerHTML = "";
  if (subs.length === 0) {
    list.innerHTML = '<li class="muted">아직 구독한 키워드가 없습니다.</li>';
    return;
  }
  subs.forEach((s) => {
    const deleteBtn = el("button", { text: "해제" });
    deleteBtn.addEventListener("click", async () => {
      await deleteSubscription(s.id, email);
      const fresh = await loadSubscriptions(email);
      renderSubscriptions(fresh, email);
    });
    const li = el("li", {
      children: [el("span", { text: s.keyword + " (" + s.type + ")" }), deleteBtn],
    });
    list.appendChild(li);
  });
}

function renderHistory(items) {
  const body = document.getElementById("history-body");
  body.innerHTML = "";
  if (items.length === 0) {
    body.innerHTML = '<tr><td colspan="6" class="muted">아직 받은 알림이 없습니다.</td></tr>';
    return;
  }
  items.forEach((n) => {
    const tr = el("tr", {
      children: [
        el("td", { text: n.keyword }),
        el("td", { children: [linkEl(n.articleLink, n.articleTitle)] }),
        el("td", { text: n.status }),
        el("td", { text: formatTime(n.createdAt) }),
        el("td", { text: formatTime(n.readAt) }),
        el("td", { text: formatTime(n.clickedAt) }),
      ],
    });
    body.appendChild(tr);
  });
}

function renderAdminStats(stats) {
  const grid = document.getElementById("stats-grid");
  const boxes = [
    ["전체 사용자", stats.totalUsers],
    ["전체 구독", stats.totalSubscriptions],
    ["수집된 뉴스", stats.totalNewsArticles],
    ["매칭됨", stats.notificationsMatched],
    ["전송됨", stats.notificationsSent],
    ["실패", stats.notificationsFailed],
    ["읽음", stats.notificationsRead],
    ["클릭됨", stats.notificationsClicked],
  ];
  grid.innerHTML = "";
  boxes.forEach(([label, value]) => {
    grid.appendChild(
      el("div", {
        className: "stat-box",
        children: [el("div", { className: "value", text: String(value) }), el("div", { className: "label", text: label })],
      }),
    );
  });

  const body = document.getElementById("admin-recent-body");
  body.innerHTML = "";
  stats.recentNotifications.forEach((n) => {
    const tr = el("tr", {
      children: [
        el("td", { text: n.userEmail }),
        el("td", { text: n.keyword }),
        el("td", { text: n.articleTitle }),
        el("td", { text: n.status }),
        el("td", { text: formatTime(n.createdAt) }),
      ],
    });
    body.appendChild(tr);
  });
}

function prependLiveNotification(matched) {
  const feed = document.getElementById("live-feed");
  const meta = el("span", {
    className: "muted",
    text: "키워드: " + matched.subscriptionKeyword + " · " + new Date().toLocaleTimeString("ko-KR"),
  });
  const li = el("li", { children: [linkEl(matched.link, matched.title), meta] });
  li.addEventListener(
    "click",
    () => fetch("/api/notifications/" + matched.notificationId + "/read", { method: "POST" }),
    { once: true },
  );
  feed.prepend(li);
}

function connectSse(userId) {
  if (eventSource) eventSource.close();
  const statusEl = document.getElementById("sse-status");
  eventSource = new EventSource("/api/notifications/stream/" + userId);
  eventSource.onopen = () => {
    statusEl.textContent = "연결됨";
    statusEl.className = "status up";
  };
  eventSource.onerror = () => {
    statusEl.textContent = "연결 끊김 (재시도 중...)";
    statusEl.className = "status down";
  };
  eventSource.addEventListener("news-matched", (event) => {
    prependLiveNotification(JSON.parse(event.data));
  });
}

async function showApp(session) {
  document.getElementById("login-view").hidden = true;
  document.getElementById("app-view").hidden = false;
  document.getElementById("user-info").hidden = false;
  document.getElementById("user-email").textContent = session.email;
  document.getElementById("admin-badge").hidden = !session.isAdmin;
  document.getElementById("admin-section").hidden = !session.isAdmin;

  renderSubscriptions(await loadSubscriptions(session.email), session.email);
  renderHistory(await loadHistory(session.email));
  connectSse(session.id);

  if (session.isAdmin) {
    renderAdminStats(await loadAdminStats(session.email));
  }
}

function showLogin() {
  document.getElementById("login-view").hidden = false;
  document.getElementById("app-view").hidden = true;
  document.getElementById("user-info").hidden = true;
  if (eventSource) {
    eventSource.close();
    eventSource = null;
  }
}

document.getElementById("login-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  const email = document.getElementById("login-email").value.trim();
  const errorEl = document.getElementById("login-error");
  errorEl.hidden = true;
  try {
    const session = await login(email);
    setSession(session);
    await showApp(session);
  } catch (err) {
    errorEl.textContent = err.message;
    errorEl.hidden = false;
  }
});

document.getElementById("logout-btn").addEventListener("click", () => {
  clearSession();
  showLogin();
});

document.getElementById("subscribe-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  const session = getSession();
  const keyword = document.getElementById("subscribe-keyword").value.trim();
  const type = document.getElementById("subscribe-type").value;
  const errorEl = document.getElementById("subscribe-error");
  errorEl.hidden = true;
  try {
    await createSubscription(session.email, keyword, type);
    document.getElementById("subscribe-keyword").value = "";
    renderSubscriptions(await loadSubscriptions(session.email), session.email);
  } catch (err) {
    errorEl.textContent = err.message;
    errorEl.hidden = false;
  }
});

document.getElementById("refresh-history-btn").addEventListener("click", async () => {
  const session = getSession();
  renderHistory(await loadHistory(session.email));
});

document.getElementById("refresh-stats-btn").addEventListener("click", async () => {
  const session = getSession();
  renderAdminStats(await loadAdminStats(session.email));
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
  showLogin();
})();

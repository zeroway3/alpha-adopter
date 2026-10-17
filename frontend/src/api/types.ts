// AdminStatsController / AuthController / SubscriptionController / NotificationHistoryController의
// 실제 응답 DTO(core-service 커틀린 코드)와 필드를 맞춘 타입들.

// 토큰은 더 이상 응답 본문에 없다 — httpOnly 쿠키로만 전달되고 JS는 값을 읽지 않는다.
export interface Session {
  id: number;
  email: string;
  isMember: boolean;
  isAdmin: boolean;
}

export type SubscriptionType = "KEYWORD" | "STOCK_CODE";

export interface Subscription {
  id: number;
  keyword: string;
  type: SubscriptionType;
}

export type NotificationStatus = "MATCHED" | "SENT" | "FAILED";

export interface NotificationHistoryItem {
  id: number;
  keyword: string;
  articleTitle: string;
  articleLink: string;
  status: NotificationStatus;
  createdAt: string;
  sentAt: string | null;
  readAt: string | null;
  clickedAt: string | null;
  relevanceScore: number | null;
  personalizationScore: number | null;
}

// 커서 기반 페이지네이션 응답. nextCursor가 null이면 마지막 페이지.
export interface NotificationHistoryPage {
  items: NotificationHistoryItem[];
  nextCursor: string | null;
  hasMore: boolean;
}

export interface AdminNotificationSummary {
  id: number;
  userEmail: string;
  keyword: string;
  articleTitle: string;
  status: NotificationStatus;
  createdAt: string;
  readAt: string | null;
  clickedAt: string | null;
  relevanceScore: number | null;
  personalizationScore: number | null;
}

export interface AdminStatsResponse {
  totalUsers: number;
  totalSubscriptions: number;
  totalNewsArticles: number;
  notificationsMatched: number;
  notificationsSent: number;
  notificationsFailed: number;
  notificationsRead: number;
  notificationsClicked: number;
  aiFilterEnabled: boolean;
  notificationsAiScored: number;
  averageRelevanceScore: number | null;
  recentNotifications: AdminNotificationSummary[];
}

export interface AdminUserSummary {
  id: number;
  email: string;
  isMember: boolean;
  isAdmin: boolean;
  subscriptionCount: number;
  createdAt: string;
}

export interface AdminKeywordSummary {
  keyword: string;
  subscriberCount: number;
}

export interface AdminDailyCount {
  day: string;
  total: number;
}

// news.matched를 릴레이하는 SSE 이벤트(NotificationPublisher가 발행하는 페이로드)
export interface MatchedNotificationEvent {
  notificationId: number;
  subscriptionKeyword: string;
  title: string;
  link: string;
  personalizationScore: number | null;
}

// UserInvestigationAgent가 report_verdict 도구 호출로 보고하는 결론. INCONCLUSIVE는 API 키
// 없음/최대 반복 초과/도구만 반복 호출 등 여러 fail-open 경로가 전부 수렴하는 값이다.
export type RiskLevel = "LOW" | "MEDIUM" | "HIGH" | "INCONCLUSIVE";

export interface InvestigationResponse {
  riskLevel: RiskLevel;
  reasoning: string;
  recommendedAction: string;
  toolCallsUsed: number;
}

export interface AdminAuditLogSummary {
  id: number;
  targetEmail: string;
  previousRole: string;
  newRole: string;
  changedBy: string;
  reason: string;
  createdAt: string;
}

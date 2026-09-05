// AdminStatsController / AuthController / SubscriptionController / NotificationHistoryController의
// 실제 응답 DTO(core-service 커틀린 코드)와 필드를 맞춘 타입들.

export interface Session {
  token: string;
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
}

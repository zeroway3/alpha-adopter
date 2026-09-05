import { useEffect, useRef, useState } from "react";
import type { MatchedNotificationEvent } from "../api/types";

export type SseStatus = "connecting" | "connected" | "disconnected";

interface FeedItem extends MatchedNotificationEvent {
  receivedAt: string;
}

// EventSource는 커스텀 헤더를 못 보내므로 토큰을 쿼리 파라미터로 실어 보낸다
// (JwtAuthFilter가 /api/notifications/stream 한정으로 이 방식을 허용).
export function useNotificationStream(token: string | undefined) {
  const [status, setStatus] = useState<SseStatus>("connecting");
  const [feed, setFeed] = useState<FeedItem[]>([]);
  const sourceRef = useRef<EventSource | null>(null);

  useEffect(() => {
    if (!token) return;

    setStatus("connecting");
    const source = new EventSource(`/api/notifications/stream?token=${encodeURIComponent(token)}`);
    sourceRef.current = source;

    source.onopen = () => setStatus("connected");
    source.onerror = () => setStatus("disconnected");
    source.addEventListener("news-matched", (event) => {
      const payload = JSON.parse((event as MessageEvent).data) as MatchedNotificationEvent;
      setFeed((prev) => [{ ...payload, receivedAt: new Date().toISOString() }, ...prev]);
    });

    return () => {
      source.close();
      sourceRef.current = null;
    };
  }, [token]);

  return { status, feed };
}

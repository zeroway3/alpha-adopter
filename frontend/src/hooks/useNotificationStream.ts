import { useEffect, useRef, useState } from "react";
import type { MatchedNotificationEvent } from "../api/types";

export type SseStatus = "connecting" | "connected" | "disconnected";

interface FeedItem extends MatchedNotificationEvent {
  receivedAt: string;
}

// access_token이 httpOnly 쿠키로 발급되므로, EventSource가 커스텀 헤더를 못 보내도
// same-origin 요청에 브라우저가 쿠키를 자동으로 실어 보내 별도 처리 없이 인증된다.
export function useNotificationStream() {
  const [status, setStatus] = useState<SseStatus>("connecting");
  const [feed, setFeed] = useState<FeedItem[]>([]);
  const sourceRef = useRef<EventSource | null>(null);

  useEffect(() => {
    setStatus("connecting");
    const source = new EventSource("/api/notifications/stream");
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
  }, []);

  return { status, feed };
}

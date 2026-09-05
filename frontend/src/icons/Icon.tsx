export type IconName =
  | "search"
  | "zap"
  | "clock"
  | "trendingUp"
  | "tag"
  | "users"
  | "bell"
  | "newspaper"
  | "barChart"
  | "checkCircle"
  | "send"
  | "alertTriangle"
  | "cpu"
  | "home"
  | "grid"
  | "mail"
  | "lock";

interface IconProps {
  name: IconName;
  size?: number;
}

// 스트로크 기반 라인 아이콘 세트. currentColor를 써서 부모 요소(.card-icon.tint-*,
// .stat-icon-box 등)에 이미 적용된 색상을 그대로 물려받는다.
function IconPath({ name }: { name: IconName }) {
  switch (name) {
    case "search":
      return (
        <>
          <circle cx="10.5" cy="10.5" r="6.5" />
          <line x1="15.5" y1="15.5" x2="21" y2="21" />
        </>
      );
    case "zap":
      return <path d="M13 2 4 14h6l-1 8 9-12h-6l1-8z" fill="currentColor" stroke="none" />;
    case "clock":
      return (
        <>
          <circle cx="12" cy="12" r="9" />
          <path d="M12 7.5v5l3.5 2" />
        </>
      );
    case "trendingUp":
      return (
        <>
          <polyline points="3 16 9 10 13 14 21 5" />
          <polyline points="14 5 21 5 21 12" />
        </>
      );
    case "tag":
      return (
        <>
          <path d="M11.5 3H5a2 2 0 0 0-2 2v6.5L13.5 22 21 14.5 11.5 3z" />
          <circle cx="8" cy="8" r="1.4" fill="currentColor" stroke="none" />
        </>
      );
    case "users":
      return (
        <>
          <circle cx="9" cy="8" r="3.2" />
          <path d="M3.5 20c0-3.3 2.5-6 5.5-6s5.5 2.7 5.5 6" />
          <circle cx="17" cy="9.5" r="2.3" />
          <path d="M15.5 14.2c2.5.5 4.5 2.7 4.5 5.8" />
        </>
      );
    case "bell":
      return (
        <>
          <path d="M6 9a6 6 0 0 1 12 0c0 5 2 6.5 2 6.5H4S6 14 6 9z" />
          <path d="M10 19a2 2 0 0 0 4 0" />
        </>
      );
    case "newspaper":
      return (
        <>
          <rect x="4" y="3" width="16" height="18" rx="1.5" />
          <line x1="7.5" y1="8" x2="16.5" y2="8" />
          <line x1="7.5" y1="12" x2="16.5" y2="12" />
          <line x1="7.5" y1="16" x2="13" y2="16" />
        </>
      );
    case "barChart":
      return (
        <>
          <line x1="6" y1="20" x2="6" y2="13" />
          <line x1="12" y1="20" x2="12" y2="5" />
          <line x1="18" y1="20" x2="18" y2="16" />
        </>
      );
    case "checkCircle":
      return (
        <>
          <circle cx="12" cy="12" r="9" />
          <path d="M8 12.3l2.6 2.6 5-5.6" />
        </>
      );
    case "send":
      return (
        <>
          <path d="M21 3 10.5 13.5" />
          <path d="M21 3 14 21l-3.5-8L3 9z" />
        </>
      );
    case "alertTriangle":
      return (
        <>
          <path d="M12 3 2.5 20h19z" />
          <line x1="12" y1="9.5" x2="12" y2="14.5" />
          <circle cx="12" cy="17.3" r="0.9" fill="currentColor" stroke="none" />
        </>
      );
    case "cpu":
      return (
        <>
          <rect x="6" y="6" width="12" height="12" rx="1.5" />
          <rect x="10" y="10" width="4" height="4" />
          <line x1="12" y1="2" x2="12" y2="6" />
          <line x1="12" y1="18" x2="12" y2="22" />
          <line x1="2" y1="12" x2="6" y2="12" />
          <line x1="18" y1="12" x2="22" y2="12" />
        </>
      );
    case "home":
      return (
        <>
          <path d="M4 11 12 4l8 7" />
          <path d="M6 10v10h5v-6h2v6h5V10" />
        </>
      );
    case "grid":
      return (
        <>
          <rect x="4" y="4" width="7" height="7" rx="1.2" />
          <rect x="13" y="4" width="7" height="7" rx="1.2" />
          <rect x="4" y="13" width="7" height="7" rx="1.2" />
          <rect x="13" y="13" width="7" height="7" rx="1.2" />
        </>
      );
    case "mail":
      return (
        <>
          <rect x="3" y="5" width="18" height="14" rx="1.5" />
          <path d="M4 7l8 6 8-6" />
        </>
      );
    case "lock":
      return (
        <>
          <rect x="5" y="11" width="14" height="9" rx="1.5" />
          <path d="M8 11V8a4 4 0 0 1 8 0v3" />
        </>
      );
  }
}

export function Icon({ name, size = 18 }: IconProps) {
  return (
    <svg
      viewBox="0 0 24 24"
      width={size}
      height={size}
      fill="none"
      stroke="currentColor"
      strokeWidth={1.8}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      <IconPath name={name} />
    </svg>
  );
}

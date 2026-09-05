import { useId } from "react";

export function Logo({ size = 40 }: { size?: number }) {
  // 그라디언트 id가 문서 내에서 겹치면 안 되므로(로고가 한 페이지에 여러 번 렌더됨) useId로 고유화
  const gradientId = `logo-grad-${useId()}`;

  return (
    <svg
      className="logo-mark"
      width={size}
      height={size}
      viewBox="0 0 52 52"
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
    >
      <defs>
        <linearGradient id={gradientId} x1="0" y1="0" x2="52" y2="52" gradientUnits="userSpaceOnUse">
          <stop offset="0" stopColor="#3b82f6" />
          <stop offset="1" stopColor="#8b5cf6" />
        </linearGradient>
      </defs>
      <rect width="52" height="52" rx="14" fill={`url(#${gradientId})`} />
      <path
        d="M13 32 L22 22 L28 28 L39 15"
        stroke="white"
        strokeWidth="3.4"
        strokeLinecap="round"
        strokeLinejoin="round"
        fill="none"
      />
      <circle cx="39" cy="15" r="3.4" fill="white" />
    </svg>
  );
}

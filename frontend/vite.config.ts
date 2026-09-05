/// <reference types="vitest/config" />
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// 빌드 결과물을 core-service의 정적 리소스 디렉터리로 직접 출력한다.
// Docker 멀티스테이지 빌드(Dockerfile 참고)와 로컬 개발(npm run build) 모두
// 이 경로 하나로 통일해 Spring Boot가 그대로 서빙하도록 한다.
export default defineConfig({
  plugins: [react()],
  build: {
    outDir: "../core-service/src/main/resources/static",
    emptyOutDir: true,
  },
  server: {
    proxy: {
      "/api": "http://localhost:8090",
      "/actuator": "http://localhost:8090",
    },
  },
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: "./src/setupTests.ts",
  },
});

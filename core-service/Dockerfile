# 빌드 컨텍스트는 저장소 루트(frontend/, core-service/ 둘 다 접근 가능해야 함) —
# `docker build -f core-service/Dockerfile .` 형태로 repo root에서 실행한다.

FROM node:20-alpine AS frontend-build
WORKDIR /frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
# vite.config.ts의 outDir(../core-service/src/main/resources/static)가 이 스테이지의
# 격리된 파일시스템 안에서는 /core-service/src/main/resources/static 로 해석된다 —
# 다음 스테이지에서 그 경로를 그대로 COPY --from한다.
RUN npm run build

FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

COPY core-service/gradlew ./
COPY core-service/gradle ./gradle
COPY core-service/build.gradle.kts core-service/settings.gradle.kts ./
RUN chmod +x gradlew

COPY core-service/src ./src
COPY --from=frontend-build /core-service/src/main/resources/static ./src/main/resources/static
RUN ./gradlew bootJar -x test --no-daemon

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

COPY --from=build /workspace/build/libs/*.jar app.jar

EXPOSE 8090
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

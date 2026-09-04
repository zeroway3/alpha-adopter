FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

COPY gradlew ./
COPY gradle ./gradle
COPY build.gradle.kts settings.gradle.kts ./
RUN chmod +x gradlew

COPY src ./src
RUN ./gradlew bootJar -x test --no-daemon

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

COPY --from=build /workspace/build/libs/*.jar app.jar

EXPOSE 8090
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

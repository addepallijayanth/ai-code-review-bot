# syntax=docker/dockerfile:1.7
# Build from the repository root:  docker build -f reviewer-app/Dockerfile -t ai-code-review-bot .

FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Resolve dependencies first so source-only changes reuse this layer.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY reviewer-app/pom.xml reviewer-app/
COPY mcp-server/pom.xml mcp-server/
COPY eval/pom.xml eval/
RUN --mount=type=cache,target=/root/.m2 \
    chmod +x mvnw && ./mvnw -B -ntp -q -pl reviewer-app -am dependency:go-offline

COPY reviewer-app/src reviewer-app/src
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -ntp -q -pl reviewer-app -am package -DskipTests \
 && java -Djarmode=tools -jar reviewer-app/target/reviewer-app-*-exec.jar extract --layers --launcher --destination extracted

FROM eclipse-temurin:21-jre
RUN groupadd --system app && useradd --system --gid app --home-dir /app app
WORKDIR /app

# One layer per Spring Boot layer: dependencies change rarely, application classes often.
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./

USER app
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]

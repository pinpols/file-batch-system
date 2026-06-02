# syntax=docker/dockerfile:1.7
# batch-worker-sdk-template — multi-stage build
#
# Stage 1: maven 编 fat-jar(Eclipse Temurin 25 JDK)
# Stage 2: alpine + JRE 跑(镜像 ~ 230 MB)
#
# Build:
#   docker build -t batch-worker-sdk-template:dev .
# Run:
#   docker run --rm --env-file .env batch-worker-sdk-template:dev

FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /workspace

# 先拷 pom 拉依赖,利用 docker layer cache(改 src 不重拉)
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests dependency:go-offline

COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests package

# ---- runtime ----
FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S worker && adduser -S -G worker worker
WORKDIR /app

COPY --from=build /workspace/target/batch-worker-sdk-template.jar /app/app.jar
USER worker

# JVM 默认值;租户按需在 .env 里 override JAVA_OPTS
ENV JAVA_OPTS="-XX:+UseG1GC -XX:MaxRAMPercentage=75.0 -Dfile.encoding=UTF-8"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]

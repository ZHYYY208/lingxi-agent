FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY backend/pom.xml backend/pom.xml
COPY backend/src backend/src
COPY web web
COPY samples samples

RUN mvn -B -q -f backend/pom.xml -DskipTests package

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /workspace/backend/target/lingxi-backend.jar /app/app.jar
RUN mkdir -p /app/data/uploads

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75" \
    TZ=Asia/Shanghai

EXPOSE 8000

HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=5 \
    CMD curl -fsS http://127.0.0.1:8000/api/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]

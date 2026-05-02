FROM maven:3.9.12-eclipse-temurin-21 AS builder
WORKDIR /workspace

COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

RUN groupadd --system modelgate \
    && useradd --system --gid modelgate --home-dir /app --shell /usr/sbin/nologin modelgate

COPY --from=builder --chown=modelgate:modelgate /workspace/target/modelgate-0.1.jar /app/modelgate.jar

USER modelgate
ENV PORT=8787
EXPOSE 8787

HEALTHCHECK --interval=30s --timeout=3s --start-period=15s --retries=3 \
  CMD curl --fail --silent http://localhost:${PORT}/health > /dev/null || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/modelgate.jar"]

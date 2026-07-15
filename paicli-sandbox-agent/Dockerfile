FROM eclipse-temurin:25-jre

RUN useradd --create-home --uid 10001 sandbox \
    && mkdir -p /workspace \
    && chown -R sandbox:sandbox /workspace

WORKDIR /app
COPY target/paicli-sandbox-agent-0.6.0-SNAPSHOT.jar app.jar

USER sandbox
EXPOSE 8081
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "/app/app.jar"]

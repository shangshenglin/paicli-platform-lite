FROM eclipse-temurin:24-jre AS java-runtime

FROM mcr.microsoft.com/dotnet/sdk:8.0

COPY --from=java-runtime /opt/java/openjdk /opt/java/openjdk
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="${JAVA_HOME}/bin:${PATH}"
ENV POWERSHELL_TELEMETRY_OPTOUT=1

RUN useradd --create-home --uid 10001 sandbox \
    && mkdir -p /workspace \
    && chown -R sandbox:sandbox /workspace

WORKDIR /app
COPY target/paicli-sandbox-agent-0.6.0-SNAPSHOT.jar app.jar

USER sandbox
EXPOSE 8081
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "/app/app.jar"]

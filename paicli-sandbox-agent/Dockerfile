FROM eclipse-temurin:17-jdk AS java-runtime

FROM mcr.microsoft.com/dotnet/sdk:8.0

COPY --from=java-runtime /opt/java/openjdk /opt/java/openjdk
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="${JAVA_HOME}/bin:${PATH}"
ENV POWERSHELL_TELEMETRY_OPTOUT=1

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        bash \
        ca-certificates \
        curl \
        git \
        maven \
        nodejs \
        npm \
        python3 \
        python3-pip \
        python3-venv \
        unzip \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 sandbox \
    && useradd --create-home --uid 10001 --gid 10001 sandbox \
    && mkdir -p /workspace \
    && chown -R sandbox:sandbox /workspace

WORKDIR /app
COPY target/paicli-sandbox-agent-0.6.0-SNAPSHOT.jar app.jar

USER sandbox
EXPOSE 8081
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "/app/app.jar"]

FROM mcr.microsoft.com/openjdk/jdk:17-ubuntu AS java-runtime

FROM mcr.microsoft.com/dotnet/sdk:8.0

COPY --from=java-runtime /usr/lib/jvm/msopenjdk-17 /opt/java/openjdk
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="${JAVA_HOME}/bin:${PATH}"
ENV HOME=/home/sandbox
ENV XDG_CACHE_HOME=/tmp/.cache
ENV XDG_CONFIG_HOME=/tmp/.config
ENV XDG_DATA_HOME=/tmp/.local/share
ENV DOTNET_CLI_HOME=/tmp/dotnet
ENV POWERSHELL_TELEMETRY_OPTOUT=1

RUN sed -i 's|http://deb.debian.org|https://deb.debian.org|g' /etc/apt/sources.list.d/debian.sources \
    && apt-get -o Acquire::http::Timeout=20 -o Acquire::https::Timeout=20 -o Acquire::Retries=3 update \
    && apt-get install --yes --no-install-recommends \
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

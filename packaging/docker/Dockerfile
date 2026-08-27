# CodeZaiku in a container.
#
#   docker build -f packaging/docker/Dockerfile -t codezaiku .
#   docker run --rm codezaiku help
#
# Two things to understand before using this seriously:
#
#  1. CodeZaiku shells out constantly — git, build toolchains, docker, the language
#     servers. A container gets only what is installed here, so the CODING surface is
#     limited to what this image carries. Research, MCP and the operator are fine.
#
#  2. To operate the HOST's containers it needs the docker socket, which grants
#     effective root on that host. CodeZaiku's own security scanner reports a
#     socket mount as a HIGH finding, and it is right to. Prefer running it on the
#     host, or against a remote target over ssh, and use this image when you want
#     the process itself contained.
FROM eclipse-temurin:21-jdk-noble AS build

WORKDIR /src
# Wrapper and build files first so dependency resolution caches across source edits.
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY core/build.gradle.kts ./core/
RUN ./gradlew --no-daemon -q :core:dependencies >/dev/null 2>&1 || true

COPY core ./core
COPY ops-knowledge ./ops-knowledge
COPY knowledge-packs ./knowledge-packs
COPY docs/public/README.md docs/public/LICENSE ./docs/public/
RUN ./gradlew --no-daemon -q :core:installDist


FROM eclipse-temurin:21-jre-noble

# The runtime dependencies CodeZaiku actually invokes. docker-cli is the client only —
# it talks to whatever socket you mount, and is useless without one.
RUN apt-get update && apt-get install -y --no-install-recommends \
        bash git curl ca-certificates procps iproute2 openssl docker.io \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /src/core/build/install/codezaiku /opt/codezaiku
RUN ln -s /opt/codezaiku/bin/codezaiku /usr/local/bin/codezaiku

# State lives on a volume: learned cards, research findings and the audit trail should
# outlive the container.
ENV CODEZAIKU_CONFIG=/data/config \
    CODEZAIKU_HOME=/opt/codezaiku \
    CODEZAIKU_DRIVE=http://host.docker.internal:8200
VOLUME /data
WORKDIR /work

ENTRYPOINT ["codezaiku"]
CMD ["help"]

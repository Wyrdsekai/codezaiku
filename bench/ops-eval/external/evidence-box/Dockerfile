# A plain box that holds the external scenario's evidence artifacts under /evidence.
# The ops loop investigates by reading these files with ordinary shell commands.
FROM debian:bookworm-slim
RUN apt-get update \
 && apt-get install -y --no-install-recommends jq python3 procps coreutils \
 && rm -rf /var/lib/apt/lists/*
RUN mkdir -p /evidence
CMD ["sleep", "infinity"]

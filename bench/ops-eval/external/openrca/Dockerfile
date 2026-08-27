# The JAILED analysis box for OpenRCA. The agent gets python + pandas + duckdb to analyse telemetry — and
# NOTHING ELSE. query.csv (whose `scoring_points` column is the answer key) and record.csv (ground truth)
# are NEVER mounted here: only `telemetry/`, read-only.
#
# The PERSISTENT kernel is started BY THE CONTAINER ITSELF (not via docker exec): a `nohup ... &` launched
# inside a `docker exec` is killed when that exec session ends, so the kernel never survived and every
# python call silently waited out its full timeout. Starting it from CMD makes it always-alive.
#
# Why a persistent session at all: OpenRCA's trace file is 1.1GB/day and our shell tool spawns a fresh
# python per turn, so the agent re-loaded it EVERY turn and never finished. OpenRCA's own reference agent
# runs model-written python in a persistent IPython kernel — this is parity with that baseline.
FROM python:3.11-slim
RUN pip install --no-cache-dir pandas duckdb pyarrow \
 && apt-get update && apt-get install -y --no-install-recommends procps coreutils \
 && rm -rf /var/lib/apt/lists/*
COPY kernel.py /opt/kernel.py
RUN mkdir -p /data
CMD ["sh", "-c", "python3 -u /opt/kernel.py & exec sleep infinity"]

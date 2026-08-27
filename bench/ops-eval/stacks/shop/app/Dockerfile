# One tiny role-based service image, reused for backend / frontend / worker (role via env). Keeps the
# stack lightweight and reproducible (no GPU/heavy images) while mirroring a real compose topology.
FROM python:3.11-alpine
RUN apk add --no-cache curl
COPY app.py /opt/app.py
# ROLE/PORT/CHECK_TCP/CHECK_HTTP are supplied per-service by the compose file.
CMD ["python3", "-u", "/opt/app.py"]

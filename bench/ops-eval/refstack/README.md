# cp-refstack — representative AI/RAG stack for CodeZaiku autonomous-SRE validation

See `DESIGN.md` for the full design (hybrid substrate, autonomy ladder, roadmap).

A basic RAG app (`app/`) couples every engine so a fault anywhere breaks a real query end-to-end, while each
engine keeps its own objective health oracle. The app's `GET /health` reports **per-dependency** status — the
localization signal (it names which dependency is down).

## Stack (docker compose, single box)
nginx (edge, :28080) → app (RAG, :28000) → postgres :25432 · redis :26379 · rabbitmq :25672/:25673 ·
qdrant :26333 · opensearch :29200 · neo4j :27474/:27687 · localstack(S3+SQS) :24566 · ollama :21434.
Ports use a distinct 2xxxx range to avoid the standalone chaos containers.

## Run (${CP_HOST}: "${CP_WORK:-/opt/codezaiku}"/refstack)
```bash
docker compose up -d --build
docker exec refstack-ollama-1 ollama pull nomic-embed-text
docker exec refstack-ollama-1 ollama pull qwen2.5:0.5b
# end-to-end:
curl -XPOST localhost:28080/ingest -d '{"id":"d1","text":"..."}'  -H content-type:application/json
curl -XPOST localhost:28080/query  -d '{"q":"..."}'               -H content-type:application/json
curl localhost:28080/health   # {status, deps:{postgres,redis,qdrant,opensearch,neo4j,s3,sqs,ollama,rabbitmq}}
```

## Ingest / query path (what couples the engines)
ingest: S3 (store) → SQS (async) → worker: Ollama-embed → Qdrant + OpenSearch → Neo4j → Postgres → RabbitMQ event.
query:  Redis cache → Ollama-embed → Qdrant vector + OpenSearch keyword → Postgres/Neo4j → Ollama answer → cache.

## P0 status: DONE
Stack stands; end-to-end RAG works (ingest canary → query returns it); fault-sensitive + localization signal
validated (stop qdrant → /health names `qdrant: down`, others ok, query 500s; recovers on restart).

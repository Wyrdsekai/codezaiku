"""cp-refstack — a BASIC simple RAG app that COUPLES every engine, so a fault in any one breaks a real
query end-to-end (the hybrid substrate's end-to-end signal). Deliberately minimal — no product features.

Ingest path : POST /ingest {id,text} -> store in S3 (LocalStack) -> enqueue to SQS (LocalStack). A worker
              drains SQS -> read S3 -> embed (Ollama) -> upsert Qdrant + index OpenSearch -> MERGE entities
              in Neo4j -> metadata row in Postgres -> publish a 'doc_ingested' event to RabbitMQ.
Query path  : POST /query {q} -> Redis cache -> embed (Ollama) -> Qdrant vector + OpenSearch keyword search
              -> Postgres metadata + Neo4j entities -> Ollama answer -> cache in Redis.
Health      : GET /health probes every dependency and reports per-dep status (the localization signal — the
              app NAMES which dependency is down; the sensor grounds the model with this).

Every dependency call is wrapped so a single engine failure degrades the app (surfaced in /health and as an
error on the request path) rather than crashing the process."""
import base64, json, os, threading, time, traceback

import boto3, pika, psycopg2, redis, requests
from botocore.config import Config as BotoConfig
from fastapi import FastAPI
from neo4j import GraphDatabase
from opensearchpy import OpenSearch
from pydantic import BaseModel
from qdrant_client import QdrantClient
from qdrant_client.models import Distance, VectorParams, PointStruct

# ---- config (service DNS names on the compose network) ----
AWS = os.environ.get("AWS_ENDPOINT", "http://localstack:4566")
QDRANT = os.environ.get("QDRANT_URL", "http://qdrant:6333")
OS_HOST = os.environ.get("OPENSEARCH_HOST", "opensearch")
NEO4J = os.environ.get("NEO4J_URL", "bolt://neo4j:7687")
PG = os.environ.get("PG_DSN", "host=postgres dbname=refstack user=refstack password=refstack")
REDIS_HOST = os.environ.get("REDIS_HOST", "redis")
RABBIT = os.environ.get("RABBIT_URL", "amqp://refstack:refstack@rabbitmq:5672/")
OLLAMA = os.environ.get("OLLAMA_URL", "http://ollama:11434")
EMBED_MODEL = os.environ.get("EMBED_MODEL", "nomic-embed-text")
GEN_MODEL = os.environ.get("GEN_MODEL", "qwen2.5:0.5b")
DIM = int(os.environ.get("EMBED_DIM", "768"))
BUCKET, QUEUE, COLL, INDEX = "docs", "ingest", "docs", "docs"

app = FastAPI()
_aws = boto3.client  # lazy per-call clients avoid holding dead connections across faults


def s3():
    return _aws("s3", endpoint_url=AWS, aws_access_key_id="test", aws_secret_access_key="test",
                region_name="us-east-1", config=BotoConfig(retries={"max_attempts": 1}))


def sqs():
    return _aws("sqs", endpoint_url=AWS, aws_access_key_id="test", aws_secret_access_key="test",
                region_name="us-east-1", config=BotoConfig(retries={"max_attempts": 1}))


def qdrant():
    return QdrantClient(url=QDRANT, timeout=8)


def opensearch():
    return OpenSearch([{"host": OS_HOST, "port": 9200}], http_compress=False, timeout=8)


def embed(text):
    r = requests.post(f"{OLLAMA}/api/embeddings", json={"model": EMBED_MODEL, "prompt": text}, timeout=30)
    d = r.json()
    if "embedding" not in d:                      # surface Ollama's real error (e.g. "model not found") so
        raise RuntimeError(d.get("error", "embed failed")[:90])  # /health names it -> the card can match
    return d["embedding"]


def generate(prompt):
    r = requests.post(f"{OLLAMA}/api/generate",
                      json={"model": GEN_MODEL, "prompt": prompt, "stream": False}, timeout=60)
    return r.json().get("response", "")


# ---- ensure (idempotent): buckets, queue, collection, index, tables. Called periodically by the worker so
# the app SELF-HEALS resource loss (e.g. LocalStack is ephemeral -> loses bucket/queue on restart). ----
def ensure():
    try: s3().create_bucket(Bucket=BUCKET)
    except Exception: pass
    try: sqs().create_queue(QueueName=QUEUE)
    except Exception: pass
    try:
        qc = qdrant()
        if COLL not in [c.name for c in qc.get_collections().collections]:
            qc.create_collection(COLL, vectors_config=VectorParams(size=DIM, distance=Distance.COSINE))
    except Exception: pass
    try:
        oc = opensearch()
        if not oc.indices.exists(INDEX):
            oc.indices.create(INDEX, body={"mappings": {"properties": {"text": {"type": "text"}}}})
    except Exception: pass
    try:
        cx = psycopg2.connect(PG); cx.autocommit = True
        cx.cursor().execute("CREATE TABLE IF NOT EXISTS docs (id TEXT PRIMARY KEY, text TEXT, ts BIGINT)")
        cx.close()
    except Exception: pass


def setup():
    for _ in range(60):
        ensure()
        try:
            GraphDatabase.driver(NEO4J, auth=("neo4j", "refstackpass"), connection_timeout=5, connection_acquisition_timeout=5).session().run("RETURN 1").consume()
            return
        except Exception:
            time.sleep(3)


def _queue_url():
    return sqs().get_queue_url(QueueName=QUEUE)["QueueUrl"]


# ---- ingest worker: drains SQS and fans the doc into every engine ----
def process(doc_id):
    text = s3().get_object(Bucket=BUCKET, Key=doc_id)["Body"].read().decode()
    vec = embed(text)
    qdrant().upsert(COLL, points=[PointStruct(id=abs(hash(doc_id)) % (10**9),
                                              vector=vec, payload={"doc_id": doc_id, "text": text})])
    opensearch().index(INDEX, id=doc_id, body={"text": text}, refresh=True)
    with GraphDatabase.driver(NEO4J, auth=("neo4j", "refstackpass"), connection_timeout=5, connection_acquisition_timeout=5) as d:
        d.session().run("MERGE (x:Doc {id:$id}) SET x.text=$t", id=doc_id, t=text[:200]).consume()
    cx = psycopg2.connect(PG); cx.autocommit = True
    cx.cursor().execute("INSERT INTO docs VALUES (%s,%s,%s) ON CONFLICT (id) DO UPDATE SET text=EXCLUDED.text",
                        (doc_id, text, int(time.time())))
    cx.close()
    try:
        conn = pika.BlockingConnection(pika.URLParameters(RABBIT))
        ch = conn.channel(); ch.queue_declare("events", durable=True)
        ch.basic_publish("", "events", json.dumps({"doc_id": doc_id}).encode())
        conn.close()
    except Exception:
        pass  # event bus is fire-and-forget; not on the critical query path


_last_ensure = 0
def worker():
    global _last_ensure
    while True:
        try:
            if time.time() - _last_ensure > 15:
                ensure(); _last_ensure = time.time()
            url = _queue_url()
            msgs = sqs().receive_message(QueueUrl=url, MaxNumberOfMessages=5, WaitTimeSeconds=2).get("Messages", [])
            for m in msgs:
                try:
                    process(json.loads(m["Body"])["id"])
                except Exception:
                    traceback.print_exc()
                finally:
                    try:
                        sqs().delete_message(QueueUrl=url, ReceiptHandle=m["ReceiptHandle"])
                    except Exception:
                        pass
        except Exception:
            time.sleep(3)


# ---- API ----
class IngestReq(BaseModel):
    id: str
    text: str


class QueryReq(BaseModel):
    q: str


@app.post("/ingest")
def ingest(r: IngestReq):
    s3().put_object(Bucket=BUCKET, Key=r.id, Body=r.text.encode())
    sqs().send_message(QueueUrl=_queue_url(), MessageBody=json.dumps({"id": r.id}))
    return {"accepted": r.id}


@app.post("/query")
def query(r: QueryReq):
    rc = redis.Redis(host=REDIS_HOST, socket_timeout=5)
    ck = "q:" + base64.b64encode(r.q.encode()).decode()
    cached = rc.get(ck)
    if cached:
        return {"answer": cached.decode(), "cached": True}
    vec = embed(r.q)
    hits = qdrant().search(COLL, query_vector=vec, limit=3)
    ctx = "\n".join(h.payload.get("text", "") for h in hits)
    kw = opensearch().search(index=INDEX, body={"query": {"match": {"text": r.q}}, "size": 3})
    sources = [h["_id"] for h in kw["hits"]["hits"]] or [h.payload.get("doc_id") for h in hits]
    answer = generate(f"Answer using only this context:\n{ctx}\n\nQuestion: {r.q}\nAnswer:")
    rc.setex(ck, 300, answer)
    return {"answer": answer, "sources": sources}


def _rabbit_probe():
    prm = pika.URLParameters(RABBIT)
    prm.blocked_connection_timeout = 4; prm.socket_timeout = 4; prm.heartbeat = 0
    c = pika.BlockingConnection(prm); ch = c.channel(); ch.confirm_delivery()
    ch.queue_declare("_probe", durable=False)
    ch.basic_publish("", "_probe", b"1", properties=pika.BasicProperties(delivery_mode=1), mandatory=True)
    c.close()


def _pg_write():
    cx = psycopg2.connect(PG, connect_timeout=4); cx.autocommit = True
    cx.cursor().execute("INSERT INTO docs VALUES ('_probe','_',0) ON CONFLICT (id) DO UPDATE SET ts=0")
    cx.close()


@app.get("/health")
def health():
    """READINESS probe — tests WRITES on the stateful engines, not just connectivity, so degraded
    write-failures (disk-full read-only, a wedged node) that still serve reads are detected and NAMED. This
    is the localization signal the sensor grounds the model with."""
    deps = {}

    def chk(name, fn):
        try:
            fn(); deps[name] = "ok"
        except Exception as e:
            deps[name] = f"down: {(str(e)[:150] or type(e).__name__)}"

    chk("postgres", _pg_write)                                                    # write
    chk("redis", lambda: redis.Redis(host=REDIS_HOST, socket_timeout=4).set("_probe", "1"))  # write
    chk("qdrant", lambda: qdrant().upsert(COLL, points=[PointStruct(id=0, vector=[0.0] * DIM)], wait=True))  # synchronous write
    chk("opensearch", lambda: opensearch().index(INDEX, id="_probe", body={"text": "_"}))    # write
    chk("neo4j", lambda: GraphDatabase.driver(NEO4J, auth=("neo4j", "refstackpass"), connection_timeout=5, connection_acquisition_timeout=5).session().run("MERGE (p:Probe {id:1})").consume())  # write
    chk("s3", lambda: s3().put_object(Bucket=BUCKET, Key="_probe", Body=b"_"))    # write
    chk("sqs", lambda: _queue_url())
    chk("ollama", lambda: embed("_"))  # embed write-probe: detects a missing model
    chk("rabbitmq", _rabbit_probe)
    ok = all(v == "ok" for v in deps.values())
    return {"status": "ok" if ok else "degraded", "deps": deps}


@app.get("/ready")
def ready():
    """DEEP readiness — the write-probes PLUS the end-to-end WORKLOAD, so an 'up but wrong' fault surfaces
    (a stage that silently returns bad while its write-probe passes). /health can be ok while /ready is
    degraded. Reported in the SAME dep format keyed by SERVICE, so the localizer handles it uniformly:
      - ollama: /health only probes EMBED; a missing GENERATE model passes /health but breaks /query. /ready
        exercises generate → names ollama degraded.
      - opensearch/qdrant: a search that ERRORS (vs returns no hits) is a workload fault a write-probe misses.
    """
    h = health()
    deps = dict(h["deps"])
    # generate (the gen model — /health never touches it)
    if deps.get("ollama") == "ok":
        try:
            r = generate("Reply with the single word OK.")
            if not (r and r.strip()):
                deps["ollama"] = (f"down: generate returned EMPTY — the generation model '{GEN_MODEL}' is "
                                  "missing/broken (embed works, so /health is green, but /query answers are empty)")
        except Exception as e:
            deps["ollama"] = f"down: generate failed with model '{GEN_MODEL}' — " + str(e)[:100]
    # search paths error (vs empty) — a real workload fault a write-probe won't show
    if deps.get("qdrant") == "ok":
        try:
            qdrant().search(COLL, query_vector=[0.0] * DIM, limit=1)
        except Exception as e:
            deps["qdrant"] = "down: vector search errored — " + str(e)[:110]
    if deps.get("opensearch") == "ok":
        try:
            opensearch().search(index=INDEX, body={"query": {"match_all": {}}, "size": 1})
        except Exception as e:
            deps["opensearch"] = "down: search errored — " + str(e)[:110]
    ok = all(v == "ok" for v in deps.values())
    return {"status": "ok" if ok else "degraded", "deps": deps}


threading.Thread(target=lambda: (setup(), worker()), daemon=True).start()

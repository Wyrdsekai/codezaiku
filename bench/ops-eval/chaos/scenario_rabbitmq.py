"""RabbitMQ resource-alarm scenario — the "RabbitMQ goes dark" classic. The data disk genuinely fills below
`disk_free_limit`, so RabbitMQ raises `disk_free_alarm` and applies flow control: ALL publishing connections
are BLOCKED (consumers keep running). Fault is real (a large file fills the data volume); oracle is the
engine's OWN behaviour (an AMQP publish-with-confirms actually blocks under the alarm — the mgmt HTTP publish
does NOT, so it would be a false oracle) plus a surviving canary message; grade also requires disk GENUINELY
freed (defeats the lower-disk_free_limit shortcut that silences the alarm on a still-full disk). The core
injects `sh`."""
import os, subprocess, time
import requests
import pika

CONTAINER = os.environ.get("CP_RABBIT_CONTAINER", "cp-rabbit")
MGMT = os.environ.get("CP_RABBIT_MGMT", "http://localhost:15672/api")
AMQP_HOST, AMQP_PORT = "localhost", 5672
USER, PW = "cp", "cp"
DATA = "/var/lib/rabbitmq"
AUTH = (USER, PW)
DISK_LIMIT_MB = 600
HOG_MB = 1600

SYS = (
    "You are a senior SRE operating a host that runs a RabbitMQ container. Investigate the reported failure, "
    "ACT to fix its ROOT cause, and verify the fix took effect.\n\n"
    f"ENVIRONMENT: the container is `{CONTAINER}`. Management API at {MGMT} (basic auth {USER}:{PW}); AMQP on "
    f"5672. Run shell on the host: `docker exec {CONTAINER} <cmd>` for in-container work "
    "(rabbitmqctl, rabbitmq-diagnostics, df, du, ls, rm), and `curl -u cp:cp` for the management API.\n\n"
    "RULES: one action per turn, a SINGLE shell command. Inspect, form a hypothesis, then run the command "
    "that FIXES the root cause. Re-check that publishing works again.\n\n"
    "Output EXACTLY ONE line per turn:\n  ACTION: <one shell command>\n  DONE: <one-sentence fix>\n"
)
INCIDENT = ("INCIDENT\nApplication: message broker (RabbitMQ)\n"
            "Description: producers can no longer publish — publish confirms hang and the app is stalled, "
            "though consumers still run. Durable business data in existing queues (e.g. `orders_q`) must NOT "
            "be lost — do not delete or purge application queues. Investigate and fix the root cause now.")

_fault_free_mb = 0


def _free_mb():
    try:
        out = subprocess.check_output(["docker", "exec", CONTAINER, "df", "-P", DATA], timeout=15)
        return int(out.decode().splitlines()[1].split()[3]) // 1024
    except Exception:
        return 0


def _publish_ok(timeout=5):
    """AMQP publish WITH publisher confirms — the faithful 'are publishers blocked?' probe. Under a resource
    alarm the connection is blocked and the confirm never arrives -> ConnectionBlockedTimeout -> False."""
    try:
        c = pika.BlockingConnection(pika.ConnectionParameters(
            host=AMQP_HOST, port=AMQP_PORT, credentials=pika.PlainCredentials(USER, PW),
            blocked_connection_timeout=timeout, socket_timeout=timeout, heartbeat=0))
        ch = c.channel()
        ch.confirm_delivery()
        ch.basic_publish("", "work_q", b"probe",
                         properties=pika.BasicProperties(delivery_mode=1), mandatory=True)
        c.close()
        return True
    except Exception:
        return False


def _canary_ok():
    try:
        m = requests.get(f"{MGMT}/queues/%2F/orders_q", auth=AUTH, timeout=8).json().get("messages", 0)
        return m >= 1
    except Exception:
        return False


def healthy(timeout=120):
    start = time.time()
    while time.time() - start < timeout:
        try:
            if requests.get(f"{MGMT}/overview", auth=AUTH, timeout=5).status_code == 200:
                return True
        except Exception:
            pass
        time.sleep(5)
    return False


def steady_ok():
    pub = _publish_ok()
    can = _canary_ok()
    return (pub and can), f"publish={pub} canary={can}"


def grade():
    func_ok, detail = steady_ok()
    free = _free_mb()
    freed = free - _fault_free_mb >= 800
    return (func_ok and freed), f"{detail} free {_fault_free_mb}MB->{free}MB freed={freed}"


def stack_text():
    return f"rabbitmq rabbit amqp docker {CONTAINER}".lower()


def scan_logs():
    text = sh(f"docker logs --tail 120 {CONTAINER} 2>&1").lower()
    state = sh(f"docker exec {CONTAINER} rabbitmq-diagnostics alarms 2>&1")
    state += "\n" + sh(f"docker exec {CONTAINER} df -h {DATA} 2>&1")
    try:
        state += "\n" + requests.get(f"{MGMT}/nodes", auth=AUTH, timeout=8).text
    except Exception:
        pass
    return text + "\n" + state.lower()


def _mgmt(method, path, body=None):
    fn = {"put": requests.put, "post": requests.post}[method]
    fn(f"{MGMT}{path}", auth=AUTH, json=body, timeout=10)


def reset(wait=120):
    global _fault_free_mb
    print("[chaos] restarting RabbitMQ container for a clean node...", flush=True)
    subprocess.run(f"docker restart {CONTAINER}", shell=True, stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT)
    if not healthy():
        print("[chaos] WARNING RabbitMQ not healthy after restart", flush=True)
        return False
    # durable queues + protected canary
    _mgmt("put", "/queues/%2F/orders_q", {"durable": True})
    _mgmt("put", "/queues/%2F/work_q", {"durable": True})
    _mgmt("post", "/exchanges/%2F/amq.default/publish",
          {"properties": {"delivery_mode": 2}, "routing_key": "orders_q",
           "payload": "CANARY-42", "payload_encoding": "string"})
    # realistic-but-safe production disk_free_limit, then a real large file fills the data volume below it
    subprocess.run(f"docker exec {CONTAINER} rabbitmqctl set_disk_free_limit {DISK_LIMIT_MB}MB",
                   shell=True, stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT)
    subprocess.run(f"docker exec {CONTAINER} sh -c 'fallocate -l {HOG_MB}M {DATA}/rabbit-audit.dump'",
                   shell=True, stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT)
    print(f"[chaos] injected disk-fill (free ~{_free_mb()}MB < {DISK_LIMIT_MB}MB limit)", flush=True)
    start, stable = time.time(), 0
    while time.time() - start < wait:
        ok, detail = steady_ok()
        stable = stable + 1 if not ok else 0   # debounce: require 2 consecutive blocked probes (the AMQP
        if stable >= 2:                          # probe can flicker at connect) so the before-check agrees
            _fault_free_mb = _free_mb()
            print(f"[chaos] fault applied ({detail}) fault_free={_fault_free_mb}MB", flush=True)
            return True
        time.sleep(4)
    print(f"[chaos] WARNING fault did not apply within {wait}s", flush=True)
    return False


sh = None  # injected by chaos_core

"""LocalStack (synthetic AWS) S3 delete-marker scenario — a genuinely NON-OBVIOUS AWS incident. The bucket
has versioning enabled; a DELETE placed a delete marker as the current version, so the app's GET returns
404 NoSuchKey even though the object's data is intact as a prior version. Real fault (a real delete marker
on a real versioned bucket — LocalStack community enforces S3 versioning), objective oracle (a GET returns
200 AND the object's real content), fix = remove the delete marker (or restore the prior version) WITHOUT
fabricating content (the content check is the anti-shortcut). The core injects `sh`.

Chosen because LocalStack COMMUNITY does not enforce IAM/bucket policies (the obvious AccessDenied incidents
don't manifest) but does enforce S3 versioning + delete markers."""
import os, subprocess, time

# creds so the agent's `aws` calls (and ours) authenticate to LocalStack
os.environ.setdefault("AWS_ACCESS_KEY_ID", "test")
os.environ.setdefault("AWS_SECRET_ACCESS_KEY", "test")
os.environ.setdefault("AWS_DEFAULT_REGION", "us-east-1")

CONTAINER = os.environ.get("CP_LOCALSTACK_CONTAINER", "cp-localstack")
ENDPOINT = os.environ.get("CP_AWS_ENDPOINT", "http://localhost:4566")
BUCKET = "cp-data"
KEY = "orders/report.txt"
CONTENT = "hello-canary-42"
AWS = f"aws --endpoint-url={ENDPOINT}"

SYS = (
    "You are a senior SRE operating AWS (via an S3-compatible endpoint). Investigate the reported failure, "
    "ACT to fix its ROOT cause, and verify the fix took effect.\n\n"
    f"ENVIRONMENT: use the AWS CLI with `{AWS} ...` (credentials are already in the environment). The app "
    f"reads the object `s3://{BUCKET}/{KEY}`. Run shell on the host (aws CLI + standard tools).\n\n"
    "RULES: one action per turn, a SINGLE shell command. Inspect (s3api head-object/get-object/"
    "list-object-versions/get-bucket-versioning), form a hypothesis, then run the command that FIXES the "
    "root cause. Re-check that the object can be read with its real content.\n\n"
    "Output EXACTLY ONE line per turn:\n  ACTION: <one shell command>\n  DONE: <one-sentence fix>\n"
)
INCIDENT = ("INCIDENT\nApplication: object storage (AWS S3)\n"
            f"Description: the app can no longer read `s3://{BUCKET}/{KEY}` — every GET returns 404 NoSuchKey "
            "— but the data was never intentionally deleted and must be recovered intact. Investigate and fix "
            "the root cause now.")


def _run(cmd, t=25):
    try:
        return subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=t)
    except Exception:
        return None


def _get_content():
    r = _run(f"{AWS} s3api get-object --bucket {BUCKET} --key {KEY} /tmp/_cp_get.txt")
    if r is not None and r.returncode == 0:
        try:
            return open("/tmp/_cp_get.txt").read().strip()
        except Exception:
            return None
    return None


def healthy(timeout=60):
    start = time.time()
    while time.time() - start < timeout:
        r = _run(f"curl -s -o /dev/null -m4 -w '%{{http_code}}' {ENDPOINT}/_localstack/health", t=8)
        if r is not None and r.stdout.strip() == "200":
            return True
        time.sleep(3)
    return False


def steady_ok():
    c = _get_content()
    ok = c == CONTENT
    return ok, f"get={'ok' if c is not None else 'fail'} content_match={ok}"


def grade():
    return steady_ok()               # get-200-with-real-content is functional recovery AND anti-shortcut


def stack_text():
    return f"s3 aws localstack object storage docker {CONTAINER}".lower()


def scan_logs():
    text = ""
    r = _run(f"{AWS} s3api get-object --bucket {BUCKET} --key {KEY} /tmp/_cp_probe.txt")
    if r is not None:
        text += (r.stdout or "") + (r.stderr or "")
    r = _run(f"{AWS} s3api list-object-versions --bucket {BUCKET} --prefix {KEY}")
    if r is not None:
        text += "\n" + (r.stdout or "")
    return text.lower()


def reset(wait=60):
    if not healthy():
        print("[chaos] WARNING LocalStack not healthy", flush=True)
        return False
    # deterministic clean slate: drop the bucket, recreate it versioned, put the canary, then DELETE it so a
    # delete marker hides it (data intact as the prior version)
    _run(f"{AWS} s3 rb s3://{BUCKET} --force")
    _run(f"{AWS} s3api create-bucket --bucket {BUCKET}")
    _run(f"{AWS} s3api put-bucket-versioning --bucket {BUCKET} "
         f"--versioning-configuration Status=Enabled")
    subprocess.run(f"printf '{CONTENT}' > /tmp/_cp_obj.txt", shell=True)
    _run(f"{AWS} s3api put-object --bucket {BUCKET} --key {KEY} --body /tmp/_cp_obj.txt")
    _run(f"{AWS} s3api delete-object --bucket {BUCKET} --key {KEY}")   # inject the delete marker
    start = time.time()
    while time.time() - start < wait:
        ok, detail = steady_ok()
        if not ok:
            print(f"[chaos] fault applied ({detail})", flush=True)
            return True
        time.sleep(3)
    print(f"[chaos] WARNING fault did not apply within {wait}s", flush=True)
    return False


sh = None  # injected by chaos_core

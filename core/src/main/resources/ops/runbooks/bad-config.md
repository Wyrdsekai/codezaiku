# Runbook: a daemon won't load because its config is invalid

Symptom: a service is down and its config test fails, or its log shows a parse/syntax/"unknown directive"
error. The root cause is the invalid config — not "the service is stopped."

## Workflow
1. Run the daemon's config validator and READ the exact error:
   - nginx: `nginx -t`  • sshd: `sshd -t`  • apache: `apachectl configtest`  • many apps: `<app> --check`.
2. The validator names the file + line + offending directive. Open that file at that line: `cat -n <file>`.
3. Confirm this is why the service is down (it refuses to start/reload while the config is invalid).
4. Conclude: `bad_config` — <file>:<line> has <the specific problem, e.g. unknown directive / missing
   semicolon>. Quote the validator output as evidence.

## Trap
High CPU/memory or a downstream 5xx are symptoms of the outage, not its cause. If `nginx -t` fails, the
config is the cause regardless of what the resource graphs show.

## Remediation (separate, gated)
Fix the offending directive, re-run the validator until it passes, then reload/start and verify serving.

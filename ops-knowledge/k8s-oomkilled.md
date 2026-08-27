match: kubectl, kubernetes, k8s, pod, memory
signature: oomkilled, exit code 137, reason: oomkilled, last state: terminated
push: rescue
status: candidate
platform: kubernetes
# Kubernetes pod OOMKilled — fix procedure
1. `kubectl get pods -n <ns>` shows CrashLoopBackOff/restarts; `kubectl describe pod <pod> -n <ns>` -> Last State: Terminated, Reason: OOMKilled, Exit Code 137.
2. Read the limit: `kubectl get pod <pod> -n <ns> -o jsonpath='{.spec.containers[*].resources.limits.memory}'` — it is below real usage.
3. Raise it: `kubectl set resources deploy/<d> -n <ns> --limits=memory=<higher> --requests=memory=<higher>` (or edit the manifest). If leaking, fix the app instead of only raising.
4. Rollout restarts the pod with the new limit.
5. Confirm the pod stays Running without OOM restarts, then conclude (submit) immediately.

match: kubectl, kubernetes, k8s, deployment, pod
signature: crashloopbackoff, pending, failedscheduling, 0/0, no pods
push: rescue
status: validated
platform: kubernetes
# Kubernetes workload fault — fix procedure
1. `kubectl get deploy -n <ns>`: READY 0/0 → `kubectl scale deploy <d> --replicas=1`.
2. Pending pod → `kubectl describe pod` Events names the cause (bad nodeSelector/affinity → patch it out).
3. CrashLoop → `kubectl logs --previous <pod>` names the exit cause; fix that config/resource.
4. Apply the one fix, watch the pods reach Ready once, then conclude (submit) immediately.

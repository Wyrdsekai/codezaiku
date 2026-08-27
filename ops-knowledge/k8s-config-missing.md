match: kubectl, kubernetes, k8s, configmap, secret
signature: createcontainerconfigerror, configmap not found, secret not found, couldn't find key
push: rescue
status: candidate
platform: kubernetes
# Kubernetes missing ConfigMap/Secret — fix procedure
1. Pod stuck ContainerCreating with CreateContainerConfigError -> a referenced ConfigMap/Secret/key is missing.
2. `kubectl describe pod <pod> -n <ns>` Events name it: `configmap "x" not found`, `secret "x" not found`, or `couldn't find key <k>`.
3. Confirm: `kubectl get configmap,secret -n <ns>` — the named object is absent (or in the wrong namespace).
4. Create it in the pod's namespace: `kubectl create configmap <name> --from-literal=<k>=<v> -n <ns>` (or `create secret generic ...`), matching the key the pod reads.
5. Pod recreates automatically; when it reaches Running, conclude (submit) immediately.

match: kubectl, kubernetes, k8s, dns, coredns
signature: plugin/loop, loop detected, no servers could be reached, connection timed out; no servers
push: rescue
status: candidate
platform: kubernetes
# Kubernetes CoreDNS down — fix procedure
1. Cluster-wide "no such host"/"server misbehaving" on service names -> cluster DNS is broken.
2. `kubectl get pods -n kube-system -l k8s-app=kube-dns` — CoreDNS not Running/CrashLoop; `kubectl logs -n kube-system -l k8s-app=kube-dns` names the cause (e.g. resolv.conf loop).
3. Test resolution: `kubectl run t --rm -it --image=busybox --restart=Never -- nslookup kubernetes.default`.
4. Fix: if 0 replicas `kubectl scale deploy coredns -n kube-system --replicas=2`; on a "Loop" crash, correct the upstream in the `coredns` ConfigMap (`kubectl -n kube-system edit cm coredns`) then `kubectl -n kube-system rollout restart deploy coredns`.
5. When in-cluster lookups resolve again, conclude (submit) immediately.

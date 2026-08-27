match: kubectl, kubernetes, k8s, node, taint
signature: notready, node.kubernetes.io/not-ready, diskpressure, node.kubernetes.io/disk-pressure
push: rescue
status: candidate
platform: kubernetes, systemd
# Kubernetes Node NotReady / DiskPressure — fix procedure
1. Pods Pending/Evicted -> `kubectl get nodes`: a node is NotReady.
2. `kubectl describe node <node>` Conditions/Taints name it: DiskPressure/MemoryPressure, or kubelet not posting status (NotReady).
3. DiskPressure -> free disk on that node: prune images/logs (`crictl rmi --prune`, clear `/var/log`), which lifts the taint once usage drops below the eviction threshold.
4. NotReady from a dead agent -> on the node `systemctl restart kubelet` (and containerd) and check `journalctl -u kubelet`.
5. When `kubectl get nodes` shows Ready and pods schedule, conclude (submit) immediately.

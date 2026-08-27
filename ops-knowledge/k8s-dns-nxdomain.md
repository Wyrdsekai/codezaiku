match: kubectl, kubernetes, k8s, coredns
signature: temporary failure in name resolution, could not resolve host, no such host
push: immediate
status: validated
platform: kubernetes
# Kubernetes: one service name fails DNS while its pods are healthy — fix procedure
1. Apps logging "Temporary failure in name resolution"/getaddrinfo/"no such host" for a SERVICE NAME whose
   pods are Running means cluster DNS answers NXDOMAIN for that name — fix the record, not the pods.
2. Confirm: `kubectl run t --rm -i --image=busybox --restart=Never -- nslookup <svc>.<ns>.svc.cluster.local`
   fails while `kubectl get svc <svc> -n <ns>` exists.
3. Look for an override poisoning the name in `kubectl -n kube-system get cm coredns -o yaml`: a `template`,
   `rewrite`, or `hosts` stanza mentioning the service or returning NXDOMAIN. Remove it with
   `kubectl -n kube-system edit cm coredns`.
4. `kubectl -n kube-system rollout restart deploy coredns`; when the nslookup resolves and the app errors
   stop, conclude (submit) immediately.

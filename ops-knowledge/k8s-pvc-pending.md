match: kubectl, kubernetes, k8s, pvc, volume
signature: unbound immediate persistentvolumeclaims, waiting for a volume to be created, provisioningfailed
push: rescue
status: candidate
platform: kubernetes
# Kubernetes PVC pending — fix procedure
1. Pod stuck Pending with "pod has unbound immediate PersistentVolumeClaims" -> its PVC is not bound.
2. `kubectl get pvc -n <ns>` (STATUS Pending); `kubectl describe pvc <pvc> -n <ns>` Events: "waiting for a volume to be created" or "ProvisioningFailed".
3. Check storage: `kubectl get storageclass` — PVC's `storageClassName` must name an existing SC with a working provisioner (and a default SC should exist).
4. Fix: point the PVC at a valid SC, or `kubectl patch storageclass <sc> -p '{"metadata":{"annotations":{"storageclass.kubernetes.io/is-default-class":"true"}}}'`, or hand-create a matching PV.
5. When the PVC goes Bound and the pod starts, conclude (submit) immediately.

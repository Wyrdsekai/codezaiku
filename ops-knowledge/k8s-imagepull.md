match: kubectl, kubernetes, k8s, pod, image
signature: imagepullbackoff, errimagepull, manifest unknown, pull access denied
push: rescue
status: candidate
platform: docker, kubernetes
# Kubernetes ImagePullBackOff — fix procedure
1. `kubectl get pods -n <ns>`: status ImagePullBackOff/ErrImagePull.
2. `kubectl describe pod <pod> -n <ns>` Events names it: "manifest unknown" = bad tag; "pull access denied"/"unauthorized" = missing registry creds; "no such host" = wrong registry.
3. Bad tag -> set an existing tag: `kubectl set image deploy/<d> <ctr>=<repo>:<goodtag> -n <ns>` (verify with `skopeo`/registry if available).
4. Auth -> create the pull secret and attach it: `kubectl create secret docker-registry ... ` + imagePullSecrets on the pod spec.
5. Watch the pod reach Running once, then conclude (submit) immediately.

match: mongo, mongodb
signature: certificate is expired, ssl certificate, sslhandshakefailed
push: immediate
status: validated
platform: kubernetes
# MongoDB TLS fault — fix procedure
1. A mongod that requires TLS with a bad/expired certificate DIES AT STARTUP — its own log shows a fatal
   assertion naming the certificate; client apps log "No suitable servers found" against that host.
2. Find where TLS is set: `kubectl get deploy <mongo> -o yaml` (args like --tlsMode/--sslMode) and the
   mounted config: `kubectl get cm -n <ns> | grep mongo` then read the mongod.conf for a net.tls block.
3. Fix by REMOVING the requirement, not the secret: set tls mode to disabled in the configmap or drop the
   --tlsMode/--sslMode args from the deployment, then `kubectl rollout restart deploy <mongo>`.
4. When the mongo pod is Running and the client "No suitable servers" errors stop, conclude (submit) immediately.

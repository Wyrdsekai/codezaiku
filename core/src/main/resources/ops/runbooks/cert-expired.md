# Runbook: TLS/HTTPS failing — certificate problem

Symptom: clients report certificate warnings, HTTPS handshakes fail, or a browser refuses the site over
TLS. The service itself is usually UP (nginx loads an expired cert fine — expiry is a client-side check).

## Workflow
1. Find the cert the service presents: its config names it (`grep -ri ssl_certificate /etc/nginx`), or
   inspect what's served: `echo | openssl s_client -connect localhost:443 2>/dev/null | openssl x509 -noout -dates`.
2. Check validity on the cert FILE: `openssl x509 -in <cert> -noout -enddate` and
   `openssl x509 -in <cert> -noout -checkend 0` (non-zero exit = expired). Read the `notAfter` date.
3. Conclude `cert_expired` — the served certificate expired on <date>. Cite the `openssl` output.

## Trap
High CPU / load or a downstream error is not the cause of a TLS handshake failure. If the cert's
`notAfter` is in the past, that is the cause regardless of resource graphs.

## Remediation (separate, gated)
Reissue/renew the certificate (or fix the path if it points at the wrong file), reload the service, and
verify the served cert is now valid (`openssl x509 -checkend 0`).

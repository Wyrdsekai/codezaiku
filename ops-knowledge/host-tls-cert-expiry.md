match: linux, host, tls, ssl, certificate
signature: certificate has expired, certificate verify failed, certificate_expired, notafter
push: rescue
status: candidate
platform: systemd
# Host TLS certificate expiry — fix procedure
1. "certificate has expired"/"certificate verify failed" -> a served cert is past NotAfter.
2. Confirm expiry on the endpoint: `echo | openssl s_client -connect <host>:<port> -servername <host> 2>/dev/null | openssl x509 -noout -enddate` (or `openssl x509 -enddate -noout -in <cert.pem>`).
3. If notAfter is in the past, replace with a current cert: renew (`certbot renew`) or drop the fresh fullchain/key into the configured path.
4. Reload the server to pick it up: `nginx -s reload` / `systemctl reload <svc>`.
5. Re-verify enddate is in the future and the handshake succeeds, then conclude (submit) immediately.

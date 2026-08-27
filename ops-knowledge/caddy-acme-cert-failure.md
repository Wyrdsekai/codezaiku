match: caddy
signature: could not get certificate from issuer, http.acme_client, challenge failed
push: rescue
status: candidate
platform: systemd
# Caddy ACME certificate failure — fix procedure
1. "http.acme_client" + "challenge failed" + "could not get certificate from issuer" mean Caddy cannot complete the ACME challenge, so HTTPS never starts — usually HTTP-01 on :80 is unreachable or DNS is wrong.
2. Read the failing domain/challenge: `journalctl -u caddy | grep -Ei "acme|challenge|certificate"`.
3. Confirm reachability: DNS must point here (`dig +short <domain>`) and :80 open inbound for HTTP-01 (`ss -ltnp | grep ':80'`, check firewall).
4. Fix the cause — correct DNS, open :80, or switch to DNS-01 (`tls { dns <provider> ... }`) for internal hosts — then `caddy reload --config /etc/caddy/Caddyfile`.
5. Recheck `curl -sv https://<domain>` shows a valid cert and the ACME errors stop; when HTTPS serves, conclude (submit) immediately.

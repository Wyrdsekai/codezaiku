match: apache, httpd, apache2
signature: server reached MaxRequestWorkers setting, AH00484, consider raising the MaxRequestWorkers
push: rescue
status: candidate
platform: systemd
# Apache MaxRequestWorkers saturation — fix procedure
1. "AH00484: server reached MaxRequestWorkers setting" = all worker slots busy and requests queuing; Apache is starved (usually slow PHP/backend), not misconfigured.
2. Confirm: `grep MaxRequestWorkers /var/log/apache2/error.log` (RHEL: /var/log/httpd/error_log) and `curl -s localhost/server-status?auto | grep BusyWorkers`.
3. Relieve backpressure: find and fix the slow upstream (PHP-FPM/app/DB) so in-flight requests drain.
4. Raise the cap: in the MPM conf (e.g. /etc/apache2/mods-available/mpm_event.conf) increase MaxRequestWorkers and ServerLimit, then `apachectl configtest && systemctl reload apache2`.
5. Recheck BusyWorkers drops below the limit and the route returns 200; when workers are no longer maxed, conclude (submit) immediately.

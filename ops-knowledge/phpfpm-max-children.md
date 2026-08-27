match: php-fpm, php-fpm8, phpfpm
signature: server reached pm.max_children setting, consider raising it, WARNING: [pool
push: rescue
status: candidate
platform: systemd
# PHP-FPM max_children reached — fix procedure
1. "server reached pm.max_children setting (N), consider raising it" means every FPM child is busy, so PHP requests queue behind the web server — surfacing as slow pages / 502s.
2. Confirm: `grep "max_children" /var/log/php*-fpm.log` (or the pool log); check the live child count via the pool status page or `systemctl status php*-fpm`.
3. Verify RAM headroom before raising: `free -m` and mean child RSS `ps --no-headers -o rss -C php-fpm | awk '{s+=$1}END{print s/NR/1024" MB"}'`.
4. Raise the cap in the pool conf (e.g. /etc/php/8.2/fpm/pool.d/www.conf): increase `pm.max_children` within RAM budget, then `systemctl reload php8.2-fpm`.
5. Recheck the warning stops and pages return 200; when children are no longer maxed, conclude (submit) immediately.

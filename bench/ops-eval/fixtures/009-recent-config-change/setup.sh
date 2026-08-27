#!/usr/bin/env bash
# TRUE ROOT CAUSE: a RECENT CONFIG CHANGE. Someone edited /etc/shopapp/app.conf and set db_port=5433 (a
# typo — the database actually listens on 5432). The app now 500s on every request.
#
# THE TRAP: the app's error says "cannot connect to database 127.0.0.1:5433", so the obvious conclusion is
# "the database is down" — but the DATABASE IS HEALTHY AND LISTENING ON 5432. Blaming the DB is the
# misdiagnosis. The only thing that changed on this box in the last 24h is that config file.
set -u
mkdir -p /var/log/app

# 1) The database is UP and stays up (listening on 5432).
python3 -u /opt/db/db.py 2>> /var/log/app/db.log &
sleep 0.5

# 2) THE RECENT CHANGE: repoint the app at the wrong port (mtime = now; everything else is 30 days old).
sed -i 's/^db_port=5432$/db_port=5433/' /etc/shopapp/app.conf

# 3) Start the app; drive a request so the failure is logged (naming the port it tried).
python3 -u /opt/app/app.py 2>> /var/log/app/app.log &
for _ in $(seq 1 30); do curl -s -o /dev/null http://127.0.0.1:8080/ && break; sleep 0.2; done
curl -s -o /dev/null http://127.0.0.1:8080/ || true
sleep 0.3

echo "fault injected: app.conf db_port 5432->5433 (just now); DB is HEALTHY on 5432; app 500s"

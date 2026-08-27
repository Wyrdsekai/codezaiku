#!/usr/bin/env bash
# TRUE ROOT CAUSE: a rogue/leftover process is already bound to :8080, so the real app cannot bind that
# port and exits with "Address already in use". RED HERRING: a scary but irrelevant stack trace in the
# app log pointing at a code exception, to lure a shallow diagnosis into blaming the application code.
set -u

# 1) A rogue listener squats on :8080 (e.g. a stray debug server someone left running).
python3 -c "import socket,time
s=socket.socket(socket.AF_INET,socket.SOCK_STREAM)
s.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)
s.bind(('0.0.0.0',8080)); s.listen(16)
open('/tmp/rogue.pid','w').write(str(__import__('os').getpid()))
time.sleep(10**9)" &
sleep 0.5

# 2) The real app tries to start, fails to bind, and logs the fatal error (the TRUE evidence).
mkdir -p /var/log/app
python3 /opt/app/app.py >> /var/log/app/app.log 2>&1 &
sleep 0.5

# 3) RED HERRING: a plausible-but-irrelevant application stack trace.
cat >> /var/log/app/app.log <<'EOF'
Traceback (most recent call last):
  File "/opt/app/handlers.py", line 88, in process
    result = compute_widget(payload)
ValueError: invalid widget id (this line is historical noise, not today's failure)
EOF

echo "fault injected: :8080 held by rogue pid=$(cat /tmp/rogue.pid 2>/dev/null); app cannot bind"

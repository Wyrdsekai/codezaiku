match: rabbitmq, rabbit, amqp
signature: disk_free_alarm, resource_limit_alarm, blocked connection, connection.blocked, disk free limit, publishers blocked, blocked connection timeout
push: rescue
status: candidate
# RabbitMQ disk_free_alarm blocking publishers — fix procedure
1. Publishers are BLOCKED (publish confirms hang / "blocked connection") while consumers keep running —
   RabbitMQ raised a disk_free_alarm because free disk on the data dir fell below `disk_free_limit`.
2. Diagnose the CAUSE: `rabbitmq-diagnostics status` (or `rabbitmqctl status`) shows both `disk_free` (real
   free space) and `disk_free_limit`. Compare them.
3. Fix by cause: if `disk_free_limit` is set unreasonably HIGH vs real free disk (a misconfig), lower it to a
   sane value — `rabbitmqctl set_disk_free_limit <bytes, e.g. 50000000 for 50MB>`. If the disk is genuinely
   near-full, FREE SPACE instead (delete stray files / grow the volume). The alarm clears once free_disk >
   disk_free_limit.
4. Confirm a publish succeeds again, then conclude.

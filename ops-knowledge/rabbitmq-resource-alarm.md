match: rabbitmq, rabbit, amqp
signature: disk_free_alarm, resource_limit_alarm, disk free limit, connection.blocked, mem_alarm, blocked connection, publishers blocked
push: immediate
status: validated
# RabbitMQ resource alarm blocking publishers — fix procedure
1. A RabbitMQ resource alarm (disk_free_alarm or memory) applies flow control: ALL publishing connections
   are BLOCKED (publish confirms never arrive) while consumers keep running — producers hang.
2. Identify the alarm: `rabbitmq-diagnostics alarms` (or `rabbitmqctl status`, or GET /api/nodes →
   `disk_free_alarm`/`mem_alarm`). disk_free_alarm = free space on the data dir fell below disk_free_limit.
3. Fix the RESOURCE, not the alarm: for a disk alarm, FREE SPACE on the RabbitMQ data dir — delete stray
   large files/dumps or grow the volume — until free > disk_free_limit. Do NOT just lower disk_free_limit on
   a genuinely full disk; that strips the safety margin. The alarm auto-clears once the resource recovers.
4. Confirm a publish succeeds again, then conclude.

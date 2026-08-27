match: rabbitmq
signature: resource limit alarm, memory resource limit, disk resource limit, publishers will be blocked
push: rescue
status: candidate
# RabbitMQ resource alarm (publishers blocked) — fix procedure
1. "memory resource limit alarm set" or "disk resource limit alarm" means RabbitMQ crossed a watermark and
   has BLOCKED all publishers to protect itself — connections hang rather than error.
2. Confirm which alarm and why: `rabbitmqctl status` (or `rabbitmq-diagnostics alarms`) shows the active
   alarm; check whether memory use is real backlog (deep queues) or disk is genuinely low (`df -h`).
3. Clear the cause: drain/consume the backed-up queues or purge dead ones to release memory; free disk for
   the disk alarm. As a stopgap, raise the watermark
   (`rabbitmqctl set_vm_memory_high_watermark <frac>` / `set_disk_free_limit`).
4. When the alarm clears and publishers unblock, conclude (submit) immediately.

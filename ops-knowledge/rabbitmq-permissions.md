match: rabbitmq, rabbit, amqp
signature: not_allowed, access to vhost, access refused, refused for user, (530), permissions
push: rescue
status: candidate
# RabbitMQ user permissions revoked — publish/consume refused — fix procedure
1. The app cannot publish or consume: "NOT_ALLOWED - access to vhost '/' refused for user X" (AMQP error
   530). The user's permissions on the virtual host were cleared, so it has no configure/write/read rights.
2. Confirm: `rabbitmqctl list_permissions -p /` — the affected user is missing or shows empty regexes.
3. Restore full permissions on the vhost: `rabbitmqctl set_permissions -p / <user> ".*" ".*" ".*"` (grants
   configure/write/read). Then confirm a publish succeeds and conclude.

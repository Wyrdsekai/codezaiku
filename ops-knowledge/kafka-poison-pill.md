match: kafka
signature: kafka consumer-group lag stalled, deserialization error, serializationexception, poison pill
push: rescue
status: candidate
platform: kubernetes
# Kafka consumer stalled by an unprocessable record (poison pill) — fix procedure
1. A consumer group whose LAG grows while CURRENT-OFFSET stays frozen (consumer pods Running) is stuck on
   an unprocessable record; restarts re-read it and stall again. Fix the OFFSET, not the pods.
2. Identify the group + stuck topic: `kubectl exec <kafka-pod> -n <ns> -- kafka-consumer-groups.sh
   --bootstrap-server localhost:9092 --describe --group <group>` (try /opt/kafka/bin/ if not on PATH).
3. ALL THREE, in order (a restart alone does NOTHING without the reset): scale the consumer to 0; skip the
   record — `kubectl exec <kafka-pod> -- kafka-consumer-groups.sh --bootstrap-server localhost:9092
   --group <group> --topic <topic> --reset-offsets --shift-by 1 --execute`; scale the consumer back to 1.
4. Re-describe the group — when CURRENT-OFFSET advances and LAG drains, conclude (submit) immediately.

match: kafka
signature: notleaderforpartition, offsetoutofrangeexception, shrinking isr, offline partitions
push: rescue
status: candidate
# Kafka broker/partition fault — fix procedure
1. Symptoms differ by cause: "UnderReplicatedPartitions" and "NotLeaderForPartitionException" point at a
   BROKER that left the ISR; consumer "rebalancing"/growing lag points at a stuck consumer group.
2. Check the cluster: `kafka-topics.sh --bootstrap-server <b> --describe --under-replicated-partitions`
   (which partitions lost replicas) and `kafka-consumer-groups.sh --describe --group <g>` (LAG per member).
3. Fix by cause: a down/ISR-shrunk broker → restart it and let replicas catch up (ISR refills); a wedged
   consumer → restart its pods so the group rebalances; wrong offset → reset with kafka-consumer-groups.
4. Recheck under-replicated count is 0 and lag is draining, then conclude (submit) immediately.

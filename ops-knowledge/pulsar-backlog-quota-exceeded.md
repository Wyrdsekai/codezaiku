match: pulsar
signature: backlog quota exceeded, producerblockedquotaexceeded, producer on topic with backlog quota exceeded
push: rescue
status: candidate
# Pulsar backlog quota exceeded — fix procedure
1. "producer on topic with backlog quota exceeded" / ProducerBlockedQuotaExceededError = a stuck subscription's backlog hit the quota; the broker holds new producers.
2. Confirm: `pulsar-admin topics stats <topic>` (backlogSize + each sub msgBacklog); `pulsar-admin namespaces get-backlog-quotas <ns>`.
3. Fix the stuck consumer (restart / fix acks) so it drains; unblock now by clearing backlog: `pulsar-admin topics expire-messages-all-subscriptions <topic> -t 1h` or `skip-all -s <sub>`; if quota truly too small: `pulsar-admin namespaces set-backlog-quota <ns> --limit 10G --policy producer_request_hold`.
4. Recheck stats backlog falls and producers create, then conclude (submit) immediately.

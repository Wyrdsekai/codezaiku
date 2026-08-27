match: activemq
signature: usage manager store is full, producer flow control, stopping producer
push: rescue
status: candidate
# ActiveMQ store full, producers blocked — fix procedure
1. "Usage Manager Store is Full ... Stopping producer" = the persistent store hit systemUsage.storeUsage; producer flow control is blocking sends.
2. Confirm: `activemq-admin query --view MemoryPercentUsage,StorePercentUsage` (or the web console) — StorePercentUsage at 100; check KahaDB dir size vs the storeUsage limit in activemq.xml.
3. Fix: drain/consume or purge the backed-up queues (console Purge, or `activemq purge <queue>`) so the store frees; free disk on the KahaDB volume; if sized wrong, raise `<storeUsage limit="8 gb"/>` in `<systemUsage>` and restart.
4. Recheck StorePercentUsage drops and producers send again, then conclude (submit) immediately.

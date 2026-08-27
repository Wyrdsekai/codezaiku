match: etcd, quay.io/coreos/etcd, registry.k8s.io/etcd
signature: mvcc: database space exceeded, alarm:nospace, database space exceeded
push: rescue
status: candidate
# etcd NOSPACE database space exceeded — fix procedure
1. "etcdserver: mvcc: database space exceeded" with a raised NOSPACE alarm = the backend bbolt db hit --quota-backend-bytes; etcd now rejects all writes.
2. Confirm: `etcdctl endpoint status -w table` (db size at quota) and `etcdctl alarm list` shows NOSPACE.
3. Fix in order — compact, defrag, disarm: get rev from `etcdctl endpoint status --write-out=json`, then `etcdctl compact <rev>`; `etcdctl defrag --cluster`; `etcdctl alarm disarm`.
4. Recheck: `etcdctl alarm list` is empty and a test `etcdctl put k v` succeeds — when writes are accepted, conclude (submit) immediately.

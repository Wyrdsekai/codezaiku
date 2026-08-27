match: redis
signature: misconf redis is configured to save, unable to persist on disk, bgsave error
push: rescue
status: candidate
# Redis persistence failure (MISCONF / stop-writes) — fix procedure
1. "MISCONF Redis is configured to save RDB snapshots but is currently unable to persist on disk" means a
   background save FAILED and, with stop-writes-on-bgsave-error on, Redis now rejects every write.
2. Find why BGSAVE fails — almost always the data dir: `redis-cli CONFIG GET dir`, then check that path for
   FULL disk (`df -h`) or wrong ownership/permissions (the redis user must be able to write it).
3. Fix the disk/permission cause, then force a save to clear the flag: `redis-cli BGSAVE` (watch
   `redis-cli INFO persistence` for rdb_last_bgsave_status:ok).
4. Recheck writes succeed again, then conclude (submit) immediately.

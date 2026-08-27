# Process Scheduling Patterns

## When to use
- Understanding or modifying how an OS allocates CPU time to processes and threads
- Diagnosing latency issues caused by scheduling decisions
- Configuring real-time scheduling for latency-sensitive workloads
- Designing systems that interact with scheduler behavior (thread pools, work queues)

## Pattern

### Completely Fair Scheduler (CFS)
- Default scheduler for normal (SCHED_NORMAL) tasks on Linux
- Virtual runtime: each task tracks how much CPU time it has consumed, weighted by priority (nice)
- Red-black tree keyed by virtual runtime — task with lowest vruntime runs next
- Fairness: over any sufficiently long window, each task gets its fair share proportional to weight
- Granularity: minimum scheduling slice prevents excessive context switching (default ~1-4ms)
- EEVDF (Earliest Eligible Virtual Deadline First) replaced CFS in Linux 6.6+

### Real-Time Scheduling
- `SCHED_FIFO`: runs until it yields, blocks, or is preempted by higher-priority RT task
- `SCHED_RR`: like FIFO but with time quantum — round-robin among same-priority tasks
- `SCHED_DEADLINE`: earliest deadline first, each task declares runtime/deadline/period
- RT tasks always preempt normal tasks — a runaway RT task starves everything else
- RT throttling: limit RT tasks to 95% of CPU (default) to prevent total system lockup

### Priority Inversion
- High-priority task blocked waiting for lock held by low-priority task
- Meanwhile, medium-priority task preempts the low-priority task — inversion
- Priority inheritance: temporarily boost lock holder to waiter's priority
- Priority ceiling: lock acquires a pre-defined ceiling priority — prevents chained inversion
- Design: minimize lock hold time, prefer lock-free data structures for shared RT resources

### Preemption Models
- **Voluntary preemption**: kernel code yields at explicit preemption points — lower overhead, higher latency
- **Full preemption (PREEMPT)**: kernel code preemptible except in critical sections — better latency
- **PREEMPT_RT**: nearly all kernel code preemptible, spinlocks become sleeping locks — hard RT
- Preemption disabled regions: spinlocks, RCU read-side, per-CPU data access
- Context switch cost: TLB flush, cache pollution, pipeline flush — typically 1-10 microseconds

### CPU Affinity and Topology
- CPU affinity: pin tasks to specific cores to improve cache locality
- NUMA awareness: scheduler prefers running task on the node where its memory resides
- CPU isolation (isolcpus): dedicate cores to specific tasks, remove them from general scheduling
- Symmetric multiprocessing: load balancer migrates tasks between CPUs for utilization
- Migration latency: moving a task to another core invalidates L1/L2 cache — not free

### Scheduling Domains and Groups
- Scheduling domains model the CPU topology: SMT → core → package → NUMA node
- Load balancing frequency increases at lower levels (SMT balanced more often than NUMA)
- Task groups (cgroups): allocate CPU shares to groups of tasks, not just individual tasks
- Bandwidth control: cgroup can be limited to N% of CPU regardless of idle capacity

## Gotchas / Anti-patterns
- Running latency-sensitive tasks at SCHED_NORMAL — they get preempted by batch jobs
- SCHED_FIFO priority 99 for application tasks — conflicts with kernel threads at same priority
- Busy-wait loops in RT tasks — consumes 100% CPU, starves everything on that core
- Not accounting for SMT: two threads on the same core share execution resources
- Setting affinity to a single core without understanding interrupt routing — IRQs on that core add jitter
- Cgroup CPU limit without understanding period/quota — short bursts get throttled unnecessarily
- Assuming nanosleep(1ms) wakes in 1ms — scheduler granularity and timer resolution affect actual wake time

## References
- Linux Scheduler Documentation: https://docs.kernel.org/scheduler/
- EEVDF Scheduler: https://lwn.net/Articles/925371/
- PREEMPT_RT Wiki: https://wiki.linuxfoundation.org/realtime/start
- CPU Scheduling (Operating Systems: Three Easy Pieces): https://pages.cs.wisc.edu/~remzi/OSTEP/cpu-sched.pdf
- CFS Design Document: https://docs.kernel.org/scheduler/sched-design-CFS.html

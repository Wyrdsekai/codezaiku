# GPU Scheduling

## When to use
- Multiple users or teams sharing a pool of GPU resources
- Balancing interactive inference workloads with batch training jobs
- Preventing resource starvation and ensuring fair allocation
- Maximizing utilization of expensive GPU hardware

## Pattern

### Resource Allocation Models

**Exclusive allocation**:
- One job gets full access to one or more GPUs
- Simplest model, no contention, predictable performance
- Wasteful when jobs do not fully utilize GPU memory or compute
- Use for training jobs that genuinely need full GPU resources

**Time-sliced sharing**:
- Multiple jobs share a GPU by taking turns
- NVIDIA MPS (Multi-Process Service) enables concurrent kernel execution
- Context switching overhead makes this unsuitable for latency-sensitive work
- Use for development and experimentation workloads

**Memory partitioning (MIG)**:
- NVIDIA Multi-Instance GPU splits A100/H100 into isolated instances
- Each instance gets dedicated memory, compute, and cache
- Fixed partition sizes (e.g., A100 80GB splits into up to 7 instances)
- Use for inference serving where multiple small models coexist

### Priority Queuing

Define priority tiers:
- **P0 (critical)**: Production inference, cannot be preempted
- **P1 (high)**: Scheduled training runs with deadlines
- **P2 (normal)**: Development fine-tuning and experiments
- **P3 (low)**: Background research, hyperparameter sweeps, idle-time jobs

Preemption policy:
- Higher priority jobs can preempt lower priority jobs
- Preempted jobs must checkpoint before yielding (or accept restart from last checkpoint)
- P0 jobs are never preempted
- Set maximum preemption wait time (e.g., 60 seconds to checkpoint and vacate)

### Scheduling Strategies

**FIFO with priority**: Simple queue, higher priority jumps ahead. Works for small teams.
**Fair share**: Each user or team gets a guaranteed fraction of resources. Unused share is available to others.
**Gang scheduling**: Multi-GPU training jobs must get all requested GPUs simultaneously (no partial allocation).
**Backfill**: Short, low-priority jobs fill gaps while waiting for large job resources to become available.

### Quota and Limits

- Set per-user or per-team GPU-hour quotas (daily, weekly, or monthly)
- Limit maximum concurrent GPUs per user to prevent monopolization
- Enforce maximum job duration (kill or checkpoint after N hours)
- Reserve a fraction of capacity for interactive/urgent work

### Implementation Approaches

**Kubernetes + device plugin**: GPU scheduling via resource requests and limits. Works with NVIDIA device plugin. Supports MIG and time-slicing via configuration.

**SLURM**: Traditional HPC scheduler. Mature GPU support with GRES (generic resources). Better for pure training workloads. Supports preemption, fair share, and accounting.

**Custom queue**: Simple for small teams. Job submission script checks GPU availability, queues if full. Track usage in a shared database. Sufficient for 2-5 users with a handful of GPUs.

## Gotchas / Anti-patterns
- Allowing unlimited job duration without checkpointing requirements (one runaway job blocks everyone)
- Not distinguishing between training and inference scheduling needs (different latency and preemption requirements)
- Using exclusive GPU allocation for small inference models that use 20% of VRAM
- Implementing preemption without checkpoint-and-resume support (preempted work is lost)
- Setting quotas without monitoring actual utilization (quotas may not reflect reality)
- Scheduling multi-GPU jobs without gang scheduling (partial allocation wastes resources waiting)
- Ignoring GPU memory as a scheduling dimension (two jobs may fit compute-wise but OOM together)
- Not accounting for CPU, RAM, and disk alongside GPU scheduling (training often needs all four)

## References
- NVIDIA MIG guide: https://docs.nvidia.com/datacenter/tesla/mig-user-guide/
- NVIDIA MPS documentation: https://docs.nvidia.com/deploy/mps/
- Kubernetes GPU scheduling: https://kubernetes.io/docs/tasks/manage-gpus/scheduling-gpus/
- SLURM GPU scheduling: https://slurm.schedmd.com/gres.html

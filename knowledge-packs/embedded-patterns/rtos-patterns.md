# RTOS Patterns

## When to use
- Hard or soft real-time constraints where deadline misses are unacceptable or degrading
- Multiple concurrent activities with different timing requirements
- Hardware interaction requiring deterministic response times
- Systems too complex for bare-metal superloop but too constrained for a full OS

## Pattern

### Task Design
- One responsibility per task — don't multiplex unrelated work in a single task
- Periodic tasks for regular activities (sensor reading, control loops, heartbeats)
- Event-driven tasks for aperiodic work (button press, message arrival, interrupt handling)
- Keep tasks short — long-running work blocks lower-priority tasks from meeting deadlines
- Deferred processing: ISR posts to queue, task does the heavy work

### Priority Assignment
- Rate Monotonic: highest priority to highest frequency task (optimal for fixed-priority preemptive)
- Deadline Monotonic: highest priority to shortest deadline (when deadline != period)
- Never assign the same priority to unrelated tasks — non-deterministic scheduling
- Leave priority gaps for future tasks — renumbering priorities is painful
- Idle task at lowest priority for power management and watchdog feeding

### Synchronization
- Mutexes with priority inheritance to prevent unbounded priority inversion
- Binary semaphores for signaling between tasks or from ISR to task
- Counting semaphores for resource pools (buffer slots, connection handles)
- Avoid recursive mutexes — they mask design problems where lock ordering is unclear
- Keep critical sections as short as possible — disable interrupts only for atomic multi-word access

### ISR Design
- ISRs must be fast: acknowledge hardware, capture data, defer processing
- No blocking calls in ISRs — no mutex locks, no memory allocation, no printf
- Use ISR-safe queue/semaphore APIs (xQueueSendFromISR, not xQueueSend)
- Shared data between ISR and task: volatile + critical section or lock-free ring buffer
- Nest interrupts only when priority scheme is well understood and tested

### Timing Analysis
- Worst-case execution time (WCET) for each task and ISR — measure, don't guess
- Schedulability analysis: sum of (WCET_i / period_i) must be within utilization bound
- Stack usage analysis: static analysis tools + watermarking at runtime
- Watchdog timer as last resort — if the system misses deadlines, it resets cleanly

### Communication
- Message queues for producer-consumer between tasks — type-safe, bounded, non-copying where possible
- Event flags/groups for multi-condition synchronization
- Direct task notifications as lightweight alternative to semaphores (1:1 only)
- Shared memory + mutex only when queue overhead is genuinely too high

## Gotchas / Anti-patterns
- Priority inversion without inheritance — high-priority task blocked by low-priority task
- Unbounded priority: making everything "high priority" means nothing is prioritized
- Polling in high-priority tasks — wastes CPU and starves lower tasks
- Dynamic memory allocation in real-time paths — non-deterministic timing
- Deadlocks from inconsistent lock ordering across tasks
- Stack overflow from deep call chains or large local variables — silent corruption
- Using RTOS tick as a precision timer — tick resolution is typically 1ms, not microsecond

## References
- FreeRTOS Kernel Developer Docs: https://www.freertos.org/Documentation/
- Zephyr RTOS Kernel Services: https://docs.zephyrproject.org/latest/kernel/services/index.html
- Rate Monotonic Analysis (SEI): https://insights.sei.cmu.edu/documents/631/1993_005_001_16047.pdf
- MISRA C Guidelines for safety-critical: https://www.misra.org.uk/
- Jack Ganssle, "The Art of Designing Embedded Systems" (reference text)

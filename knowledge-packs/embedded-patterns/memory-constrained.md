# Memory-Constrained Design Patterns

## When to use
- Targets with kilobytes (not megabytes) of RAM
- Systems where heap fragmentation leads to eventual failure
- Safety-critical systems requiring deterministic memory behavior
- Long-running embedded devices that cannot be rebooted to reclaim memory

## Pattern

### Stack vs Heap
- Prefer stack allocation for all short-lived data — deterministic, no fragmentation
- Size stacks conservatively but verify with watermark checks or static analysis
- Avoid recursion — bounded iteration uses predictable stack space
- Each RTOS task has its own stack — account for all tasks plus interrupt stack

### Memory Pools (Fixed-Block Allocators)
- Pre-allocate pools of fixed-size blocks at startup — allocation is O(1) and cannot fragment
- One pool per object type: message buffers, sensor readings, protocol frames
- Pool exhaustion is a design-time problem, not a runtime surprise — size pools for worst case
- Return blocks to pool promptly — leaked blocks are permanently lost capacity

### Fragmentation Avoidance
- If you must use a heap, use a best-fit or TLSF allocator designed for real-time
- Allocate all long-lived objects at startup, before any dynamic allocation
- Same-size allocations from same region — blocks are interchangeable, no fragmentation
- Monitor high-water mark of heap usage — if it only grows, you have a leak

### Static Allocation
- Allocate everything at compile time when possible — arrays, buffers, task stacks
- Use linker scripts to place critical data in specific memory regions (TCM, SRAM banks)
- Compile-time assertions on structure sizes — catch layout changes before they overflow buffers
- Global pools initialized once in main() before scheduler starts

### Data Structure Choices
- Fixed-capacity ring buffers over dynamically-growing queues
- Intrusive linked lists (node embedded in object) over separately-allocated list nodes
- Flat arrays with index-based references over pointer-chasing structures
- Bit fields and packed structs for protocol frames — but mind alignment and portability

### Memory-Mapped I/O
- Volatile pointers for hardware registers — compiler must not optimize away reads/writes
- Use register definition structs mapped to base addresses, not raw pointer arithmetic
- Barrier instructions (DSB, DMB) between register accesses when ordering matters
- Read-modify-write on shared registers requires critical section or atomic access

## Gotchas / Anti-patterns
- Using malloc/free in a long-running system without fragmentation analysis
- Stack overflow corrupting adjacent memory silently — enable MPU stack guards if available
- Assuming sizeof(struct) is the sum of field sizes — padding and alignment add bytes
- String operations (sprintf, strcat) with unchecked buffer bounds
- Copying large buffers on the stack — overflows the task stack
- Dynamic allocation in ISRs — most allocators are not ISR-safe
- Ignoring alignment requirements — hard faults on Cortex-M, silent corruption on others

## References
- TLSF Allocator: http://www.gii.upv.es/tlsf/
- FreeRTOS Memory Management: https://www.freertos.org/a00111.html
- Barr Group Embedded C Coding Standard: https://barrgroup.com/embedded-systems/books/embedded-c-coding-standard
- ARM Cortex-M Memory Protection Unit: https://developer.arm.com/documentation/
- Embedded Artistry Memory Management: https://embeddedartistry.com/fieldmanual-terms/memory-management/

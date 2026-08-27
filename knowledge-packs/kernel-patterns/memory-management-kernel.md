# Kernel Memory Management Patterns

## When to use
- Implementing or modifying virtual memory subsystems
- Writing kernel code that allocates memory under various constraints
- Understanding page fault handling, memory reclaim, and OOM behavior
- Designing memory-efficient kernel data structures

## Pattern

### Virtual Memory
- Each process has its own virtual address space mapped via page tables
- Demand paging: pages allocated on first access (page fault), not at mmap time
- Copy-on-write (COW): forked processes share pages until one writes — then a private copy is made
- Kernel virtual address space is shared across all processes — mapped in upper portion
- Large pages (2MB, 1GB) reduce TLB pressure for large contiguous allocations

### Page Tables
- Multi-level hierarchy (4 or 5 levels on x86-64) — walk from PGD to PTE
- TLB caches translations — flush required on page table changes (targeted > full flush)
- Page table entries encode permissions (read/write/execute), dirty/accessed bits, present bit
- PCID (process context ID) allows TLB entries to survive context switch
- Per-architecture implementation — abstracted behind generic page table API

### Slab Allocator
- Object caches for frequently allocated same-size structures (inodes, dentry, task_struct)
- Eliminates per-allocation overhead — objects are pre-initialized and recycled
- Per-CPU caches reduce lock contention on allocation hot paths
- SLUB is the default on Linux — simpler than original SLAB, better debugging
- Use `kmem_cache_create()` for your own object types; `kmalloc()` for generic allocations

### Memory Allocation Strategies
- `GFP_KERNEL`: may sleep, may trigger reclaim — the default for most allocations
- `GFP_ATOMIC`: never sleeps, limited reserves — for interrupt context and spinlock holders
- `GFP_NOWAIT`: like GFP_ATOMIC but without dipping into emergency reserves
- `vmalloc()` for large non-contiguous allocations — page table overhead, no DMA
- `alloc_pages()` for contiguous physical memory — needed for DMA, hardware buffers
- Memory pools (`mempool_create`) for guaranteed allocation from pre-reserved pages

### OOM Handling
- OOM killer selects a process to kill when all reclaim paths are exhausted
- Scoring: oom_score based on resident memory, adjusted by oom_score_adj
- Kernel allocations should handle failure gracefully — not every allocation needs to succeed
- Cgroup memory limits trigger per-cgroup OOM before system-wide OOM
- `__GFP_NOFAIL`: allocation will retry forever — use only when failure truly cannot be handled

### Page Reclaim
- LRU lists: active/inactive for anonymous and file-backed pages
- kswapd reclaims pages in background when watermarks are crossed
- Direct reclaim when kswapd can't keep up — synchronous, latency impact on allocator
- Writeback: dirty file pages written to backing store before freeing
- Swap: anonymous pages written to swap device/file — configured per policy

## Gotchas / Anti-patterns
- Large `kmalloc()` under spinlock — may fail and can't sleep to reclaim
- Forgetting to free memory on error paths — kernel memory leaks accumulate over uptime
- Using `vmalloc()` for small allocations — unnecessary overhead
- Holding references to pages without incrementing refcount — use-after-free when reclaim runs
- Kernel stack overflow from deep call chains — kernel stacks are small (8KB-16KB)
- GFP_KERNEL allocation in interrupt context — triggers sleeping in atomic context BUG
- Not handling allocation failure — NULL dereference panic crashes the kernel

## References
- Understanding the Linux Virtual Memory Manager (Mel Gorman): https://www.kernel.org/doc/gorman/
- Linux kernel memory management docs: https://docs.kernel.org/mm/
- SLUB allocator: https://docs.kernel.org/mm/slub.html
- LWN memory management articles: https://lwn.net/Kernel/Index/#Memory_management
- Jonathan Corbet, "Linux Device Drivers" Chapter 8 (Allocating Memory)

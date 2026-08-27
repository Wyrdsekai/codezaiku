# GPU Memory Management Patterns

## When to use
- Managing buffers and textures in explicit graphics APIs (Vulkan, D3D12, Metal)
- Streaming assets into GPU memory within a fixed budget
- Designing upload strategies for dynamic data (per-frame uniforms, particles)
- Avoiding GPU memory fragmentation in long-running applications

## Pattern

### Memory Types
- **Device-local**: fastest for GPU access, not CPU-visible — use for static geometry, textures
- **Host-visible, device-local (BAR/ReBAR)**: CPU-writable, GPU-fast — ideal for dynamic data if available
- **Host-visible, host-coherent**: CPU-writable staging memory — use for upload buffers
- Query memory heaps at startup and adapt strategy to available hardware
- Budget: leave headroom (10-20%) below reported capacity — driver and OS have overhead

### Buffer Management
- Suballocate from large buffer allocations — avoid per-object Vulkan/D3D12 allocations
- Ring buffer for per-frame dynamic data: write at current offset, advance, wrap around
- Frame fence: don't overwrite data still in use by the GPU — track per-frame fence values
- Staging buffer: CPU writes to host-visible memory, then GPU copies to device-local
- Persistent mapping: map host-visible buffers once at creation, keep mapped for the lifetime

### Texture Streaming
- Virtual textures / sparse resources: allocate only the mip levels and tiles actually needed
- Feedback buffer: GPU reports which tiles/mips were sampled, CPU schedules loading
- Priority queue: load tiles nearest to camera first, highest-used mips first
- Eviction: when budget is exceeded, evict least-recently-used tiles, fall back to lower mip
- Transcoding: load compressed format (Basis/KTX2), transcode to GPU-native (BC7/ASTC) on CPU

### Upload Heaps
- Double or triple buffer staging memory: CPU writes frame N+1 while GPU reads frame N
- Batch uploads: collect all per-frame updates, issue a single copy command batch
- Async transfer queue: upload on a dedicated transfer queue parallel to rendering
- Defragmentation: periodically compact device-local memory by copying and updating references
- Large uploads (level load): spread across multiple frames to avoid hitches

### Descriptor Sets / Resource Binding
- Bindless: all textures and buffers in a large descriptor array, index by integer in shader
- Reduces descriptor set changes to zero — one set bound for the entire frame
- Dynamic offsets for per-draw uniform data within a shared buffer
- Descriptor update templates (Vulkan) for efficient batch updates
- Material system: group textures used together in the same descriptor set to minimize rebinds

### Memory Allocators
- VMA (Vulkan Memory Allocator): industry-standard suballocator for Vulkan
- D3D12MA: equivalent for Direct3D 12
- Pool allocator: separate pools per resource lifetime (per-frame, per-level, persistent)
- Allocation strategy: prefer dedicated allocation for large resources (>256KB), suballocate small ones
- Track allocations: log allocation count, total size, fragmentation ratio

## Gotchas / Anti-patterns
- One VkDeviceMemory per buffer/image — Vulkan limits total allocations (often 4096)
- Writing to memory the GPU is currently reading — data race causes corruption or flicker
- Not aligning buffer offsets to device requirements — UB or crash on some hardware
- Blocking CPU to wait for GPU before uploading — pipeline bubble kills throughput
- Allocating and freeing GPU memory every frame — fragmentation and allocation overhead
- Ignoring non-coherent memory flush/invalidate — CPU writes not visible to GPU
- Exceeding VRAM budget without fallback — driver starts paging, frame rate collapses

## References
- Vulkan Memory Allocator (VMA): https://gpuopen.com/vulkan-memory-allocator/
- D3D12 Memory Allocator: https://gpuopen.com/d3d12-memory-allocator/
- Vulkan Memory Management: https://docs.vulkan.org/guide/latest/memory_allocation.html
- "GPU-Driven Rendering" (Wihlidal, SIGGRAPH): descriptor management patterns
- "Vulkan Dos and Don'ts" (NVIDIA): https://developer.nvidia.com/blog/vulkan-dos-donts/

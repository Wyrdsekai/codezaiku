# Graphics Optimization Patterns

## When to use
- Rendering performance is below target frame rate
- GPU or CPU bound and need to identify and fix the bottleneck
- Scaling rendering quality to support a range of hardware
- Reducing power consumption on mobile or battery-powered devices

## Pattern

### Draw Call Batching
- Each draw call has CPU overhead (state validation, command encoding) — minimize count
- Static batching: merge meshes sharing the same material into a single vertex buffer at build time
- Dynamic batching: combine small meshes at runtime into a single draw call — CPU cost of merging
- Instancing: draw many copies of the same mesh with per-instance transforms — one draw call
- Indirect drawing: GPU fills draw parameters buffer, CPU issues a single multi-draw-indirect call

### Level of Detail (LOD)
- Multiple mesh versions at decreasing polygon counts for each asset
- Switch based on distance from camera or screen-space size (projected pixel area)
- LOD transitions: discrete pop (acceptable with crossfade), continuous (mesh morphing/tessellation)
- Automatic LOD generation: mesh simplification tools (MeshOptimizer, Simplygon)
- Nanite-style: virtual geometry — GPU selects triangle clusters per-frame, no artist-authored LODs

### Culling
- Frustum culling: test bounding volume against camera frustum — discard everything outside
- Occlusion culling: skip objects hidden behind occluders (hierarchical Z-buffer, software rasterizer)
- Small object culling: skip objects whose screen-space projection is below a pixel threshold
- Backface culling: GPU-side, enabled by default — ensure consistent winding order
- Contribution culling: skip objects too faint to matter (very dim lights, tiny decals)

### GPU Profiling
- Frame capture tools: RenderDoc, Nsight Graphics, PIX, Metal GPU Capture
- Identify bottleneck: vertex-bound (simplify meshes), pixel-bound (reduce overdraw/shader complexity), memory-bound (reduce texture size)
- GPU timestamp queries: measure per-pass timing from within the application
- Pipeline statistics: count vertices processed, fragments shaded, overdraw ratio
- Thermal throttling: sustained heavy load reduces clock speed — profile under sustained conditions

### Overdraw Reduction
- Render opaque objects front-to-back — early depth test rejects hidden fragments before shading
- Depth pre-pass: render depth only, then shade only visible fragments — eliminates all overdraw
- Alpha test and discard are expensive — prevents early depth rejection on many GPUs
- Transparent objects: render back-to-front, minimize transparent surface area
- Overdraw visualization mode: color pixels by how many times they were written

### Bandwidth Optimization
- Texture compression (BC/ASTC): 4-8x reduction in bandwidth with minor quality loss
- Render target format: use the smallest format that maintains quality (R11G11B10F for HDR color)
- Tiled rendering (mobile): keep intermediate data on-chip, avoid external memory load/store
- Subpass dependencies (Vulkan): merge passes to keep data in tile memory
- Mipmap usage: always generate mipmaps for 3D-sampled textures — reduces cache thrashing

### Shader Optimization
- Avoid dependent texture reads — compute UV fully before sampling
- Use mediump/half precision where full precision isn't needed — doubles throughput on some GPUs
- Move per-draw computation to vertex shader, per-vertex to CPU, per-frame to uniform buffer
- Minimize register pressure — complex shaders reduce occupancy, reducing latency hiding
- Specialize shader variants for common cases — remove unused features via compile-time defines

## Gotchas / Anti-patterns
- Optimizing the GPU when the CPU is the bottleneck (or vice versa) — profile first, then optimize
- Reducing polygon count when the scene is fragment-bound — wrong bottleneck
- Alpha-to-coverage instead of alpha test without MSAA — undefined behavior
- Profiling in debug mode or with validation layers enabled — not representative of release
- LOD popping without crossfade — visually distracting, players notice
- Batching everything including rarely-visible objects — large batch with mostly-culled meshes wastes memory
- Async compute without measuring — can actually hurt performance if GPU is already fully utilized

## References
- GPU Performance Best Practices (ARM Mali): https://developer.arm.com/documentation/101897/latest/
- NVIDIA GPU Optimization Guide: https://developer.nvidia.com/performance
- RenderDoc: https://renderdoc.org/
- MeshOptimizer Library: https://github.com/zeux/meshoptimizer
- "Real-Time Rendering" (Akenine-Moller et al.) — optimization chapters

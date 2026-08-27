# Render Pipeline Patterns

## When to use
- Building a rendering system for a game or visualization application
- Choosing between forward and deferred rendering architectures
- Structuring render passes for a modern graphics API (Vulkan, D3D12, Metal)
- Optimizing rendering throughput for complex scenes

## Pattern

### Forward Rendering
- Each object drawn once per light (or accumulated in a single pass with light list)
- Simple: one pass per object, shader receives geometry + material + lights
- Transparent objects handled naturally — just blend during the forward pass
- Efficient for scenes with few lights or mobile/low-end hardware
- Clustered forward: divide view frustum into clusters, assign lights per cluster — scales to hundreds of lights

### Deferred Rendering
- Geometry pass: render all objects to G-buffer (albedo, normals, depth, roughness, metallic)
- Lighting pass: for each light, read G-buffer and accumulate lighting as screen-space quads
- Decouples geometry complexity from light count — lighting cost depends only on screen resolution
- Transparent objects still require a forward pass — deferred only works for opaque geometry
- G-buffer bandwidth is significant — 4+ render targets at full resolution

### Render Passes
- Organize rendering into explicit passes with defined inputs and outputs
- Shadow pass: render depth from each light's perspective into shadow maps
- Geometry pass: populate G-buffer or forward-render the scene
- Lighting pass: compute lighting from G-buffer + shadow maps
- Post-processing: bloom, tone mapping, AA applied to the final color buffer
- Each pass declares its attachments — enables automatic render pass merging on tiled GPUs

### Command Buffers
- Record rendering commands on CPU, submit to GPU as batches
- Build command buffers on multiple threads in parallel — one per render pass or per scene region
- Primary command buffers submitted to queue; secondary command buffers for reusable sub-sequences
- Sort draw calls within a pass: by pipeline state first (minimize state changes), then front-to-back for depth
- Triple-buffer command recording: frame N records while frame N-1 executes on GPU

### Frame Graph
- Declare all render passes and their resource dependencies as a directed acyclic graph
- Framework allocates transient resources, schedules passes, inserts barriers automatically
- Resources only live as long as needed — memory reused across non-overlapping passes
- Enables automatic pass reordering and parallel execution on async compute queues
- Simplifies adding/removing render features without manually managing resource lifetimes

### Visibility and Culling
- Frustum culling: discard objects outside the camera's view frustum
- Occlusion culling: skip objects hidden behind other geometry (HZB, GPU occlusion queries)
- LOD selection: render simpler meshes for distant objects
- GPU-driven rendering: visibility tests and draw call generation run on the GPU via compute shaders

## Gotchas / Anti-patterns
- Deferred rendering on mobile tiled GPUs without adaptation — G-buffer bandwidth kills performance
- Rendering transparent and opaque objects with the same pipeline — transparency needs special handling
- Recording command buffers single-threaded on modern APIs — wastes available parallelism
- Not sorting draw calls — excessive pipeline state changes dominate GPU time
- Shadow maps without cascades or atlas — one resolution doesn't serve all distances
- Ignoring async compute — post-processing and shadow updates can overlap with geometry rendering
- Manual resource barriers everywhere instead of a frame graph — error-prone and hard to optimize

## References
- Vulkan Render Pass Best Practices: https://docs.vulkan.org/guide/latest/render_pass.html
- "Rendering in Frostbite" (SIGGRAPH 2017): frame graph architecture
- "GPU-Driven Rendering Pipelines" (Wihlidal, SIGGRAPH 2015)
- LearnOpenGL — Deferred Shading: https://learnopengl.com/Advanced-Lighting/Deferred-Shading
- "Real-Time Rendering" (Akenine-Moller et al.) — comprehensive reference text

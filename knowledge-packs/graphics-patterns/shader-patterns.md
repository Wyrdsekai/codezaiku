# Shader Patterns

## When to use
- Writing or organizing shaders for a rendering engine
- Implementing physically-based materials for realistic lighting
- Using compute shaders for general-purpose GPU work
- Managing shader permutations across material types and features

## Pattern

### PBR Materials
- Base model: metallic-roughness (glTF standard) or specular-glossiness
- Inputs: albedo (base color), metallic, roughness, normal map, ambient occlusion, emissive
- Microfacet BRDF: Cook-Torrance specular (GGX distribution, Smith geometry, Fresnel-Schlick)
- Energy conservation: diffuse + specular never exceed incoming light
- Image-based lighting (IBL): pre-filtered environment map for specular, irradiance map for diffuse

### Shader Variants
- Feature permutations: skinning on/off, normal mapping on/off, shadow receiving on/off
- Use preprocessor defines to compile variants from a single source file
- Variant explosion: N features = 2^N variants — prune combinations that never occur
- Uber-shaders: one large shader with branches — simpler management, possible GPU divergence cost
- Specialization constants (Vulkan/SPIR-V): variant selection at pipeline creation without recompilation

### Shader Includes and Modules
- Common code (lighting, noise, math utilities) in shared include files
- Include guard or `#pragma once` equivalent to prevent double-inclusion
- GLSL: `#include` via extension or custom preprocessor; HLSL: native `#include`
- WGSL / SPIR-V module linking: emerging standards for proper module systems
- Keep includes small and focused — large includes increase compile time for all consumers

### Compute Shaders
- General-purpose GPU computation: particle updates, culling, image processing, physics
- Workgroups: define thread count per group and group count — total threads = groups * threads_per_group
- Shared memory (workgroup local): fast on-chip memory for inter-thread communication within a group
- Synchronization: `barrier()` within a workgroup; cross-workgroup sync via atomic operations or multiple dispatches
- Indirect dispatch: GPU generates dispatch parameters — enables GPU-driven pipelines

### Data Flow
- Uniform buffers: small, frequently updated data (matrices, time, camera) — fast access, limited size
- Storage buffers: large read/write data (instance transforms, particle arrays) — flexible, slightly slower
- Push constants (Vulkan) / root constants (D3D12): tiny per-draw data without buffer overhead
- Texture sampling: filtered reads from images — use for spatial data and pre-computed tables
- Image load/store: unfiltered direct texel access — use in compute and post-processing

### Debugging and Profiling
- Visualize intermediate values: output normals, roughness, or debug colors to screen
- Render doc / GPU debugger: step through draw calls, inspect textures and buffers
- Shader printf (where supported) for quick debugging — remove before profiling
- GPU timestamp queries around shader dispatches for per-pass timing
- Watch for register pressure: complex shaders spill to VRAM, killing occupancy

## Gotchas / Anti-patterns
- Branching on non-uniform data in fragment shaders — all threads in a warp take both paths
- Texture sampling in a loop with dynamic count — prevents hardware prefetch optimization
- Not normalizing interpolated normals — interpolation shortens them, lighting breaks
- Forgetting gamma correction — lighting math must happen in linear space
- Shader compilation at runtime causing hitches — pre-compile or warm the pipeline cache
- Shared memory bank conflicts — threads accessing the same bank serialize
- Unbounded loop in shader — GPU hangs with no timeout on some drivers

## References
- LearnOpenGL PBR: https://learnopengl.com/PBR/Theory
- Vulkan GLSL Best Practices: https://docs.vulkan.org/guide/latest/shader_best_practices.html
- HLSL Shader Model 6 Reference: https://learn.microsoft.com/en-us/windows/win32/direct3dhlsl/hlsl-shader-model-6
- Filament Material Guide (Google): https://google.github.io/filament/Materials.html
- GPU Gems (NVIDIA): https://developer.nvidia.com/gpugems/gpugems/contributors

# Asset Pipeline Patterns

## When to use
- Games or applications with non-trivial content (textures, models, audio, levels)
- Teams where artists and designers produce content in authoring tools
- Projects needing fast iteration with hot-reload during development
- Shipping to multiple platforms with different format requirements

## Pattern

### Import and Processing
- Source assets (PSD, FBX, WAV) are canonical — never modify them in the pipeline
- Import step converts source to intermediate representation (engine-native format)
- Processing steps: texture compression, mesh optimization, audio encoding, LOD generation
- Each step is a deterministic function: same input always produces same output
- Dependency tracking: if a shader changes, re-process all materials using that shader

### Asset Database
- Map source file to processed output(s) with metadata (hash, dependencies, settings)
- Content hash of inputs determines if reprocessing is needed — not file modification time
- Database survives across builds — only reprocess what changed (incremental pipeline)
- Store processing settings per-asset: texture resolution override, compression format, platform target
- Build manifests list all assets and their processed locations for the game to load

### Format Conversion
- Compress textures per-platform: BC7 (desktop), ASTC (mobile/console), basis/KTX2 (universal)
- Meshes: strip unused attributes, quantize positions/normals to 16-bit where quality allows
- Audio: platform-appropriate codec (Opus for streaming, ADPCM for short effects)
- Shaders: compile to target API bytecode (SPIR-V, DXIL, Metal IR) at build time, not runtime
- Version stamp processed assets — engine rejects stale formats after pipeline changes

### Hot Reload
- File watcher detects source asset changes during development
- Reprocess only the changed asset and its dependents
- Push updated asset to running game via network socket or shared memory
- Renderer/audio engine swaps resource references without restarting the game
- State preservation: hot-reloaded material keeps current parameter values

### Streaming
- Split large assets (open-world terrain, cinematic audio) into streamable chunks
- Priority queue: load what the camera will see next, evict what's behind
- Mip-level streaming: load low-res first, refine to full resolution as bandwidth allows
- Budget: fixed memory budget for streamed assets, enforce with eviction policy
- Async I/O: loading happens on background threads, game thread never blocks on disk

### Build Variants
- Per-platform builds: different texture formats, audio codecs, shader targets
- Quality tiers: high/medium/low texture resolution, LOD distances, effect complexity
- Localization: swap audio/texture assets per language without rebuilding code
- Development vs shipping: dev builds include debug symbols, uncompressed assets for speed
- Addressable assets: reference by stable ID, not file path — allows reorganization without code changes

## Gotchas / Anti-patterns
- Rebuilding all assets from scratch on every change — multi-hour build times at scale
- Source assets in the game's runtime directory — shipping PSD files to players
- File-path references that break when assets are moved or renamed
- No dependency tracking — changed texture not re-applied to materials using it
- Platform-specific source assets instead of processing from a single high-quality source
- Hot reload that only works for some asset types — breaks iteration flow
- Ignoring import settings in hash — same source file processed differently produces wrong cache hits

## References
- Unity Asset Pipeline: https://docs.unity3d.com/Manual/AssetWorkflow.html
- Unreal Content Pipeline: https://dev.epicgames.com/documentation/en-us/unreal-engine/assets-and-content-packs-in-unreal-engine
- Basis Universal (texture compression): https://github.com/BinomialLLC/basis_universal
- "Our Machinery" Asset Pipeline (archived blog): concepts of hash-based dependency tracking
- GDC: "Destiny's Asset Pipeline" (Bungie) — large-scale asset management patterns

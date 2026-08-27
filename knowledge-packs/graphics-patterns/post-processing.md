# Post-Processing Patterns

## When to use
- Adding screen-space visual effects after the main scene render
- Implementing tone mapping for HDR rendering pipelines
- Anti-aliasing to smooth jagged edges without supersampling
- Creating cinematic visual styles (bloom, depth of field, color grading)

## Pattern

### Bloom
- Threshold pass: extract pixels brighter than a luminance threshold
- Downsample: progressively halve resolution (mip chain), applying Gaussian blur at each level
- Upsample: progressively combine from smallest back to full resolution with additive blending
- Dual-filter approach: 13-tap downsample, bilinear upsample — efficient and artifact-free
- Threshold with soft knee: gradual transition instead of hard cutoff avoids flickering on edges

### Screen-Space Ambient Occlusion (SSAO)
- Sample depth buffer around each pixel to estimate local occlusion
- SSAO: random hemisphere sampling, typically 16-64 samples per pixel
- HBAO: horizon-based — ray-march in screen space along a few directions
- GTAO: ground-truth AO approximation with better accuracy at lower sample count
- Blur the AO buffer to hide noise from low sample counts — bilateral blur preserves edges

### Temporal Anti-Aliasing (TAA)
- Jitter camera position by sub-pixel offset each frame (Halton or blue noise sequence)
- Blend current frame with history buffer using exponential moving average
- Neighborhood clamping: clamp history color to the AABB of current frame's neighborhood — prevents ghosting
- Motion vectors: reproject history using per-pixel velocity — handles camera and object motion
- Sharpen after TAA to counteract the softening from temporal blending

### Tone Mapping
- HDR scene values (0 to unbounded) must be mapped to displayable range (0-1)
- Reinhard: simple `color / (1 + color)` — loses contrast in highlights
- ACES: filmic curve, industry standard, good highlight rolloff and color handling
- Tony McMapface / AgX: more recent alternatives addressing ACES hue shifts
- Exposure: apply before tone mapping — adjust via auto-exposure (histogram-based) or manual

### Effect Ordering
- Recommended order: SSAO → lighting composite → bloom → DOF → motion blur → tone mapping → color grading → AA → UI
- SSAO and bloom operate in linear HDR space — apply before tone mapping
- Color grading (LUT-based) applied after tone mapping in display-referred space
- Anti-aliasing last (or integrated via TAA throughout) — AA on already-aliased input is ineffective
- UI rendered after all post-processing — UI elements should not be bloomed or motion-blurred

### Depth of Field
- Circle of confusion (CoC) per pixel based on depth relative to focus distance
- Separate near-field and far-field blur — near-field bleeds over in-focus regions
- Bokeh shape: circular by default, shaped bokeh (hexagonal, anamorphic) with scatter or gather
- Half-resolution blur for far field is usually sufficient — reduces cost significantly
- Focus pull: animate focus distance for cinematic transitions

### Color Grading
- 3D LUT (lookup table): 32x32x32 or 64x64x64 color cube applied as texture lookup
- Create LUT in image editor or grading tool, export as strip texture
- Neutral LUT (identity) applied during development — swap for creative LUT in production
- Split toning: different color tints for shadows and highlights
- White balance, contrast, saturation as parameterized adjustments before LUT

## Gotchas / Anti-patterns
- Bloom without threshold — everything glows, looks like vaseline on the lens
- TAA without motion vectors — ghosting trails on moving objects
- Applying tone mapping twice (once in post-processing, once in display output)
- SSAO on sky pixels — false occlusion at depth discontinuities (mask by depth)
- Post-processing in gamma space instead of linear — incorrect blending and color math
- Full-resolution blur passes when half-res is perceptually identical — wasted bandwidth
- Not clamping TAA history — ghosting, temporal smearing, and trailing artifacts

## References
- "Next Generation Post Processing in Call of Duty: Advanced Warfare" (SIGGRAPH 2014)
- LearnOpenGL — HDR and Bloom: https://learnopengl.com/Advanced-Lighting/HDR
- "A Survey of Temporal Antialiasing Techniques" (Lei Yang, SIGGRAPH)
- ACES Tone Mapping: https://github.com/ampas/aces-dev
- "Physically Based Rendering" (Pharr, Jakob, Humphreys) — tone mapping chapter

# CAD File Formats

## When to use
- Exchanging geometry between different CAD systems or simulation tools
- Archiving designs for long-term retrieval and reproducibility
- Publishing models for manufacturing (CNC, 3D printing, injection molding)
- Integrating CAD data into downstream workflows (PLM, visualization, web)

## Pattern

### STEP (ISO 10303)
- **AP203**: geometry + product structure; broadest compatibility, use as default exchange format
- **AP214**: adds colors, layers, GD&T, assembly structure; preferred for automotive
- **AP242**: current edition combining AP203/AP214 + PMI (embedded tolerances), tessellation, kinematics
- Preserves: solid geometry (B-rep), assembly structure, basic metadata
- Loses: feature history, parametric constraints, sketches, construction geometry
- Use for: inter-company exchange, long-term archival (ISO-backed, vendor-neutral)

### IGES (Initial Graphics Exchange Specification)
- Legacy format, still encountered in older toolchains and some CNC workflows
- Surface-based: represents trimmed surfaces, not necessarily watertight solids
- Common issues: gaps between surfaces, duplicate entities, large file sizes
- Use for: legacy system integration only; prefer STEP for new workflows

### STL (Stereolithography)
- Tessellated triangular mesh; no units, no color, no metadata, no assembly structure
- ASCII and binary variants; binary is ~5x smaller, always prefer binary
- Quality controlled by chord deviation (max distance from true surface to mesh) and angle tolerance
- Use for: 3D printing input, quick visualization; never for precision manufacturing or archival

### 3MF (3D Manufacturing Format)
- Modern replacement for STL in additive manufacturing contexts
- Includes: mesh geometry, materials, colors, print ticket, build layout, units
- ZIP-based container with XML; extensible via OPC conventions
- Support extensions: beam lattices, slices, production scheduling
- Use for: 3D printing workflows where material/color/build info matters

### Format Conversion Strategy
- **Source of truth**: always keep the native parametric file as master; exports are derived artifacts
- **Round-trip testing**: export, re-import, compare geometry (bounding box, volume, surface area) to detect loss
- **Healing on import**: run automatic geometry repair (close gaps, merge edges, remove degenerate faces) after importing neutral formats
- **Batch conversion**: automate with headless CAD APIs or open-source tools (FreeCAD, OpenCascade) for large catalogs
- **Metadata mapping**: define a mapping table for layer/color/attribute conventions between source and target systems

### Choosing a Format
| Need | Format |
|---|---|
| Vendor-neutral exchange | STEP AP242 |
| 3D printing (simple) | STL binary |
| 3D printing (rich) | 3MF |
| Legacy CNC/CMM | IGES |
| Web visualization | glTF/GLB |
| Archival (decades) | STEP AP242 + native |

## Gotchas / Anti-patterns
- **STL as source of truth**: losing parametric data by only keeping the mesh export
- **Ignoring units on import**: STEP carries units but STL does not; always verify scale after import
- **IGES for solids**: IGES surfaces often have gaps; import as solid may fail without healing
- **Single-format archival**: archiving only the native format risks vendor lock-in; archive STEP alongside native
- **Over-tessellation**: exporting STL with unnecessarily fine mesh creates huge files with no manufacturing benefit
- **Assuming lossless conversion**: every format transition can lose data; validate geometry and metadata after each conversion

## References
- ISO 10303 (STEP) — full standard series, especially AP242 Ed2
- 3MF Consortium specification — https://3mf.io/specification/
- NIST STEP File Analyzer — validation tool for STEP conformance
- OpenCascade Technology (OCCT) — open-source geometry kernel supporting STEP/IGES
- LOTAR (Long Term Archival and Retrieval) — aerospace archival best practices for 3D data

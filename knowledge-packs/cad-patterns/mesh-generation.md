# Mesh Generation

## When to use
- Preparing CAD geometry for finite element analysis (FEA) or computational fluid dynamics (CFD)
- Converting continuous geometry into discrete elements for numerical simulation
- Balancing accuracy against computational cost for engineering analysis
- Geometry contains regions of varying complexity requiring different mesh densities

## Pattern

### Structured vs Unstructured Meshing
- **Structured (mapped) meshes**: use regular grids of hex/quad elements; preferred for simple geometries, give better accuracy per element, faster solver convergence
- **Unstructured meshes**: use tet/tri elements; handle arbitrary geometry, automated generation, but need more elements for equivalent accuracy
- **Hybrid approach**: structured mesh in critical regions (boundary layers, load paths), unstructured fill elsewhere
- Choose element type by physics: hex for structural stress, prism layers for CFD boundary layers, tet for complex organic shapes

### Element Quality Metrics
- **Aspect ratio**: keep below 5:1 for general analysis, below 3:1 near stress concentrations
- **Skewness**: equilateral deviation; keep below 0.8 (ideally below 0.5) for tets
- **Jacobian ratio**: measures element distortion; negative Jacobian = inverted element = invalid mesh
- **Warpage**: for quad/hex faces, keep below 15 degrees
- Run quality checks before submitting to solver; one bad element can dominate error or cause divergence

### Adaptive Refinement
- Start with a coarse mesh, solve, then refine based on error indicators (stress gradients, flux jumps)
- H-refinement: subdivide elements in high-error regions (most common)
- P-refinement: increase polynomial order of shape functions (fewer elements, higher per-element cost)
- HP-refinement: combine both; optimal convergence rate but complex implementation
- Set convergence criteria: refine until result change between iterations is below threshold (e.g., 2% stress change)

### Mesh Independence Study
- Run same analysis on 3+ mesh densities (coarse, medium, fine)
- Plot key result vs element count; result should plateau at acceptable mesh density
- Document the study: mesh sizes, result values, chosen density, and justification
- Automate where possible; re-run on geometry changes to catch mesh-sensitive regions

### Geometry Cleanup for Meshing
- Remove small features (fillets < element size, sliver faces, short edges) that create tiny elements
- Heal gaps and overlaps in imported geometry before meshing
- Defeaturing threshold: features smaller than 1/3 the local element size are candidates for removal
- Preserve features that matter to physics (crack tips, contact surfaces, load application regions)

## Gotchas / Anti-patterns
- **Mesh-too-fine-everywhere**: uniform fine mesh wastes compute; refine only where gradients exist
- **Ignoring boundary layers**: CFD without prism layers at walls gives wrong drag/heat transfer
- **Tet-only structural meshes**: tets are stiffer than hex; results can be artificially stiff unless mesh is very fine
- **Skipping convergence study**: reporting results from a single mesh density without independence evidence
- **Meshing before cleanup**: attempting to mesh dirty imported geometry, leading to failed or poor quality meshes
- **Over-defeaturing**: removing fillets that are actual stress concentrators invalidates stress results

## References
- Frey & George, "Mesh Generation" (2nd ed.) — comprehensive meshing theory
- NASA CFD Verification & Validation guidelines — mesh convergence best practices
- Knupp, "Algebraic Mesh Quality Metrics" — element quality metric definitions
- OpenFOAM / Gmsh / Salome documentation — open-source meshing tool references

# Simulation Integration

## When to use
- Coupling CAD geometry with finite element analysis (FEA) or computational fluid dynamics (CFD)
- Validating design performance before physical prototyping
- Running multi-physics simulations (thermal-structural, fluid-structure interaction)
- Automating design exploration through parametric simulation sweeps

## Pattern

### Geometry-to-Simulation Pipeline
- Export analysis-ready geometry: defeature small holes, fillets, chamfers that are irrelevant to the physics
- Maintain a separate "analysis geometry" derived from the master CAD model; link updates, do not fork
- Mid-surface extraction for thin-walled parts: reduces 3D solid to 2D shell elements, dramatically cuts solve time
- Partition geometry at load application points, material boundaries, and mesh density transitions before meshing

### Boundary Condition Application
- Map physical loads to mathematical boundary conditions: fixed support = zero displacement, pressure = distributed force
- Apply loads to geometry features (faces, edges, vertices), not to mesh entities; re-meshing should not break load definitions
- Use remote points / distributing couplings for loads applied at non-modeled locations (e.g., bolt preload at hole center)
- Verify reaction forces sum to applied loads as a sanity check on BCs
- Symmetry and cyclic boundary conditions: cut model size by 2x-Nx when geometry and loads permit

### FEA Integration
- **Linear static**: start here for stiffness and stress screening; fast, well-understood
- **Nonlinear**: required for large deformations, contact, plasticity; 10-100x more expensive, needs convergence monitoring
- **Modal analysis**: extract natural frequencies; compare against excitation frequencies to avoid resonance
- **Fatigue**: post-process stress results with S-N or strain-life curves; requires stress history (not just peak)
- Material model selection: linear elastic for metals below yield, hyperelastic for elastomers, orthotropic for composites

### CFD Integration
- Define fluid domain as the negative space of the solid geometry (internal flow) or bounding box minus solid (external flow)
- Inlet: specify velocity/mass flow/pressure profile; outlet: pressure outlet or outflow
- Wall treatment: resolve boundary layer (y+ < 1 for low-Re models) or use wall functions (30 < y+ < 300)
- Turbulence model selection: k-omega SST for general external/internal flow; LES/DES for separation-dominated flows
- Monitor residuals and key quantities (drag, pressure drop, temperature) for convergence

### Multi-Physics Coupling
- **One-way coupling**: solve domain A, pass results as BC to domain B (thermal field as input to structural)
- **Two-way (strong) coupling**: iterate between solvers until both converge; needed when physics are tightly coupled (FSI with flexible structures)
- **Interface mapping**: interpolate results between non-matching meshes at the coupling surface; ensure conservation of forces/fluxes
- Stagger coupling steps with time step control; mismatched time steps between solvers cause instability

### Results Post-Processing
- Always plot deformed shape with scale factor to visually verify boundary conditions make sense
- Check: are displacements reasonable? Do stress contours show expected patterns? Is the solution mesh-converged?
- Extract quantities at specific locations (probe points) for comparison with test data or requirements
- Report safety factors against yield, ultimate, buckling, fatigue as design metrics
- Automate result extraction for parametric studies: write scripts to pull key values from each run

## Gotchas / Anti-patterns
- **Analysis on raw CAD geometry**: small features create mesh problems and add elements without improving accuracy
- **Single load case validation**: real parts see multiple load combinations; analyze the full load envelope
- **Ignoring contact**: assuming bonded connections between parts that actually slide or separate under load
- **CFD without mesh independence study**: CFD results are highly mesh-sensitive; always verify convergence
- **Mismatched units**: CAD in mm, solver expecting m; off by factor of 1000 in all results
- **Pretty pictures over validation**: contour plots without convergence checks, reaction force verification, or comparison to hand calculations

## References
- Bathe, "Finite Element Procedures" — foundational FEA theory
- Versteeg & Malalasekera, "Introduction to Computational Fluid Dynamics" — CFD fundamentals
- NAFEMS guidelines — simulation quality assurance best practices
- ASME V&V 10/20 — verification and validation standards for computational solid/fluid mechanics
- NASA-STD-7009A — models and simulations standard for NASA programs

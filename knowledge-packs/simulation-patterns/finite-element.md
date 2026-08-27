# Finite Element Method Patterns

## When to use
- Solving partial differential equations (PDEs) on complex geometries
- Structural mechanics, heat transfer, fluid flow, electromagnetics
- Problems where analytical solutions don't exist due to geometry or nonlinearity
- Engineering analysis requiring quantitative stress, strain, temperature, or field predictions

## Pattern

### Mesh Generation
- Discretize the domain into elements: triangles/quadrilaterals (2D), tetrahedra/hexahedra (3D)
- Structured mesh: regular grid — fast generation, efficient storage, limited to simple geometries
- Unstructured mesh: arbitrary connectivity — handles complex geometries, higher storage/access cost
- Mesh quality metrics: aspect ratio, skewness, Jacobian — poor elements degrade accuracy and stability
- Refinement: h-refinement (smaller elements), p-refinement (higher polynomial order), hp-adaptive (both)
- Boundary layer meshing: thin, high-aspect-ratio elements near walls for resolving gradients

### Element Types
- Linear elements (first-order): simple, cheap, require fine mesh for accuracy
- Quadratic elements (second-order): better accuracy per element, fewer elements needed, higher per-element cost
- Hexahedral > tetrahedral for accuracy at same DOF count — but harder to mesh complex geometries
- Isoparametric mapping: element geometry and solution use the same shape functions
- Special elements: shell (thin structures), beam (slender members), contact (surface interaction)

### Assembly
- Each element contributes a local stiffness matrix and load vector
- Assembly: sum element contributions into a global sparse matrix (overlapping DOFs are added)
- Sparse storage: CSR (Compressed Sparse Row) or CSC — global matrix is >99% zeros for large problems
- Boundary conditions: Dirichlet (fixed values) applied by modifying matrix rows, Neumann (flux) added to load vector
- Numbering optimization: bandwidth reduction (Cuthill-McKee) for direct solvers, not needed for iterative

### Solvers
- **Direct solvers** (LU, Cholesky): exact to machine precision, O(N^1.5) for 2D, O(N^2) for 3D banded
- **Iterative solvers** (CG, GMRES): approximate, scalable, O(N * iterations) — required for large 3D problems
- Preconditioners: ILU, multigrid, domain decomposition — reduce iteration count dramatically
- Conjugate Gradient (CG) for symmetric positive definite (structural, thermal)
- GMRES for non-symmetric systems (fluid, advection-dominated problems)
- Multigrid: solve on coarse mesh, refine — optimal O(N) complexity for elliptic problems

### Error Estimation
- A posteriori error estimators: compute error indicator per element from the solution itself
- Residual-based: measure how well the solution satisfies the PDE locally
- Recovery-based (ZZ estimator): compare raw gradient with smoothed gradient — cheap and effective
- Adaptive refinement: refine elements with highest error, re-solve, repeat until target accuracy met
- Convergence study: solve on progressively finer meshes, verify solution converges at expected rate

### Nonlinear and Time-Dependent
- Nonlinear: Newton-Raphson iteration — linearize, solve, update, repeat until convergence
- Load stepping: apply load incrementally for highly nonlinear problems (large deformation, plasticity)
- Time integration: implicit (Newmark, backward Euler) for stability, explicit for wave propagation
- Implicit: solve linear system each time step — stable at large dt, expensive per step
- Explicit: no system solve, conditionally stable — time step limited by smallest element

## Gotchas / Anti-patterns
- Insufficient mesh refinement in regions of high gradient — inaccurate results with no warning
- Distorted elements (high aspect ratio, negative Jacobian) — solver may converge to wrong solution
- Not performing a mesh convergence study — solution depends on mesh, not just the physics
- Linear elements for bending-dominated problems — locking (artificially stiff behavior)
- Direct solver on a million-DOF problem — memory exhaustion (O(N^2) fill-in for 3D)
- Ignoring units — all inputs must be in a consistent unit system
- Trusting the first result without validation against analytical solutions or experiments

## References
- "The Finite Element Method" (Zienkiewicz, Taylor, Zhu) — definitive FEM textbook
- FEniCS Project: https://fenicsproject.org/ — open-source FEM framework
- deal.II: https://www.dealii.org/ — adaptive FEM library in C++
- Gmsh (mesh generator): https://gmsh.info/
- "Introduction to the Finite Element Method" (Reddy) — accessible introduction
- PETSc (solvers): https://petsc.org/

# Physics Integration Patterns

## When to use
- Games or simulations requiring physically plausible motion
- Real-time physics with rigid bodies, particles, or soft bodies
- Collision detection and response at interactive frame rates
- Deterministic simulation for multiplayer or replay systems

## Pattern

### Integration Methods
- **Explicit Euler**: `pos += vel * dt; vel += acc * dt` — simple but energy-gaining, unstable at large dt
- **Semi-implicit (Symplectic) Euler**: update velocity first, then position with new velocity — energy-preserving, stable
- **Verlet**: `pos_new = 2*pos - pos_old + acc*dt^2` — velocity-free, good for constraints, stable
- **RK4** (Runge-Kutta 4th order): high accuracy but 4x the cost — use when accuracy matters more than speed
- For most games, semi-implicit Euler at a fixed timestep is sufficient and correct

### Collision Detection: Broadphase
- Purpose: quickly reject pairs that cannot possibly collide — reduces O(n^2) to O(n log n) or better
- **Spatial hashing**: divide world into grid cells, check only entities in same/adjacent cells
- **Bounding volume hierarchy** (BVH): tree of AABBs, update incrementally as objects move
- **Sweep and prune** (SAP): sort objects on each axis, overlapping intervals are candidates
- Choice depends on object distribution: grid for uniform, BVH for clustered, SAP for mostly-static

### Collision Detection: Narrowphase
- Operate on candidate pairs from broadphase — produce contact points, normals, penetration depth
- **GJK** (Gilbert-Johnson-Keerthi): convex vs convex, iterative support function
- **SAT** (Separating Axis Theorem): test all potential separating axes, efficient for boxes and convex polygons
- **EPA** (Expanding Polytope Algorithm): find penetration depth after GJK confirms overlap
- Sphere-sphere, AABB-AABB: closed-form solutions, always use specialized tests for simple shapes

### Collision Response
- Impulse-based: compute impulse that resolves velocity along contact normal, apply to both bodies
- Coefficient of restitution (0-1): controls bounciness — 0 is perfectly inelastic, 1 is perfectly elastic
- Friction: tangential impulse opposing sliding — Coulomb model with static/kinetic coefficients
- Penetration resolution: push objects apart by penetration depth, or use positional correction (Baumgarte stabilization)
- Contact caching: reuse contact data across frames for stable resting contact

### Constraint Solving
- Joints, contacts, and limits modeled as constraints on relative position/velocity
- Sequential impulse solver: iterate through constraints, apply corrective impulses, converge over iterations
- More iterations = more accurate stacking and joint behavior (8-20 iterations typical)
- Warm starting: use previous frame's impulse as initial guess — dramatically improves convergence
- Constraint order affects convergence — randomize or use priority ordering

### Determinism
- Fixed timestep is mandatory — different dt produces different results
- Use consistent floating-point mode (no fast-math, consistent rounding)
- Process entities in deterministic order (by ID, not pointer address)
- Same broadphase pair order each frame — affects impulse resolution order
- Cross-platform: IEEE 754 compliance varies, test on all target platforms

## Gotchas / Anti-patterns
- Explicit Euler for springs — exponential energy gain, objects fly off to infinity
- Variable timestep physics — non-deterministic, tunneling at low frame rates
- Missing broadphase — O(n^2) narrowphase kills performance at >100 objects
- Tunneling: fast objects pass through thin walls — use swept tests or CCD (continuous collision detection)
- Infinite mass objects colliding with each other — division by zero in impulse calculation
- Modifying physics state from rendering code — breaks determinism and causes glitches
- Over-constraining: more constraints than degrees of freedom — solver oscillates or explodes

## References
- "Game Physics Engine Development" (Ian Millington)
- Erin Catto's GDC presentations (Box2D author): https://box2d.org/publications/
- "Real-Time Collision Detection" (Christer Ericson)
- Gaffer on Games — Physics: https://gafferongames.com/categories/game-physics/
- Jolt Physics (modern C++ engine): https://github.com/jrouwe/JoltPhysics

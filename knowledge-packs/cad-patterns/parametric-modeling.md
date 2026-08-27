# Parametric Modeling

## When to use
- Building CAD models that must adapt to changing dimensions or configurations
- Creating families of parts that share geometric relationships
- Design requires capturing engineering intent, not just final geometry
- Models will undergo iterative refinement through design reviews

## Pattern

### Constraint-Based Design
- Define geometry through constraints (parallel, tangent, coincident, dimensional) rather than absolute coordinates
- Fully constrain sketches: under-constrained sketches drift; over-constrained sketches reject valid changes
- Use reference geometry (planes, axes, points) as stable anchors for feature positioning
- Prefer geometric constraints over dimensional where intent is relational (e.g., "centered" vs "offset 50mm")

### Feature Tree Organization
- Order features by manufacturing logic: stock shape, primary machining, secondary features, finishing
- Group related features using folders or named sections in the feature tree
- Place datum features (planes, axes, coordinate systems) at the top of the tree for visibility
- Minimize parent-child depth: deep chains make edits fragile and rebuild times slow

### Design Intent Capture
- Name every feature, sketch, and parameter with descriptive identifiers (not "Extrude1")
- Drive dimensions from a master sketch or spreadsheet of parameters, not inline literals
- Use equations/relations to link dependent dimensions (wall thickness = outer - inner, not a separate number)
- Document non-obvious constraints with comments in the model or linked design notes

### Multi-Body and Assembly Strategy
- Use multi-body modeling for parts manufactured as one piece but designed in sections
- Split into separate part files at assembly boundaries where independent manufacturing applies
- Define interfaces (mating faces, alignment datums) explicitly so assemblies survive part edits
- Parameterize at the assembly level for top-down design; propagate to parts via in-context references sparingly

## Gotchas / Anti-patterns
- **Sketch spaghetti**: cramming all geometry into one sketch instead of layering simple sketches per feature
- **Magic numbers**: hard-coded dimensions with no parameter name or link to design requirements
- **Circular references**: part A drives part B drives part A, causing rebuild failures
- **Over-reliance on in-context references**: breaks when assembly structure changes; prefer published geometry
- **Ignoring rebuild order**: moving features in the tree without understanding parent-child dependencies
- **Boolean-first modeling**: using boolean operations (cut, union) when direct sketch-based features are simpler and more editable

## References
- ISO 10303 (STEP) — geometry and constraint representation standards
- ASME Y14.5 — dimensioning and tolerancing (informs constraint choices)
- Camba et al., "Extended Design Intent Representation" (2014) — academic framework for DI capture
- CAD vendor documentation for parametric constraint solvers (each has solver-specific behaviors)

# Tolerance Analysis

## When to use
- Determining if assembled parts will fit and function given manufacturing variation
- Allocating tolerances to individual dimensions to meet assembly-level requirements
- Evaluating whether a design can be manufactured at acceptable cost and quality
- Performing root cause analysis on assembly failures or quality escapes

## Pattern

### GD&T Fundamentals
- Use geometric dimensioning and tolerancing (ASME Y14.5 / ISO 1101) over bilateral +/- tolerances for features that control fit
- Define datum reference frames (primary, secondary, tertiary) based on assembly function, not machining convenience
- Apply feature control frames: form (flatness, cylindricity), orientation (perpendicularity, angularity), location (position, concentricity), runout
- Position tolerance with MMC (Maximum Material Condition) for fastener clearance; RFS (Regardless of Feature Size) for precision fits
- Profile tolerances for complex surfaces where individual dimension tolerances are impractical

### Stack-Up Analysis Methods
- **Worst-case (arithmetic)**: sum all tolerances at extremes; guarantees 100% assembly but yields tight (expensive) individual tolerances
- **RSS (Root Sum of Squares)**: statistical combination assuming normal distribution and independent dimensions; ~99.73% assembly at 3-sigma
- **Modified RSS (Bender method)**: applies a correction factor between worst-case and RSS; use when some dimensions are correlated
- **1D chain**: dimensions along a single direction; simplest, solve with spreadsheet
- **2D/3D stack-up**: angular and multi-axis contributions; requires loop diagrams or specialized tools

### Monte Carlo Tolerance Simulation
- Assign statistical distributions to each dimension (normal, uniform, skewed based on process data)
- Run 10,000+ iterations sampling from distributions; collect assembly-level results
- Analyze: mean, standard deviation, Cpk, percent out-of-spec, sensitivity indices
- Sensitivity analysis: which individual dimension contributes most to assembly variation
- Use actual process capability data (Cp/Cpk from production) rather than assumed distributions when available

### Tolerance Allocation
- Start from assembly requirement (gap, clearance, alignment), work backward to component tolerances
- Tightest tolerances on dimensions with highest sensitivity (from Monte Carlo or partial derivatives)
- Consider manufacturing capability: standard machining ~0.05mm, precision ~0.01mm, grinding ~0.005mm
- Cost increases non-linearly with tighter tolerance; plot cost vs tolerance to find the knee
- Iterate: allocate, simulate, check assembly yield, reallocate

## Gotchas / Anti-patterns
- **All tolerances equal**: assigning identical tolerance to every dimension regardless of sensitivity
- **Worst-case-only in high-count stacks**: 15+ dimension chains with worst-case yields impossibly tight tolerances
- **Ignoring datum precedence**: different datum order changes the meaning of position tolerances entirely
- **Tolerance on basic dimensions**: basic dimensions locate true position; the tolerance is in the feature control frame, not on the dimension
- **No process feedback**: using textbook tolerances instead of measured capability from the actual manufacturing process
- **2D problem treated as 1D**: ignoring angular contributions in a stack-up that traverses direction changes

## References
- ASME Y14.5-2018 — Dimensioning and Tolerancing standard
- ISO 1101:2017 — Geometrical product specifications
- Drake, "Dimensioning and Tolerancing Handbook" — practical stack-up methods
- Creveling, "Tolerance Design" — Taguchi methods for tolerance allocation
- NIST GD&T resources — free educational materials and worked examples

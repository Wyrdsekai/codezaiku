# Timing Closure

## When to use
- FPGA design fails to meet timing constraints after place-and-route
- Pushing clock frequencies near the limit of the target device
- Design has paths crossing between clock domains or traversing large physical distances on-chip
- Post-implementation timing analysis shows negative slack on critical paths

## Pattern

### Timing Constraints (SDC/XDC)
- Define all clocks: primary (input pins), generated (PLLs, dividers), virtual (off-chip interfaces)
- Constrain all I/O paths: `set_input_delay` and `set_output_delay` relative to the appropriate clock
- Set `set_false_path` for truly asynchronous paths (CDC synchronizers, static configuration registers)
- Set `set_multicycle_path` for paths that are sampled every N cycles by design
- Constrain clock groups: `set_clock_groups -asynchronous` between unrelated clock domains
- Review unconstrained path reports: every path must be either constrained or explicitly excluded

### Pipelining for Timing
- Insert register stages to break combinational paths that exceed the clock period
- Target: combinational delay per stage should be 60-80% of clock period, leaving margin for routing and clock uncertainty
- Pipeline both data and control; misaligned pipeline stages cause functional bugs
- Consider pipeline flush and stall logic: adding stages changes latency and may require backpressure updates
- Retiming: allow synthesis tools to move registers across combinational logic to balance path delays (`retiming` attribute)

### Addressing Failing Paths
- **Logic optimization**: simplify combinational expressions, reduce mux depth, pre-compute conditions
- **Physical constraints**: floorplan critical modules close together; use Pblocks (Xilinx) or LogicLock (Intel)
- **Register duplication**: replicate high-fan-out registers to reduce routing congestion (synthesis attribute `max_fanout`)
- **SRL inference control**: shift registers inferred into SRL primitives cannot be retimed; force into flip-flops if on critical path
- **RAM-based**: replace large muxes or case statements with BRAM lookup tables

### Clock Architecture
- Use dedicated clock resources (BUFG, PLL, MMCM) for all clocks; never route clocks on general fabric
- Minimize clock domain count; each crossing is a verification and timing burden
- PLL/MMCM placement: close to I/O banks they serve; clock routing delay adds to uncertainty
- Phase-shifted clocks for source-synchronous interfaces: use PLL phase output, not combinational delay

### Incremental Compilation
- Lock down placement of timing-clean modules; only re-implement changed logic
- Use incremental synthesis and implementation flows to preserve timing closure across edits
- Mark stable IP cores and infrastructure as out-of-context (OOC) modules
- Track timing margin trends across builds; gradual erosion indicates growing congestion

## Gotchas / Anti-patterns
- **Missing constraints**: unconstrained paths are not timed; design "meets timing" but I/O interfaces fail
- **Over-constraining**: setting unrealistically tight clocks forces the tools into suboptimal placement; use realistic frequencies
- **False path abuse**: marking paths as false to hide real timing failures; every false_path needs design justification
- **Ignoring hold violations**: often caused by short paths or clock skew; tools usually fix with delay insertion, but verify
- **Routing congestion as timing failure**: high utilization (>80%) causes routing detours; reduce utilization or floorplan
- **Manual placement without profiling**: constraining placement based on intuition rather than timing analysis data

## References
- Xilinx UG903 "Vivado Design Suite User Guide: Using Constraints" — XDC constraint reference
- Intel Quartus "Timing Analyzer Cookbook" — SDC constraint patterns for Intel FPGAs
- Synopsys Design Constraints (SDC) specification — industry standard timing constraint format
- Cummings, "Static Timing Analysis for Nanometer Designs" — STA theory and methodology

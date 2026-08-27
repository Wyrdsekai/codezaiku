# HDL Design Patterns

## When to use
- Designing digital logic for FPGA or ASIC implementation
- Writing synthesizable RTL in Verilog/SystemVerilog or VHDL
- Building state machines, data paths, or control logic in hardware
- Crossing between clock domains or managing reset distribution

## Pattern

### RTL Coding Style
- Separate combinational and sequential logic in distinct always/process blocks for clarity and synthesis predictability
- Use `always_ff` for registers, `always_comb` for combinational logic (SystemVerilog); avoid plain `always @*` in new designs
- Avoid latches: ensure every signal assigned in combinational blocks has a value on every path (use default assignments at block top)
- Parameterize widths, depths, and counts with `parameter`/`generic`; never hard-code magic numbers in RTL
- One module per file, file name matches module name

### Finite State Machine Design
- Use two-process (or three-process) FSM pattern: one block for state register, one for next-state logic, optional one for output logic
- Enumerate states with `typedef enum` (SystemVerilog) or `type` (VHDL); avoid raw integer encoding
- Include explicit `default` case in state decoders mapping to a safe/idle state
- For one-hot encoding: let synthesis tools choose encoding via attributes rather than manual encoding in RTL
- Keep FSMs small (< 16 states); decompose complex control into hierarchical FSMs or datapath + microcode

### Clock Domain Crossing (CDC)
- **Single-bit signals**: double-flop synchronizer (two back-to-back registers in destination clock domain)
- **Multi-bit buses**: use gray-code encoding + synchronizer for counters; async FIFO for general data
- **Async FIFO**: gray-code read/write pointers synchronized across domains; standard proven pattern, do not roll your own
- **Pulse crossing**: convert pulse to level in source domain, synchronize level, detect edge in destination, acknowledge back
- Never assume timing relationships between asynchronous clocks; treat every crossing as potentially metastable
- Annotate all CDC paths with synthesis constraints (set_false_path or set_max_delay) and CDC tool waivers

### Reset Strategy
- **Asynchronous assert, synchronous deassert**: most robust pattern; ensures clean release aligned to clock
- Reset synchronizer: two-stage synchronizer on the async reset input to generate the synchronized reset
- Avoid mixing reset polarities within a design; standardize on active-low or active-high
- Minimize reset fan-out: use reset trees or local reset generation rather than one global net driving everything
- Some blocks (e.g., BRAM, DSP) may not need reset; avoid resetting resources where it wastes routing

### Pipeline Design
- Insert register stages to break long combinational paths and meet timing
- Pipeline control signals alongside data to maintain alignment
- Use valid/ready handshaking between pipeline stages for flow control with backpressure
- Skid buffers (2-deep FIFOs) at pipeline boundaries to decouple ready signal timing
- Document pipeline latency in module headers; it is part of the interface contract

## Gotchas / Anti-patterns
- **Inferred latches**: incomplete case/if statements in combinational blocks creating unintended storage
- **Multi-driven signals**: two blocks driving the same signal; synthesis error or unpredictable behavior
- **Clock gating without dedicated cells**: using logic gates on clock nets creates glitches; use vendor clock-enable primitives
- **Async reset on datapath**: resetting every register wastes routing; reset only control state, let data be invalid until valid asserts
- **Blocking assignments in sequential blocks**: causes simulation/synthesis mismatch; use `<=` (non-blocking) in `always_ff`
- **Rolling your own async FIFO**: subtle bugs in pointer synchronization; use proven IP or exhaustively verified designs

## References
- Cummings, "Simulation and Synthesis Techniques for Asynchronous FIFO Design" (SNUG 2002)
- Cummings, "Clock Domain Crossing (CDC) Design & Verification" (SNUG 2008)
- Sutherland, "RTL Modeling with SystemVerilog" — comprehensive coding style guide
- Xilinx/Intel FPGA coding guidelines — vendor-specific synthesis recommendations

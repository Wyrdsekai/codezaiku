# Synthesis Optimization

## When to use
- FPGA design exceeds resource budget (LUTs, FFs, BRAM, DSP slices)
- Need to trade off area, speed, and power for a given target device
- Arithmetic operations should map to DSP hard blocks instead of fabric logic
- Memory structures should infer BRAM/URAM instead of distributed RAM

## Pattern

### Resource Sharing
- **Time-multiplexed arithmetic**: if two additions never execute simultaneously, share one adder with a mux
- Synthesis tools perform automatic resource sharing within a process/block; help by structuring code to expose sharing opportunities
- Manual sharing: instantiate a single operator, mux inputs based on state, register partial results
- Trade-off: sharing saves area but adds mux delay and control complexity; profile before and after

### Area vs Speed Trade-offs
- **Speed-optimized**: duplicate logic, flatten hierarchy, unroll loops, pipeline heavily
- **Area-optimized**: share resources, use sequential (iterative) implementations, deeper pipelines with narrower datapaths
- Synthesis tool directives: `KEEP_HIERARCHY`, `DONT_TOUCH`, `MAX_FANOUT`, `USE_DSP48`, `RAM_STYLE`
- Start with speed-optimized for critical paths, area-optimized for everything else
- Utilization target: 70-80% LUT usage is practical maximum; beyond this, routing congestion degrades timing

### DSP Inference
- Write multiply-accumulate patterns that match the DSP primitive structure (pre-adder -> multiplier -> accumulator)
- Use signed arithmetic: DSP blocks are natively signed; unsigned operations may waste a bit
- Chain DSP blocks for wide multiplies or FIR filter taps using cascade ports (avoid fabric routing between DSPs)
- Avoid operations that prevent inference: asynchronous reset on accumulator, non-standard bit widths, logic mixed into the multiply chain
- Verify inference in synthesis report: check "DSP48 Usage" or equivalent; un-inferred multiplies consume many LUTs

### BRAM Usage
- Write memory patterns that infer BRAM: synchronous read, registered output, power-of-two depths
- True dual-port: both ports can read and write independently (use for dual-clock-domain shared memory)
- Simple dual-port: one port writes, one port reads (most common for FIFOs and buffers)
- Initialize BRAM contents via parameter/generic for lookup tables and ROM
- Avoid: asynchronous read (infers distributed RAM or LUTs), read-during-write behavior mismatches between simulation and synthesis
- URAM (UltraRAM, Xilinx): higher density, 72-bit wide, cascade-able; use for large buffers (>256KB)

### Distributed RAM and SRL
- Small memories (< 64 deep) efficiently infer into LUT-based distributed RAM
- Shift Register LUT (SRL): delay lines and small FIFOs infer into SRL primitives automatically
- SRL limitations: no reset, no direct access to intermediate taps (in most families)
- Force SRL or flip-flop inference with synthesis attributes when the tool chooses wrong

### Power Optimization
- Clock gating: disable clocks to unused regions (use vendor clock-enable primitives, not fabric gates)
- Data gating: hold register enable low when data is not changing; reduces toggle activity
- Reduce toggle rate on high-fan-out buses: gray-code counters, registered bus outputs
- Lower clock frequency for non-critical subsystems using derived clocks from PLL
- Block RAM over distributed RAM: BRAM consumes less dynamic power per bit for large storage

### Synthesis Reports to Review
- Resource utilization summary: LUT, FF, BRAM, DSP, IO — compare against device capacity
- Timing summary: worst negative slack (WNS), total negative slack (TNS), failing path count
- DSP and BRAM inference: verify arithmetic and memory mapped to hard blocks as intended
- High fan-out nets: candidates for register duplication or restructuring
- Critical path detail: identify the bottleneck — is it logic depth, routing, or clock uncertainty?

## Gotchas / Anti-patterns
- **Ignoring synthesis warnings**: "inferring latch" and "signal has no driver" warnings indicate real bugs
- **Manual instantiation of primitives**: use inference-friendly RTL first; instantiate only when inference fails
- **Reset on everything**: unnecessary resets prevent SRL inference, waste routing, and can prevent BRAM inference
- **Division in RTL**: hardware division is very expensive; use shifts (power-of-two) or reciprocal multiply
- **Large case statements**: N-way muxes grow O(N) in LUTs; consider BRAM lookup or binary search tree
- **Premature optimization**: optimize after profiling synthesis reports, not based on assumptions

## References
- Xilinx UG901 "Vivado Synthesis User Guide" — inference rules and attributes
- Intel Quartus "Synthesis Handbook" — synthesis directives and optimization settings
- Chu, "FPGA Prototyping by SystemVerilog Examples" — practical synthesis-friendly coding
- Xilinx UG579 "UltraScale Memory Resources" — BRAM/URAM architecture and inference
- Cummings, "Synthesizable Finite State Machine Design Techniques" (SNUG 1998)

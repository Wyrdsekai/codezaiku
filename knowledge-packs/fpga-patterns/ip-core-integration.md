# IP Core Integration

## When to use
- Incorporating vendor or third-party IP blocks into an FPGA design
- Building systems-on-chip with processor cores, memory controllers, and peripherals
- Connecting custom RTL to standard bus interfaces (AXI, Avalon, Wishbone)
- Packaging reusable design blocks for use across projects or teams

## Pattern

### AXI Interconnect
- **AXI4**: full-featured, burst-based for high-throughput memory-mapped access (DDR, large BRAM)
- **AXI4-Lite**: simplified single-beat for register access (control/status registers, configuration)
- **AXI4-Stream**: point-to-point data flow without addressing; use for DSP pipelines, DMA data paths
- Connect IP through an interconnect/crossbar for multi-master arbitration and address decoding
- Assign non-overlapping address ranges to each slave; document the memory map in a header file or register description

### AXI Protocol Essentials
- Five channels: AW (write address), W (write data), B (write response), AR (read address), R (read response)
- Handshake: transfer occurs when both VALID and READY are asserted on the same clock edge
- Never make VALID dependent on READY; this creates combinational loops and deadlocks
- READY can depend on VALID (wait for valid before accepting) but not the reverse
- Burst types: FIXED (FIFO access), INCR (sequential memory), WRAP (cache line fill)

### IP Packaging
- Define clear interface boundaries: clock, reset, bus interfaces, interrupts, side-band signals
- Use standard interface definitions (AXI, AXI-Stream) so the IP plugs into any system without glue logic
- Parameterize: data width, address width, FIFO depth, feature enables as generics/parameters
- Include register map documentation (IP-XACT / SystemRDL format) for automated driver generation
- Version IP with semantic versioning; breaking interface changes require major version bump

### Parameter Configuration
- Expose only parameters that users should change; derive internal parameters from top-level ones
- Validate parameter combinations at elaboration time with generate-if checks or assertions
- Common parameters: data width (8/16/32/64), clock frequency (for timing-aware IP), buffer depths
- Document valid parameter ranges and illegal combinations in the IP metadata

### Integration Testing
- Use vendor-provided verification IP (AXI VIP) to exercise bus interfaces with protocol checking
- Test edge cases: back-to-back transactions, simultaneous read/write, bus errors, timeout/watchdog
- Simulate the integrated system (processor + IP) with a firmware test program in simulation
- Check interrupt behavior: assert, clear, mask, edge vs level sensitivity
- Verify reset behavior: IP returns to known state, no bus hangs after reset

### System Architecture
- Place clock domain crossings at IP boundaries, not inside IP; each IP operates in a single clock domain
- Use DMA engines for bulk data movement; do not burn processor cycles on word-by-word transfers
- Interrupt coalescing: batch multiple events into one interrupt to reduce CPU overhead
- Shared resources (BRAM, external memory) need arbitration; define priority and fairness policy

## Gotchas / Anti-patterns
- **VALID-depends-on-READY**: AXI protocol violation causing deadlock; most common integration bug
- **Unconnected AXI signals**: leaving BRESP or RRESP unconnected hides bus errors
- **Ignoring backpressure**: AXI-Stream source must respect TREADY; dropping data silently corrupts results
- **Address map collisions**: overlapping slave address ranges cause unpredictable routing
- **Hardcoded addresses in RTL**: use parameterized base addresses from the system address map
- **No protocol checker**: integrating AXI IP without an AXI protocol checker in simulation misses violations

## References
- ARM AMBA AXI4 specification (IHI0022) — protocol definition
- Xilinx PG059 "AXI Interconnect" — interconnect IP usage
- IP-XACT (IEEE 1685-2014) — IP packaging and metadata standard
- SystemRDL 2.0 — register description language for IP register maps
- Wishbone B4 specification — open-source bus alternative

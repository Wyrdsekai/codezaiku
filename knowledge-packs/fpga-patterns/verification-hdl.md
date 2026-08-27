# HDL Verification

## When to use
- Verifying correctness of RTL designs before synthesis and implementation
- Building reusable testbench infrastructure for hardware modules
- Achieving coverage targets required for tape-out or FPGA sign-off
- Debugging complex timing-dependent or state-dependent hardware behavior

## Pattern

### Testbench Architecture
- Use a layered testbench: driver (stimulus) -> monitor (observation) -> scoreboard (checking) -> coverage (metrics)
- Separate testbench from DUT with well-defined interfaces; use SystemVerilog interfaces or VHDL records
- Self-checking testbenches: scoreboard compares DUT output against a reference model automatically
- Transaction-level modeling: drivers convert high-level transactions into pin-level wiggling; monitors do the reverse
- Clock and reset generation in a top-level harness module; parameterize clock period

### Constrained Random Verification
- Define transaction classes with randomizable fields and constraints that bound legal stimulus space
- Constraints encode protocol rules (e.g., burst length 1-16, address aligned) while allowing random exploration
- Use coverage-driven verification: run random tests, measure functional coverage, add directed constraints to fill holes
- Seed management: log random seeds for every run; replay any failure deterministically
- Weighted distributions: bias randomization toward interesting corners (near-empty FIFO, max burst, boundary addresses)

### Assertion-Based Verification
- **Immediate assertions**: check conditions at specific simulation points (like software assertions)
- **Concurrent assertions**: monitor signal sequences over time using SVA (SystemVerilog Assertions) or PSL
- Write protocol-level assertions: request must be followed by grant within N cycles, data valid only when enable is high
- Place assertions in the RTL source (white-box) for internal invariants and in the testbench (black-box) for interface contracts
- Assertions serve dual purpose: catch bugs in simulation and generate formal verification properties

### Coverage Strategy
- **Code coverage**: line, branch, toggle, FSM state/transition — aim for >95% as a baseline
- **Functional coverage**: define covergroups for important scenarios (all state transitions, boundary conditions, error injection)
- Cross coverage: combinations of relevant variables (e.g., operation type x data size x alignment)
- Coverage closure: track coverage over regression runs; create targeted tests for uncovered bins
- Do not chase 100% code coverage blindly; unreachable code (defensive defaults) should be excluded with justification

### Regression and CI
- Nightly regression: run full test suite across multiple random seeds
- Categorize tests: smoke (minutes), standard (hours), deep (overnight)
- Fail on: assertion violations, scoreboard mismatches, unexpected unknowns (X), coverage regression below threshold
- Waveform dumping: off by default in regression; enable on failure for debug
- Track coverage trends over time; coverage should monotonically increase as verification progresses

## Gotchas / Anti-patterns
- **Visual waveform inspection as primary verification**: does not scale; use self-checking testbenches
- **No random seed logging**: cannot reproduce failures without the seed
- **Testing only the happy path**: hardware must handle illegal inputs, partial transactions, resets mid-operation
- **Ignoring X-propagation**: unknowns in simulation that get optimized away in synthesis hide real bugs
- **Monolithic test files**: one huge test instead of composable sequences and reusable components
- **Coverage without review**: high coverage numbers from tests that do not actually check correctness (monitors disabled)

## References
- Bergeron, "Writing Testbenches using SystemVerilog" — layered testbench methodology
- Spear & Tumbush, "SystemVerilog for Verification" (3rd ed.) — constrained random and coverage
- IEEE 1800-2017 (SystemVerilog) — language reference for assertions and coverage
- UVM (Universal Verification Methodology) — standard class library for SV testbenches
- SymbiYosys — open-source formal verification framework for Yosys

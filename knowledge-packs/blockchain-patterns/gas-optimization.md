# Gas Optimization

## When to use
- Deploying contracts where transaction cost impacts user adoption
- Optimizing hot-path functions called frequently (transfers, swaps, claims)
- Reducing deployment cost for large contracts approaching the 24KB size limit
- Operating on Layer 1 where gas costs are significant (Layer 2 costs are dominated by calldata)

## Pattern

### Storage Layout Optimization
- Storage is the most expensive operation: SSTORE (new value) costs 20,000 gas; SSTORE (update) costs 5,000 gas; SLOAD costs 2,100 gas
- Pack variables into 32-byte slots: `uint128 a; uint128 b;` shares one slot; `uint256 a; uint128 b;` uses two slots
- Order struct fields by size descending to minimize padding, or group fields accessed together
- Use `uint256` for standalone variables (no packing benefit, avoids masking overhead)
- Mappings and dynamic arrays: each element occupies its own slot (keccak256-derived); cannot pack across entries
- Use `bytes32` over `string` for short fixed-length data; strings require extra length encoding

### Calldata vs Memory
- Function parameters: use `calldata` for external function array/bytes params (read-only, cheapest)
- `memory` copies data from calldata; necessary only if the function modifies the data
- `storage` pointers reference contract state directly; use for reading/writing existing state variables
- For view/pure functions: `calldata` parameters avoid unnecessary memory allocation

### Batch Operations
- Amortize fixed per-transaction overhead (21,000 base gas) across multiple operations
- Multicall pattern: array of encoded function calls executed in a loop; one transaction for N operations
- Batch transfers: single function transferring to multiple recipients; saves 21,000 * (N-1) base gas
- Batch minting: sequential tokenIds minted in one call; update balance once instead of N times

### Computation Optimization
- Cache storage reads: load to a local variable, operate on it, write back once (`uint256 _balance = balance; ... balance = _balance;`)
- Unchecked arithmetic: `unchecked { i++; }` in loops where overflow is impossible (saves ~80 gas per iteration)
- Short-circuit evaluation: put cheap checks and likely-to-fail conditions first in `require` chains
- Avoid redundant checks: if a called function already validates, do not re-validate in the caller
- Use `immutable` for constructor-set values and `constant` for compile-time values; both avoid SLOAD

### Deployment Optimization
- Use the optimizer: `solc --optimize --optimize-runs N`; low N (200) favors deployment cost; high N (1000000) favors runtime cost
- Clone pattern (EIP-1167 minimal proxy): deploy one full implementation, clone proxies for ~45 bytes each
- Libraries with `DELEGATECALL`: shared logic deployed once, called by many contracts
- Remove unused code: dead functions still cost deployment gas
- Shorter error strings or custom errors: `error InsufficientBalance()` is cheaper than `require(bal >= amt, "Insufficient balance")`

### Layer 2 Considerations
- L2 execution gas is cheap; the dominant cost is calldata posted to L1
- Minimize calldata size: use tightly packed structs, bitmap flags, compressed encodings
- Blob transactions (EIP-4844): data availability at reduced cost; relevant for rollup sequencers
- On L2, storage optimization still matters but computation optimization has less impact

## Gotchas / Anti-patterns
- **Premature optimization**: optimize after profiling gas; readability and security come first
- **Over-packing structs**: packing saves storage gas but adds masking computation; only pack if the slot is read/written together
- **Unchecked everywhere**: disabling overflow checks on user-supplied values creates vulnerabilities
- **String concatenation on-chain**: extremely expensive; emit events with individual fields instead
- **Looping over unbounded arrays**: gas cost scales with array length; can exceed block gas limit
- **Optimizer run count mismatch**: setting runs=200 then calling the function millions of times wastes runtime gas

## References
- EVM opcode gas costs — Ethereum Yellow Paper, Appendix G
- EIP-1167 — minimal proxy (clone) standard
- EIP-2929 — cold/warm storage access gas pricing
- Solidity documentation — "Gas Optimization" and "Layout of State Variables in Storage"
- Paradigm, "Foundry Gas Reports" — tooling for gas profiling with `forge test --gas-report`

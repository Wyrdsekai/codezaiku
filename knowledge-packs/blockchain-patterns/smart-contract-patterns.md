# Smart Contract Patterns

## When to use
- Writing Solidity or EVM-compatible smart contracts for deployment on public or private chains
- Designing contracts that handle value transfer, state mutation, or cross-contract interaction
- Building upgradeable contract systems that evolve without losing state
- Implementing access control for multi-role contract administration

## Pattern

### Checks-Effects-Interactions (CEI)
- **Checks**: validate all preconditions (require statements, access control) at the top of the function
- **Effects**: update all contract state (balances, flags, mappings) before any external calls
- **Interactions**: make external calls (transfers, cross-contract calls) last, after state is finalized
- This ordering prevents reentrancy attacks: even if the external call re-enters, state already reflects the update
- Complement with reentrancy guards (`nonReentrant` modifier) as defense in depth

### Access Control
- **Ownable**: single owner address for simple admin functions; transfer/renounce patterns
- **Role-based (RBAC)**: define roles (ADMIN, MINTER, PAUSER) as bytes32 constants; grant/revoke per address
- Use a timelock for sensitive operations: propose -> wait N blocks -> execute
- Multi-sig requirement for high-value operations (treasury withdrawals, contract upgrades)
- Separate operational roles from upgrade roles; compromise of one should not give both

### Upgrade Patterns
- **Transparent proxy**: proxy delegates all calls to implementation; admin calls go to proxy logic; users cannot accidentally call proxy admin functions
- **UUPS (Universal Upgradeable Proxy Standard)**: upgrade logic lives in the implementation contract; lighter proxy, but implementation must include upgrade mechanism
- **Beacon proxy**: multiple proxies share one beacon pointing to implementation; upgrade beacon to upgrade all proxies at once
- **Diamond (EIP-2535)**: multiple implementation contracts (facets) behind one proxy; fine-grained function-level routing
- Storage layout: use unstructured storage (EIP-1967 slots) for proxy admin variables; never collide with implementation storage
- Always include storage gap arrays (`uint256[50] __gap`) in upgradeable base contracts to allow future storage additions

### Emergency Mechanisms
- Circuit breaker (pausable): freeze contract operations when vulnerability detected
- Emergency withdrawal: allow users to withdraw funds even when contract is paused
- Time-delayed operations: critical changes require waiting period during which they can be cancelled
- Guardian role: separate from owner; can pause but not upgrade or withdraw

### Contract Structure
- Keep contracts focused: one responsibility per contract; compose via inheritance or delegation
- Library contracts for pure utility functions (math, string manipulation)
- Interface-first design: define IMyContract interface, then implement; external systems code against the interface
- Events for every state change: indexed parameters for filtering, non-indexed for data; events are the primary off-chain data source

## Gotchas / Anti-patterns
- **Reentrancy**: external call before state update allows attacker to re-enter and drain funds
- **Unchecked return values**: low-level `.call()` returns bool; ignoring it means silent failures
- **tx.origin for auth**: `tx.origin` is the EOA, not the calling contract; phishing attacks exploit this
- **Storage collision in proxies**: implementation storage layout must never change slot positions across upgrades
- **Selfdestruct reliance**: `selfdestruct` is deprecated in recent EVM upgrades; do not depend on it
- **Unbounded loops over dynamic arrays**: gas limit exceeded for large arrays; use pagination or pull patterns

## References
- OpenZeppelin Contracts — industry-standard implementations of common patterns
- Consensys "Smart Contract Best Practices" — security-focused development guide
- EIP-1967 — standard proxy storage slots
- EIP-2535 — Diamond/multi-facet proxy standard
- Trail of Bits "Building Secure Smart Contracts" — tooling and methodology

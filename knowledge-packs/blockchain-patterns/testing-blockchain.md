# Blockchain Testing

## When to use
- Validating smart contract correctness before deployment to mainnet
- Testing contract interactions with real protocol state via fork testing
- Finding edge cases through fuzz testing and invariant testing
- Building CI pipelines for contract development with automated verification

## Pattern

### Unit Testing
- Test each function in isolation with known inputs and expected outputs
- Test the happy path, boundary conditions, and revert conditions for every external/public function
- Use setUp/fixture functions to deploy contracts and set initial state before each test
- Assert on return values, state changes (storage), emitted events, and reverts
- Name tests descriptively: `test_transfer_revertsWhenInsufficientBalance`

### Foundry Testing (Solidity-native)
- Tests written in Solidity; inherit from `Test` base contract
- `vm.prank(address)`: impersonate caller for the next call
- `vm.expectRevert(bytes)`: assert the next call reverts with specific error
- `vm.expectEmit(true, true, false, true)`: assert specific event emission with topic matching
- `vm.warp(timestamp)` / `vm.roll(blockNumber)`: manipulate block context
- `vm.deal(address, amount)`: set ETH balance; `deal(token, address, amount)` for ERC-20
- `forge test --gas-report`: profile gas usage per function

### Hardhat Testing (JavaScript/TypeScript)
- Tests in JS/TS using Mocha/Chai with ethers.js
- `loadFixture(deployFixture)`: snapshot and restore for fast test isolation
- `expect(tx).to.emit(contract, "Event").withArgs(...)`: event assertion
- `expect(tx).to.be.revertedWith("message")` or `.to.be.revertedWithCustomError`
- `time.increase(seconds)` / `mine(blocks)`: time manipulation helpers
- `impersonateAccount(address)`: act as any address in tests

### Fork Testing
- Fork mainnet state at a specific block: test against real deployed contracts and balances
- Foundry: `forge test --fork-url <RPC_URL> --fork-block-number <BLOCK>`
- Hardhat: `hardhat_reset` with forking config in `hardhat.config.ts`
- Use for: testing integration with live protocols (Uniswap, Aave), upgrade simulations, incident reproduction
- Pin block number for deterministic tests; unpinned forks are non-reproducible
- Cache RPC responses locally to speed up repeated runs and reduce provider costs

### Fuzz Testing
- Foundry: prefix test with `testFuzz_` and add parameters; the fuzzer generates random inputs
- Set meaningful bounds with `vm.assume(x > 0 && x < 1e30)` to avoid wasted runs on invalid inputs
- Run count: default 256 runs; increase to 10,000+ for security-critical functions
- Fuzz testing finds edge cases that unit tests miss: overflow near boundaries, unexpected zero values, extreme amounts

### Invariant Testing
- Define properties that must always hold regardless of action sequence
- Examples: total supply equals sum of all balances; pool reserves satisfy x*y >= k; contract balance >= total deposits
- Foundry: `invariant_` prefix functions; configure target contracts and selectors in setUp
- Handler contracts: wrapper that constrains random calls to valid sequences (bound amounts, select existing users)
- Run with many sequences and calls per sequence: `runs = 256, depth = 128`
- Invariant failures produce a call sequence for reproduction; shrink to minimal failing sequence

### Formal Verification (Lightweight)
- Symbolic execution: tools like Halmos or HEVM check properties across all possible inputs
- Write properties as Solidity functions returning bool; tool explores all paths
- Effective for: arithmetic correctness, access control invariants, simple state machine properties
- Complementary to fuzz testing: formal is exhaustive but limited to simpler properties; fuzz handles complex scenarios

## Gotchas / Anti-patterns
- **Testing only the happy path**: contracts must handle reverts, zero values, max values, and reentrancy
- **Non-deterministic fork tests**: unpinned block numbers cause flaky tests as chain state changes
- **Slow test suites**: fork tests without caching or fixture reuse; use snapshots and local caching
- **Invariant tests without handlers**: unconstrained random calls mostly revert, providing little coverage
- **No event testing**: events are the primary data source for off-chain systems; untested events break indexers
- **Testing in isolation only**: contracts interact with others on-chain; fork tests catch integration issues unit tests miss

## References
- Foundry Book — https://book.getfoundry.sh/ (forge test, fuzz, invariant)
- Hardhat documentation — testing guide and network helpers
- Trail of Bits "Properties for Smart Contract Testing" — invariant design patterns
- Halmos documentation — symbolic testing for Solidity
- Crytic (Trail of Bits) Echidna — property-based fuzzer for smart contracts

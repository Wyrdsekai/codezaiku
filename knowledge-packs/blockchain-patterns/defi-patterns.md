# DeFi Patterns

## When to use
- Building decentralized exchange or liquidity pool mechanisms
- Implementing lending/borrowing protocols with collateralization
- Integrating external price data via oracles for on-chain financial logic
- Protecting protocols against flash loan exploits and price manipulation

## Pattern

### Automated Market Maker (AMM)
- **Constant product (x * y = k)**: simplest model (Uniswap v2); price determined by reserve ratio
- **Concentrated liquidity**: LPs provide liquidity in specific price ranges (Uniswap v3); higher capital efficiency, complex position management
- **Stable pools**: specialized invariant curve for like-kind assets (stablecoins); lower slippage near peg
- LP tokens represent proportional share of pool reserves; mint on deposit, burn on withdrawal
- Fee accrual: trade fees increase reserves, proportionally benefiting all LPs
- Price impact: large trades move the price significantly; implement slippage protection (minimum output amount)

### Lending Protocol Patterns
- **Over-collateralization**: borrowers deposit collateral worth more than the loan (typically 150%+ collateral ratio)
- **Interest rate model**: utilization-based curve; low utilization = low rates, high utilization = high rates to incentivize deposits
- **Liquidation**: when collateral ratio falls below threshold, third parties can repay debt and seize collateral at a discount
- **Health factor**: ratio of collateral value to debt value; liquidation triggers when health factor < 1
- Share-based accounting: depositors receive share tokens; share price increases as interest accrues (no per-user interest tracking)

### Oracle Integration
- **Chainlink**: decentralized oracle network; request latest price via `latestRoundData()`; check `updatedAt` for staleness
- **TWAP (Time-Weighted Average Price)**: compute average price over a window using on-chain cumulative price accumulators; resistant to single-block manipulation
- **Multi-oracle strategy**: query multiple sources, use median or require agreement; fallback to secondary oracle if primary fails
- Always validate oracle responses: check for zero price, stale data (timestamp too old), and round completeness
- Circuit breaker: if oracle price deviates more than X% from previous value, pause operations and alert

### Flash Loan Protection
- Flash loans provide uncollateralized loans within a single transaction; attacker can manipulate state and revert if unprofitable
- **Defense: TWAP over spot price**: do not use instantaneous pool reserves for pricing; use time-averaged prices
- **Defense: multi-block delay**: require that price-sensitive operations span multiple blocks (attacker cannot control consecutive blocks easily)
- **Defense: access control on sensitive functions**: restrict liquidations or large operations to approved actors or add time delays
- **Reentrancy guard**: flash loan callbacks can re-enter; apply `nonReentrant` on all state-mutating functions

### Protocol Safety Mechanisms
- **Debt ceiling**: maximum total debt per asset or globally; limits exposure to any single asset
- **Supply cap**: maximum deposits per asset; prevents concentration risk
- **Withdrawal queue**: for large withdrawals, process over multiple blocks to prevent bank-run dynamics
- **Governance timelock**: parameter changes (interest rates, collateral factors) require N-day delay before activation
- **Insurance fund**: reserve pool funded by protocol fees to cover bad debt from liquidation shortfalls

## Gotchas / Anti-patterns
- **Spot price as oracle**: using current pool reserves for pricing enables flash loan manipulation
- **No slippage protection**: trades without minimum output amount can be sandwich attacked
- **Precision loss in share math**: integer division truncation accumulates; use high-precision math (1e18 scaling) and round against the user
- **Single oracle dependency**: if the oracle goes down or is compromised, the entire protocol is at risk
- **Ignoring ERC-20 quirks**: fee-on-transfer, rebasing, and non-standard tokens break naive accounting
- **No liquidation incentive testing**: if liquidation bonus is too low, no one liquidates; if too high, borrowers are over-penalized

## References
- Uniswap v2/v3 whitepapers — AMM design and concentrated liquidity
- Aave/Compound protocol documentation — lending/borrowing reference implementations
- Chainlink documentation — oracle integration best practices
- Euler Finance post-mortem — flash loan attack case study
- OpenZeppelin "DeFi Security Best Practices" — security considerations for financial protocols

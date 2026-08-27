# Token Standards

## When to use
- Implementing fungible tokens (currencies, utility tokens, governance tokens)
- Creating non-fungible tokens (collectibles, real-world asset representations, access passes)
- Building multi-token systems (game items, mixed fungible/non-fungible collections)
- Designing token economics with supply controls, vesting, or fee mechanisms

## Pattern

### ERC-20 (Fungible Tokens)
- Core interface: `totalSupply`, `balanceOf`, `transfer`, `approve`, `transferFrom`, `allowance`
- Always emit `Transfer` event on every balance change (including mint/burn with zero address)
- Use `SafeERC20` wrapper for interacting with external ERC-20 tokens (handles non-standard return values)
- Approval race condition: use `increaseAllowance`/`decreaseAllowance` instead of raw `approve` to prevent front-running
- Decimals: 18 is conventional (matching ETH); use fewer for stablecoins or tokens representing discrete units

### ERC-721 (Non-Fungible Tokens)
- Each token has a unique `tokenId`; one owner per token
- Core: `ownerOf`, `transferFrom`, `safeTransferFrom`, `approve`, `setApprovalForAll`
- `safeTransferFrom` checks if receiver is a contract and calls `onERC721Received`; prevents tokens locked in non-compatible contracts
- Metadata extension: `tokenURI(tokenId)` returns URI to JSON with name, description, image
- Enumerable extension: `totalSupply`, `tokenByIndex`, `tokenOfOwnerByIndex` — adds gas cost to transfers; use only if on-chain enumeration is needed

### ERC-1155 (Multi-Token)
- Single contract manages multiple token types (fungible and non-fungible) identified by `id`
- Batch operations: `balanceOfBatch`, `safeBatchTransferFrom` — significant gas savings for multi-item transactions
- URI pattern: `uri(id)` with `{id}` substitution in the URI string (hex-encoded, zero-padded to 64 chars)
- Supply tracking: not built-in; add `totalSupply(id)` mapping if needed
- Use for: game inventories, mixed collections, SFTs (semi-fungible: fungible until redeemed, then unique)

### Extensions and Patterns
- **Mintable**: restricted mint function (role-gated) with optional supply cap
- **Burnable**: allow holders to destroy their tokens; `burn(amount)` and `burnFrom(account, amount)`
- **Pausable**: emergency stop on all transfers; combine with access control
- **Permit (ERC-2612)**: gasless approvals via EIP-712 signed messages; user signs off-chain, relayer submits
- **Votes (ERC-5805)**: delegation, checkpointing, voting power snapshots for governance
- **Snapshot**: record balances at specific blocks for airdrops or governance without delegation overhead
- **Soulbound**: non-transferable tokens (override transfer to revert); use for credentials, reputation

### Gas Optimization for Tokens
- Pack storage: use uint96 for balances if max supply fits (saves a storage slot when packed with address)
- Avoid per-transfer hooks unless needed: ERC-1155 `_beforeTokenTransfer` adds gas to every transfer
- Bitmap for claimed/minted status: one storage slot tracks 256 boolean flags
- Merkle tree allowlists: store root on-chain, prove eligibility off-chain; O(1) storage vs O(N) for mapping

## Gotchas / Anti-patterns
- **Missing zero-address checks**: minting to or transferring to address(0) should be explicitly handled
- **Rebase/fee-on-transfer tokens**: break assumptions in DeFi protocols that expect transfer amount = received amount
- **Unlimited approval**: `approve(spender, type(uint256).max)` is gas-efficient but risky if spender is compromised
- **Sequential tokenId for NFTs**: predictable IDs enable front-running; use commit-reveal or random assignment if order matters
- **Metadata on-chain**: storing images or large JSON on-chain is extremely expensive; use IPFS/Arweave URIs
- **Non-standard ERC-20**: some tokens (USDT) do not return bool from transfer; always use SafeERC20

## References
- EIP-20 — ERC-20 token standard
- EIP-721 — ERC-721 non-fungible token standard
- EIP-1155 — ERC-1155 multi-token standard
- EIP-2612 — permit extension for ERC-20
- OpenZeppelin token implementations — reference implementations with security audits

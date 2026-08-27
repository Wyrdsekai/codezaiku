# Multiplayer Networking Patterns

## When to use
- Games with real-time multiplayer requiring responsive player interactions
- Competitive games where fairness and cheat prevention are critical
- Cooperative games tolerating some inconsistency for better feel
- Any networked game dealing with latency, packet loss, and bandwidth constraints

## Pattern

### Client-Server Architecture
- Server is authoritative: validates all actions, owns canonical game state
- Client sends inputs (not positions) — server simulates and sends results back
- Client predicts locally for responsiveness, reconciles with server corrections
- Cheat prevention: server rejects impossible actions (speed hacks, invalid damage)
- Dedicated server preferred for competitive; host migration for casual P2P

### Client-Side Prediction
- Client applies input locally immediately — no waiting for server roundtrip
- Server processes same input and sends authoritative state back
- If prediction matches server, no visible correction needed
- If mismatch, client rewinds to server state and re-simulates with buffered inputs
- Only predict the local player's movement and actions — don't predict other players

### Server Reconciliation
- Server tags each state update with the last processed input sequence number
- Client discards all predicted states up to that sequence number
- Remaining predicted inputs are re-applied on top of server state
- Smooth correction: blend toward server state over a few frames to hide small errors
- Hard snap for large divergence — blending a 10-meter error looks worse than snapping

### Entity Interpolation (Remote Players)
- Display remote entities at a slightly past position (interpolation delay, typically 100-200ms)
- Buffer server snapshots, interpolate between the two most recent
- Smooth movement even with packet loss — extrapolate briefly when no new data arrives
- Trade-off: more delay = smoother, less delay = more responsive but jittery
- Never extrapolate more than one snapshot interval — prediction error grows fast

### Rollback Netcode
- Each client runs the simulation locally at full speed
- When remote input arrives late, rewind simulation to the frame it applies to
- Re-simulate forward from that point with corrected inputs
- Display results of the corrected simulation — may cause visual "pops" on misprediction
- Well-suited for fighting games and fast-paced 1v1 — scales poorly beyond 4-8 players

### Lag Compensation
- Server rewinds world state to where the shooting client saw enemies (based on client's latency)
- Hit detection runs against the historical state, not current server state
- Bounds: cap maximum rewind time (e.g., 200ms) — too much compensation is unfair to the target
- "Favor the shooter": widely accepted principle — the shooter sees their shot land
- Combine with server-side validation — rewind for hit check, but validate plausibility

### Bandwidth Optimization
- Delta compression: send only what changed since last acknowledged state
- Quantization: compress positions to 16-bit fixed-point, angles to 8-bit
- Prioritization: nearby entities get higher update rates than distant ones
- Interest management: only send entities relevant to each client's area
- Unreliable channel for state updates (latest wins); reliable channel for events (death, pickup)

## Gotchas / Anti-patterns
- Trusting client-sent positions or health values — trivially exploitable
- Sending full world state every tick — bandwidth explosion with entity count
- Synchronizing simulation with floating-point math across different CPUs — non-determinism
- TCP for real-time game state — head-of-line blocking adds latency on packet loss
- Fixed interpolation delay without adapting to actual network conditions
- Rollback with expensive simulation — re-simulating N frames per input is CPU-intensive
- Not accounting for clock drift between client and server — desync over time

## References
- Gaffer on Games — Networked Physics: https://gafferongames.com/categories/networked-physics/
- Valve Developer Wiki — Source Multiplayer Networking: https://developer.valvesoftware.com/wiki/Source_Multiplayer_Networking
- Gabriel Gambetta — Fast-Paced Multiplayer: https://www.gabrielgambetta.com/client-server-game-architecture.html
- GGPO (rollback library): https://www.ggpo.net/
- GDC: "Overwatch Gameplay Architecture and Netcode" (Blizzard)

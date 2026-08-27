# Game Loop Patterns

## When to use
- Any interactive application that updates and renders continuously
- Games requiring deterministic physics and simulation regardless of frame rate
- Applications where input, logic, and rendering must be decoupled
- Multiplayer games where simulation must be reproducible across machines

## Pattern

### Fixed Timestep with Interpolation
- Physics and game logic update at a fixed rate (e.g., 60Hz, dt = 1/60s)
- Rendering runs as fast as possible, interpolating between last two states
- Accumulator tracks unprocessed time: add frame delta, subtract fixed step per update
- Multiple logic updates per render frame if machine is slow; skip renders if ahead
- Deterministic: same inputs produce same outputs regardless of hardware speed

```
accumulator += frameDelta
while accumulator >= fixedStep:
    previousState = currentState
    update(fixedStep)
    accumulator -= fixedStep
alpha = accumulator / fixedStep
render(lerp(previousState, currentState, alpha))
```

### Variable Timestep
- Logic and rendering run once per frame with actual elapsed time as delta
- Simpler to implement but non-deterministic — physics varies with frame rate
- Suitable for UI-heavy applications, turn-based games, or where precision doesn't matter
- Clamp maximum delta to prevent physics explosion after a long pause or breakpoint

### Semi-Fixed Timestep
- Cap maximum frame delta, then subdivide into fixed steps plus a remainder
- Compromise: avoids spiral of death while maintaining mostly-fixed simulation
- Remainder handled by a smaller final step — slight non-determinism on that step

### Frame Rate Independence
- All movement and animation must be multiplied by delta time — never assume a fixed frame rate
- Cooldowns, timers, and delays tracked in seconds, not frames
- Animation speeds in units-per-second, not units-per-frame
- Exception: fixed timestep logic uses the constant dt, not measured frame delta

### Spiral of Death
- If one logic update takes longer than the fixed timestep, accumulator grows without bound
- Each frame does more updates, making it even slower — positive feedback loop
- Solution: cap maximum number of logic steps per frame (e.g., 5), accept slowdown over divergence
- Monitor: if capping triggers frequently, the simulation is too expensive for the hardware

### Decoupled Update and Render
- Update produces world state; render reads state — render never modifies game state
- Enables: running simulation faster than display (server), slower for replays, pausing without freezing rendering
- Thread separation: simulation on one thread, rendering on another, double-buffer the world state
- Input sampling should happen at the start of the frame, before any updates

## Gotchas / Anti-patterns
- Tying game speed to frame rate — game runs 2x fast at 120fps, half speed at 30fps
- `position += speed` without delta time — the most common beginner mistake
- Rendering stale state without interpolation — visible stuttering at low frame rates
- Using `float` accumulators without care — floating-point drift over long sessions
- Processing input in the render loop — input response time varies with frame rate
- Variable timestep with physics engines that expect fixed steps — non-deterministic collision
- Sleeping to cap frame rate instead of using vsync or a proper frame limiter

## References
- "Fix Your Timestep!" (Glenn Fiedler): https://gafferongames.com/post/fix_your_timestep/
- Game Programming Patterns — Game Loop: https://gameprogrammingpatterns.com/game-loop.html
- Gaffer on Games — Integration Basics: https://gafferongames.com/post/integration_basics/
- Casey Muratori, Handmade Hero Day 10-12 (game loop implementation)
- Jason Gregory, "Game Engine Architecture" Chapter 7 (The Game Loop and Real-Time Simulation)

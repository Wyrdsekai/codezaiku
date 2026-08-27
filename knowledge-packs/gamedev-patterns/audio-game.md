# Game Audio Patterns

## When to use
- Games requiring spatial audio, ambient soundscapes, and responsive sound effects
- Interactive music that adapts to gameplay state
- Audio systems that must run on a strict real-time budget alongside rendering
- Multi-platform games with varying audio hardware capabilities

## Pattern

### Spatial Audio
- Position sounds in 3D space relative to the listener (camera or player head)
- Distance attenuation: inverse square law or custom rolloff curves per sound type
- Occlusion: reduce high frequencies when sound source is behind walls (raycast-based)
- Reverb zones: different environments (cave, forest, hall) apply different reverb profiles
- HRTF (head-related transfer function) for headphone spatialization — more immersive than panning

### Mixing Architecture
- Hierarchical bus structure: Master → Music / SFX / Voice / Ambient
- Each bus has volume, EQ, compression — mix at the bus level, not per-sound
- Ducking: lower music and ambient volume when dialog plays (sidechain compression)
- Priority system: when voice limit reached, stop lowest-priority sounds first
- Snapshot-based mixing: define mix states (combat, stealth, menu) and blend between them

### Music Systems
- Horizontal layering: multiple stems (drums, melody, bass) mixed in/out based on game state
- Vertical sequencing: transition between musical sections at beat-aligned boundaries
- Stinger system: short musical phrases triggered by events (kill, discovery, danger)
- Transition rules: define valid transitions between music states with crossfade or beat-sync
- Adaptive intensity: track a gameplay intensity value, drive layer mix and tempo from it

### Event-Driven Sound
- Game events (footstep, explosion, UI click) trigger sound events, not raw audio files
- Sound event maps to: one or more audio files, randomization, pitch variance, attenuation settings
- Middleware (Wwise, FMOD) or custom event system decouples game logic from audio implementation
- One-shot sounds: fire and forget with automatic voice management
- Looping sounds: attached to entities, follow position updates, stop when entity dies

### Voice Management
- Limited simultaneous voices (32-128 typical) — prioritize and steal when full
- Virtual voices: track inaudible sounds without mixing them, resume when audible again
- Stealing policy: replace quietest or lowest-priority voice when a new high-priority sound fires
- Voice pooling: pre-allocate voice slots to avoid runtime allocation

### Streaming vs Preloaded
- Short effects (<2s): preload into memory — instant playback, small footprint
- Music and ambient loops: stream from disk — too large to hold in memory
- Dialog: stream with small pre-buffer — balance memory and latency
- Compressed in memory (Vorbis, Opus, ADPCM) decoded on playback
- Platform-specific codecs for hardware-accelerated decode where available

## Gotchas / Anti-patterns
- Playing identical sounds simultaneously — phase cancellation makes it quieter, not louder
- No random pitch/volume variation on repeated sounds (footsteps) — robotic repetition
- Audio processing on the main thread — blocks rendering, causes frame drops
- Distance attenuation starting at zero distance — sounds are infinitely loud at source
- Music transitions that ignore beat boundaries — jarring cuts mid-phrase
- Allocating memory in the audio callback — causes glitches from allocator latency
- Forgetting to stop looping sounds on entity destruction — phantom sounds persist forever

## References
- FMOD Documentation: https://www.fmod.com/docs/
- Wwise Fundamentals: https://www.audiokinetic.com/en/library/
- Game Audio Programming (Guy Somberg, ed.): practical patterns from industry veterans
- Steam Audio (spatial audio SDK): https://valvesoftware.github.io/steam-audio/
- "A Programmer's Guide to Sound" (GDC classic talk series)

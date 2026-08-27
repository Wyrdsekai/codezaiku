# Entity-Component-System Architecture

## When to use
- Game or simulation with many entities sharing overlapping but non-identical behaviors
- Performance-critical update loops over thousands of entities
- Need to add/remove behaviors at runtime without modifying class hierarchies
- Avoiding deep inheritance trees that become rigid and hard to extend

## Pattern

### Core Concepts
- **Entity**: unique ID (integer), no data, no behavior — just an identifier
- **Component**: plain data struct attached to an entity (Position, Velocity, Health, Sprite)
- **System**: function/logic that processes all entities matching a component query
- Composition over inheritance: an entity's behavior emerges from its component combination
- Systems are stateless where possible — data lives in components, logic lives in systems

### Archetypal Storage
- Group entities by their component signature (archetype = unique set of component types)
- Components stored in contiguous arrays per archetype — cache-friendly iteration
- Adding/removing a component moves entity to a different archetype table
- Well-suited when most entities are long-lived and archetypes are stable
- Used by Unity DOTS, Flecs, Bevy

### Sparse Set Storage
- Each component type has its own sparse set (dense array + sparse index array)
- Component queries do set intersection at runtime
- Adding/removing components is O(1) without table migration
- More flexible for highly dynamic entity composition
- Used by EnTT

### System Scheduling
- Declare what components each system reads and writes
- Scheduler runs non-conflicting systems in parallel (read-read is parallel, write conflicts serialize)
- Explicit ordering constraints when system output feeds another system's input
- Phase grouping: input → simulation → rendering — systems within a phase can parallelize

### When ECS Does Not Fit
- Small projects with few entity types — overhead of ECS plumbing exceeds benefit
- Deeply relational data (scene graphs, skill trees) — better modeled with explicit graph structures
- UI systems — retained-mode widget trees are a better fit
- One-off singleton state (game settings, input state) — use resources/singletons, not entities

### Component Design
- Components should be small, focused data bundles — not God components
- Marker components (zero-size) for tagging: `Player`, `Enemy`, `Invisible`
- Shared/referenced data via entity references in components, not nested components
- Avoid pointers in components — they break serialization and cache coherence
- Version/generation on entity IDs to detect stale references

## Gotchas / Anti-patterns
- Systems that query too many components — sign of a God system doing too much
- Mutating component sets during iteration — invalidates iterators in most implementations
- Using ECS for everything including UI and menus — forces data-oriented design where it doesn't fit
- Entity references without generation checks — dangling references to deleted entities
- Components with heap allocations (Vec, String) — defeats cache-friendly layout
- Circular system dependencies — indicates design needs refactoring
- Over-decomposing components (position split into X and Y) — iteration overhead per component

## References
- Bevy ECS: https://bevyengine.org/learn/quick-start/getting-started/ecs/
- EnTT (C++ ECS): https://github.com/skypjack/entt
- Flecs Documentation: https://www.flecs.dev/flecs/
- Data-Oriented Design (Richard Fabian): https://www.dataorienteddesign.com/dodbook/
- "Overwatch" GDC Talk on ECS: https://www.youtube.com/watch?v=W3aieHjyNvw

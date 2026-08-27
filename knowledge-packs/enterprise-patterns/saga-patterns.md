# Saga Patterns

## When to use
- Business transactions span multiple services or aggregates that cannot share a database transaction
- Operations require coordination across bounded contexts (order + payment + inventory + shipping)
- Failure in one step requires compensating (undoing) previously completed steps
- Long-running processes (minutes to days) that cannot hold locks

## Pattern

### Orchestration Saga
- Central orchestrator (saga coordinator) drives the sequence of steps
- Orchestrator sends commands to each participant service and waits for responses
- On failure: orchestrator sends compensation commands to previously completed steps in reverse order
- State machine: orchestrator maintains saga state (pending, step2-complete, compensating, failed, completed)
- Advantages: easy to understand control flow, centralized error handling, clear visibility of saga progress
- Store saga state durably: if orchestrator crashes, it resumes from last persisted state on restart

### Choreography Saga
- No central coordinator: each service reacts to events and publishes its own events
- Service A completes and publishes event; Service B listens, processes, publishes next event; and so on
- Compensation: a failure event triggers each preceding service to listen and compensate independently
- Advantages: no single point of coordination failure, services remain fully decoupled
- Disadvantages: harder to trace the overall flow, implicit ordering, compensation logic distributed across services
- Best for: simple sagas (2-3 steps) or when organizational structure prevents central coordination

### Compensation Design
- Every forward step must have a defined compensation action: `reserveInventory` -> `releaseInventory`
- Compensations are not always perfect inverses: a sent email cannot be unsent (send an apology/correction instead)
- Compensations must be idempotent: the same compensation applied twice should have the same effect as once
- Order of compensation: reverse order of forward steps (last completed step compensated first)
- Partial completion: only compensate steps that actually succeeded; skip steps that were never reached

### Idempotency
- Every saga step (forward and compensation) must be idempotent because messages may be delivered more than once
- Use idempotency keys: unique identifier per saga step execution; check before processing
- Store the key and outcome: if the key exists, return the stored result without re-executing
- Idempotency applies to side effects too: do not send duplicate emails, charge cards twice, or create duplicate records

### Saga State Management
- Persist saga state on every transition: `{ sagaId, currentStep, status, participantResults, createdAt, timeout }`
- Timeout handling: if a step does not respond within SLA, trigger compensation or retry with backoff
- Dead saga detection: periodic sweep for sagas stuck in intermediate states beyond their timeout
- Correlation: every message in the saga carries the saga ID for routing responses back to the correct saga instance

### Choosing Orchestration vs Choreography
| Factor | Orchestration | Choreography |
|---|---|---|
| Number of steps | Many (4+) | Few (2-3) |
| Visibility | High (central state) | Low (distributed) |
| Coupling | Services depend on orchestrator | Services depend on events |
| Complexity growth | Linear | Exponential (event spaghetti) |
| Team structure | Central platform team | Autonomous service teams |

## Gotchas / Anti-patterns
- **No compensation defined**: forward step succeeds but failure path is undefined; manual intervention required
- **Non-idempotent steps**: retries cause duplicate side effects (double charges, double inventory deductions)
- **Synchronous saga**: blocking on each step defeats the purpose; use async messaging with timeouts
- **Compensation that can fail**: if compensation itself fails, saga gets stuck; design compensations to be reliable and retriable
- **Saga state in memory only**: coordinator crash loses saga progress; always persist state
- **Choreography at scale**: 8-step choreography saga becomes untraceable event spaghetti; switch to orchestration

## References
- Garcia-Molina & Salem, "Sagas" (1987) — original academic paper
- Richardson, "Microservices Patterns" — ch. 4 on saga implementation
- Microsoft, "Saga distributed transactions pattern" — Azure architecture docs
- Axon Framework saga support — JVM implementation example
- Temporal.io — workflow engine that simplifies orchestration saga implementation

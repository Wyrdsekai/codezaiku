# Discrete Event Simulation Patterns

## When to use
- Modeling systems where state changes occur at discrete points in time
- Queueing systems (networks, call centers, manufacturing lines, hospitals)
- Performance analysis of systems too complex for analytical models
- Capacity planning, what-if analysis, and bottleneck identification

## Pattern

### Event Queue (Future Event List)
- Priority queue ordered by event time — the next event to process has the earliest timestamp
- Simulation clock advances to the time of the next event — no processing between events
- Each event carries: timestamp, event type, and associated entity/data
- Processing an event may schedule new future events (arrival triggers service start, service end triggers departure)
- Heap-based priority queue is standard — O(log n) insert and O(log n) extract-min

### Time Advance Mechanisms
- **Next-event**: clock jumps to earliest scheduled event — efficient when events are sparse
- **Fixed-increment**: clock advances by a fixed step, check for events at each step — simpler but wasteful
- Next-event is dominant for discrete simulation — fixed-increment is for continuous/hybrid models
- Tie-breaking: events at the same time should have deterministic processing order (by type priority or FIFO)

### Entity Lifecycle
- Entities (customers, packets, jobs) are created, processed through the system, and destroyed
- Creation: arrival events generated from inter-arrival time distributions
- Processing: entity enters a queue, waits for a resource, is served for a service time, departs
- Resources: servers, machines, or channels with limited capacity — entities compete for them
- Entity attributes: track arrival time, accumulated wait time, and routing decisions per entity

### Statistical Collection
- Record observations: wait times, queue lengths, utilization, throughput
- Warm-up period: discard initial transient statistics before the system reaches steady state
- Batching: divide the run into batches, compute statistics per batch, use batch means for confidence intervals
- Replication: run multiple independent simulations with different random seeds, aggregate results
- Common metrics: mean wait time, P95/P99 wait time, server utilization, throughput, queue length distribution

### Random Number Generation
- Use a well-tested PRNG (Mersenne Twister, PCG, xoshiro) — never roll your own
- Separate streams per entity source or per random variate — enables variance reduction techniques
- Transform uniform random numbers to target distributions: exponential (inter-arrivals), normal (processing times)
- Inverse CDF method for standard distributions; accept-reject for complex ones
- Seed management: record seeds for reproducibility; vary seeds across replications

### Model Validation
- Face validity: does the model behave as domain experts expect?
- Trace-driven validation: feed real system data as input, compare model output to real output
- Sensitivity analysis: vary parameters to understand which inputs most affect outputs
- Extreme condition tests: zero arrival rate (empty), infinite arrival rate (saturated) — check edge behavior
- Conservation laws: entities in = entities out + entities in system (for stable systems)

## Gotchas / Anti-patterns
- No warm-up period — initial transient biases all statistics
- Single replication — no confidence interval, results may be from an unusual seed
- Using system clock as random seed — non-reproducible results
- Modeling continuous processes as discrete events at high resolution — inefficient and inaccurate
- Infinite queue assumption when real systems have finite buffers — optimistic results
- Not validating against the real system — a model that looks good but predicts wrong is useless
- Event processing that modifies other pending events — invalidates queue ordering

## References
- "Simulation Modeling and Analysis" (Law) — the standard simulation textbook
- SimPy (Python DES library): https://simpy.readthedocs.io/
- "Discrete-Event System Simulation" (Banks, Carson, Nelson, Nicol)
- Arena / AnyLogic / Simio — commercial simulation software for reference
- Winter Simulation Conference proceedings: https://www.informs-sim.org/

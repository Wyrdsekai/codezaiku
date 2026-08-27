# Agent-Based Modeling Patterns

## When to use
- Studying emergent behavior arising from individual agent interactions
- Systems where aggregate behavior is not predictable from individual rules alone
- Modeling heterogeneous populations with diverse behaviors and decision-making
- Social, ecological, economic, or epidemiological simulations

## Pattern

### Agent Design
- Each agent has: state (position, resources, beliefs), behavior rules, and interaction capabilities
- Behavior as simple rules: if-then conditions, state machines, or utility-based decision functions
- Heterogeneity: agents differ in parameters, rules, or learning capability — not all clones
- Memory: agents may remember past interactions, learn from experience, or adapt strategies
- Agent types: define archetypes with shared rule sets, parameterized for individual variation

### Environment
- Spatial environment: grid (cellular automata-style), continuous 2D/3D space, or network (graph)
- Grid: agents occupy cells, interact with neighbors (Moore or von Neumann neighborhood)
- Continuous: agents have real-valued coordinates, interact within a radius
- Network: agents are nodes, edges define interaction partners (social networks, trade networks)
- Environment may have its own dynamics: resource regrowth, seasonal changes, infrastructure decay

### Interaction Rules
- Direct interaction: agents in proximity exchange information, trade, compete, or cooperate
- Indirect interaction: agents modify the environment (stigmergy — ants leaving pheromones)
- Communication: broadcast messages, directed messages, or observation of neighbors' state
- Game-theoretic interactions: payoff matrices, prisoner's dilemma, cooperation/defection dynamics
- Conflict resolution: when agents compete for a resource, define a clear resolution mechanism

### Scheduling
- **Synchronous**: all agents observe state at time T, all update to T+1 simultaneously — order-independent
- **Asynchronous**: agents update one at a time, see effects of prior updates within the same step
- **Random activation**: shuffle agent order each step — prevents systematic bias from fixed order
- **Event-driven**: agents scheduled by next action time — efficient for asynchronous models
- Synchronous vs asynchronous choice significantly affects emergent dynamics — document the choice

### Emergent Behavior
- Emergence: macro-level patterns not explicitly coded in agent rules
- Examples: flocking from alignment/cohesion/separation rules, segregation from mild preferences (Schelling)
- Detect emergence through aggregate metrics: clustering coefficients, wealth distribution, population dynamics
- Visualization: spatial plots over time reveal patterns invisible in summary statistics
- Sensitivity to initial conditions: small rule changes can produce qualitatively different emergence

### Calibration and Validation
- Parameter sweeps: systematically vary agent parameters, observe aggregate outcomes
- Empirical calibration: adjust parameters until model output matches observed real-world data
- Pattern-oriented modeling: validate by checking if the model produces multiple known real-world patterns simultaneously
- Replication: different random seeds should produce statistically similar aggregate behavior
- Compare against null model: does the agent-based model outperform a simpler analytical model?

## Gotchas / Anti-patterns
- Overly complex agents — if individual behavior is a mini-simulation, the model is intractable
- Too many parameters — model becomes unfalsifiable, can fit anything
- Fixed agent ordering without randomization — artifacts from update sequence
- Ignoring stochastic variability — single runs are anecdotes, not evidence
- Equating emergence with correctness — interesting patterns can emerge from wrong models
- No sensitivity analysis — unclear which parameters drive observed behavior
- Infinite agent populations without computational feasibility assessment

## References
- "An Introduction to Agent-Based Modeling" (Wilensky & Rand) — comprehensive ABM textbook
- NetLogo: https://ccl.northwestern.edu/netlogo/ — educational ABM platform
- Mesa (Python ABM framework): https://mesa.readthedocs.io/
- MASON (Java ABM library): https://cs.gmu.edu/~eclab/projects/mason/
- Schelling Segregation Model: classic reference for emergence from simple rules
- ODD Protocol (Overview, Design concepts, Details): standard ABM documentation format

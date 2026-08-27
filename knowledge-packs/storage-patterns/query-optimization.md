# Query Optimization

## When to use
- Building or tuning a query planner for a database or query engine
- Diagnosing slow queries via execution plans (EXPLAIN)
- Implementing join algorithms or choosing between existing ones
- Gathering and maintaining statistics for cost-based optimization

## Pattern

### Query Plan Representation
- A query plan is a tree of physical operators: scan, filter, join, sort, aggregate, project
- Leaves are data sources (table scans, index scans); the root produces the final result
- Each operator has an estimated cost (CPU, I/O, rows produced) used for plan comparison
- The optimizer explores alternative plans and picks the one with the lowest estimated cost

### Logical vs Physical Plan
- **Logical plan**: what to compute (join A and B, filter on X, sort by Y) — no implementation choices
- **Physical plan**: how to compute it (hash join, index scan on X, quicksort) — concrete algorithms and access methods
- Optimization has two phases: logical rewriting (push filters down, eliminate redundant projections) then physical plan selection

### Cost Estimation
- Estimate the number of rows each operator produces (cardinality estimation)
- Use table statistics: total row count, distinct values per column (NDV), histograms of value distribution, null fraction
- Selectivity of `WHERE col = val`: approximately `1 / NDV(col)` for uniform distribution; use histograms for skewed data
- Multiply selectivities for conjunctions (AND) — this assumes independence, which is often wrong but pragmatic
- I/O cost dominates for disk-based systems; CPU cost matters more for in-memory engines

### Join Algorithms
- **Nested loop join**: for each row in the outer table, scan the inner table; O(N * M); viable when the inner side has an index
- **Index nested loop join**: for each outer row, look up the matching inner row(s) via an index; efficient for small outer sides
- **Hash join**: build a hash table on the smaller side, probe with the larger side; O(N + M); requires memory for the hash table
- **Sort-merge join**: sort both sides on the join key, then merge; O(N log N + M log M); efficient when inputs are already sorted
- **Broadcast join** (distributed): send the smaller table to all nodes holding the larger table
- Optimizer chooses based on table sizes, available indexes, and memory budget

### Filter Pushdown
- Move filter (WHERE) predicates as close to the data source as possible
- A filter on table A's column should be applied before joining A with B — reduces the join's input size
- Push filters through projections (if the filter column is still available) and into subqueries
- In federated or remote queries, push filters to the remote engine to minimize data transfer

### Join Reordering
- The order in which tables are joined dramatically affects cost (factorial explosion of possibilities)
- For small numbers of tables (< 10), dynamic programming explores all orderings
- For large joins, use greedy heuristics or genetic algorithms
- Principle: join the most selective (smallest intermediate result) combination first

### Statistics Maintenance
- Gather statistics with `ANALYZE` (PostgreSQL), `ANALYZE TABLE` (MySQL), or equivalent
- Statistics go stale as data changes — schedule periodic re-analysis or use auto-analyze triggers
- Histograms capture skew: equi-width (simple) or equi-depth/equi-height (better for skewed data)
- Multi-column statistics (functional dependencies, most-common-value-lists) improve accuracy for correlated columns
- Stale statistics are the number-one cause of bad query plans

### Adaptive / Runtime Optimization
- If the optimizer's cardinality estimate is wrong, the chosen plan may be terrible
- Adaptive execution: start with the estimated plan, monitor actual row counts, switch plan mid-execution if estimates are far off
- Materialized intermediate results can be probed to refine estimates for downstream operators
- Query feedback: record actual vs estimated cardinalities and use them to correct future estimates

## Gotchas / Anti-patterns
- **No statistics**: the optimizer guesses, usually badly — always analyze tables after bulk loads
- **Assuming independence**: `WHERE country = 'US' AND state = 'CA'` — the optimizer multiplies selectivities as if country and state are independent
- **Cartesian products from missing join predicates**: forgetting a join condition causes an N * M blowup
- **Over-indexing for the optimizer**: too many indexes give the optimizer too many bad choices and slow writes
- **Ignoring EXPLAIN output**: tuning queries without reading the execution plan is guesswork
- **Forcing hints everywhere**: query hints bypass the optimizer; use them as a last resort, not a first

## References
- Kleppmann, M. "Designing Data-Intensive Applications", Chapter 3 (O'Reilly, 2017)
- Selinger et al., "Access Path Selection in a Relational Database Management System" (1979, foundational paper)
- PostgreSQL EXPLAIN documentation: https://www.postgresql.org/docs/current/using-explain.html
- How the PostgreSQL query planner works: https://www.postgresql.org/docs/current/planner-optimizer.html
- CMU Database Group lectures (Andy Pavlo): https://15445.courses.cs.cmu.edu/

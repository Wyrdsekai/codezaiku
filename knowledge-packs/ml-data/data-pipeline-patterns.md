# Data Pipeline Patterns

## When to use
- ML project moves beyond notebook prototyping to repeated or production training
- Features need to be computed consistently across training and inference
- Multiple models share common feature transformations
- Data freshness matters (real-time predictions vs batch)
- Team needs reproducible, auditable data flows

## Pattern

### ETL for ML (Extract-Transform-Load)
- **Extract**: pull from sources (databases, APIs, event streams, files)
- **Transform**: clean, validate, engineer features, join data sources
- **Load**: write to feature store, training data lake, or model-ready format
- Key difference from analytics ETL: ML pipelines must track lineage for reproducibility and produce training-ready tensors/tables, not reports

### Feature store architecture
- **Offline store**: batch-computed features stored for training (Parquet, Delta, Hive)
- **Online store**: low-latency serving of latest feature values (Redis, DynamoDB, Bigtable)
- **Feature registry**: catalog of feature definitions, owners, schemas, freshness SLAs
- **Point-in-time correctness**: training must use features as they existed at prediction time, not current values

### Offline vs online feature computation
| Aspect | Offline (batch) | Online (streaming/real-time) |
|--------|-----------------|------------------------------|
| Latency | Hours to days | Milliseconds to seconds |
| Compute | Spark, SQL, pandas | Flink, Kafka Streams, custom |
| Use case | Historical features, aggregates | Session features, live signals |
| Complexity | Lower | Higher (exactly-once, ordering) |

- **Hybrid pattern**: compute historical features offline, compute real-time features online, join at inference time
- Precompute expensive features offline; reserve online for features that require fresh data

### Pipeline orchestration
- Define pipelines as DAGs (directed acyclic graphs) of tasks
- Each task is idempotent: safe to retry without side effects
- Use orchestrators for scheduling, dependency management, retry, and monitoring
- Parameterize runs by date/partition to enable backfilling

### Data format selection
- **Parquet**: columnar, compressed, excellent for analytics and batch ML training
- **TFRecord / WebDataset / Arrow**: framework-optimized formats for training data loading
- **Delta / Iceberg**: table formats with ACID transactions, schema evolution, time travel
- Choose based on read patterns: columnar for feature selection, row-based for sequential access

### Pipeline testing
- **Unit tests**: test individual transform functions with known input/output pairs
- **Data contracts**: define expected schema, value ranges, and null rates; fail pipeline on violation
- **Shadow pipelines**: run new pipeline version alongside old one; compare outputs before switching
- **Integration tests**: end-to-end run with a small dataset slice; verify output schema and sample values

### Practical workflow
1. Start with a simple script; extract into pipeline stages as complexity grows
2. Separate data acquisition from feature computation from model training
3. Version pipeline code and data artifacts together
4. Monitor pipeline health: freshness, row counts, null rates, distribution statistics
5. Document data sources, update frequency, and ownership

## Gotchas / Anti-patterns
- **Training/serving skew**: features computed differently in training vs inference; use the same code path or a feature store
- **Non-idempotent transforms**: appending duplicates on retry; always write to partitions and overwrite atomically
- **Monolithic pipelines**: one giant script that does everything; break into composable, testable stages
- **No backfill strategy**: when feature logic changes, old data must be recomputed; design for this from the start
- **Ignoring data freshness**: stale features in the online store cause silent model degradation
- **Over-engineering early**: do not build a feature store for a single-model prototype; extract patterns when reuse emerges

## References
- Feast: open-source feature store (https://feast.dev)
- Hopsworks: feature store and ML platform
- Apache Airflow, Dagster, Prefect: pipeline orchestrators
- "Designing Machine Learning Systems" (Huyen, O'Reilly) — Chapter 7: Feature Engineering + Chapter 9: Infrastructure

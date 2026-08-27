# Dataset Documentation

## When to use
- Any dataset that will be shared with others (teammates, community, future self)
- Compliance or regulatory requirements demand data provenance records
- Model auditing requires tracing predictions back to training data characteristics
- Building on top of public or third-party datasets
- Maintaining datasets over time as they evolve

## Pattern

### Data cards
- Structured documentation covering: purpose, composition, collection process, preprocessing, distribution, maintenance
- Concise enough to read in 5 minutes; detailed enough to make informed usage decisions
- Include both quantitative summaries (row counts, class distributions) and qualitative context (intended use, known limitations)

### Recommended sections
1. **Overview**: name, version, creation date, maintainer, license
2. **Motivation**: why was this dataset created? What task does it serve?
3. **Composition**: number of instances, features, labels; data types; class distribution
4. **Collection process**: how was data gathered? Time period? Geographic scope? Sampling strategy?
5. **Preprocessing**: what cleaning, filtering, or transformation was applied to raw data?
6. **Annotation**: who labeled the data? What guidelines were used? Inter-annotator agreement?
7. **Distribution**: where is it stored? Access controls? File formats?
8. **Known issues**: biases, gaps, quality problems, deprecated fields
9. **Ethical considerations**: sensitive attributes, consent, potential for misuse
10. **Maintenance**: update frequency, point of contact, deprecation policy

### Schema documentation
- Every column/field: name, type, description, units, allowed values, null policy
- Store schema alongside data (e.g., `schema.json`, `schema.yaml`, or embedded in Parquet metadata)
- Use schema validation tools to enforce documented constraints at data load time

### Lineage tracking
- Record the full provenance chain: raw sources, transformations applied, intermediate artifacts
- Link dataset version to pipeline version and code commit that produced it
- Enable answering: "what code and raw data produced training set v2.3?"
- Tools: DVC pipelines, ML metadata stores, or simple manifest files in version control

### Machine-readable metadata
- Standardize on a format: Croissant (schema.org-based), HuggingFace dataset cards (YAML front matter), or custom JSON
- Include checksums (SHA-256) for integrity verification
- Tag with relevant keywords for discoverability

### Practical workflow
1. Create the data card template at project start (before data collection)
2. Fill in collection and composition sections during data gathering
3. Update preprocessing and annotation sections as pipeline matures
4. Review and update documentation at every dataset version release
5. Store documentation in version control alongside data pointers

## Gotchas / Anti-patterns
- **Documentation after the fact**: writing docs months later leads to inaccurate or missing details; document during creation
- **Only documenting schema**: schema is necessary but not sufficient; context (why, how, limitations) is equally important
- **Static docs for evolving data**: documentation must be versioned and updated with each dataset release
- **No known-issues section**: every dataset has limitations; documenting them prevents misuse
- **Over-documentation**: 50-page data docs that nobody reads; keep it concise and scannable
- **Undocumented preprocessing**: if you dropped 30% of rows during cleaning, that must be recorded

## References
- "Datasheets for Datasets" (Gebru et al., 2021) — foundational paper
- "Data Cards" (Pushkarna et al., 2022) — Google structured documentation framework
- Croissant metadata format: https://mlcommons.org/croissant
- HuggingFace dataset card guide: https://huggingface.co/docs/hub/datasets-cards
- "Model Cards for Model Reporting" (Mitchell et al.) — complementary model documentation

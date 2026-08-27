# Reproducible Bioinformatics Pipeline Patterns

## When to use
- Multi-step analysis workflows that must produce identical results when re-run
- Pipelines shared across lab members, collaborators, or for publication
- Long-running analyses where intermediate results should be cached
- Deploying pipelines across different compute environments (laptop, cluster, cloud)

## Pattern

### Workflow Managers
- **Nextflow**: Groovy-based DSL, strong container integration, nf-core community pipelines
- **Snakemake**: Python-based, rule-file approach (similar to Make), conda integration
- Both provide: dependency resolution, parallel execution, resume from failure, cluster/cloud execution
- Define workflows as directed acyclic graphs (DAGs) of tasks with explicit inputs and outputs
- Workflow manager handles scheduling, parallelism, and resource allocation — you define the logic

### Containerization
- Package each tool with its exact dependencies in a container (Docker, Singularity/Apptainer)
- Pin container versions by digest (sha256), not just tag — tags are mutable
- One tool per container (microcontainer) for composability; or one container per pipeline step
- Singularity/Apptainer for HPC: runs unprivileged, no root required, compatible with cluster schedulers
- BioContainers: pre-built containers for thousands of bioinformatics tools — use before building your own

### Version Pinning
- Pin every tool version: `samtools==1.18`, not just `samtools`
- Pin reference data versions: genome build (GRCh38), annotation release (Ensembl 110), database date
- Conda environment files (`environment.yml`) or pip freeze (`requirements.txt`) for Python dependencies
- Lock files (conda-lock, poetry.lock) capture the full resolved dependency tree
- Record the workflow manager version itself — syntax and behavior can change between versions

### Pipeline Structure
- Input: sample sheet (CSV/TSV) listing sample names, paths, and metadata
- Steps: QC → preprocessing → alignment → quantification → analysis → reporting
- Each step reads from previous step's output directory — explicit data flow
- Parameters in a separate config file, not hardcoded — easy to change without editing pipeline code
- Output: results directory with structured layout, log files per step, final report

### Caching and Resumability
- Content-addressed caching: hash inputs to determine if a step needs re-running
- Nextflow: `-resume` flag uses work directory hashes to skip completed steps
- Snakemake: timestamps or content-based checks to determine staleness
- Cache expensive steps (alignment, variant calling) — re-run only what changed
- Work directories should be on fast local storage; final outputs on shared storage

### Testing and Validation
- Test dataset: small subset of real data that exercises all pipeline branches in minutes, not hours
- CI: run pipeline on test data with every code change — verify outputs match expected results
- Checksums: compare output file hashes against known-good results for the test dataset
- nf-test (Nextflow) or Snakemake unit tests for individual pipeline modules
- Release tagging: version the pipeline itself with semantic versioning

## Gotchas / Anti-patterns
- Hardcoded file paths — breaks when run on a different machine
- Downloading reference data inside the pipeline without caching — re-downloads every run
- No version pinning — pipeline breaks when a tool updates and changes output format
- Running on bare metal without containers — "works on my machine" syndrome
- Monolithic pipeline script with no modularization — untestable, hard to modify
- Ignoring exit codes — a failed step produces partial output consumed by the next step
- Pipeline that only works with one cluster scheduler — use workflow manager's abstraction layer

## References
- Nextflow Documentation: https://www.nextflow.io/docs/latest/
- Snakemake Documentation: https://snakemake.readthedocs.io/
- nf-core Pipelines: https://nf-co.re/
- BioContainers: https://biocontainers.pro/
- "Ten Simple Rules for Reproducible Computational Research" (Sandve et al., PLoS Comp Bio 2013)
- Conda-lock: https://github.com/conda/conda-lock

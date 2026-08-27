# RNA-Seq Analysis Patterns

## When to use
- Measuring gene expression levels across conditions (treatment vs control)
- Identifying differentially expressed genes between experimental groups
- Discovering novel transcripts, alternative splicing, or gene fusions
- Characterizing transcriptomes of non-model organisms

## Pattern

### Preprocessing
- Quality control: FastQC to assess read quality, adapter content, GC distribution
- Adapter trimming: Trim Galore, fastp, Cutadapt — remove adapter sequences and low-quality bases
- Ribosomal RNA filtering: remove rRNA contamination if not depleted during library prep
- Read alignment: STAR or HISAT2 to reference genome (splice-aware); Salmon or kallisto for pseudo-alignment to transcriptome
- Feature counting: featureCounts (Subread) or HTSeq-count — assign reads to genes/transcripts

### Normalization
- Raw counts are not comparable across samples — library size and composition differ
- **CPM** (Counts Per Million): simple library size normalization — does not account for composition bias
- **TPM** (Transcripts Per Million): normalizes for gene length and library size — comparable within and across samples
- **TMM** (Trimmed Mean of M-values, edgeR): robust normalization accounting for composition bias
- **DESeq2 median-of-ratios**: similar to TMM, accounts for library size and composition
- Do not use RPKM/FPKM for cross-sample comparison — not comparable due to composition bias

### Differential Expression
- **DESeq2** (R): negative binomial model, shrinkage estimation, robust for small sample sizes
- **edgeR** (R): negative binomial with empirical Bayes, slightly different statistical framework
- **limma-voom** (R): transforms counts to log-CPM with precision weights, then linear models
- All three produce comparable results for well-powered studies — DESeq2 most widely used
- Input: raw counts (not normalized) — the tools handle normalization internally
- Multiple testing correction: Benjamini-Hochberg (FDR) — report adjusted p-values, not raw

### Batch Effects
- Technical variation from processing samples at different times, by different technicians, or on different lanes
- Detection: PCA plot should separate by biology (condition), not by batch
- Correction: include batch as a covariate in the statistical model (preferred)
- ComBat (sva package): explicit batch correction when batch is confounded with biology (last resort)
- Design: avoid confounding batch with condition — randomize sample processing across batches
- RUVSeq: remove unwanted variation using control genes or replicate samples

### Visualization
- PCA plot: first two principal components — check for sample clustering and outliers
- Volcano plot: log2 fold change (x-axis) vs -log10 adjusted p-value (y-axis)
- MA plot: average expression (x) vs log2 fold change (y) — shows expression-dependent bias
- Heatmap: clustered expression of top differentially expressed genes across samples
- Gene set enrichment: pathway-level visualization (GSEA, clusterProfiler) — biological interpretation

### Experimental Design
- Biological replicates: minimum 3 per condition, 6+ recommended for adequate power
- Sequencing depth: 20-30 million reads per sample for differential expression; more for rare transcripts
- Paired design: same individual before/after treatment — more statistical power
- Spike-ins (ERCC): synthetic RNA standards for technical QC and normalization validation
- Document: library prep method, sequencing platform, read length, strandedness

## Gotchas / Anti-patterns
- Using normalized counts (TPM, RPKM) as input to DESeq2/edgeR — these tools require raw counts
- No biological replicates — no statistical power, no p-values, results are anecdotal
- Batch confounded with condition — biological signal is inseparable from batch effect
- Filtering genes after differential expression testing — inflates false discovery rate
- Interpreting fold change without statistical significance (or vice versa)
- Ignoring sample outliers visible in PCA — one bad sample can skew all results
- Over-interpreting enrichment of broad GO terms ("metabolic process") — too vague to be informative

## References
- DESeq2 Vignette: https://bioconductor.org/packages/release/bioc/vignettes/DESeq2/inst/doc/DESeq2.html
- edgeR User Guide: https://bioconductor.org/packages/release/bioc/vignettes/edgeR/inst/doc/edgeRUsersGuide.pdf
- STAR Aligner: https://github.com/alexdobin/STAR
- Salmon: https://combine-lab.github.io/salmon/
- Conesa et al., "A survey of best practices for RNA-seq data analysis" (Genome Biology 2016)
- clusterProfiler: https://bioconductor.org/packages/release/bioc/html/clusterProfiler.html

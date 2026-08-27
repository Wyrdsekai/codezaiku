# Sequence Alignment Patterns

## When to use
- Comparing DNA, RNA, or protein sequences to identify similarities
- Finding homologous genes or proteins across species
- Identifying conserved regions, mutations, or evolutionary relationships
- Database searches to find known sequences similar to a query

## Pattern

### Pairwise Alignment
- **Global alignment** (Needleman-Wunsch): align entire length of both sequences end-to-end
- **Local alignment** (Smith-Waterman): find the most similar subsequence within two sequences
- Dynamic programming: O(mn) time and space for sequences of length m and n
- Scoring: substitution matrix (BLOSUM62 for proteins, simple match/mismatch for DNA) plus gap penalties
- Affine gap penalty: opening cost + extension cost — biologically realistic (one long gap more likely than many short ones)

### BLAST (Basic Local Alignment Search Tool)
- Heuristic search: finds approximate local alignments much faster than Smith-Waterman
- Seed-and-extend: find exact word matches (seeds), extend to high-scoring pairs (HSPs)
- E-value: expected number of alignments with this score by chance — lower = more significant
- Database choice: nr (non-redundant protein), nt (nucleotide), custom databases
- Variants: blastn (DNA vs DNA), blastp (protein vs protein), blastx (translated DNA vs protein), tblastn (protein vs translated DNA)
- Not guaranteed to find the optimal alignment — may miss weak similarities

### Multiple Sequence Alignment (MSA)
- Align three or more sequences simultaneously to identify conserved positions
- Progressive alignment (ClustalW, MUSCLE): build guide tree, align pairs, merge
- Iterative refinement (MAFFT, MUSCLE): progressively align, then re-align to improve
- Profile HMMs (HMMER): statistical model trained from MSA, used for sensitive database searches
- Consistency-based (T-Coffee): use pairwise alignments to guide multiple alignment — better accuracy, higher cost
- Output: alignment matrix with gaps inserted to maximize overall similarity

### Scoring and Statistics
- Substitution matrices: BLOSUM62 (general proteins), PAM250 (distant homologs), identity matrix (DNA)
- Gap penalties: affine (-10 open, -1 extend is common for proteins) — tune based on expected indel rates
- Statistical significance: E-value accounts for database size and query length
- Raw score depends on scoring matrix; bit score is normalized and comparable across searches
- Multiple testing: searching large databases inflates false positive risk — use E-value, not raw score

### Alignment Applications
- Ortholog detection: find corresponding genes across species (reciprocal best BLAST hit)
- Domain identification: align against domain databases (Pfam, InterPro)
- Primer design: align to target region, identify conserved sites for primers
- Phylogenetics: MSA is the input for tree-building methods
- Structural alignment: when sequence similarity is too low, align based on 3D structure

## Gotchas / Anti-patterns
- Using global alignment when only a domain or motif is expected to match — masks the real signal
- BLAST E-value threshold too lenient (>0.01) — many false positives in large databases
- Aligning very divergent sequences with DNA scoring — protein alignment is more sensitive for coding regions
- Ignoring gap placement quality in MSA — gaps in conserved regions indicate alignment error
- Using default BLAST parameters without considering query type and database size
- Over-interpreting low-complexity region matches (poly-A, repeat regions) — mask or filter first
- Treating BLAST results as ground truth — BLAST is heuristic, may miss true homologs

## References
- NCBI BLAST: https://blast.ncbi.nlm.nih.gov/
- MAFFT: https://mafft.cbrc.jp/alignment/software/
- HMMER: http://hmmer.org/
- BLOSUM Matrices (Henikoff & Henikoff, 1992): foundational scoring reference
- "Biological Sequence Analysis" (Durbin, Eddy, Krogh, Mitchison) — standard bioinformatics textbook
- Pfam Protein Families: https://www.ebi.ac.uk/interpro/

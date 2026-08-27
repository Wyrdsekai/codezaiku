# Genomic Data Format Patterns

## When to use
- Working with sequencing data at any stage of analysis
- Choosing appropriate formats for storage, transfer, and processing
- Handling large genomic files efficiently (indexing, random access, compression)
- Interoperating with standard bioinformatics tools and databases

## Pattern

### FASTA
- Reference sequences: genome assemblies, protein databases, transcript sequences
- Format: header line (`>identifier description`) followed by sequence lines
- Line width convention: 60 or 80 characters per line — some tools are sensitive to this
- Indexed access: `samtools faidx` creates `.fai` index for O(1) random access by sequence name
- Multi-FASTA: multiple sequences in one file — standard for genome assemblies and databases

### FASTQ
- Raw sequencing reads with per-base quality scores
- Four lines per record: `@header`, sequence, `+`, quality string (Phred+33 encoding)
- Paired-end reads: two files (R1, R2) with matching read order — never sort independently
- Compression: always gzip FASTQ files (`.fastq.gz`) — 3-5x size reduction, most tools read gzipped natively
- Quality encoding: Phred+33 (Sanger/Illumina 1.8+) is universal now — Phred+64 is obsolete

### BAM/CRAM (Aligned Reads)
- **BAM**: binary, compressed SAM (Sequence Alignment/Map) — the standard for aligned reads
- **CRAM**: reference-based compression, 30-50% smaller than BAM — requires reference FASTA for decoding
- Index: `.bai` (BAM index) or `.crai` (CRAM index) — enables random access by genomic coordinate
- Mandatory: sort by coordinate before indexing — `samtools sort` then `samtools index`
- Header: contains reference sequence dictionary and read group information — essential metadata
- Flags: bitwise field encoding paired/mapped/duplicate/supplementary status per read

### VCF/BCF (Variants)
- **VCF** (Variant Call Format): text format for SNPs, indels, structural variants
- **BCF**: binary VCF — faster to parse, smaller, recommended for large cohorts
- Index: tabix (`.tbi`) for VCF, CSI (`.csi`) for BCF — enables region queries
- Key fields: CHROM, POS, REF, ALT, QUAL, FILTER, INFO, FORMAT, sample genotypes
- Multi-sample VCF: one file with genotypes for all samples — standard for cohort analyses
- Normalization: left-align and trim indels (`bcftools norm`) before merging or comparing VCFs

### BED (Genomic Intervals)
- Tab-separated: chromosome, start (0-based), end (exclusive), optional name/score/strand
- 0-based, half-open coordinates — different from VCF (1-based) and GFF (1-based, closed)
- Use for: target regions, gene coordinates, blacklisted regions, peak calls
- Sort: `sort -k1,1 -k2,2n` — required for bedtools and tabix indexing
- bedtools: comprehensive toolkit for interval arithmetic (intersect, subtract, merge, closest)

### Handling Large Files
- Never load entire BAM/VCF into memory — use streaming or indexed access
- Region queries: `samtools view file.bam chr1:1000000-2000000` — reads only relevant index entries
- Parallel processing: split by chromosome or region, process independently, merge results
- Storage tiers: active analysis on fast storage (SSD), archive completed data to cold storage
- Checksums: MD5 for file integrity verification after transfer — genomic files are large, corruption is possible

### Format Conversion
- SAM to BAM: `samtools view -bS input.sam > output.bam` — always convert, never keep SAM
- BAM to CRAM: `samtools view -C -T reference.fa input.bam > output.cram` — for long-term storage
- VCF to BCF: `bcftools view -Ob input.vcf.gz > output.bcf` — for large cohorts
- Interleaved to split FASTQ: `seqtk seq` or `bbtools reformat.sh`
- Always verify conversion: compare record counts before and after

## Gotchas / Anti-patterns
- Storing uncompressed FASTQ or SAM — wastes 3-5x storage
- Mixing 0-based (BED) and 1-based (VCF, GFF) coordinates — off-by-one errors everywhere
- Unsorted BAM files used for variant calling — most callers require coordinate-sorted input
- CRAM without the matching reference — file is unreadable without the exact reference it was encoded against
- Paired FASTQ files with mismatched read order — silent errors in downstream tools
- VCF with unnormalized indel representation — same variant represented differently prevents matching
- Ignoring BAM index after modifying the BAM — stale index causes wrong region queries

## References
- SAM/BAM/CRAM Specification: https://samtools.github.io/hts-specs/
- VCF Specification: https://samtools.github.io/hts-specs/VCFv4.3.pdf
- samtools: https://www.htslib.org/
- bcftools: https://samtools.github.io/bcftools/
- bedtools: https://bedtools.readthedocs.io/
- BioStars (Q&A): https://www.biostars.org/ — community answers for format questions

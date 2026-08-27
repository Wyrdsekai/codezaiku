# Variant Calling Patterns

## When to use
- Identifying genetic variants (SNPs, indels, structural variants) from sequencing data
- Clinical genomics for diagnosing genetic diseases
- Population genetics for studying genetic diversity
- Cancer genomics for identifying somatic mutations in tumors

## Pattern

### Read Mapping
- Align sequencing reads to a reference genome using a read mapper (BWA-MEM2, minimap2, Bowtie2)
- Reference genome version matters: GRCh38 (hg38) is current for human — be consistent across the pipeline
- Mark duplicates (Picard, sambamba): PCR duplicates inflate variant confidence — flag but keep in BAM
- Base quality score recalibration (BQSR): adjust quality scores using known variant sites — reduces systematic errors
- Output: sorted BAM/CRAM file with mapping quality per read

### SNP and Indel Detection
- **GATK HaplotypeCaller**: local reassembly + pair-HMM, gold standard for germline variants
- **DeepVariant**: deep learning-based caller, competitive accuracy, especially for indels
- **FreeBayes**: Bayesian haplotype-based, simpler setup, good for population-level calling
- Joint calling: call variants across a cohort simultaneously — borrows strength from shared patterns
- GVCF workflow: per-sample GVCFs, then joint genotyping — scalable for large cohorts

### Quality Filtering
- GATK VQSR (Variant Quality Score Recalibration): machine learning model trained on known true and false sites
- Hard filters when cohort is too small for VQSR: QD > 2, FS < 60, MQ > 40 (for SNPs)
- Genotype quality (GQ): confidence in the called genotype — GQ >= 20 is common threshold
- Read depth (DP): minimum 10-20x coverage at the site for reliable calls
- Strand bias: variants observed on only one strand may be artifacts
- Multi-allelic sites: split into biallelic records before downstream analysis

### Variant Annotation
- Functional annotation: classify variant effect (missense, nonsense, splice site, intergenic)
- Tools: VEP (Ensembl), SnpEff, ANNOVAR
- Population frequency: annotate with gnomAD allele frequencies — common variants unlikely to be pathogenic
- Clinical databases: ClinVar (pathogenicity), OMIM (disease associations)
- Computational predictions: CADD, REVEL, AlphaMissense for variant pathogenicity scoring
- Conservation scores: PhyloP, GERP — highly conserved positions are more likely functionally important

### Somatic Variant Calling (Cancer)
- Tumor-normal pair: call variants present in tumor but absent from matched normal tissue
- Tools: Mutect2 (GATK), Strelka2, VarScan2
- Low variant allele frequency (VAF): somatic variants may be present in 5-20% of reads (subclonal)
- Panel of normals (PoN): filter recurrent artifacts seen across normal samples
- Tumor purity and ploidy estimation: affects VAF interpretation (PureCN, FACETS)
- Mutational signatures: patterns of mutations reveal underlying mutagenic processes

### Structural Variant Detection
- Larger events: deletions, duplications, inversions, translocations (>50bp)
- Evidence types: split reads, discordant read pairs, read depth changes, assembly
- Tools: Manta, DELLY, LUMPY, GRIDSS — each uses different evidence combination
- Long-read sequencing (PacBio, ONT) dramatically improves SV detection sensitivity
- SV calling is less mature than SNP calling — expect higher false positive rates

## Gotchas / Anti-patterns
- Not marking PCR duplicates — inflated coverage causes false confident variant calls
- Using GRCh37 (hg19) reference with GRCh38 annotation databases — coordinate mismatch
- Applying SNP quality filters to indels (or vice versa) — different error profiles
- Somatic calling without matched normal — germline variants misclassified as somatic
- Ignoring multi-allelic sites — some tools silently drop or mishandle them
- Not accounting for population stratification in frequency filtering — rare in one population may be common in another
- Trusting a single variant caller — consensus across callers reduces false positives

## References
- GATK Best Practices: https://gatk.broadinstitute.org/hc/en-us/sections/360007226651-Best-Practices-Workflows
- DeepVariant: https://github.com/google/deepvariant
- gnomAD: https://gnomad.broadinstitute.org/
- Ensembl VEP: https://www.ensembl.org/info/docs/tools/vep/
- ClinVar: https://www.ncbi.nlm.nih.gov/clinvar/
- BWA-MEM2: https://github.com/bwa-mem2/bwa-mem2

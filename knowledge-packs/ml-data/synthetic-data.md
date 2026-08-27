# Synthetic Data

## When to use
- Real data is insufficient, expensive, or impossible to collect at scale
- Privacy regulations (GDPR, HIPAA) restrict sharing or using real data
- Testing edge cases, rare events, or failure modes that are underrepresented in real data
- Data augmentation alone is not enough to cover the distribution gap
- Need to create development/testing datasets without exposing production data

## Pattern

### Generation approaches

#### Statistical / rule-based
- **Parametric**: fit distributions to real data, sample from them (Gaussian, Poisson, copulas for correlations)
- **Agent-based simulation**: define rules and simulate interactions (e.g., synthetic user behavior, market transactions)
- **Template-based**: fill templates with sampled values (synthetic addresses, names, transactions)
- Best for structured/tabular data with well-understood distributions

#### Deep generative models
- **GANs** (Generative Adversarial Networks): generator vs discriminator training; high-fidelity images, tabular data (CTGAN, TVAE)
- **VAEs** (Variational Autoencoders): encode-decode with latent space; smoother but sometimes blurrier outputs
- **Diffusion models**: iterative denoising; state-of-the-art for image generation quality
- **LLMs for text**: prompt-based generation of synthetic text, conversations, documents; validate quality rigorously

#### Privacy-preserving methods
- **Differential privacy + generative models**: add calibrated noise during training to bound re-identification risk
- **Marginal-based synthesis**: preserve statistical properties of individual columns and pairwise correlations while breaking individual records
- **Synthetic data with privacy guarantees**: PATE-GAN, DP-CTGAN — formal privacy budgets (epsilon values)

### Validation framework
1. **Fidelity**: synthetic data should match real data distributions (column-wise and joint)
   - Compare marginals, correlations, and higher-order statistics
   - Train-on-synthetic, test-on-real (TSTR) benchmark
2. **Utility**: models trained on synthetic data should perform comparably to models trained on real data
   - Measure downstream task performance (accuracy, F1, AUROC)
3. **Privacy**: synthetic data should not leak individual real records
   - Membership inference attacks, nearest-neighbor distance ratios
   - Distance to closest record (DCR) metric
4. **Diversity**: synthetic data should cover the full distribution, not mode-collapse to common patterns

### Practical workflow
1. Profile real data thoroughly before generating synthetic data
2. Generate candidates, validate with fidelity and utility metrics
3. Run privacy tests if the synthetic data will be shared externally
4. Document generation method, parameters, and validation results
5. Version synthetic datasets separately from real data (see data-versioning.md)

## Gotchas / Anti-patterns
- **Assuming synthetic = private**: generation without formal privacy guarantees can still memorize and leak real records
- **Training and evaluating on synthetic only**: always validate on real held-out data; synthetic-only evaluation is circular
- **Mode collapse**: generative models may only produce common patterns; check coverage of rare categories and tail distributions
- **Ignoring temporal/relational structure**: synthetic tabular rows without preserving time ordering or foreign key relationships are unrealistic
- **Over-reliance on synthetic data**: synthetic data supplements real data; it cannot fully replace domain-representative real data collection
- **Not documenting provenance**: downstream users must know data is synthetic; label it clearly

## References
- SDV (Synthetic Data Vault): https://sdv.dev — tabular, relational, time-series synthesis
- Gretel.ai: synthetic data platform with privacy metrics
- CTGAN paper: "Modeling Tabular Data using Conditional GAN" (Xu et al.)
- "The Synthetic Data Vault" (Patki et al., 2016)
- NIST synthetic data resources and de-identification guidelines

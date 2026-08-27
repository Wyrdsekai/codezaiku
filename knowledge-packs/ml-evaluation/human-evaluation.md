# Human Evaluation

## When to use
- Tasks where automated metrics are insufficient or unreliable (open-ended generation, creative writing, dialogue)
- Establishing ground truth for training automated evaluation systems (LLM-as-judge calibration)
- High-stakes decisions where model quality must be validated by domain experts
- Measuring subjective qualities: helpfulness, naturalness, tone, cultural appropriateness

## Pattern

### Annotation Guideline Design
- Write guidelines before any annotation begins; iterate on them, not on the data post-hoc
- Include:
  - Task definition and context (what is the annotator evaluating and why)
  - Rating scale with anchored descriptions for each level (not just "1=bad, 5=good")
  - Worked examples: at least 3 per rating level, with explanations
  - Edge cases and how to handle them
  - What to do when unsure (flag, default, skip)
- Test guidelines with a pilot group; if agreement is low, revise guidelines, not annotators
- Version control guidelines and update them as new edge cases emerge

### Rating Scales
- **Likert scale (1-5 or 1-7)**: for subjective quality judgments
  - Anchor each point with a concrete description and example
  - Avoid scales wider than 7 points; humans cannot reliably distinguish more
- **Binary (yes/no)**: for factual checks (is this answer correct? does it contain hallucinations?)
  - Simpler, higher agreement, but loses nuance
- **Pairwise comparison (A vs B vs tie)**: for model comparison
  - Easier for annotators than absolute scoring; produces more consistent results
  - Requires more comparisons to rank K models (K*(K-1)/2 pairs)
- **Ranking**: order N items from best to worst
  - Efficient for comparing many options; cognitively harder for annotators

### Inter-Annotator Agreement (IAA)
- Measure how consistently different annotators rate the same items
- **Cohen's Kappa**: two annotators, categorical labels; accounts for chance agreement
  - Kappa < 0.4 = poor, 0.4-0.6 = moderate, 0.6-0.8 = substantial, > 0.8 = excellent
- **Fleiss' Kappa**: extends Cohen's to multiple annotators
- **Krippendorff's Alpha**: works for any number of annotators, missing data, and different scale types
  - Most general measure; recommended as the default
- **ICC (Intraclass Correlation)**: for continuous/ordinal scales
- Low IAA usually indicates unclear guidelines, not bad annotators; revise the guidelines first

### Minimum Annotators
- At least 3 annotators per item for categorical judgments; 5 for subjective quality ratings
- For pairwise comparisons: 3-5 judges per pair
- More annotators per item = more reliable aggregate; fewer unique items evaluated
- Trade off breadth (more items, fewer annotators each) vs depth (fewer items, more annotators each) based on your goal

### Crowdsourcing Patterns
- **Qualification tasks**: screen workers with a test set where ground truth is known
- **Attention checks**: embed obvious items (honeypots) to detect random or inattentive workers
- **Calibration items**: include items with known scores to detect annotator drift over time
- **Fair compensation**: pay for time, not per item; per-item payment incentivizes speed over quality
- **Batching**: keep task batches short (10-20 items) to maintain focus
- Provide a feedback channel for annotators to report confusing items or guideline gaps

### Expert vs Crowd Evaluation
- Domain experts: higher quality, more expensive, slower, limited availability
- Crowd workers: scalable, faster, cheaper, but need more quality controls
- Hybrid: use experts for guideline validation and a subset of annotations; crowd for scale
- For specialized domains (medical, legal, scientific): expert evaluation is non-negotiable

### Bias Mitigation in Human Eval
- **Position bias**: randomize the order of items or model outputs presented
- **Anchoring bias**: do not show automated scores before human rating
- **Fatigue effects**: randomize item order across annotators; limit session length
- **Cultural bias**: use annotators representative of the target user population
- **Confirmation bias**: annotators should not know which model produced which output

### Workflow
1. Define evaluation criteria and build annotation guidelines with examples
2. Pilot with 3-5 annotators on 30-50 items; measure IAA
3. If IAA is low, revise guidelines and re-pilot (do not proceed with poor guidelines)
4. Run full annotation with quality controls (honeypots, calibration items)
5. Compute final IAA; report it alongside results
6. Aggregate annotations (majority vote for categorical, mean/median for ordinal)
7. Analyze disagreements: high-disagreement items often reveal genuinely ambiguous cases

## Gotchas / Anti-patterns
- Skipping the pilot phase and going directly to full annotation
- Writing vague guidelines and blaming annotators for low agreement
- Using only 1-2 annotators per item (no way to measure or ensure reliability)
- Paying per-item without time-based minimums (incentivizes rushing)
- Not randomizing presentation order of model outputs
- Reporting aggregate human scores without IAA metrics
- Treating human evaluation as ground truth without acknowledging annotator subjectivity
- Comparing human eval results across studies with different guidelines and annotator pools

## References
- Artstein & Poesio, "Inter-Coder Agreement for Computational Linguistics" (2008)
- Daniel et al., "Quality Control in Crowdsourcing" (2018)
- Clark et al., "All That's 'Human' Is Not Gold" (2021)
- Karpinska et al., "Large Language Models Are Not Fair Evaluators" (2023)

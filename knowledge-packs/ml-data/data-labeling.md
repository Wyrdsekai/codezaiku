# Data Labeling

## When to use
- Building a supervised learning model and ground truth labels do not exist
- Existing labels are noisy, inconsistent, or incomplete
- Scaling labeling beyond what one person can do manually
- Label budget is limited and must be spent efficiently
- Continuously improving label quality over the project lifecycle

## Pattern

### Labeling strategies
- **Manual labeling**: human annotators with clear guidelines; gold standard but expensive
- **Crowdsourced labeling**: distribute tasks across many annotators; use redundancy (3-5 labels per item) and aggregate (majority vote, Dawid-Skene model)
- **Expert labeling**: domain specialists for high-stakes tasks (medical, legal); fewer annotators, higher per-label quality
- **Semi-automated**: model pre-labels, humans correct; faster than from-scratch annotation

### Active learning
- Start with a small labeled seed set, train a model, then iteratively select the most informative unlabeled samples to label next
- **Uncertainty sampling**: pick samples where the model is least confident
- **Query-by-committee**: pick samples where an ensemble of models disagrees most
- **Diversity sampling**: pick samples that are most different from already-labeled data
- Reduces total labeling cost by 30-70% compared to random selection in many domains

### Weak supervision
- **Labeling functions**: programmatic rules that generate noisy labels (regex patterns, heuristics, knowledge bases)
- **Label model**: combine multiple noisy labeling functions to estimate true labels (Snorkel paradigm)
- **Zero-shot / few-shot classification**: use an LLM to generate candidate labels, then validate a sample manually
- Weak labels are a starting point; refine with active learning or manual correction on hard cases

### Annotation guidelines
- Write detailed, unambiguous guidelines with examples for each class
- Include edge cases and explicitly define boundary decisions
- Version the guidelines alongside the dataset
- Pilot with a small batch, review inter-annotator agreement, refine guidelines before full-scale annotation

### Label quality metrics
- **Inter-annotator agreement**: Cohen's kappa (2 annotators), Fleiss' kappa (3+), Krippendorff's alpha (any scale)
  - Kappa > 0.8 is strong; 0.6-0.8 is moderate; below 0.6 signals guideline or task problems
- **Label error detection**: confident learning (Cleanlab), cross-validation consensus filtering
- **Adjudication**: resolve disagreements via expert review or majority vote with confidence threshold

### Annotation formats
- **Classification**: CSV/JSONL with sample ID, label, annotator, confidence
- **Sequence labeling (NER)**: IOB2/BIOES format, standoff annotations
- **Object detection**: COCO JSON (bounding boxes), Pascal VOC XML
- **Segmentation**: COCO polygons, binary masks, RLE encoding
- Standardize early; converting between formats is error-prone

## Gotchas / Anti-patterns
- **No annotation guidelines**: leads to inconsistent labels; always write guidelines before labeling begins
- **Single annotator, no redundancy**: no way to measure label quality; use at least 2 annotators on a quality subset
- **Ignoring annotator bias**: some annotators are systematically biased; model per-annotator reliability
- **Labeling test data with a model**: test labels must be human-verified; model-generated test labels create circular evaluation
- **Not versioning labels**: label corrections over time must be tracked (see data-versioning.md)
- **Anchor bias from pre-labels**: annotators tend to accept model suggestions; randomize and include deliberate errors to keep annotators attentive

## References
- Snorkel: https://www.snorkel.org — programmatic labeling framework
- Cleanlab: https://cleanlab.ai — label error detection
- Label Studio: open-source annotation tool
- Prodigy: scriptable annotation tool (commercial)
- "A Survey of Active Learning for Text Classification" (Settles, 2009)

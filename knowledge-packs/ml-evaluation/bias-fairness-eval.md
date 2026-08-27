# Bias and Fairness Evaluation

## When to use
- Models that make decisions affecting people (hiring, lending, healthcare, content moderation)
- Any deployment where disparate outcomes across demographic groups carry legal or ethical risk
- Auditing existing models for unintended discrimination
- Regulatory compliance (EU AI Act, US Equal Credit Opportunity Act, etc.)

## Pattern

### Group Fairness Metrics
- **Demographic Parity (Statistical Parity)**: positive prediction rate is equal across groups
  - P(Y_hat=1 | A=a) = P(Y_hat=1 | A=b) for protected attribute A
  - Ignores base rates; a group with genuinely higher qualification rate will be under-served
- **Equalized Odds**: true positive rate and false positive rate are equal across groups
  - Conditions on the actual outcome; allows different prediction rates if justified by ground truth
  - Harder to satisfy than demographic parity
- **Equal Opportunity**: relaxation of equalized odds; only requires equal true positive rates
  - Focus: the model should not miss qualified individuals more often for one group
- **Predictive Parity**: precision is equal across groups
  - When a positive prediction is made, it is equally likely to be correct regardless of group
- **Calibration**: predicted probability matches true probability within each group
  - P(Y=1 | score=s, A=a) = s for all groups

### Impossibility Results
- Demographic parity, equalized odds, and predictive parity cannot all hold simultaneously (except in degenerate cases)
- Choose the fairness criterion that aligns with the specific harm you want to prevent
- Document the choice and rationale explicitly

### Disparate Impact Ratio
- Ratio of positive outcome rates: min(P(Y=1|A=a), P(Y=1|A=b)) / max(...)
- Four-fifths rule (US EEOC): ratio below 0.8 suggests adverse impact
- A screening tool, not a definitive test; investigate root causes when triggered
- Apply to model predictions and to each stage of a multi-stage pipeline

### Intersectional Analysis
- Single-attribute analysis can mask compounding biases
- Evaluate across intersections: e.g., (gender x race), (age x disability status)
- Small subgroup sizes make statistical estimates noisy; use bootstrapped confidence intervals
- Reporting: show metric breakdowns by intersection, not just single attributes

### Evaluation Workflow
1. Identify protected attributes relevant to the deployment context and jurisdiction
2. Ensure evaluation data has demographic labels (or use proxy analysis with care)
3. Compute all candidate fairness metrics per group and per intersection
4. Select the primary fairness criterion based on the deployment's harm model
5. Set thresholds (e.g., disparate impact > 0.8) with stakeholder and legal input
6. Test on held-out data; report confidence intervals, not just point estimates
7. Re-evaluate periodically as user population and data distributions shift

### Data Bias vs Model Bias
- Biased training data is the most common source of model bias
- Label bias: annotators encode societal biases into ground truth
- Representation bias: underrepresented groups have fewer or lower-quality samples
- Measurement bias: features proxy for protected attributes (zip code proxying race)
- Distinguish: does the model amplify existing data bias, or introduce new bias?

## Gotchas / Anti-patterns
- Optimizing for one fairness metric without checking others (may worsen a different dimension)
- Assuming "fairness through unawareness" (removing protected attributes) is sufficient; proxies remain
- Evaluating fairness only on aggregate groups without intersectional breakdown
- Using a single threshold for all groups without examining group-specific calibration
- Treating fairness evaluation as a one-time audit rather than continuous monitoring
- Ignoring the impossibility theorem and expecting all metrics to be satisfied simultaneously
- Using synthetic or simulated demographic data without validating its representativeness

## References
- Barocas, Hardt & Narayanan, "Fairness and Machine Learning" (fairmlbook.org)
- Chouldechova, "Fair prediction with disparate impact: A study of bias in recidivism prediction" (2017)
- Buolamwini & Gebru, "Gender Shades" (2018)
- Mitchell et al., "Model Cards for Model Reporting" (2019)

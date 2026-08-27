# Data Augmentation

## When to use
- Training data is scarce or expensive to collect
- Model overfits on the training set
- Domain-specific invariances are known (e.g., a rotated cat is still a cat)
- Class imbalance exists and augmentation can supplement minority classes
- You want to improve robustness to real-world variations

## Pattern

### Image augmentation
- **Geometric**: rotation, flipping, cropping, scaling, affine transforms
- **Photometric**: brightness, contrast, saturation, hue jittering, noise injection
- **Structured**: cutout/random erasing, mixup (blend two images), CutMix (patch replacement)
- **Advanced**: style transfer, elastic deformations (medical imaging), mosaic (object detection)
- Apply augmentation online (during training) to maximize variety per epoch

### Text augmentation
- **Surface-level**: synonym replacement, random insertion/deletion/swap of words
- **Back-translation**: translate to another language and back to generate paraphrases
- **Contextual**: mask tokens and use a language model to fill (contextual word substitution)
- **Template-based**: entity swapping, sentence restructuring using predefined patterns
- **LLM-generated**: prompt a model to rephrase or generate similar examples (validate quality)

### Tabular augmentation
- **SMOTE variants**: interpolate between minority-class neighbors (see class-imbalance.md)
- **Noise injection**: add Gaussian noise to continuous features within plausible bounds
- **Feature permutation**: shuffle one feature at a time to create semi-synthetic rows
- **Mixup for tabular**: linear interpolation between feature vectors (works with tree models too)

### Audio augmentation
- Time stretching, pitch shifting, adding background noise
- SpecAugment: mask frequency bands and time steps on spectrograms
- Room impulse response simulation for robustness to recording conditions

### General principles
1. Augmentations must preserve the label semantics (rotating a digit 6 by 180 degrees makes it a 9)
2. Compose augmentations stochastically with varying probability and magnitude
3. Validate augmented data visually or via spot-checks before full training
4. Track which augmentations are applied as training metadata

## Gotchas / Anti-patterns
- **Augmenting test/validation data**: never augment evaluation sets; it inflates metrics and hides real performance
- **Label-breaking transforms**: verify that augmentation does not change the correct label (especially geometric transforms for detection/segmentation)
- **Excessive augmentation**: too-aggressive transforms create unrealistic samples that confuse the model
- **Augmenting already-clean data without need**: augmentation adds training cost; apply when there is a clear signal of overfitting or data scarcity
- **Ignoring domain constraints**: medical/legal/financial domains have strict rules about what constitutes valid data

## References
- Albumentations library: fast image augmentation pipelines
- nlpaug: text augmentation library
- AugLy (Meta): multi-modal augmentation
- "A Survey on Data Augmentation for Text Classification" (Feng et al.)
- SpecAugment paper: "SpecAugment: A Simple Data Augmentation Method for ASR"

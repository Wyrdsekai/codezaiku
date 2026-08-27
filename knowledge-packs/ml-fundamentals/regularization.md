# Regularization

## When to use
- Training loss is much lower than validation loss (overfitting)
- Model capacity is large relative to dataset size
- You need to improve generalization without reducing model size
- Regularization is almost always beneficial — the question is which techniques and how much

## Pattern

### Dropout
- Randomly zeroes activations during training with probability p (typically 0.1-0.5)
- Forces redundancy — the network cannot rely on any single neuron
- Apply after activation functions, before the next linear layer
- Standard for fully-connected and attention layers. Typical p=0.1 for transformers, p=0.5 for MLPs
- Spatial dropout for CNNs (drops entire feature maps)
- Disabled at inference time — activations are scaled by (1-p) or use inverted dropout during training

### Weight Decay (L2 Regularization)
- Penalizes large weight magnitudes, encouraging simpler models
- In AdamW: directly shrinks weights each step (decoupled from gradient). Typical: 0.01-0.1
- In SGD: equivalent to L2 penalty on the loss. Typical: 1e-4 to 5e-4
- Exclude bias parameters and normalization layer parameters from weight decay
- Stronger decay = stronger regularization = lower capacity

### Early Stopping
- Monitor validation loss; stop training when it stops improving for N epochs (patience)
- Simple, effective, and nearly universal
- Typical patience: 3-10 epochs depending on dataset size and training speed
- Save the best checkpoint (by validation metric), not the last one
- Combines naturally with all other regularization methods

### Data Augmentation
- Generate training variety without collecting new data
- Vision: random crop, flip, rotation, color jitter, cutout, mixup, CutMix
- Text: synonym replacement, back-translation, random insertion/deletion, token masking
- Audio: time stretching, pitch shift, noise injection, SpecAugment
- Acts as implicit regularization by expanding the effective training distribution
- Stronger augmentation allows training larger models on smaller datasets

### Label Smoothing
- Replace hard targets (0/1) with soft targets (epsilon/K, 1-epsilon)
- Prevents overconfident predictions, improves calibration
- Typical epsilon: 0.1. Applied in the loss function
- Especially useful for classification with noisy labels

### Stochastic Depth
- Randomly skip entire layers during training (residual networks)
- Reduces effective model depth, acts as implicit ensemble
- Used in vision transformers and deep ResNets

## Gotchas / Anti-patterns
- Applying too much regularization — underfitting is worse than mild overfitting for many tasks
- Using dropout in batch-normalized networks — they interact poorly (variance shift at test time)
- Applying weight decay to all parameters including biases and norms — degrades performance
- Relying solely on early stopping without other regularization — misses generalization gains from dropout/augmentation
- Data augmentation that distorts label-relevant features (e.g., flipping text that changes meaning, rotating digits 6/9)
- Stacking every regularization method at maximum strength — they compound; tune them together

## References
- "Dropout: A Simple Way to Prevent Neural Networks from Overfitting" (Srivastava et al., 2014)
- "Decoupled Weight Decay Regularization" (Loshchilov & Hutter, 2019)
- "CutMix: Regularization Strategy to Train Strong Classifiers" (Yun et al., 2019)
- "When Does Label Smoothing Help?" (Muller et al., 2019)

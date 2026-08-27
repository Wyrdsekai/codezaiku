# Loss Functions

## When to use
- Every supervised training run requires a loss function — the choice directly shapes what the model optimizes for
- The loss function must match your task type, class distribution, and output format
- Wrong loss selection is a silent killer — training proceeds but produces poor or biased models

## Pattern

### Cross-Entropy Loss
- **Use for**: Multi-class classification, language modeling (next token prediction)
- Outputs must be logits or log-probabilities over a discrete set of classes
- Binary cross-entropy (BCE) for multi-label problems where classes are independent
- Softmax cross-entropy for mutually exclusive classes
- Numerically stable when computed from logits directly (avoid softmax then log separately)

### Mean Squared Error (MSE) / L2 Loss
- **Use for**: Regression tasks, continuous value prediction
- Sensitive to outliers — large errors are penalized quadratically
- Use L1 (MAE) instead when outlier robustness matters more than smooth gradients
- Huber loss combines L1 and L2: smooth near zero, linear for large errors

### Focal Loss
- **Use for**: Heavily imbalanced classification (e.g., object detection, rare event prediction)
- Down-weights well-classified examples, focusing training on hard negatives
- Controlled by gamma parameter: higher gamma = more focus on hard examples
- gamma=0 reduces to standard cross-entropy. Start with gamma=2.0

### Contrastive / Triplet Loss
- **Use for**: Learning embeddings, similarity search, few-shot learning
- Contrastive: pairs of (similar, dissimilar) — pulls similar together, pushes dissimilar apart
- Triplet: (anchor, positive, negative) — margin-based separation
- InfoNCE / NT-Xent: softmax over similarity scores in a batch, used in CLIP and SimCLR
- Hard negative mining is critical — random negatives become uninformative quickly

### KL Divergence
- **Use for**: Distribution matching, VAE latent regularization, knowledge distillation
- Asymmetric: KL(P||Q) != KL(Q||P). Direction matters for the training signal
- Forward KL (teacher as P) mode-covering. Reverse KL mode-seeking

### CTC Loss
- **Use for**: Sequence-to-sequence without explicit alignment (speech recognition, OCR)
- Handles variable-length input/output without requiring frame-level labels

## Gotchas / Anti-patterns
- Using MSE for classification — gradients are weak when predictions are confident but wrong
- Using cross-entropy for imbalanced data without adjustment — model learns to predict the majority class
- Forgetting to use `from_logits=True` — applying softmax twice silently degrades training
- Ignoring label smoothing — hard targets can cause overconfident predictions
- Using triplet loss without hard negative mining — training stalls after initial epochs
- Mixing up reduction modes (mean vs sum) — affects effective learning rate

## References
- "Focal Loss for Dense Object Detection" (Lin et al., 2017)
- "A Simple Framework for Contrastive Learning" (Chen et al., 2020) — NT-Xent loss
- PyTorch `nn.CrossEntropyLoss` documentation — stable computation from logits
- "Connectionist Temporal Classification" (Graves et al., 2006)

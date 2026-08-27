# Transfer Learning

## When to use
- Your dataset is small (< 10k samples) but a large pretrained model exists for the domain
- The source domain shares structural similarity with your target domain (e.g., ImageNet features transfer well to medical imaging)
- Training from scratch is prohibitively expensive in compute or time
- You need strong performance quickly and can tolerate some domain mismatch

## Pattern

### Fine-tuning (update all or most weights)
- Start from a pretrained checkpoint and train on your data with a low learning rate
- Freeze early layers initially, then unfreeze progressively (gradual unfreezing)
- Use a learning rate 10-100x smaller than training from scratch
- Fine-tune the full model when you have sufficient data (> 5k samples) and the domain shift is significant

### Feature extraction (freeze pretrained weights)
- Use the pretrained model as a fixed feature extractor — only train a new head
- Appropriate when your dataset is very small (< 1k samples) and overfitting is the primary risk
- Faster to train since only the head parameters update
- Works best when source and target domains are closely related

### Domain shift considerations
- Measure distribution gap: if target data looks very different from pretraining data, feature extraction will underperform
- Watch for negative transfer — performance worse than training from scratch. This happens when domains are too dissimilar
- For NLP, language mismatch between pretraining corpus and target is a common source of negative transfer
- For vision, pretrained models on natural images transfer poorly to satellite imagery, microscopy, or other non-photographic domains without fine-tuning

### Practical workflow
1. Start with feature extraction as a baseline
2. If performance is insufficient, try fine-tuning the last N layers
3. If still insufficient, fine-tune the full model with a very low learning rate
4. If negative transfer is observed, consider training from scratch or using a different pretrained model

## Gotchas / Anti-patterns
- Using a high learning rate when fine-tuning — destroys pretrained features in early layers (catastrophic forgetting)
- Fine-tuning with very small datasets without regularization — overfits to noise
- Assuming transfer always helps — always compare against a from-scratch baseline
- Ignoring input preprocessing — pretrained models expect specific normalization, resolution, or tokenization
- Fine-tuning a model pretrained on a vastly different domain without first validating feature relevance

## References
- ULMFiT paper (Howard & Ruder, 2018) — gradual unfreezing strategy
- "How transferable are features in deep neural networks?" (Yosinski et al., 2014)
- Hugging Face Transformers documentation — practical fine-tuning guides
- timm library documentation — vision model fine-tuning patterns

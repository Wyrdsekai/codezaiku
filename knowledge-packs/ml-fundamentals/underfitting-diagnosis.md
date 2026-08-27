# Underfitting Diagnosis

## When to use
- Both training loss and validation loss remain high
- Model performs poorly on the training set itself
- A simpler baseline (linear model, random forest) outperforms your neural network
- Model predictions cluster around the mean or majority class

## Pattern

### Detection signals
- **High training loss**: The model cannot fit even the data it trains on
- **Train and val loss are similar but both poor**: No overfitting, but no learning either
- **Loss barely decreases during training**: Optimization is stuck or learning rate is wrong
- **Predictions lack variance**: Model outputs near-constant values or always predicts the majority class
- **Performance below a reasonable baseline**: If a linear model beats your deep model, something is wrong

### Root causes and remedies

#### 1. Insufficient model capacity
- Model is too small or shallow for the complexity of the task
- **Fix**: Increase layers, hidden dimensions, number of parameters
- **Fix**: Switch architecture (e.g., from linear to transformer, from shallow CNN to deeper ResNet)

#### 2. Bad learning rate
- Too low: training converges extremely slowly or stalls
- Too high: loss oscillates wildly or diverges
- **Fix**: Run a learning rate range test — plot loss vs LR over a short sweep
- **Fix**: Start with known-good defaults for your architecture (1e-4 for Adam+transformers, 0.01-0.1 for SGD+CNNs)

#### 3. Insufficient training duration
- Model needs more epochs to converge
- **Fix**: Train longer, check if loss is still trending downward
- Distinguish from optimization issues — if loss is flat, more epochs will not help

#### 4. Data quality issues
- Noisy labels, corrupted data, or data that lacks signal for the target
- **Fix**: Audit a sample of training data manually
- **Fix**: Clean labels (majority vote, expert review, confident learning)
- **Fix**: Check for class imbalance — model may learn to predict majority class only

#### 5. Poor feature representation
- Raw input does not contain sufficient signal in a form the model can learn
- **Fix**: Better preprocessing (normalization, tokenization, feature engineering)
- **Fix**: Domain-specific input representation (spectrograms for audio, graphs for molecular data)
- **Fix**: Use pretrained embeddings or transfer learning to provide richer features

#### 6. Excessive regularization
- Too much dropout, weight decay, or data augmentation suppresses learning
- **Fix**: Temporarily remove all regularization. If training loss drops, add regularization back gradually
- This is a common cause when inheriting hyperparameters from a different task

### Diagnostic workflow
1. Verify data pipeline: are inputs and labels correct? Visualize samples
2. Overfit a small batch: can the model memorize 10-100 examples? If not, architecture or optimizer is broken
3. Remove all regularization and retrain
4. Run learning rate sweep
5. Increase model capacity
6. If all else fails, re-examine whether the task is learnable from the available features

## Gotchas / Anti-patterns
- Adding regularization to an underfitting model — regularization makes underfitting worse
- Blaming the model when the data is the problem — always audit data first
- Not testing overfitting on a tiny subset — if the model cannot overfit 100 examples, fix that before scaling
- Assuming more data helps underfitting — more data helps overfitting, not underfitting
- Using an architecture unsuited to the data modality (e.g., MLP on spatial data where CNN excels)

## References
- "A Recipe for Training Neural Networks" (Karpathy, 2019) — practical debugging guide
- See also: `overfitting-diagnosis.md`, `regularization.md`, `optimization.md`

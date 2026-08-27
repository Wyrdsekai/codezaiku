# DSPy Declarative Prompt Optimization

## When to use
- Building multi-step LLM pipelines where manual prompt engineering is unsustainable
- Need systematic optimization of prompts with measurable metrics
- Want to automatically generate few-shot examples from a training set
- Porting prompts across models (optimized prompts transfer better than hand-tuned ones)
- Complex chains-of-thought, retrieval-augmented, or tool-use programs

## Pattern

### Signatures (Declarative I/O)
```python
import dspy

# Simple signature — string shorthand
classify = dspy.Predict("sentence -> sentiment: bool")

# Class-based signature for complex tasks
class GenerateCode(dspy.Signature):
    """Generate code given a specification."""
    spec = dspy.InputField(desc="natural language specification")
    language = dspy.InputField(desc="target programming language")
    code = dspy.OutputField(desc="working implementation")
```

### Modules (Composable Programs)
```python
class CodeGenerator(dspy.Module):
    def __init__(self):
        self.plan = dspy.ChainOfThought("spec -> plan")
        self.generate = dspy.ChainOfThought(GenerateCode)
        self.review = dspy.Predict("code, spec -> is_correct: bool, feedback")

    def forward(self, spec, language):
        plan = self.plan(spec=spec)
        code = self.generate(spec=plan.plan, language=language)
        review = self.review(code=code.code, spec=spec)
        return dspy.Prediction(code=code.code, review=review)
```

### Optimizers
- **MIPROv2** — state-of-the-art; optimizes instructions + few-shot examples jointly
- **BootstrapFewShot** — generates few-shot examples from labeled training data
- **BootstrapFewShotWithRandomSearch** — BootstrapFewShot + random restarts
- **COPRO** — optimizes instruction text via coordinate ascent

```python
# Define metric
def code_metric(example, pred, trace=None):
    return int(pred.review.is_correct == True)

# Optimize
optimizer = dspy.MIPROv2(metric=code_metric, auto="medium")
optimized = optimizer.compile(CodeGenerator(), trainset=train_data)
```

### LM Configuration
```python
lm = dspy.LM("openai/gpt-4o", temperature=0.1)
# or local:
lm = dspy.LM("ollama_chat/qwen3-8b", api_base="http://localhost:11434")

dspy.configure(lm=lm)
```

### Saving and Loading
```python
# Save optimized program
optimized.save("optimized_codegen.json")

# Load later
program = CodeGenerator()
program.load("optimized_codegen.json")
```

### Evaluation
```python
evaluator = dspy.Evaluate(
    devset=dev_data,
    metric=code_metric,
    num_threads=4,
    display_progress=True,
)
score = evaluator(optimized)
```

## Gotchas / Anti-patterns
- Writing prompts manually instead of letting the optimizer find them — defeats the purpose of DSPy
- Too few training examples — MIPROv2 needs 50-200+ examples to optimize well
- Not defining a clear metric — garbage metric in, garbage optimization out
- Over-complicated signatures — keep I/O fields minimal; let CoT handle reasoning
- Ignoring `trace` in metrics — trace-aware metrics enable better optimization feedback
- Optimizing on the test set — classic data leakage; always split train/dev/test

## References
- DSPy docs: https://dspy.ai/
- DSPy GitHub: https://github.com/stanfordnlp/dspy
- MIPROv2 paper: https://arxiv.org/abs/2406.11695

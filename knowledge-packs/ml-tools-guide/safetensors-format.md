# SafeTensors Model Storage

## When to use
- Storing and distributing model weights securely (no arbitrary code execution)
- Fast memory-mapped loading of model checkpoints
- Any context where pickle-based formats (`.bin`, `.pt`) pose a security risk
- Sharing models on Hugging Face Hub (default format since 2023)
- Need zero-copy loading for large models

## Pattern

### Saving Tensors
```python
from safetensors.torch import save_file, load_file

# Save a dict of tensors
tensors = {
    "weight": model.linear.weight,
    "bias": model.linear.bias,
}
save_file(tensors, "model.safetensors")

# Save with metadata
save_file(tensors, "model.safetensors", metadata={"format": "pt", "dtype": "bf16"})
```

### Loading Tensors
```python
from safetensors.torch import load_file

# Load all tensors
tensors = load_file("model.safetensors")
# Returns dict: {"weight": tensor, "bias": tensor}

# Load to specific device
tensors = load_file("model.safetensors", device="cuda:0")
```

### HF Transformers Integration
```python
# Saving (default behavior)
model.save_pretrained("./output")
# Creates model.safetensors (or model-00001-of-00003.safetensors for sharded)

# Loading (auto-detected)
model = AutoModelForCausalLM.from_pretrained("./output")

# Force safetensors only (reject pickle fallback)
model = AutoModelForCausalLM.from_pretrained("./output", use_safetensors=True)
```

### Converting from PyTorch `.bin`
```python
from safetensors.torch import save_file
import torch

# Load old format
state_dict = torch.load("pytorch_model.bin", map_location="cpu", weights_only=True)

# Save as safetensors
save_file(state_dict, "model.safetensors")
```

```bash
# CLI conversion for HF models
python -c "
from transformers import AutoModelForCausalLM
model = AutoModelForCausalLM.from_pretrained('./old_model')
model.save_pretrained('./new_model')
"
```

### Sharded Models
- Large models split into multiple files: `model-00001-of-00003.safetensors`
- Index file `model.safetensors.index.json` maps parameter names to shard files
- HF handles sharding automatically with `max_shard_size` parameter:
  `model.save_pretrained("./output", max_shard_size="5GB")`

### Inspecting Files
```python
from safetensors import safe_open

with safe_open("model.safetensors", framework="pt") as f:
    # List all tensor names
    print(f.keys())

    # Get tensor metadata without loading
    for key in f.keys():
        tensor = f.get_tensor(key)
        print(f"{key}: shape={tensor.shape}, dtype={tensor.dtype}")

    # Load single tensor (memory efficient)
    weight = f.get_tensor("model.layers.0.self_attn.q_proj.weight")
```

### Security Properties
- No executable code — format is pure tensor data + JSON metadata header
- Cannot contain pickle payloads, arbitrary Python objects, or shell commands
- Safe to load from untrusted sources (unlike `.bin` / `.pt` files)
- Fixed header format prevents zip bombs and path traversal attacks

## Gotchas / Anti-patterns
- Using `torch.load()` on untrusted `.bin` files — arbitrary code execution risk; prefer safetensors
- Not setting `use_safetensors=True` when security matters — HF falls back to pickle silently
- Storing non-tensor data in safetensors — use JSON sidecar files for configs, tokenizers, etc.
- Loading entire sharded model when you need one layer — use `safe_open` for selective loading
- Converting without verifying — always compare `state_dict` keys before and after conversion

## References
- SafeTensors GitHub: https://github.com/huggingface/safetensors
- SafeTensors docs: https://huggingface.co/docs/safetensors
- Format specification: https://github.com/huggingface/safetensors/blob/main/FORMAT.md

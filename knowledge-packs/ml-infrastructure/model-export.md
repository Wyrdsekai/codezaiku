# Model Export

## When to use
- Deploying trained models to production inference environments
- Moving models between frameworks (e.g., PyTorch to Java/C++ runtime)
- Optimizing models for specific hardware targets
- Standardizing model storage and distribution

## Pattern

### Format Comparison

**SafeTensors**:
- Secure, fast, memory-mapped tensor storage
- No arbitrary code execution (unlike pickle-based formats)
- De facto standard for Hugging Face model distribution
- Framework-agnostic: loadable by PyTorch, JAX, TensorFlow, and Rust
- Use as default storage format for weights

**ONNX (Open Neural Network Exchange)**:
- Computation graph format with operator definitions
- Enables cross-runtime deployment (ONNX Runtime, TensorRT, OpenVINO)
- ONNX Runtime available for Java, C++, C#, Python, JavaScript
- Supports graph optimizations and hardware-specific compilation
- Use when deploying to non-Python runtimes or specialized hardware

**TorchScript (torch.jit)**:
- PyTorch-native serialization of model + computation graph
- Two modes: tracing (records operations on example input) and scripting (parses Python)
- Deployable via libtorch (C++) without Python dependency
- Being superseded by torch.compile/torch.export in newer PyTorch versions
- Use when staying within the PyTorch ecosystem but removing Python dependency

**GGUF**:
- Quantized model format for llama.cpp ecosystem
- Self-contained (model + tokenizer + metadata in one file)
- Optimized for CPU and hybrid CPU/GPU inference
- Use for local deployment, edge devices, or Ollama-based serving

### Export Decision Flow

1. **Staying in Python for inference?** Keep as SafeTensors, no export needed
2. **Need non-Python runtime (Java, C++, browser)?** Export to ONNX
3. **Deploying to CPU or consumer hardware?** Convert to GGUF
4. **Need PyTorch C++ inference?** Use TorchScript or torch.export
5. **Distributing model weights?** SafeTensors as interchange format

### ONNX Export Guidance

- Use opset version >= 17 for transformer model support
- Specify dynamic axes for variable batch size and sequence length
- Validate exported model against original with matching inputs (rtol=1e-3, atol=1e-4)
- Run ONNX graph optimization passes (constant folding, operator fusion)
- Test inference latency with ONNX Runtime before and after optimization

### Post-Export Optimization

- ONNX: apply ORT graph optimizations, optionally convert to TensorRT engine
- GGUF: select quantization level during conversion (Q4_K_M is a common balanced choice)
- TorchScript: use torch.jit.optimize_for_inference() to fold batch norms and fuse ops
- All formats: benchmark end-to-end latency and validate output quality after conversion

## Gotchas / Anti-patterns
- Exporting to ONNX without specifying dynamic axes (produces fixed-shape model)
- Using pickle-based formats (.pt, .bin) in production (arbitrary code execution risk)
- Not validating numerical equivalence between original and exported model
- Exporting models with custom Python operators that have no ONNX equivalent
- Assuming TorchScript tracing captures all control flow (it only records the traced path)
- Converting to GGUF without checking that your model architecture is supported by llama.cpp
- Exporting without the tokenizer or chat template metadata (model alone is not deployable)

## References
- SafeTensors: https://huggingface.co/docs/safetensors/
- ONNX specification: https://onnx.ai/
- ONNX Runtime: https://onnxruntime.ai/
- PyTorch export guide: https://pytorch.org/docs/stable/export.html
- llama.cpp GGUF conversion: https://github.com/ggerganov/llama.cpp/tree/master/convert

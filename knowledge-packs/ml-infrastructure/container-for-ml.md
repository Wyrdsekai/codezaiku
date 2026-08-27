# Containers for ML

## When to use
- Creating reproducible training and inference environments
- Deploying ML models in production with consistent dependencies
- Sharing development environments across a team
- Running training jobs on cluster schedulers (Kubernetes, SLURM)

## Pattern

### Base Image Selection

**NVIDIA CUDA images** (`nvidia/cuda`):
- Minimal: only CUDA runtime and libraries
- Three variants: base (runtime only), runtime (+ cuDNN), devel (+ headers and compilers)
- Use `runtime` for inference, `devel` for training (needs nvcc for some packages)
- Pin the CUDA version to match your framework requirements

**NVIDIA PyTorch/TensorFlow NGC images** (`nvcr.io/nvidia/pytorch`):
- Pre-built with framework, CUDA, cuDNN, NCCL, and common libraries
- Large (10-15 GB) but avoids dependency resolution headaches
- Good starting point for training containers
- Pin to a specific monthly tag, not `latest`

**Community images** (e.g., Hugging Face, vLLM, SGLang):
- Purpose-built for specific inference or training workflows
- Smaller scope, fewer unnecessary dependencies
- Check that they are actively maintained and pin versions

### Dockerfile Structure

```dockerfile
# 1. Start from a CUDA-enabled base
FROM nvidia/cuda:12.6.0-runtime-ubuntu22.04

# 2. Install system dependencies (minimal)
RUN apt-get update && apt-get install -y --no-install-recommends \
    python3 python3-pip git && \
    rm -rf /var/lib/apt/lists/*

# 3. Install Python dependencies from pinned requirements
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# 4. Copy application code
COPY . /app
WORKDIR /app

# 5. Set runtime defaults
ENV NVIDIA_VISIBLE_DEVICES=all
ENV NVIDIA_DRIVER_CAPABILITIES=compute,utility
```

### Key Practices

**Dependency pinning**: Pin every dependency version in requirements.txt or a lock file. Pin the base image tag. Pin CUDA version. Floating versions break reproducibility.

**Layer caching**: Put slow-changing layers (system packages, pip install) before fast-changing layers (application code). This maximizes Docker build cache hits.

**Image size**: Use multi-stage builds to separate build-time dependencies from runtime. Remove package manager caches (`--no-cache-dir`, `rm -rf /var/lib/apt/lists/*`). Do not include training data in the image.

**GPU access at runtime**: Use `--gpus all` or `--gpus '"device=0,1"'` with `docker run`. Requires NVIDIA Container Toolkit installed on the host. The container CUDA version must be compatible with the host driver version.

### Reproducible Environments

- Store Dockerfile and requirements files in version control alongside training code
- Tag built images with the git commit hash
- Record the exact image digest (sha256) used for each training run
- Test that the container produces identical results given the same inputs and seeds

### Container Registries

- Use a private registry for proprietary models and code
- Tag images with both version and commit hash (e.g., `v1.2.3-abc1234`)
- Set retention policies to garbage-collect old images
- Scan images for vulnerabilities before deployment

### Multi-GPU: verify peer copies before trusting a multi-GPU run

Multi-GPU training can produce silently WRONG numbers — not a crash, not a warning — when the host's
IOMMU sits between the cards. With IOMMU in translated mode and PCIe ACS redirect enabled, peer-to-peer
copies between GPUs (especially across NUMA nodes) can be corrupted while every layer above reports
success. The symptoms look like a framework bug: loss that will not converge, gradients that differ
between ranks, output that changes run to run on fixed seeds. Time gets spent debugging the dispatch
logic of whatever framework is in use.

Check the host before blaming the stack:

```bash
cat /proc/cmdline | tr ' ' '\n' | grep -i iommu      # look for iommu=pt
nvidia-smi topo -m                                    # inter-GPU links and NUMA affinity
```

The usual fix is passthrough mode (`iommu=pt` on the kernel cmdline), which skips DMA translation for
devices that do not need it. Some boards additionally need ACS disabled on the relevant bridges.

Two practical rules:

- **A single-GPU pin is immune.** `--gpus '"device=1"'` does host↔device transfers only and never peer
  copies, so single-GPU serving and training are unaffected by this class of fault. It is a reasonable
  default until multi-GPU is verified.
- **Corruption at 100% is a hardware or platform smell.** A subtle bug in your own code rarely fails
  *every* time on *every* cross-device path. Totality is a hint to look beneath the framework.

## Gotchas / Anti-patterns
- Using `latest` tag for base images (breaks reproducibility silently)
- Installing CUDA inside the container when the base image already provides it (version conflicts)
- Copying training data into the image (bloats image, slows builds, data should be mounted)
- Not testing GPU access in the container before submitting long training jobs
- Trusting a multi-GPU run without verifying peer copies (IOMMU/ACS can corrupt them silently — see above)
- Building on one CUDA version and running on a host with an incompatible driver
- Running containers as root in production without security consideration
- Forgetting to set `NVIDIA_VISIBLE_DEVICES` and `NVIDIA_DRIVER_CAPABILITIES` environment variables
- Including development tools (vim, debuggers) in production images
- Not using .dockerignore (accidentally copying .git, data directories, checkpoints into image)

## References
- NVIDIA Container Toolkit: https://docs.nvidia.com/datacenter/cloud-native/container-toolkit/
- NVIDIA NGC catalog: https://catalog.ngc.nvidia.com/
- Docker multi-stage builds: https://docs.docker.com/build/building/multi-stage/
- CUDA compatibility matrix: https://docs.nvidia.com/deploy/cuda-compatibility/

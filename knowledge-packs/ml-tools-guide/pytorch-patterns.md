# Core PyTorch Patterns

## When to use
- Building custom neural network architectures
- Need fine-grained control over training loop
- Research or production models where framework flexibility matters
- Any deep learning task where higher-level abstractions are insufficient

## Pattern

### Module Definition
- Subclass `nn.Module`, define layers in `__init__`, wire them in `forward()`
- Use `nn.Sequential` for simple stacks, explicit `forward()` for branching logic
- Register buffers (non-parameter tensors) with `self.register_buffer()` so they move with `.to(device)`

### Device Management
- Detect device once: `device = torch.device("cuda" if torch.cuda.is_available() else "cpu")`
- Move model and data to device consistently; mismatched devices cause silent shape bugs
- For multi-GPU: prefer `torch.nn.parallel.DistributedDataParallel` over `DataParallel`
- Use `torch.cuda.amp.autocast()` context manager for mixed precision

### DataLoader
- Always set `num_workers > 0` for GPU training (2-4x CPU cores is a starting point)
- Use `pin_memory=True` when loading to CUDA
- Implement `__len__` and `__getitem__` on your `Dataset` subclass
- For large datasets, use `IterableDataset` with proper worker sharding
- Set `persistent_workers=True` to avoid re-fork overhead across epochs

### Training Loop
```
optimizer.zero_grad()          # or optimizer.zero_grad(set_to_none=True) for speed
loss = criterion(model(x), y)
loss.backward()
torch.nn.utils.clip_grad_norm_(model.parameters(), max_norm=1.0)
optimizer.step()
scheduler.step()               # after optimizer.step()
```

### Checkpointing
- Save: `torch.save({"model": model.state_dict(), "optimizer": opt.state_dict(), "epoch": e}, path)`
- Load: load dict, then `model.load_state_dict(ckpt["model"])`
- Always save `state_dict()`, never the raw model object (avoids pickle fragility)

### Inference
- Wrap in `torch.no_grad()` or `torch.inference_mode()` (latter is faster, stricter)
- Call `model.eval()` before inference (disables dropout, batchnorm running stats)
- Call `model.train()` when resuming training

## Gotchas / Anti-patterns
- Forgetting `model.eval()` before validation — batchnorm and dropout behave differently
- Calling `scheduler.step()` before `optimizer.step()` — causes LR warnings and wrong rates
- Creating tensors inside the loop without `.detach()` — causes memory leaks via retained graph
- Using `DataParallel` instead of `DistributedDataParallel` — DP has GIL bottleneck, always prefer DDP
- Not calling `zero_grad()` — gradients accumulate by default (sometimes intentional, usually a bug)
- Moving data to GPU one sample at a time — batch the transfer via DataLoader + pin_memory

## References
- PyTorch tutorials: https://pytorch.org/tutorials/
- DDP tutorial: https://pytorch.org/tutorials/intermediate/ddp_tutorial.html
- Performance tuning guide: https://pytorch.org/tutorials/recipes/recipes/tuning_guide.html

# Jupyter Notebook Patterns for ML

## When to use
- Exploratory data analysis and feature investigation
- Prototyping model architectures before committing to scripts
- Creating reproducible experiment documentation with inline results
- Interactive debugging of training runs, data pipelines, or model outputs
- Sharing findings with collaborators who need to see code + results together

## Pattern

### Project Structure
```
project/
  notebooks/
    01_data_exploration.ipynb
    02_feature_engineering.ipynb
    03_model_training.ipynb
    04_evaluation.ipynb
  src/
    data.py          # Refactored data loading
    model.py         # Refactored model code
    utils.py         # Shared utilities
  configs/
  data/
```
- Notebooks for exploration and documentation; `.py` modules for reusable code
- Number notebooks to indicate execution order
- Import from `src/` as code matures: `from src.model import build_model`

### Cell Organization
- First cell: imports and configuration (seeds, device, paths)
- Second cell: data loading and inspection
- Keep cells short (10-20 lines) — one logical step per cell
- Use markdown cells liberally to document intent, not just code

### Reproducibility
- Pin random seeds: `torch.manual_seed(42)`, `np.random.seed(42)`
- Record environment: `!pip freeze > requirements.txt` or use `%watermark`
- Use `%load_ext autoreload` + `%autoreload 2` during development to reload changed modules
- Clear all outputs before committing to version control

### Memory Management
- `del variable; gc.collect(); torch.cuda.empty_cache()` between heavy cells
- Use `%%time` or `%%timeit` to profile cells
- Avoid storing large intermediate DataFrames — process in streaming fashion or sample

### Visualization
- Set figure defaults once: `plt.rcParams.update({...})`
- Use `%matplotlib inline` for static plots, `%matplotlib widget` for interactive
- For training curves: log to a list, plot in a separate cell — keeps training cell clean

### Version Control
- Use `nbstripout` as a git filter to auto-strip outputs on commit
- Alternative: `jupytext` to pair `.ipynb` with `.py` percent-format scripts
- Never commit notebooks with large binary outputs (images, model weights)

### Moving to Production
- Refactor proven notebook code into `.py` modules
- Keep the notebook as a runnable integration test / demo
- Use `papermill` for parameterized notebook execution in CI/CD

## Gotchas / Anti-patterns
- Running cells out of order — use "Restart and Run All" before sharing or committing
- Monolithic notebooks (500+ lines) — split into numbered stages
- Hardcoded paths — use `pathlib.Path` and config cells at the top
- Storing secrets in notebooks — use environment variables or dotenv
- Giant DataFrames displayed inline — use `.head()`, `.sample()`, `.describe()`
- Not clearing GPU memory between experiments — leads to phantom OOM errors

## References
- Jupyter docs: https://docs.jupyter.org/
- nbstripout: https://github.com/kynan/nbstripout
- Jupytext: https://github.com/mwouts/jupytext
- Papermill: https://github.com/nteract/papermill

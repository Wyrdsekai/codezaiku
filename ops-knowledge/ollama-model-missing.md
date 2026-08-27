match: ollama, llm, embedding, embeddings
signature: model not found, try pulling it first, no such model, model requires more system memory, not found, try pulling
push: rescue
status: candidate
# Ollama model not loaded — embeddings/generation failing — fix procedure
1. Requests to Ollama fail with "model '<name>' not found, try pulling it first" — the required model is not
   present on the server (removed, or never pulled). Listing tags still works; only inference fails, so a
   shallow health check can miss it — the ERROR names the exact model.
2. Confirm: `ollama list` — the expected model is absent.
3. Pull it: `ollama pull <name>` using the exact model name from the error; wait for it to finish.
4. Confirm an embeddings/generate request succeeds, then conclude.

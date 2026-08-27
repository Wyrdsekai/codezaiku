package org.codezaiku.library;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Framework-knowledge worked-examples, retrieved by language + framework and PUSHED into the
 * prompt (push, never pull — the 9B won't reliably call a retrieve tool). Entries are LANGUAGE/
 * FRAMEWORK GATED so a wrong-stack example never reaches a task (the TS-into-JS lesson, RESET §2).
 *
 * <p>Content lives as data under {@code resources/library/}. This is the compartment to invest in
 * (RESET §3.5 / L6): a failing fixture is a gap here, not a loop patch. Slice: one compartment
 * (java/spring-boot REST). Lucene/embeddings/DevDocs packs are the later buildout.
 */
public final class Library {

    private record Entry(String manifestMarker, String resource) {
    }

    // framework detected by a marker in the project manifest(s) -> the worked-example to push.
    // Multiple markers may map to the same example (first hit wins per resource; dedup below).
    private final List<Entry> entries = List.of(
            new Entry("org.springframework.boot", "/library/java-spring-boot-rest.md"),
            new Entry("express", "/library/node-express-rest.md"),
            new Entry("fastapi", "/library/python-fastapi-rest.md"),
            new Entry("project.godot", "/library/godot-game.md"),
            new Entry("ratatui", "/library/rust-tui-app.md"),
            new Entry("sysinfo", "/library/rust-tui-app.md"),
            new Entry("crossterm", "/library/rust-tui-app.md"),
            // FILE-PRESENCE markers (like project.godot): a from-scratch project's manifest names no
            // framework yet (bare package.json / empty requirements.txt), which left js and py runs with
            // NO worked example at all (battery22: that's exactly where assertion-theater tests lived).
            // The language-level example still applies before any framework is chosen; dedup keeps a
            // framework project from receiving it twice.
            new Entry("package.json", "/library/node-express-rest.md"),
            new Entry("requirements.txt", "/library/python-fastapi-rest.md"),
            new Entry("pyproject.toml", "/library/python-fastapi-rest.md"));

    // CONCERN-keyed idioms: the push must surface ALGORITHM knowledge, not just framework WIRING. The
    // framework examples make an app BOOT; without the algorithm idiom the model writes the pipeline from
    // faulty memory and it goes hollow (battery34: py keyed threads on message-id → no grouping; faked
    // TF-IDF → a constant classifier). These are LANGUAGE-AGNOSTIC (the algorithm is the same in py/js/java)
    // and matched on what the GOAL asks for, so the relevant idiom is PUSHED for any stack — nothing
    // important is left pull-only in the index where a non-erroring hollow impl never retrieves it.
    // triggers = body keywords for the PUSH (does the goal involve this concern at all → push the idiom).
    // topicWords = HEADER-level concern words for COVERAGE (is a spec SECTION about this concern). Coverage
    // must anchor on the header, not incidental body mentions — else a section that merely names a keyword
    // ("Email Ingestion" describes In-Reply-To headers) gets falsely reported grounded and HIDES the real
    // gap (battery36: ingestion mis-grounded to threading, masking the un-grounded mbox-parsing concern).
    private record Concern(List<String> triggers, List<String> topicWords, String resource) { }

    private final List<Concern> concerns = List.of(
            new Concern(List.of("in-reply-to", "conversation thread", "thread the messages"),
                    List.of("thread", "threading", "conversation"), "/library/idiom-email-threading.md"),
            new Concern(List.of("tf-idf", "tfidf", "term frequency"),
                    List.of("classif", "categor", "tf-idf", "tagging"), "/library/idiom-tfidf-classifier.md"),
            new Concern(List.of("bill", "invoice", "receipt", "payment"),
                    List.of("bill", "invoice", "payment", "expense"), "/library/idiom-bill-extraction.md"),
            new Concern(List.of("time-series", "predictive analytics", "forecast", "predict who"),
                    List.of("predict", "analytic", "forecast", "time-series", "trend"), "/library/idiom-timeseries-prediction.md"),
            // WIRING: expose each pipeline concern as an endpoint + render the dashboard from real data.
            // The framework examples show generic CRUD; this grounds the multi-concern exposure the model
            // skips (battery34: pipelines computed values but 404'd their own routes / 500'd the dashboard).
            new Concern(List.of("endpoints exposing", "dashboard showing", "dashboard page rendering",
                    "prediction panel", "recent messages per category"),
                    List.of("web", "api", "dashboard", "interface", "endpoint", "http", "rest", "frontend"),
                    "/library/idiom-api-wiring.md"),
            // ML modeling + serving idioms (2026-06-26, from the ML-fixture findings): the model writes real
            // ML code but (a) claims imbalance handling in a comment without wiring it, and (b) thrashes
            // verifying heavy-model services (re-boots → model reload → timeout → spin). General ML grounding.
            new Concern(List.of("imbalance", "imbalanced", "fraud", "class imbalance", "scale_pos_weight",
                    "class_weight", "churn", "rare class", "anomaly detection"),
                    List.of("fraud", "imbalance", "imbalanced", "churn", "anomaly", "minority", "rare"),
                    "/library/idiom-imbalanced-classification.md"),
            new Concern(List.of("semantic search", "vector search", "embedding", "embeddings",
                    "nearest neighbor", "vector index", "vector database", "retrieval-augmented"),
                    List.of("embedding", "semantic", "vector", "retrieval", "rag", "similarity", "faiss"),
                    "/library/idiom-embedding-search.md"),
            // Split sharp companions to embedding-search (one technique each). The fat multi-step card
            // backfired: the 9B picked up the intent but botched persist (json.dump of float32 → empty index)
            // and thrashed on verify (slow first-boot). Co-keyed to the same vector concern → all three push.
            new Concern(List.of("semantic search", "vector search", "embedding", "embeddings",
                    "vector index", "build the index", "load at startup"),
                    List.of("embedding", "semantic", "vector", "index", "faiss", "persist"),
                    "/library/idiom-persist-model-artifact.md"),
            new Concern(List.of("semantic search", "embedding", "embeddings", "vector search",
                    "model server", "inference service", "serve the model", "loads a model"),
                    List.of("embedding", "semantic", "vector", "serve", "inference", "model"),
                    "/library/idiom-verify-heavy-service.md"),
            // Sharp last-mile-wiring cards (2026-06-27, from docs-rag 30B 2/2 RED + search 30B n1, all the
            // same class). load-index-at-startup fixes "built the index but never loaded it on startup → the
            // bare uvicorn entry 500s" (search-n1, rag-n1). rag-context fixes "passed ids/placeholder text to
            // the LLM, not real doc text → hollow answers" (rag-n2).
            new Concern(List.of("semantic search", "vector search", "embedding", "embeddings", "vector index",
                    "retrieval-augmented", "rag", "model server", "inference service", "index at startup"),
                    List.of("embedding", "semantic", "vector", "index", "rag", "retrieval", "serve"),
                    "/library/idiom-load-index-at-startup.md"),
            new Concern(List.of("rag", "retrieval-augmented", "/chat", "grounded", "citation", "cite",
                    "question answering", "question-answering"),
                    List.of("rag", "retrieval", "chat", "grounded", "citation", "answer"),
                    "/library/idiom-rag-context.md"),
            // Embedding fine-tune idiom (2026-07-08, item I2): CONTRASTIVE training of a base encoder into a
            // retrieval embedder (in-batch-negatives / InfoNCE, masked mean-pool). Distinct from the causal
            // LoRA-SFT card and from the retrieval-SERVING cards above — this is how to TRAIN the embedding
            // model. Keyed on embedding-training language so it fires for "fine-tune an embedding" goals, not
            // for generic "embedding" retrieval-serving goals.
            new Concern(List.of("contrastive learning", "in-batch negatives", "retrieval embedding model",
                    "text-embedding model", "fine-tune an embedding", "sentence embedding", "MultipleNegativesRanking"),
                    List.of("contrastive", "in-batch", "retrieval embedding", "text-embedding", "embedding model"),
                    "/library/idiom-embedding-finetune.md"),
            // Fine-tuning / PEFT idiom (2026-06-27), mined from a proven SFT run-substrate recipe:
            // conservative small-model LoRA config + the 3 guards (learned / regressed / collapsed) + merge-serve.
            new Concern(List.of("fine-tune", "fine tune", "finetune", "lora", "qlora", "peft", "sft",
                    "supervised fine-tuning", "instruction-tune", "adapter"),
                    List.of("fine-tune", "finetune", "lora", "peft", "sft", "train", "adapter"),
                    "/library/idiom-lora-finetune.md"),
            // Multi-adapter LoRA serving idiom (2026-07-08, item I7): the peft multi-adapter LOAD trap — the 9B
            // reliably calls PeftModel.from_pretrained twice (2nd adapter silently not registered → routing falls
            // back to base). The card pins from_pretrained(#1) + load_adapter(#2+) + set_adapter-per-request.
            new Concern(List.of("multiple LoRA adapters", "multi-adapter", "load_adapter", "set_adapter",
                    "route each request to the named adapter", "serving pattern with multiple adapters"),
                    List.of("multi-adapter", "load_adapter", "set_adapter", "multiple lora", "route", "named adapter"),
                    "/library/idiom-multi-adapter-serving.md"),
            // CLIP / multimodal vision fine-tune idiom (2026-07-08, item #10): the 9B pattern-matches "fine-tune"
            // to trl SFTTrainer (wrong for CLIP → import fails, run wasted). The card pins CLIPModel+CLIPProcessor
            // + return_loss contrastive train + logits_per_image classify (and the get_*_features object trap).
            new Concern(List.of("CLIP", "vision-language", "classify images", "image classification", "vision fine-tune",
                    "clip-vit", "multimodal", "logits_per_image", "image-text"),
                    List.of("clip", "vision", "image", "multimodal", "classify images", "logits_per_image"),
                    "/library/idiom-clip-vision-finetune.md"),
            // Collaborative-filtering recommender idiom (2026-07-09, A3 gap-close): the card FILE existed but was
            // orphaned (no trigger → KnowledgeProvisioner flagged the recommendation goal UNGROUNDED, no push).
            // Unguided, both 9B AND 30B built rating-WEIGHTED item-item cosine (values='rating', score+=sim*rating)
            // with no diagonal-zero → recommendations collapse to a tiny popular-item set (Recall@10 ~0.007, a
            // 66-item universe for everyone). The card pins the BINARY matrix + zero-diagonal + seen-mask fix.
            new Concern(List.of("collaborative filtering", "collaborative-filtering", "recommender", "recommendation",
                    "item-item", "user-item interactions", "top-10 recommend", "recommend the top", "personalized recommendations"),
                    List.of("recommend", "collaborative", "cosine", "item-item", "personalized", "ranking", "interactions"),
                    "/library/idiom-collaborative-filtering.md"),
            // ORPO alignment idiom (2026-07-09, A3 gap-close): the card FILE existed but was orphaned (no
            // trigger) AND its target_modules were wrong (attention-only). ORPO's odds-ratio signal is weak on
            // a small base — q,v-only underfits to ~0.27 pref-acc (BELOW 0.5, prefers rejected), q,k,v,o stalls
            // at ~0.53; ALL-linear (incl. gate/up/down MLP) reaches ~0.98 with identical beta/lr/epochs. Both
            // 9B and 30B failed here on the missing MLP targets. Card now pins all-linear + the sub-0.5 tell.
            new Concern(List.of("orpo", "odds-ratio preference", "odds ratio preference", "reference-free preference",
                    "ORPOTrainer", "monolithic preference optimization", "align a small LLM with orpo"),
                    List.of("orpo", "odds-ratio", "preference", "alignment", "reference-free", "peft", "adapter"),
                    "/library/idiom-orpo-finetune.md"),
            // ML technique cards (2026-07-09 wiring fix): these "ONE complete reference" cards existed but were
            // ALL orphaned (no trigger → never pushed). The generic lora-finetune Concern covered plain SFT, so
            // the alignment/export techniques still certified — but the technique-SPECIFIC guidance (e.g. ORPO
            // all-linear targets, distillation's teacher-label loop) never reached the model, which is exactly
            // how I5 distillation turn-starved (built the pipeline blind). Register each on its technique name.
            new Concern(List.of("knowledge distillation", "distill a large teacher", "teacher-labeled", "teacher labels",
                    "teacher to label", "large teacher model", "distill the teacher"),
                    List.of("distill", "distillation", "teacher", "student"),
                    "/library/idiom-distillation.md"),
            new Concern(List.of("dpo", "direct preference optimization", "dpotrainer"),
                    List.of("dpo", "preference", "alignment"), "/library/idiom-dpo-finetune.md"),
            new Concern(List.of("kto", "kahneman-tversky", "ktotrainer", "unpaired preference", "unpaired feedback"),
                    List.of("kto", "preference", "unpaired"), "/library/idiom-kto-finetune.md"),
            new Concern(List.of("grpo", "grpotrainer", "group relative policy", "programmatic reward"),
                    List.of("grpo", "reward", "policy"), "/library/idiom-grpo-finetune.md"),
            new Concern(List.of("ppo", "ppotrainer", "rlhf", "proximal policy"),
                    List.of("ppo", "rlhf", "reward"), "/library/idiom-ppo-rlhf.md"),
            new Concern(List.of("qlora", "4-bit", "nf4", "bitsandbytes", "quantized lora"),
                    List.of("qlora", "4-bit", "quantized"), "/library/idiom-qlora-finetune.md"),
            new Concern(List.of("reward model", "reward modeling", "rewardtrainer", "train a reward"),
                    List.of("reward", "preference", "ranking"), "/library/idiom-reward-model.md"),
            new Concern(List.of("merge the lora", "merge adapter", "export to gguf", "convert to gguf", "gguf export"),
                    List.of("merge", "gguf", "export"), "/library/idiom-merge-export-gguf.md"),
            new Concern(List.of("batch inference", "batch predict", "batch prediction through", "concurrent requests to a gguf"),
                    List.of("batch", "inference", "gguf"), "/library/idiom-batch-predict-gguf.md"),
            new Concern(List.of("quantization matrix", "llama-quantize", "quantize the model", "quantization sweep"),
                    List.of("quantize", "quantization", "gguf"), "/library/idiom-quant-matrix-gguf.md"),
            new Concern(List.of("fsdp", "torchrun", "distributed fine-tune", "multi-gpu training", "fully sharded"),
                    List.of("fsdp", "distributed", "multi-gpu"), "/library/idiom-distributed-fsdp.md"));

    /** Worked-example block(s) for this project (framework, manifest-keyed). Prefer {@link #select(Path,String)}
     *  when a goal is available, so algorithm idioms are pushed too. */
    public String select(Path projectRoot) {
        return select(projectRoot, "");
    }

    /** Framework worked-examples (manifest-keyed) PLUS algorithm idioms (goal/concern-keyed), or "" if none. */
    public String select(Path projectRoot, String goal) {
        StringBuilder sb = new StringBuilder();
        Set<String> pushed = new HashSet<>(); // several markers can map to one resource
        // framework worked-example(s) — what STACK to build (manifest marker → example)
        String manifest = readManifest(projectRoot);
        for (Entry e : entries) {
            if (manifest.contains(e.manifestMarker()) && pushed.add(e.resource())) {
                String body = load(e.resource());
                if (!body.isBlank()) sb.append(body).append('\n');
            }
        }
        // algorithm idiom(s) — what ALGORITHM the goal asks for (concern trigger in goal text → idiom)
        String g = goal == null ? "" : goal.toLowerCase();
        if (!g.isBlank()) {
            for (Concern c : concerns) {
                if (pushed.contains(c.resource())) continue;
                boolean hit = false;
                for (String t : c.triggers()) {
                    if (g.contains(t)) { hit = true; break; }
                }
                if (hit && pushed.add(c.resource())) {
                    String body = load(c.resource());
                    if (!body.isBlank()) sb.append(body).append('\n');
                }
            }
            // ACQUIRED idioms (stage-3 provisioner cache) — concern-keyed by their `triggers:` header. Same
            // push surface as the bundled idioms, so auto-acquired grounding reaches the model identically.
            for (Path f : cachedIdioms()) {
                String doc = readFile(f);
                if (doc.isBlank() || pushed.contains(f.toString())) continue;
                boolean hit = false;
                for (String t : cacheTriggers(doc)) {
                    if (!t.isBlank() && g.contains(t)) { hit = true; break; }
                }
                if (hit && pushed.add(f.toString())) sb.append(stripCacheHeader(doc)).append('\n');
            }
        }
        return sb.toString();
    }

    private List<Path> cachedIdioms() {
        Path dir = Acquirer.cacheDir();
        if (!Files.isDirectory(dir)) return List.of();
        try (var s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".md")).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** Parse the `<!-- acquired; triggers: a, b, c -->` header → lowercased trigger list. */
    private static List<String> cacheTriggers(String doc) {
        Matcher m = Pattern.compile("triggers:\\s*([^>]+?)\\s*-->").matcher(doc);
        if (!m.find()) return List.of();
        List<String> out = new ArrayList<>();
        for (String t : m.group(1).split(",")) { String s = t.strip().toLowerCase(); if (!s.isBlank()) out.add(s); }
        return out;
    }

    private static String stripCacheHeader(String doc) {
        int nl = doc.indexOf('\n');
        return (doc.startsWith("<!--") && nl >= 0) ? doc.substring(nl + 1) : doc;
    }

    private static String readFile(Path p) {
        try { return Files.readString(p); } catch (Exception e) { return ""; }
    }

    /** Concern-idiom resource basenames whose TOPIC matches a spec section HEADER — for coverage reporting.
     *  Pass the section HEADER (not the body): topicWords are header-level so a section is grounded only when
     *  it is ABOUT the concern, not when its body incidentally mentions a body-trigger keyword. */
    public List<String> idiomsForHeader(String header) {
        String h = header == null ? "" : header.toLowerCase();
        List<String> hits = new ArrayList<>();
        for (Concern c : concerns) {
            for (String t : c.topicWords()) {
                if (h.contains(t)) { hits.add(c.resource().replaceAll(".*/", "")); break; }
            }
        }
        return hits;
    }

    /** True if a framework worked-example applies to this project (manifest-keyed) — for coverage reporting. */
    public boolean hasFrameworkExample(Path root) {
        String manifest = readManifest(root);
        for (Entry e : entries) if (manifest.contains(e.manifestMarker())) return true;
        return false;
    }

    private String readManifest(Path root) {
        StringBuilder all = new StringBuilder();
        for (String name : List.of("build.gradle", "build.gradle.kts", "pom.xml", "package.json", "requirements.txt", "Cargo.toml")) {
            Path p = root.resolve(name);
            if (Files.isRegularFile(p)) {
                try {
                    all.append(Files.readString(p)).append('\n');
                } catch (IOException ignored) {
                    // skip unreadable manifest
                }
            }
        }
        // Godot projects have no dependency manifest — the project file itself is the marker.
        if (Files.isRegularFile(root.resolve("project.godot"))) all.append("project.godot\n");
        // File-presence markers: the manifest's NAME counts even when its content names no framework yet.
        for (String name : List.of("package.json", "requirements.txt", "pyproject.toml")) {
            if (Files.isRegularFile(root.resolve(name))) all.append(name).append('\n');
        }
        return all.toString();
    }

    /** Load a single idiom note by resource path (for write-marker pushes), or "" if absent. */
    public String idiom(String resource) {
        return load(resource);
    }

    private String load(String resource) {
        try (var in = Library.class.getResourceAsStream(resource)) {
            return in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }
}

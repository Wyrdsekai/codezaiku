package org.codezaiku.loop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.codezaiku.Config;

/**
 * Per-project working memory — the FIRST harness-observed advisory slice of
 * SPEC_CODEZAIKU_PROJECT_MEMORY (Cut 2): an EXTERNAL, harness-owned store
 * ({@code ~/.codezaiku/familiar-memory/<instance-key>/}) so it survives the lossy in-conversation
 * compaction the model otherwise leans on. This slice tracks BUILD ERRORS derived from each build
 * observation and, crucially, how many builds in a row each one has PERSISTED — so the harness can
 * pin "you have hit this N times, apply the compiler's suggested fix exactly" instead of letting the
 * model re-litigate the same error across every compaction (the E0106 "saw the fix, didn't apply it"
 * wall). DERIVED + self-healing: the current error set is whatever the latest build shows; a fixed
 * error simply stops appearing and its card drops.
 *
 * <p>Scope of THIS slice: cargo/rustc-shaped build output (the immediate case). Other toolchains'
 * error grammars are a later parser; the store + persistence mechanism are general.
 */
public final class FamiliarMemory {
    private static final ObjectMapper J = new ObjectMapper();

    private static final int MAX_API_SIGS = 24;

    private final Path file; // external, harness-owned
    private Map<String, Card> errors = new LinkedHashMap<>(); // signature -> card (insertion order)
    // API signatures the model looked up via read_dep_source — RETAINED here so they survive both
    // elision and compaction. This is the point of project memory: knowledge lives in the store and is
    // re-pinned, so the model never re-fakes an API it already looked up. Keyed by the signature text
    // (dedup); recency-ordered; bounded so the pinned block stays small. value = "dep: signature".
    private Map<String, String> apiSigs = new LinkedHashMap<>();
    // WORKING LOCATION anchor: the directory prefix (relative to the project root) where the model's
    // modified files actually live — derived MECHANICALLY by the loop, persisted here, and re-pinned
    // every turn. This is the anti-amnesia anchor: after compaction a small model can lose the thread
    // of where it was working and restart elsewhere (measured: 18 compactions → it migrated a whole
    // app from the root to output/<project>/, duplicating everything). Never left to the summarizer.
    private String workingRoot = null; // null = not yet established; "" = the project root itself

    public FamiliarMemory(Path projectRoot) {
        this.file = storeDir(projectRoot).resolve("build-errors.json");
        load();
    }

    /** One unresolved build error and how persistently it has resisted being fixed. */
    private static final class Card {
        String code = "";        // e.g. E0106
        String location = "";    // file:line:col
        String message = "";     // the human error text
        String fix = "";         // rustc's own suggested fix line (the diffed source), if any
        int builds = 1;          // consecutive builds this exact error has survived
    }

    /**
     * Observe a tool result. If it is build/compiler output, recompute the current error set
     * (derived), carrying forward persistence counts for errors that survived, and persist.
     */
    public void observeBuild(String observation) {
        if (observation == null || !looksLikeBuild(observation)) return;
        Map<String, Card> found = parse(observation);
        // Clean build (compiler output, no errors) → clear everything (self-healing).
        Map<String, Card> next = new LinkedHashMap<>();
        for (var e : found.entrySet()) {
            Card prev = errors.get(e.getKey());
            Card cur = e.getValue();
            if (prev != null) cur.builds = prev.builds + 1; // survived another build
            next.put(e.getKey(), cur);
        }
        errors = next;
        save();
    }

    /**
     * Capture the signatures returned by a read_dep_source lookup so they persist (the conversation
     * copy will be elided/compacted). Parses "path:line:  &lt;decl&gt;" hit lines; dedups; bounded.
     */
    public void observeLookup(String dependency, String result) {
        if (result == null || result.isBlank()) return;
        String dep = dependency == null ? "" : dependency.trim();
        Matcher m = SIG_LINE.matcher(result);
        boolean changed = false;
        while (m.find()) {
            String decl = m.group(1).strip();
            if (decl.endsWith("{")) decl = decl.substring(0, decl.length() - 1).strip();
            if (!isSig(decl)) continue;
            apiSigs.remove(decl);              // move-to-end = most-recent
            apiSigs.put(decl, dep.isBlank() ? decl : dep + ": " + decl);
            changed = true;
            while (apiSigs.size() > MAX_API_SIGS) {
                var it = apiSigs.keySet().iterator();
                it.next();
                it.remove();                  // drop the oldest
            }
        }
        if (changed) save();
    }

    /** Record where the work lives (longest common dir prefix of modified files). Persist on change. */
    public void observeWorkingRoot(String prefix) {
        String p = prefix == null ? "" : prefix.strip();
        if (p.equals(workingRoot)) return;
        workingRoot = p;
        save();
    }

    private String treeSplit = null; // non-null when modified files show two parallel copies of one path

    /** Record (and persist — a continuation pass must inherit it) a detected parallel-tree split. */
    public void observeTreeSplit(String note) {
        if (note == null || note.isBlank() || note.equals(treeSplit)) return;
        treeSplit = note;
        save();
    }

    private static final Pattern SIG_LINE =
            Pattern.compile("(?m):\\d+:\\s+(.+)$");

    // Retain ONLY callable signatures the model would actually invoke — NOT impl blocks, trait impls,
    // type aliases, struct/enum decls, or re-exports. Those are noise that evicted the real getters
    // (run 7: the store filled with "impl HasSendAndSync for Networks" and dropped host_name/used_memory).
    private static boolean isSig(String d) {
        boolean callable = d.startsWith("pub fn ") || d.startsWith("fn ") || d.startsWith("pub(crate) fn ")
                || d.startsWith("def ") || d.startsWith("async def ") || d.startsWith("func ")
                || d.startsWith("export function ") || d.startsWith("function ");
        if (!callable) return false;
        // Keep only the public-callable getters/constructors; skip trait-internal noise.
        return d.contains("(");
    }

    /** The pinned KNOWN-ISSUES + KNOWN-API block for the system message (empty when nothing to say). */
    public String pinned() {
        StringBuilder sb = new StringBuilder();
        if (workingRoot != null) {
            sb.append("\n\nWORKING LOCATION: your project files live ")
              .append(workingRoot.isEmpty() ? "at the PROJECT ROOT (./)" : "under `" + workingRoot + "/`")
              .append(" — keep ALL new and edited files there. Do not start a second copy of the ")
              .append("project anywhere else.");
        }
        if (treeSplit != null) {
            sb.append("\n\n⚠ ").append(treeSplit);
        }
        if (!apiSigs.isEmpty()) {
            sb.append("\n\nKNOWN API SIGNATURES (you looked these up from the real installed source — use "
                    + "them EXACTLY; do NOT guess or invent method names):");
            for (String v : apiSigs.values()) sb.append("\n - ").append(v);
        }
        if (errors.isEmpty()) return sb.toString();
        sb.append("\n\nKNOWN BUILD ERRORS (from your latest build — fix these, and do not re-introduce them):");
        for (Card c : errors.values()) {
            sb.append("\n - ");
            if (!c.code.isBlank()) sb.append(c.code).append(' ');
            sb.append("at ").append(c.location.isBlank() ? "?" : c.location).append(": ").append(c.message);
            if (!c.fix.isBlank()) sb.append("  → compiler-suggested fix: ").append(c.fix);
            if (c.builds >= 2) {
                sb.append("  ⚠ you have hit this for ").append(c.builds).append(" builds in a row — STOP "
                        + "trying variations and apply the compiler's suggested fix EXACTLY, then rebuild.");
            }
        }
        return sb.toString();
    }

    // ---- parsing (per-toolchain error grammars; the store/persistence mechanism is general) ----

    private static boolean looksLikeBuild(String o) {
        return o.contains("error[") || o.contains("could not compile") || o.contains("Finished `")
                || o.contains("test result:") || o.contains("error: ") || o.contains("Compiling ")
                || o.contains("BUILD FAILED") || o.contains("BUILD SUCCESSFUL")          // gradle
                || o.contains("FAILED ") || o.contains("passed") || o.contains("Traceback") // pytest
                || o.contains("error TS");                                                // tsc
    }

    // javac:  src/main/java/Foo.java:42: error: cannot find symbol
    private static final Pattern JAVAC =
            Pattern.compile("^(\\S+\\.java):(\\d+): error: (.+)$");
    // tsc:    src/app.ts(5,3): error TS2304: Cannot find name 'x'.
    private static final Pattern TSC =
            Pattern.compile("^(\\S+\\.tsx?)\\((\\d+),\\d+\\): error (TS\\d+): (.+)$");
    // pytest: FAILED tests/test_x.py::test_y - AssertionError: ...   |   ERROR tests/test_x.py - ImportError: ...
    private static final Pattern PYTEST =
            Pattern.compile("^(?:FAILED|ERROR) (\\S+?)(?: - (.+))?$");
    // rustc:  error[E0599]: no method named `disks` ...
    private static final Pattern ERR =
            Pattern.compile("^error(?:\\[(E\\d+)\\])?: (.+)$");

    /** Extract a card per distinct error across toolchain grammars (rustc/javac/tsc/pytest). */
    private static Map<String, Card> parse(String out) {
        Map<String, Card> cards = new LinkedHashMap<>();
        String[] lines = out.split("\n", -1);
        Card cur = null;            // rustc multi-line card being assembled
        boolean awaitFix = false;
        for (String raw : lines) {
            String t = raw.strip();

            Matcher m = JAVAC.matcher(t);
            if (m.find()) {
                commit(cards, "", m.group(1) + ":" + m.group(2), m.group(3).strip(), "");
                cur = null;
                continue;
            }
            m = TSC.matcher(t);
            if (m.find()) {
                commit(cards, m.group(3), m.group(1) + ":" + m.group(2), m.group(4).strip(), "");
                cur = null;
                continue;
            }
            m = PYTEST.matcher(t);
            if (m.find() && (t.startsWith("FAILED ") || t.startsWith("ERROR "))) {
                commit(cards, "", m.group(1), m.group(2) == null ? "test failed" : m.group(2).strip(), "");
                cur = null;
                continue;
            }

            m = ERR.matcher(t);
            if (m.find()) {
                cur = new Card();
                cur.code = m.group(1) == null ? "" : m.group(1);
                cur.message = m.group(2) == null ? t : m.group(2).strip();
                awaitFix = false;
                continue;
            }
            if (cur == null) continue;
            if (t.startsWith("-->") && cur.location.isBlank()) {
                cur.location = t.substring(3).strip();
                // commit on location (key = code+location); fix may still fill in below
                cards.putIfAbsent(cur.code + "@" + cur.location, cur);
                continue;
            }
            if (t.startsWith("help:")) {
                awaitFix = true;
                if (cur.fix.isBlank()) cur.fix = t.substring(5).strip();
                continue;
            }
            // the source line rustc prints WITH the inserted fix (contains the diffed code)
            if (awaitFix && t.matches("\\d+\\s*\\|.*\\S.*")) {
                String code = t.replaceFirst("^\\d+\\s*\\|", "").strip();
                if (!code.isEmpty()) {
                    cur.fix = code;
                    awaitFix = false;
                }
            }
        }
        return cards;
    }

    private static void commit(Map<String, Card> cards, String code, String location, String msg, String fix) {
        Card c = new Card();
        c.code = code;
        c.location = location;
        c.message = msg.length() > 200 ? msg.substring(0, 200) : msg;
        c.fix = fix;
        cards.putIfAbsent(code + "@" + location, c);
    }

    // ---- external store ----

    private void load() {
        try {
            if (!Files.isRegularFile(file)) return;
            JsonNode root = J.readTree(Files.readString(file));
            for (JsonNode n : root.path("errors")) {
                Card c = new Card();
                c.code = n.path("code").asText("");
                c.location = n.path("location").asText("");
                c.message = n.path("message").asText("");
                c.fix = n.path("fix").asText("");
                c.builds = n.path("builds").asInt(1);
                errors.put(c.code + "@" + c.location, c);
            }
            for (JsonNode n : root.path("apiSigs")) {
                apiSigs.put(n.path("sig").asText(""), n.path("text").asText(""));
            }
            if (root.has("workingRoot")) workingRoot = root.path("workingRoot").asText("");
            if (root.has("treeSplit")) treeSplit = root.path("treeSplit").asText("");
        } catch (Exception ignored) {
            // corrupt/absent → start clean; the next build re-derives ground truth
        }
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            ObjectNode root = J.createObjectNode();
            ArrayNode arr = root.putArray("errors");
            for (Card c : errors.values()) {
                ObjectNode n = arr.addObject();
                n.put("code", c.code);
                n.put("location", c.location);
                n.put("message", c.message);
                n.put("fix", c.fix);
                n.put("builds", c.builds);
            }
            ArrayNode sigs = root.putArray("apiSigs");
            for (var e : apiSigs.entrySet()) {
                ObjectNode n = sigs.addObject();
                n.put("sig", e.getKey());
                n.put("text", e.getValue());
            }
            if (workingRoot != null) root.put("workingRoot", workingRoot);
            if (treeSplit != null) root.put("treeSplit", treeSplit);
            Files.writeString(file, J.writerWithDefaultPrettyPrinter().writeValueAsString(root));
        } catch (Exception ignored) {
            // best effort; memory is an aid, never a hard dependency
        }
    }

    /** Stable per-project-instance external dir (SPEC §3): basename-sha256(absPath)[:12]. */
    private static Path storeDir(Path projectRoot) {
        Path abs = projectRoot.toAbsolutePath().normalize();
        String key = abs.getFileName() + "-" + sha12(abs.toString());
        String base = Config.get("CODEZAIKU_FAMILIAR_MEMORY_DIR", System.getProperty("user.home") + "/.codezaiku/familiar-memory");
        return Path.of(base, key);
    }

    private static String sha12(String s) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) sb.append(String.format("%02x", h[i]));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}

package org.codezaiku.chat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CodeZaiku's own undo: pre-images journaled per STEP, no git anywhere.
 *
 * <h2>The unit is the agent's step, not the person's message</h2>
 *
 * the operator's correction of the first design, which journaled one record per user turn: <i>"each turn
 * the agent does is a step, and we can undo — this allows for correction."</i> The point of pairing
 * a turn cap with undo is to let a run go free ({@code all} + cap) and then walk BACK through what
 * it did, action by action, to the exact point where it went wrong. One record per user message
 * makes that a single all-or-nothing rewind; one record per mutating action makes it surgery.
 * Read-only actions create no record — there is nothing to take back.
 *
 * <h2>Why not git (which the first implementation used)</h2>
 *
 * The first {@code /undo} was git plumbing, and the operator's first real project was not a git
 * repository — so the safety net silently did not exist exactly where a person was letting an agent
 * edit real files for the first time. A checkpoint mechanism that depends on the project's own
 * hygiene protects the projects that need it least. This one depends on nothing: the tool layer is
 * already the choke point every mutation passes through, so the journal captures pre-images there,
 * before the tool runs.
 *
 * <h2>What is undoable, exactly — tiered and said out loud</h2>
 *
 * <ul>
 *   <li><b>{@code write_file} / {@code edit_file}: always.</b> The listener sees the path before
 *       the tool executes; the prior content (or the fact the file did not exist) is saved then.</li>
 *   <li><b>Mutating shell commands: when the tree is small enough to copy first.</b> A shell
 *       command can touch anything, so the only honest pre-image is the whole tree. Up to
 *       {@link #MAX_TREE_FILES} files it is copied before the command runs; beyond that the turn is
 *       marked <b>partially undoable</b> and {@code /undo} says so — a rewind that quietly skips
 *       what a shell script did would look complete and be a lie.</li>
 * </ul>
 *
 * <h2>Depth is capped</h2>
 *
 * {@code CODEZAIKU_UNDO_DEPTH} turns are kept (default {@value #DEFAULT_DEPTH}); the oldest record
 * is dropped when a new one arrives. Pre-images live under the session store, never inside the
 * project, and the whole directory for a turn is deleted when its record retires.
 */
final class ChatJournal {

    static final int DEFAULT_DEPTH = 10;
    /** Trees up to this many files get a full pre-copy before a mutating shell command. */
    static final int MAX_TREE_FILES = 2000;

    /** One mutating step's pre-images. */
    private static final class TurnRecord {
        final String label;
        final int turn;
        final Path dir;                                        // this turn's storage
        final Map<String, Path> preImages = new LinkedHashMap<>();   // rel path -> saved copy
        final Map<String, Boolean> existed = new LinkedHashMap<>();  // rel path -> was on disk
        Path treeCopy;                                         // whole-tree snapshot, or null
        boolean shellNotCovered;                               // a mutating shell ran uncovered

        TurnRecord(int turn, String label, Path dir) { this.turn = turn; this.label = label; this.dir = dir; }
    }

    private final Path projectRoot;
    private final Path store;
    private final int depth;
    private final Deque<TurnRecord> records = new ArrayDeque<>();
    private TurnRecord current;

    ChatJournal(Path projectRoot, Path store) {
        this(projectRoot, store, DEFAULT_DEPTH);
    }

    /**
     * {@code suggestedDepth} is the caller's idea of how far back matters — the chat passes its
     * turn cap when one is set, so <b>whatever budget a person grants, the rewind covers it</b>:
     * cap 100 plus "all=stop asking" means it can run free for 100 turns and every one of them is
     * journaled back. An explicit {@code CODEZAIKU_UNDO_DEPTH} still wins, because the person
     * saying a number beats the harness inferring one.
     */
    ChatJournal(Path projectRoot, Path store, int suggestedDepth) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.store = store;
        int d = Math.max(1, suggestedDepth);
        try {
            String v = org.codezaiku.Config.get("CODEZAIKU_UNDO_DEPTH", "");
            if (!v.isBlank()) d = Math.max(1, Integer.parseInt(v.strip()));
        } catch (NumberFormatException ignored) {
            // A bad value keeps the suggestion rather than disabling undo.
        }
        this.depth = d;
    }

    int depth() { return depth; }
    int recorded() { return records.size(); }

    private int stepSeq = 0;

    /** Start journaling one mutating step — one agent action that changes something. */
    void beginStep(String label) {
        current = new TurnRecord(++stepSeq, label,
                store.resolve("undo").resolve("step-" + stepSeq + "-" + System.nanoTime()));
        records.push(current);
        while (records.size() > depth) drop(records.removeLast());
    }

    /** Called from the tool listener, BEFORE write_file/edit_file executes. */
    void preWrite(String relPath) {
        if (current == null || relPath == null || relPath.isBlank()) return;
        if (current.preImages.containsKey(relPath) || current.existed.containsKey(relPath)) return;
        Path f = projectRoot.resolve(relPath).normalize();
        if (!f.startsWith(projectRoot)) return;
        try {
            if (Files.isRegularFile(f)) {
                Files.createDirectories(current.dir);
                Path copy = current.dir.resolve(Integer.toHexString(relPath.hashCode()) + ".pre");
                Files.copy(f, copy);
                current.preImages.put(relPath, copy);
                current.existed.put(relPath, true);
            } else {
                current.existed.put(relPath, false);   // undo of a creation is a deletion
            }
        } catch (IOException ignored) {
            // A failed capture makes this turn partially undoable; recorded as such below.
            current.shellNotCovered = true;
        }
    }

    /** A step whose mutations CANNOT be covered (a background process writing after the turn
     *  ends). The undo report says PARTIAL for any range containing it — honest over comforting. */
    void notCovered() {
        if (current != null) current.shellNotCovered = true;
    }

    /** Called BEFORE a mutating shell command. Copies the tree when it is small enough. */
    void preShell() {
        if (current == null || current.treeCopy != null) return;
        try {
            var files = listTree();
            if (files.size() > MAX_TREE_FILES) {
                current.shellNotCovered = true;         // honest: too big to promise a rewind
                return;
            }
            Path dst = current.dir.resolve("tree");
            for (Path rel : files) {
                Path to = dst.resolve(rel.toString());
                Files.createDirectories(to.getParent() == null ? dst : to.getParent());
                Files.copy(projectRoot.resolve(rel), to);
            }
            Files.createDirectories(dst);               // an empty tree is still a snapshot
            current.treeCopy = dst;
        } catch (IOException e) {
            current.shellNotCovered = true;
        }
    }

    /**
     * Rewind {@code n} turns of file changes, newest first. Returns a human line; never throws.
     * The session is told separately — files roll back, the conversation does not pretend.
     */
    String undo(int n) {
        if (records.isEmpty()) return "nothing to undo — no mutating step has been journaled";
        if (n < 1) n = 1;
        int restored = 0, created = 0, removed = 0;
        boolean partial = false;
        var labels = new java.util.ArrayList<String>();
        for (int i = 0; i < n && !records.isEmpty(); i++) {
            TurnRecord r = records.pop();
            labels.add(r.label);
            partial |= r.shellNotCovered;
            try {
                if (r.treeCopy != null) {
                    // The tree snapshot first — then the per-file pre-images OVER it. The snapshot
                    // is taken when the first mutating SHELL command appears, which can be
                    // mid-turn: an edit that ran before it is already baked into the copied tree,
                    // and the only record of the pre-edit content is the preWrite image. Measured
                    // by the battery: edit-then-shell in one turn "rewound" to the edited text.
                    // Capture order is restore priority, oldest last.
                    var now = listTree();
                    var then = new java.util.HashSet<String>();
                    try (var st = Files.walk(r.treeCopy)) {
                        for (Path p : (Iterable<Path>) st.filter(Files::isRegularFile)::iterator) {
                            String rel = r.treeCopy.relativize(p).toString();
                            then.add(rel);
                            Path to = projectRoot.resolve(rel);
                            Files.createDirectories(to.getParent());
                            Files.copy(p, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            restored++;
                        }
                    }
                    for (Path rel : now) {
                        if (!then.contains(rel.toString())) {
                            Files.deleteIfExists(projectRoot.resolve(rel));
                            removed++;
                        }
                    }
                }
                {
                    for (var e : r.existed.entrySet()) {
                        Path f = projectRoot.resolve(e.getKey());
                        if (e.getValue()) {
                            Files.createDirectories(f.getParent());
                            Files.copy(r.preImages.get(e.getKey()), f,
                                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            restored++;
                        } else if (Files.deleteIfExists(f)) {
                            removed++;
                            created++;
                        }
                    }
                }
            } catch (IOException e) {
                drop(r);
                return "undo stopped at step " + r.turn + " (" + r.label + "): " + e.getMessage();
            }
            drop(r);
        }
        String what = "rewound " + labels.size() + " step(s)"
                + " (" + restored + " file(s) restored" + (removed > 0 ? ", " + removed + " removed" : "") + ")"
                + " — " + String.join("; ", labels);
        if (partial) {
            what += " — PARTIAL: a shell command in the range ran without a tree snapshot"
                    + " (tree larger than " + MAX_TREE_FILES + " files); its changes remain";
        }
        return what;
    }

    /** Non-ignored project files, relative. Skips {@code .git} and the store's own droppings. */
    private java.util.List<Path> listTree() throws IOException {
        var out = new java.util.ArrayList<Path>();
        try (var st = Files.walk(projectRoot)) {
            for (Path p : (Iterable<Path>) st::iterator) {
                if (!Files.isRegularFile(p)) continue;
                Path rel = projectRoot.relativize(p);
                String s = rel.toString();
                if (s.startsWith(".git/") || s.startsWith(".codezaiku/")
                        || s.contains("/.git/") || s.startsWith("build/") || s.contains("/build/")) continue;
                out.add(rel);
                if (out.size() > MAX_TREE_FILES + 1) return out;   // enough to know it is too big
            }
        }
        return out;
    }

    private void drop(TurnRecord r) {
        try {
            if (Files.isDirectory(r.dir)) {
                try (var st = Files.walk(r.dir)) {
                    st.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
                }
            }
        } catch (IOException ignored) {
            // Leftover pre-images are disk noise, not a correctness problem.
        }
    }
}

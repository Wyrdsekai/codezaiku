package org.codezaiku.shape;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Cross-file consistency checks surfaced into the pinned structure map so the model self-corrects
 * (RESET §2/§7: "make the structure map flag the conflict"). The 9B's genuine residual after the
 * harness is honest is cross-file consistency — one module system, one entry point, no scatter —
 * and a self-inconsistent project that no config can load is invisible unless we say so.
 *
 * <p>Checks are language-specific (the pitfalls differ: JS module systems; Python competing package
 * roots; Java duplicate entity classes). JS lands first (the §7 target); the shape is extensible.
 */
public final class Conflicts {
    private static final Set<String> IGNORE = Set.of(
            ".git", "node_modules", "build", "target", "__pycache__", "dist", ".gradle", ".venv", "venv");

    private static final Pattern ESM = Pattern.compile("(?m)^\\s*(import\\s|export\\s|export\\{|export default)");
    private static final Pattern CJS = Pattern.compile("require\\(|module\\.exports|exports\\.");
    private static final Pattern LISTEN = Pattern.compile("\\.listen\\s*\\(");
    private static final Pattern TYPE_MODULE = Pattern.compile("(?s)\"type\"\\s*:\\s*\"module\"");

    private Conflicts() {
    }

    private static final Pattern PY_ROUTER_DEF = Pattern.compile("=\\s*APIRouter\\s*\\(");
    private static final Pattern PY_INCLUDE = Pattern.compile("include_router");
    private static final Pattern PY_INCLUDE_PREFIXED =
            Pattern.compile("include_router\\(\\s*(\\w+)\\.router\\s*,\\s*prefix\\s*=\\s*[\"']([^\"']+)[\"']");
    private static final Pattern PY_ROUTE_DECORATOR =
            Pattern.compile("@\\w+\\.(?:get|post|put|delete|patch)\\(\\s*[\"']([^\"']*)[\"']");

    /** Human-readable conflict warnings for the project, or empty when it is internally consistent. */
    public static List<String> detect(Path root) {
        List<String> warnings = new ArrayList<>();
        List<Path> js = filesByExt(root, ".js");
        if (!js.isEmpty()) {
            jsConflicts(root, js, warnings);
            jsImportMismatch(root, js, warnings);
        }
        List<Path> py = filesByExt(root, ".py");
        if (!py.isEmpty()) {
            pythonConflicts(root, py, warnings);
            pyImportMismatch(root, py, warnings);
        }
        parallelTrees(root, warnings);   // cross-language SPLIT-BRAIN detector
        return warnings;
    }

    private static final String[] SRC_EXTS = {".py", ".js", ".ts", ".jsx", ".tsx", ".java", ".rs", ".go", ".gd"};

    /**
     * SPLIT-BRAIN detector (battery50, the named gap no harness ships in-loop): the SAME source file
     * (same parent-dir + filename) existing under TWO+ parallel top-level trees = the project was built
     * under more than one root (root `src/` + `output/x/src/`, or a `home/…/<project>/` ghost copy). The
     * build/boot picks ONE tree, so the split is exactly why it fails to assemble. Surfaced into the pinned
     * structure map so the 9B consolidates. Low-false-positive: requires the duplicate to span 2+ DIFFERENT
     * top-level directories (a legit project never has the same parent/file under two distinct roots).
     */
    private static void parallelTrees(Path root, List<String> warnings) {
        Path base = root.toAbsolutePath().normalize();
        Map<String, List<String>> byTail = new HashMap<>();
        for (String ext : SRC_EXTS) {
            for (Path p : filesByExt(base, ext)) {
                Path par = p.getParent();
                if (par == null || par.getFileName() == null) continue;
                String tail = par.getFileName() + "/" + p.getFileName();         // e.g. "app/classifier.py"
                byTail.computeIfAbsent(tail, k -> new ArrayList<>()).add(base.relativize(p).toString());
            }
        }
        int flagged = 0;
        for (var e : byTail.entrySet()) {
            if (e.getValue().size() < 2) continue;
            LinkedHashSet<String> roots = new LinkedHashSet<>();
            for (String rel : e.getValue()) {
                int cut = rel.length() - e.getKey().length();
                roots.add(cut <= 0 ? "(root)" : rel.substring(0, cut - 1));
            }
            // require 2+ DISTINCT TOP-LEVEL dirs (output/ vs src/ vs home/ vs root) — kills the within-tree
            // coincidence of two same-named subdirs.
            Set<String> tops = new HashSet<>();
            for (String rt : roots) tops.add(rt.equals("(root)") ? "(root)" : rt.split("/", 2)[0]);
            if (tops.size() < 2) continue;
            warnings.add("SPLIT-BRAIN: `" + e.getKey() + "` exists in " + roots.size() + " PARALLEL trees ("
                    + sample(new ArrayList<>(roots)) + ") — you built the project under MORE THAN ONE root. The "
                    + "build/boot uses only ONE tree, so this split is why it fails to assemble. Pick ONE root, "
                    + "move everything under it, and delete the duplicate tree.");
            if (++flagged >= 2) break;
        }
    }

    // ---- CROSS-MODULE IMPORT/EXPORT MISMATCH (the desync the dynamic-language LSP can't catch) -------
    // A module imports {Foo, Bar} from its own './x', but x exports neither → a runtime crash
    // (the graaljs failure: a server destructured EmailClassifier/BillExtractor from a module that
    // only exported EmailThreading). Static, deterministic, low-false-positive: only flags a NAMED
    // import against a local module whose named exports we can see and that demonstrably omits it.
    private static final Pattern JS_REQUIRE_DESTRUCT =
            Pattern.compile("(?:const|let|var)\\s*\\{([^}]*)\\}\\s*=\\s*require\\(\\s*['\"](\\.[^'\"]+)['\"]\\s*\\)");
    private static final Pattern JS_IMPORT_NAMED =
            Pattern.compile("import\\s*\\{([^}]*)\\}\\s*from\\s*['\"](\\.[^'\"]+)['\"]");

    private static void jsImportMismatch(Path root, List<Path> js, List<String> warnings) {
        for (Path b : js) {
            String src = read(b);
            for (Pattern pat : List.of(JS_REQUIRE_DESTRUCT, JS_IMPORT_NAMED)) {
                Matcher m = pat.matcher(src);
                while (m.find()) {
                    List<String> names = splitImportNames(m.group(1));
                    Path target = resolveJs(b.getParent(), m.group(2));
                    if (target == null) continue;
                    Set<String> exports = jsExports(read(target));
                    if (exports.isEmpty()) continue;        // can't see the export shape → don't guess
                    for (String n : names) {
                        if (!exports.contains(n)) {
                            warnings.add(root.relativize(b) + " imports { " + n + " } from '"
                                    + m.group(2) + "' but " + root.relativize(target) + " does NOT export it"
                                    + " (it exports: " + sample(new ArrayList<>(exports)) + ") — fix the name or add the export.");
                        }
                    }
                }
            }
            if (warnings.size() > 12) return;
        }
    }

    private static final Pattern JS_EXPORT_DECL =
            Pattern.compile("export\\s+(?:async\\s+)?(?:function|class|const|let|var)\\s+(\\w+)");
    private static final Pattern JS_EXPORT_LIST = Pattern.compile("export\\s*\\{([^}]*)\\}");
    private static final Pattern JS_MODULE_EXPORTS_OBJ = Pattern.compile("module\\.exports\\s*=\\s*\\{([^}]*)\\}");
    private static final Pattern JS_EXPORTS_PROP = Pattern.compile("(?:module\\.)?exports\\.(\\w+)\\s*=");

    private static Set<String> jsExports(String src) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m;
        for (m = JS_EXPORT_DECL.matcher(src); m.find(); ) out.add(m.group(1));
        for (m = JS_EXPORTS_PROP.matcher(src); m.find(); ) out.add(m.group(1));
        for (m = JS_EXPORT_LIST.matcher(src); m.find(); ) out.addAll(splitImportNames(m.group(1)));
        for (m = JS_MODULE_EXPORTS_OBJ.matcher(src); m.find(); ) {
            for (String e : m.group(1).split(",")) {
                String key = e.split(":")[0].strip();
                if (key.matches("\\w+")) out.add(key);
            }
        }
        return out;
    }

    /** Resolve a relative JS import spec to a file on disk (.js, /index.js), or null. */
    private static Path resolveJs(Path fromDir, String spec) {
        if (fromDir == null) return null;
        Path basep = fromDir.resolve(spec).normalize();
        for (Path cand : List.of(basep, Path.of(basep + ".js"), basep.resolve("index.js"))) {
            if (Files.isRegularFile(cand)) return cand;
        }
        return null;
    }

    private static List<String> splitImportNames(String inside) {
        List<String> out = new ArrayList<>();
        for (String part : inside.split(",")) {
            String p = part.strip();
            if (p.isEmpty()) continue;
            // `foo as bar` — the local binding (and what the module must provide) is the FIRST name.
            String name = p.split("\\s+as\\s+")[0].strip();
            if (name.matches("\\w+")) out.add(name);
        }
        return out;
    }

    private static final Pattern PY_FROM_IMPORT =
            Pattern.compile("(?m)^\\s*from\\s+(\\.[\\w.]*|[\\w.]+)\\s+import\\s+([^\\n#]+)");
    private static final Pattern PY_DEF = Pattern.compile("(?m)^(?:async\\s+)?(?:def|class)\\s+(\\w+)");
    private static final Pattern PY_TOPLEVEL_ASSIGN = Pattern.compile("(?m)^(\\w+)\\s*(?::[^=]+)?=");

    private static void pyImportMismatch(Path root, List<Path> py, List<String> warnings) {
        for (Path b : py) {
            Matcher m = PY_FROM_IMPORT.matcher(read(b));
            while (m.find()) {
                String mod = m.group(1);
                String names = m.group(2);
                if (names.contains("*")) continue;
                Path target = resolvePy(root, b, mod);
                if (target == null) continue;
                String tsrc = read(target);
                Set<String> defined = new LinkedHashSet<>();
                for (Matcher d = PY_DEF.matcher(tsrc); d.find(); ) defined.add(d.group(1));
                for (Matcher d = PY_TOPLEVEL_ASSIGN.matcher(tsrc); d.find(); ) defined.add(d.group(1));
                if (defined.isEmpty()) continue;
                for (String raw : names.replaceAll("[()]", "").split(",")) {
                    String n = raw.strip().split("\\s+as\\s+")[0].strip();
                    if (n.isEmpty() || !n.matches("\\w+")) continue;
                    if (!defined.contains(n)) {
                        warnings.add(root.relativize(b) + " does `from " + mod + " import " + n + "` but "
                                + root.relativize(target) + " defines no `" + n + "` (it defines: "
                                + sample(new ArrayList<>(defined)) + ") — fix the name or define it.");
                    }
                }
            }
            if (warnings.size() > 12) return;
        }
    }

    /** Resolve a python `from MOD import ...` to a module file, best-effort; null if unsure. */
    private static Path resolvePy(Path root, Path from, String mod) {
        String rel = mod.replace('.', '/');
        List<Path> cands = new ArrayList<>();
        if (mod.startsWith(".")) {                         // relative: resolve against this file's dir
            Path d = from.getParent();
            String tail = mod.replaceFirst("^\\.+", "");
            Path bp = (d == null ? root : d).resolve(tail.replace('.', '/'));
            cands.add(Path.of(bp + ".py"));
            cands.add(bp.resolve("__init__.py"));
        } else {                                            // absolute-ish: try under root
            cands.add(root.resolve(rel + ".py"));
            cands.add(root.resolve(rel).resolve("__init__.py"));
        }
        for (Path c : cands) if (Files.isRegularFile(c)) return c;
        return null;
    }

    /** FastAPI/Flask: a router defined but never include_router'd has unreachable endpoints. */
    private static void pythonConflicts(Path root, List<Path> py, List<String> warnings) {
        List<Path> routerFiles = new ArrayList<>();
        StringBuilder wiring = new StringBuilder();
        for (Path p : py) {
            String src = read(p);
            if (PY_ROUTER_DEF.matcher(src).find()) routerFiles.add(p);
            if (PY_INCLUDE.matcher(src).find()) wiring.append(src).append('\n');
        }
        String wired = wiring.toString();
        for (Path p : routerFiles) {
            String base = p.getFileName().toString().replaceAll("\\.py$", "");
            if (!wired.contains(base)) {
                warnings.add(root.relativize(p) + " defines an APIRouter but nothing include_router's it"
                        + " — its endpoints are UNREACHABLE; register it in app/main.py via app.include_router(...).");
            }
        }

        // Double-prefix: a router included with prefix=P whose decorators already start with P,
        // so its routes actually live at /P/P/... (the bug run 2 + the gold both made).
        Map<String, Path> byModule = new HashMap<>();
        for (Path p : routerFiles) byModule.put(p.getFileName().toString().replaceAll("\\.py$", ""), p);
        for (Path p : py) {
            Matcher inc = PY_INCLUDE_PREFIXED.matcher(read(p));
            while (inc.find()) {
                String mod = inc.group(1);
                String prefix = inc.group(2);
                Path rf = byModule.get(mod);
                if (rf == null || prefix.isEmpty()) continue;
                Matcher dec = PY_ROUTE_DECORATOR.matcher(read(rf));
                while (dec.find()) {
                    if (dec.group(1).startsWith(prefix)) {
                        warnings.add(root.relativize(rf) + " routes start with \"" + prefix
                                + "\" AND it is include_router'd with prefix=\"" + prefix + "\" → they live at "
                                + prefix + prefix + "/... (double prefix); drop the prefix from one side.");
                        break;
                    }
                }
            }
        }
    }

    private static void jsConflicts(Path root, List<Path> js, List<String> warnings) {
        List<String> esm = new ArrayList<>();
        List<String> cjs = new ArrayList<>();
        List<String> entries = new ArrayList<>();
        for (Path p : js) {
            String src = read(p);
            String rel = root.relativize(p).toString();
            if (ESM.matcher(src).find()) esm.add(rel);
            if (CJS.matcher(src).find()) cjs.add(rel);
            if (LISTEN.matcher(src).find()) entries.add(rel);
        }
        if (!esm.isEmpty() && !cjs.isEmpty()) {
            warnings.add("mixed module systems — ESM (import/export) in " + sample(esm)
                    + " but CommonJS (require/module.exports) in " + sample(cjs)
                    + "; pick ONE consistently (Node cannot load both under one package.json).");
        }
        Path pkg = root.resolve("package.json");
        if (Files.isRegularFile(pkg)) {
            boolean typeModule = TYPE_MODULE.matcher(read(pkg)).find();
            if (typeModule && !cjs.isEmpty()) {
                warnings.add("package.json has \"type\":\"module\" but CommonJS files exist (" + sample(cjs)
                        + ") — they will fail to load.");
            }
            if (!typeModule && !esm.isEmpty()) {
                warnings.add("files use ESM import/export (" + sample(esm)
                        + ") but package.json has no \"type\":\"module\" — Node loads them as CommonJS and fails.");
            }
        }
        if (entries.size() > 1) {
            warnings.add("multiple entry points each calling .listen(): " + sample(entries)
                    + " — there must be exactly ONE server entry point.");
        }
    }

    private static List<Path> filesByExt(Path root, String ext) {
        Path base = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(base)) return List.of();
        // TreeWalk prunes ignored dirs + tolerates files vanishing under target/ (rust-analyzer churn).
        return TreeWalk.files(base, IGNORE).stream()
                .filter(p -> p.getFileName().toString().endsWith(ext))
                .limit(500)
                .toList();
    }

    private static boolean notIgnored(Path p) {
        for (Path part : p) if (IGNORE.contains(part.toString())) return false;
        return true;
    }

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            return "";
        }
    }

    private static String sample(List<String> xs) {
        return xs.size() <= 3 ? String.join(", ", xs)
                : String.join(", ", xs.subList(0, 3)) + " (+" + (xs.size() - 3) + " more)";
    }
}

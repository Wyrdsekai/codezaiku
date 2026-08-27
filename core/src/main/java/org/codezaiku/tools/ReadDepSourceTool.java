package org.codezaiku.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * On-demand REAL-SOURCE reading (the "build the rest" lever the library push could not cover): the
 * model reads the ACTUAL installed dependency source to learn a library's current API, instead of
 * guessing a stale API or shelling out. Reading the canonical hollow rust-monitor showed exactly why
 * this is needed — the 9B wrote the pre-0.30 {@code SystemExt} API and ran {@code Command::new("hostname")}
 * because it did not KNOW sysinfo's current {@code System::host_name()}. The library PUSH did not fix
 * this (the model would not apply pushed signature docs); a tool that surfaces the real signature at
 * the point of need is the grounded alternative.
 *
 * <p>Deliberately reads OUTSIDE the project scope — the dependency caches: Rust
 * {@code ~/.cargo/registry/src/<index>/<crate>-<ver>/}, Python {@code <root>/.venv/.../site-packages/<pkg>},
 * Node {@code <root>/node_modules/<pkg>}. Returns SIGNATURE lines (navigation, not a file dump):
 * {@code file:line: <decl>} for declarations matching the query, capped for the small window.
 */
public final class ReadDepSourceTool implements Tool {
    private static final int MAX_HITS = 60;
    private static final int MAX_CHARS = 9_000;
    private static final int MAX_FILES = 4_000;

    private final Path root;

    public ReadDepSourceTool(Path projectRoot) {
        this.root = projectRoot.toAbsolutePath().normalize();
    }

    @Override
    public String name() {
        return "read_dep_source";
    }

    @Override
    public String description() {
        return "Look up the REAL, current API of an installed dependency by reading its actual source — "
                + "use this whenever you are unsure of a library's exact function/method/type names or "
                + "signatures. Do NOT guess an API from memory and do NOT shell out to system commands as a "
                + "substitute. Args: dependency = the crate/package name (e.g. \"sysinfo\"); query = a "
                + "symbol or word to find (e.g. \"host_name\", \"networks\"). Returns matching declaration "
                + "signatures as file:line. Omit query to see the public API surface.";
    }

    @Override
    public ObjectNode parametersSchema(ObjectMapper j) {
        ObjectNode p = j.createObjectNode();
        p.put("type", "object");
        ObjectNode props = p.putObject("properties");
        props.putObject("dependency").put("type", "string");
        props.putObject("query").put("type", "string");
        p.putArray("required").add("dependency");
        return p;
    }

    @Override
    public String execute(JsonNode args) {
        String dep = args.path("dependency").asText("").trim();
        String query = args.path("query").asText("").trim();
        if (dep.isEmpty()) return "ERROR: dependency is required";

        Located loc = locate(dep);
        if (loc == null) {
            return "ERROR: could not find installed source for '" + dep + "'. Looked in: "
                    + "~/.cargo/registry/src/*/" + dep + "-*, " + root + "/.venv/.../site-packages/, "
                    + root + "/node_modules/. Is the dependency declared and fetched (build once first)?";
        }

        List<Hit> found = new ArrayList<>(); // ALL public declarations (filtered by query afterwards)
        boolean wantApi = query.isEmpty();
        try (Stream<Path> walk = Files.walk(loc.dir)) {
            List<Path> files = walk.filter(Files::isRegularFile)
                    .filter(f -> isSource(f, loc.lang))
                    .limit(MAX_FILES)
                    .toList();
            for (Path f : files) {
                List<String> lines;
                try {
                    lines = Files.readAllLines(f);
                } catch (Exception e) {
                    continue; // non-UTF8 / unreadable — skip
                }
                String relName = loc.dir.relativize(f).toString();
                for (int i = 0; i < lines.size(); i++) {
                    String t = lines.get(i).strip();
                    if (!isDecl(t, loc.lang)) continue;
                    if (t.length() > 200) t = t.substring(0, 200) + " …";
                    found.add(new Hit(rank(t, relName), relName + ":" + (i + 1) + ":  " + t));
                }
            }
        } catch (IOException e) {
            return "ERROR: failed reading source under " + loc.dir + ": " + e.getMessage();
        }
        // Best (callable, cross-platform) declarations first so the 9B copies the right line.
        found.sort(Comparator.comparingInt(Hit::rank));

        List<String> matched = new ArrayList<>();
        for (Hit h : found) {
            if (wantApi || h.line().toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))) {
                matched.add(h.line());
                if (matched.size() >= MAX_HITS) break;
            }
        }

        // Key fix: on a no-match query, do NOT dead-end (the model loops re-asking the wrong name). Show
        // the API SURFACE so it sees the REAL names (e.g. asked "EnterTerminal" → here's EnterAlternateScreen).
        boolean noMatch = !wantApi && matched.isEmpty();
        List<String> hits = matched;
        if (noMatch) {
            for (Hit h : found) {
                hits.add(h.line());
                if (hits.size() >= MAX_HITS) break;
            }
        }
        if (hits.isEmpty()) {
            return "No public declarations found in " + dep + " (" + loc.label + ").";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(dep).append(" (").append(loc.label).append(") — ")
                .append(wantApi ? "public API surface"
                        : noMatch ? "NO symbol matched \"" + query + "\" — here is the real API surface; "
                                + "use a name that actually exists below"
                                : "declarations matching \"" + query + "\"")
                .append(" [real installed source]:\n");
        for (String h : hits) {
            if (sb.length() + h.length() > MAX_CHARS) {
                sb.append("…[more results truncated — narrow the query]");
                break;
            }
            sb.append("  ").append(h).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    private record Located(Path dir, String lang, String label) {
    }

    private record Hit(int rank, String line) {
    }

    /** Lower = more useful: truly-public callable decls in cross-platform paths float to the top. */
    private static int rank(String trimmed, String relPath) {
        boolean truePublic = (trimmed.startsWith("pub ") && !trimmed.startsWith("pub(crate)"))
                || trimmed.startsWith("export ") || trimmed.startsWith("def ") || trimmed.startsWith("class ")
                || trimmed.startsWith("async def ") || trimmed.startsWith("function ");
        boolean impl = trimmed.startsWith("impl ");
        // platform-specific dirs hold internal duplicates; the canonical public API lives at the top /
        // in common/ — prefer those so the model copies the cross-platform signature.
        boolean platform = relPath.contains("windows/") || relPath.contains("unix/")
                || relPath.contains("apple/") || relPath.contains("bsd/") || relPath.contains("freebsd/");
        boolean canonical = !platform && (relPath.contains("common/") || !relPath.contains("/"));
        if (truePublic && canonical) return 0;
        if (truePublic) return 1;
        if (impl && canonical) return 2;
        if (impl) return 3;
        return 4; // pub(crate) / other
    }

    /** Find the installed source dir for a dependency across the ecosystems present. */
    private Located locate(String dep) {
        Located r = locateRust(dep);
        if (r != null) return r;
        Located py = locatePython(dep);
        if (py != null) return py;
        return locateNode(dep);
    }

    private Located locateRust(String dep) {
        Path cargoSrc = Path.of(System.getProperty("user.home"), ".cargo", "registry", "src");
        if (!Files.isDirectory(cargoSrc)) return null;
        String pinned = pinnedCrateVersion(dep); // from Cargo.lock if present
        Path best = null;
        String bestVer = null;
        try (Stream<Path> indexes = Files.list(cargoSrc)) {
            for (Path index : (Iterable<Path>) indexes::iterator) {
                if (!Files.isDirectory(index)) continue;
                try (Stream<Path> crates = Files.list(index)) {
                    for (Path crate : (Iterable<Path>) crates::iterator) {
                        String n = crate.getFileName().toString();
                        int dash = n.lastIndexOf('-');
                        if (dash <= 0) continue;
                        String cname = n.substring(0, dash);
                        String cver = n.substring(dash + 1);
                        if (!cname.equals(dep)) continue;
                        if (pinned != null && pinned.equals(cver)) {
                            Path src = crate.resolve("src");
                            return new Located(Files.isDirectory(src) ? src : crate, "rust",
                                    "Rust crate " + n + " [pinned]");
                        }
                        if (bestVer == null || cver.compareTo(bestVer) > 0) {
                            bestVer = cver;
                            best = crate;
                        }
                    }
                }
            }
        } catch (IOException e) {
            return null;
        }
        if (best == null) return null;
        Path src = best.resolve("src");
        return new Located(Files.isDirectory(src) ? src : best, "rust",
                "Rust crate " + best.getFileName());
    }

    /** The version pinned for a crate in the project's Cargo.lock, or null. */
    private String pinnedCrateVersion(String dep) {
        Path lock = root.resolve("Cargo.lock");
        if (!Files.isRegularFile(lock)) return null;
        try {
            List<String> lines = Files.readAllLines(lock);
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).strip().equals("name = \"" + dep + "\"")) {
                    for (int k = i + 1; k < Math.min(lines.size(), i + 4); k++) {
                        String s = lines.get(k).strip();
                        if (s.startsWith("version = \"")) {
                            return s.substring("version = \"".length(), s.length() - 1);
                        }
                    }
                }
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    private Located locatePython(String dep) {
        Path venvLib = root.resolve(".venv/lib");
        if (!Files.isDirectory(venvLib)) return null;
        String pkg = dep.replace('-', '_');
        try (Stream<Path> pys = Files.list(venvLib)) {
            for (Path py : (Iterable<Path>) pys::iterator) {
                Path sp = py.resolve("site-packages");
                if (!Files.isDirectory(sp)) continue;
                Path pkgDir = sp.resolve(pkg);
                if (Files.isDirectory(pkgDir)) return new Located(pkgDir, "python", "Python package " + pkg);
                Path pkgFile = sp.resolve(pkg + ".py");
                if (Files.isRegularFile(pkgFile)) {
                    return new Located(pkgFile.getParent(), "python", "Python module " + pkg + ".py");
                }
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    private Located locateNode(String dep) {
        Path mod = root.resolve("node_modules").resolve(dep);
        if (Files.isDirectory(mod)) return new Located(mod, "js", "Node package " + dep);
        return null;
    }

    private static boolean isSource(Path f, String lang) {
        String n = f.getFileName().toString().toLowerCase(Locale.ROOT);
        return switch (lang) {
            case "rust" -> n.endsWith(".rs");
            case "python" -> n.endsWith(".py") && !n.endsWith("_test.py") && !n.startsWith("test_");
            case "js" -> (n.endsWith(".d.ts") || n.endsWith(".ts") || n.endsWith(".js") || n.endsWith(".mjs"))
                    && !n.contains(".test.") && !n.contains(".spec.");
            default -> false;
        };
    }

    /** A trimmed line that looks like a public declaration/signature in the given language. */
    private static boolean isDecl(String t, String lang) {
        if (t.isEmpty()) return false;
        return switch (lang) {
            case "rust" -> t.startsWith("pub fn ") || t.startsWith("pub struct ") || t.startsWith("pub enum ")
                    || t.startsWith("pub trait ") || t.startsWith("pub type ") || t.startsWith("pub const ")
                    || t.startsWith("pub use ") || t.startsWith("impl ") || t.startsWith("pub(crate) fn ");
            case "python" -> t.startsWith("def ") || t.startsWith("class ") || t.startsWith("async def ");
            case "js" -> t.startsWith("export ") || t.startsWith("declare ") || t.startsWith("function ")
                    || t.startsWith("export function ") || t.startsWith("export class ")
                    || t.startsWith("export const ") || t.startsWith("export default ");
            default -> false;
        };
    }
}

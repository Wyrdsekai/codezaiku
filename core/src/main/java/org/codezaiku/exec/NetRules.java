package org.codezaiku.exec;

import org.codezaiku.Config;
import org.codezaiku.tools.GivenAddresses;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which hosts a sandboxed command may reach through CodeZaiku's proxy, where its network is closed: the package
 * registries the common toolchains ask, the hosts the person names in {@code sandbox.hosts} or lets in for a chat, and the
 * hosts the person typed in the task or a message ({@link GivenHosts}). Nothing else: not what a search returned, a page
 * said or a file of the project names (those give an address to web_fetch only), and not the project's own git remotes,
 * whose hosts a refusal names so that one setting lets them in (the owner's rules of 2026-11-24). The proxy decides by host
 * and port alone: it does not read what a tunnel carries.
 *
 * <p>Publishing hosts are left out on purpose (PyPI's upload host, Maven Central's publishing portal, crates.io's API):
 * a job reads packages, and a host let in can be sent anything.
 */
public final class NetRules {

    /**
     * The registries let in by default, each on 443, with the toolchain that asks it. Each is named by the tool's documentation or
     * was seen in the proxy's record of a real fetch (2026-11-24); a host known only from memory is not here.
     */
    static final Map<String, String> REGISTRIES = registries();

    private static Map<String, String> registries() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("pypi.org", "pip");                       // pip's default index, https://pypi.org/simple (doc; seen)
        m.put("files.pythonhosted.org", "pip");         // where PyPI's files are (doc; seen)
        m.put("registry.npmjs.org", "npm");             // npm's default registry (doc; seen)
        m.put("repo.maven.apache.org", "Maven");        // the super POM's central (doc; seen from Maven and Gradle)
        m.put("plugins.gradle.org", "Gradle");          // the plugin portal (doc)
        m.put("services.gradle.org", "Gradle");         // the wrapper's distributionUrl (doc; seen: it answers 307 to github.com)
        m.put("index.crates.io", "Cargo");              // crates.io's sparse index (doc; seen)
        m.put("static.crates.io", "Cargo");             // the crates themselves (seen)
        m.put("proxy.golang.org", "Go");                // GOPROXY's default (doc)
        m.put("sum.golang.org", "Go");                  // GOSUMDB's default (doc)
        return Map.copyOf(m);
    }

    /** A host and a port; the port is -1 for "443 and 80". */
    public record HostPort(String host, int port) {
        boolean matches(String h, int p) {
            boolean portOk = port < 0 ? (p == 443 || p == 80) : p == port;
            if (!portOk) return false;
            if (host.startsWith("*.")) return h.endsWith(host.substring(1)) && h.length() > host.length() - 1;
            return h.equals(host);
        }
        @Override public String toString() { return port < 0 ? host : host + ":" + port; }
    }

    /** What was decided and by which rule, in words the record and the person read. */
    public record Decision(boolean let, String rule) { }

    /** Hosts given in the job: those the person typed ({@link GivenAddresses#givenToCommands}); never one a search, a page or a file gave. */
    public interface GivenHosts {
        boolean given(Path project, String host, int port);
    }

    private static final GivenHosts BY_THE_RULE = (project, host, port) -> GivenAddresses.givenToCommands(host, port);
    private static volatile GivenHosts given = BY_THE_RULE;

    /** For tests: another rule; null returns to the rule of given addresses. */
    public static void given(GivenHosts g) { given = g == null ? BY_THE_RULE : g; }

    private final Path project;
    private final List<HostPort> remotes;
    private final Set<HostPort> letInHere = new LinkedHashSet<>();

    NetRules(Path project, List<HostPort> remotes) {
        this.project = project;
        this.remotes = List.copyOf(remotes);
    }

    /** The rules for a project, its remotes read now (to be named, not let in): call this when the job begins. */
    public static NetRules at(Path project) { return new NetRules(project, project == null ? List.of() : remotesOf(project)); }

    /** The hosts of the project's https remotes as they were when the job began: not let in, named where a refusal or the first lines speak of them. */
    public List<HostPort> remotes() { return remotes; }

    /** Whether {@code host} on {@code port} is one of the project's remotes. */
    public boolean remote(String rawHost, int port) {
        String host = norm(rawHost);
        for (HostPort r : remotes) if (r.host().equals(host) && r.port() == port) return true;
        return false;
    }

    /** A host the person let in while a chat runs, for this project, until {@link #letOut}. */
    public synchronized void letIn(String host, int port) { letInHere.add(new HostPort(norm(host), port)); }

    /** The end of a chat's yes. */
    public synchronized void letOut(String host, int port) { letInHere.remove(new HostPort(norm(host), port)); }

    /** Whether a command may reach {@code host} on {@code port}, and why. */
    public Decision decide(String rawHost, int port) {
        String host = norm(rawHost);
        if (host.isEmpty() || port < 1 || port > 65535) return new Decision(false, "not an address");
        for (HostPort h : setting()) if (h.matches(host, port)) return new Decision(true, "sandbox.hosts");
        synchronized (this) { for (HostPort h : letInHere) if (h.matches(host, port)) return new Decision(true, "let in by the person"); }
        String registry = REGISTRIES.get(host);
        if (registry != null && port == 443) return new Decision(true, "registry (" + registry + ")");
        try {
            if (given.given(project, host, port)) return new Decision(true, "named by the person");
        } catch (RuntimeException e) {
            // a rule that fails gives nothing
        }
        return new Decision(false, remote(host, port) ? "the project's git remote: not let in by default" : "not let in");
    }

    /**
     * Whether the person named {@code host}: in {@code sandbox.hosts}, or with a yes in a chat. Only then may it lead to a loopback or
     * link-local address.
     */
    public boolean named(String rawHost, int port) {
        String host = norm(rawHost);
        for (HostPort h : setting()) if (h.matches(host, port)) return true;
        synchronized (this) { for (HostPort h : letInHere) if (h.matches(host, port)) return true; }
        return false;
    }

    /** {@code sandbox.hosts}: {@code host}, {@code host:port}, {@code *.example.com}, comma-separated. */
    static List<HostPort> setting() {
        List<HostPort> out = new ArrayList<>();
        String v = Config.get(Sandbox.HOSTS);
        if (v == null || v.isBlank()) return out;
        for (String s : v.split("[,\\s]+")) {
            HostPort h = parse(s);
            if (h != null) out.add(h);
        }
        return out;
    }

    static HostPort parse(String s) {
        String t = s == null ? "" : s.strip();
        if (t.isEmpty()) return null;
        if (t.contains("://")) {
            try {
                URI u = URI.create(t);
                if (u.getHost() == null) return null;
                return new HostPort(norm(u.getHost()), u.getPort());
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        int colon = t.lastIndexOf(':');
        if (colon > 0 && t.indexOf(':') == colon && t.substring(colon + 1).matches("\\d{1,5}")) {
            return new HostPort(norm(t.substring(0, colon)), Integer.parseInt(t.substring(colon + 1)));
        }
        return new HostPort(norm(t), -1);
    }

    /** A host as compared: lower case, without a trailing dot or the brackets of an IPv6 address. */
    static String norm(String host) {
        if (host == null) return "";
        String h = host.strip().toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) h = h.substring(1, h.length() - 1);
        while (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        return h;
    }

    /** Why an address is not one to connect a command to unless the person named its host: this machine, the link, nothing. Null when it is. */
    static String unusable(InetAddress a) {
        if (a.isLoopbackAddress()) return "a loopback address (this machine)";
        if (a.isLinkLocalAddress()) return "a link-local address";
        if (a.isAnyLocalAddress() || a.isMulticastAddress()) return "an unusable address";
        return null;
    }

    // ── the project's git remotes ───────────────────────────────────────────────────────────────────────────────────

    private static final Pattern SECTION = Pattern.compile("^\\s*\\[\\s*(remote|submodule)\\s+\"[^\"]*\"\\s*\\]\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern URL = Pattern.compile("^\\s*(url|pushurl)\\s*=\\s*(\\S+)\\s*$", Pattern.CASE_INSENSITIVE);

    /**
     * The hosts and ports of the project's remotes and submodules that speak HTTP(S): from its git settings ({@code .git/config},
     * or a worktree's common folder) and {@code .gitmodules}. A remote over ssh cannot pass an HTTP proxy and gives nothing.
     */
    static List<HostPort> remotesOf(Path project) {
        LinkedHashSet<HostPort> out = new LinkedHashSet<>();
        List<Path> files = new ArrayList<>();
        Path dotGit = project.resolve(".git");
        if (Files.isDirectory(dotGit)) files.add(dotGit.resolve("config"));
        for (Path dir : SandboxRules.gitDirs(project)) files.add(dir.resolve("config"));
        files.add(project.resolve(".gitmodules"));
        for (Path f : files) {
            try {
                if (!Files.isRegularFile(f) || Files.size(f) > 1_000_000) continue;
                boolean inSection = false;
                for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    if (line.strip().startsWith("[")) { inSection = SECTION.matcher(line).matches(); continue; }
                    if (!inSection) continue;
                    Matcher m = URL.matcher(line);
                    if (!m.matches()) continue;
                    HostPort h = httpRemote(m.group(2));
                    if (h != null) out.add(h);
                }
            } catch (IOException | RuntimeException e) {
                // a settings file that cannot be read gives nothing
            }
        }
        return new ArrayList<>(out);
    }

    static HostPort httpRemote(String url) {
        String u = url.strip();
        if (u.startsWith("\"") && u.endsWith("\"") && u.length() > 1) u = u.substring(1, u.length() - 1);
        String lower = u.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("https://") && !lower.startsWith("http://")) return null;
        try {
            URI uri = URI.create(u);
            if (uri.getHost() == null) return null;
            int port = uri.getPort() > 0 ? uri.getPort() : lower.startsWith("https://") ? 443 : 80;
            return new HostPort(norm(uri.getHost()), port);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

package org.codezaiku.exec;

import org.codezaiku.redact.Redactor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The environment of a sandboxed command: the person's, without what looks like a credential and without the addresses
 * of their session's services, and with a home, a temp folder and package caches of the run's own.
 *
 * <p>A build reads {@code ~/.gradle}, {@code ~/.m2}, {@code ~/.npm}, {@code ~/.cache/pip} and {@code ~/.cargo}. The
 * sandbox closes the person's home, so each of those is pointed into the run's folder, where the first build of a
 * project downloads what it needs again. The JVM takes its home from the account and not from {@code HOME}, so it is
 * told with {@code -Duser.home}; without that Gradle reads {@code ~/.m2} of the real home and the build fails.
 */
public final class SandboxEnv {

    private SandboxEnv() { }

    /** Where the person's session keeps its services, and their own configuration: none of it is the run's. */
    private static final Set<String> SESSION = Set.of(
            "DBUS_SESSION_BUS_ADDRESS", "XDG_RUNTIME_DIR", "SSH_AUTH_SOCK", "SSH_AGENT_PID", "GPG_AGENT_INFO", "GNOME_KEYRING_CONTROL",
            "DISPLAY", "WAYLAND_DISPLAY", "XAUTHORITY", "ICEAUTHORITY", "SESSION_MANAGER", "TMUX", "TMUX_PANE", "STY",
            "DOCKER_HOST", "DOCKER_CONFIG", "KUBECONFIG", "NETRC", "PGPASSFILE", "AWS_PROFILE", "AWS_ROLE_ARN", "AWS_CONFIG_FILE",
            "CLOUDSDK_CONFIG", "XDG_CONFIG_HOME", "XDG_DATA_HOME", "XDG_STATE_HOME", "HISTFILE", "MAIL", "OLDPWD",
            "SSH_CONNECTION", "SSH_CLIENT", "SSH_TTY");

    /** A word of a variable's name that marks a credential. */
    private static final Set<String> WORDS = Set.of(
            "KEY", "KEYS", "TOKEN", "TOKENS", "SECRET", "SECRETS", "PASSWORD", "PASSWD", "PASS", "PWD", "CREDENTIAL", "CREDENTIALS",
            "AUTH", "OAUTH", "PAT", "DSN", "COOKIE", "JWT", "BEARER");

    private static final String[] INSIDE = {"PASSWORD", "PASSWD", "SECRET", "TOKEN", "CREDENTIAL", "APIKEY", "PRIVATEKEY"};

    /**
     * Whether a variable is kept out of a sandboxed command: its name looks like a credential ({@code *_KEY},
     * {@code *_TOKEN}, {@code *_SECRET}, {@code *PASSWORD*}, a cloud provider's), or its value has the shape of one (a
     * key with a known prefix, an address with a password in it), by the same patterns that mask tool output.
     */
    public static boolean looksLikeCredential(String name, String value) {
        String n = name == null ? "" : name.toUpperCase(Locale.ROOT);
        if (n.equals("PWD")) return false;                     // the working directory
        for (String word : n.split("[^A-Z0-9]+")) if (WORDS.contains(word)) return true;
        for (String part : INSIDE) if (n.contains(part)) return true;
        if (value == null || value.isBlank()) return false;
        for (String rule : Redactor.redact(value).rules()) if (!rule.equals("email")) return true;
        return false;
    }

    /** The names taken out of {@code base}: for a test, and for the line that says how many were. */
    public static List<String> removed(Map<String, String> base, Set<String> keep) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> e : base.entrySet()) if (!passes(e.getKey(), e.getValue(), keep)) out.add(e.getKey());
        return out;
    }

    private static boolean passes(String name, String value, Set<String> keep) {
        if (keep != null && keep.contains(name)) return true;
        if (SESSION.contains(name)) return false;
        if (name.startsWith("CODEZAIKU_")) return false;      // the harness's own settings: addresses and keys of the person's models
        return !looksLikeCredential(name, value);
    }

    /**
     * The environment for a command in {@code folders}. {@code keep}: names the person listed in {@code sandbox.env},
     * which pass whatever they look like. {@code homes}: the person's home folders, where a tool manager keeps its
     * toolchains; a manager that finds them through {@code HOME} is told where they are.
     */
    public static Map<String, String> build(Map<String, String> base, Sandbox.Folders folders, Set<String> keep, Collection<Path> homes) {
        Map<String, String> env = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : base.entrySet()) if (passes(e.getKey(), e.getValue(), keep)) env.put(e.getKey(), e.getValue());

        String home = folders.home().toString(), tmp = folders.tmp().toString(), cache = folders.cache().toString();
        env.put("HOME", home);
        env.put("XDG_CACHE_HOME", cache + "/xdg");
        env.put("XDG_CONFIG_HOME", home + "/.config");
        env.put("XDG_DATA_HOME", home + "/.local/share");
        env.put("XDG_STATE_HOME", home + "/.local/state");
        env.put("TMPDIR", tmp);
        env.put("TMP", tmp);
        env.put("TEMP", tmp);
        env.put("GRADLE_USER_HOME", cache + "/gradle");
        env.put("npm_config_cache", cache + "/npm");
        env.put("YARN_CACHE_FOLDER", cache + "/yarn");
        env.put("PIP_CACHE_DIR", cache + "/pip");
        env.put("CARGO_HOME", cache + "/cargo");
        env.put("GOMODCACHE", cache + "/go/mod");
        String java = "-Duser.home=" + javaWord(home) + " -Djava.io.tmpdir=" + javaWord(tmp);
        String had = env.get("JAVA_TOOL_OPTIONS");
        env.put("JAVA_TOOL_OPTIONS", had == null || had.isBlank() ? java : had + " " + java);
        for (Path realHome : homes) {
            for (String[] manager : MANAGERS) {
                Path dir = realHome.resolve(manager[1]);
                if (!env.containsKey(manager[0]) && Files.isDirectory(dir)) env.put(manager[0], dir.toString());
            }
        }
        env.put("CODEZAIKU_SANDBOXED", "1");                  // a script can tell where it runs
        return env;
    }

    /** The variables the toolchains read for a proxy: curl and git (libcurl), cargo, pip, npm, Go, Python's urllib; each tool's own name last. */
    static final List<String> PROXY_NAMES = List.of("HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "http_proxy", "https_proxy", "all_proxy",
            "npm_config_proxy", "npm_config_https_proxy", "PIP_PROXY", "CARGO_HTTP_PROXY");

    /**
     * Where a command's network is closed: every toolchain is told to use CodeZaiku's proxy at {@code port} on 127.0.0.1, which
     * takes the place of a proxy of the person's own (the closed network cannot reach it). The address carries the command's
     * number as its user name, so the proxy's record can name the command; Java's properties carry none. Loopback stays
     * direct, so a test reaches the server it started.
     */
    public static void proxied(Map<String, String> env, int port, int command) {
        String url = "http://cz:" + command + "@127.0.0.1:" + port;
        for (String name : PROXY_NAMES) env.put(name, url);
        env.put("NO_PROXY", "localhost,127.0.0.1,::1");
        env.put("no_proxy", "localhost,127.0.0.1,::1");
        env.put("GIT_HTTP_PROXY_AUTHMETHOD", "basic");       // git waits to be asked for proxy credentials otherwise, and its tries go unnamed
        String java = "-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=" + port + " -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=" + port;
        String had = env.get("JAVA_TOOL_OPTIONS");
        env.put("JAVA_TOOL_OPTIONS", had == null || had.isBlank() ? java : had + " " + java);
    }

    /**
     * macOS, where a command's network is closed: Java opens IPv6 sockets and reaches an IPv4 address through its IPv4-mapped form
     * (::ffff:127.0.0.1), which macOS's sandbox does not count as this Mac's own. By trial on macOS 26.5 a Java server on 127.0.0.1
     * and its client got "java.net.SocketException: Operation not permitted", and so did Gradle's daemon, while with an IPv4 stack
     * they work; Java's way to the proxy on 127.0.0.1 is the same connection.
     */
    static void ipv4ForJava(Map<String, String> env) {
        String flag = "-Djava.net.preferIPv4Stack=true";
        String had = env.get("JAVA_TOOL_OPTIONS");
        if (had != null && had.contains(flag)) return;
        env.put("JAVA_TOOL_OPTIONS", had == null || had.isBlank() ? flag : had + " " + flag);
    }

    /** The first line of a Maven settings file CodeZaiku wrote, so that it is rewritten and a file of anyone else's is left alone. */
    static final String MAVEN_MARK = "<!-- written by CodeZaiku for the sandbox's closed network -->";

    /**
     * Maven 3.9's transport does not read Java's proxy properties (a trial: "Caused by: java.net.UnknownHostException:
     * repo.maven.apache.org"); its own way is a proxy in settings.xml, which it reads from the home the sandbox gives a command.
     * Written when absent or ours and different; a file the person or the job put there is left as it is.
     */
    public static void mavenSettings(Path home, int port) {
        Path f = home.resolve(".m2").resolve("settings.xml");
        String text = MAVEN_MARK + "\n<settings>\n  <proxies>\n"
                + "    <proxy><id>codezaiku-https</id><active>true</active><protocol>https</protocol><host>127.0.0.1</host><port>" + port + "</port>"
                + "<nonProxyHosts>localhost|127.0.0.1</nonProxyHosts></proxy>\n"
                + "    <proxy><id>codezaiku-http</id><active>true</active><protocol>http</protocol><host>127.0.0.1</host><port>" + port + "</port>"
                + "<nonProxyHosts>localhost|127.0.0.1</nonProxyHosts></proxy>\n"
                + "  </proxies>\n</settings>\n";
        try {
            if (Files.exists(f)) {
                String had = Files.readString(f);
                if (!had.startsWith(MAVEN_MARK) || had.equals(text)) return;
            }
            Files.createDirectories(f.getParent());
            Files.writeString(f, text);
        } catch (IOException | RuntimeException e) {
            // Maven then finds no proxy and fails on a closed network, as it would without this
        }
    }

    /**
     * Tool managers that find their toolchains under the home folder: the variable that says where, and the folder.
     * With {@code HOME} moved, rustup's {@code cargo} answered "no default toolchain" until RUSTUP_HOME named the real
     * folder; mise's shims the same with its data folder. The folders are read-only to the command.
     */
    static final String[][] MANAGERS = {
            {"RUSTUP_HOME", ".rustup"}, {"MISE_DATA_DIR", ".local/share/mise"}, {"MISE_CONFIG_DIR", ".config/mise"},
            {"SDKMAN_DIR", ".sdkman"}, {"NVM_DIR", ".nvm"}, {"PYENV_ROOT", ".pyenv"}, {"VOLTA_HOME", ".volta"}, {"ASDF_DATA_DIR", ".asdf"},
    };

    /** A value as one word of JAVA_TOOL_OPTIONS, which the JVM splits at spaces unless quoted. */
    private static String javaWord(String s) {
        return s.chars().anyMatch(Character::isWhitespace) ? "\"" + s + "\"" : s;
    }
}

package org.codezaiku.exec;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A git remote's address as CodeZaiku pushes to it, fetches from it and names its repository on GitHub: one of the forms git's
 * documentation lists for a server (urls(7): {@code ssh://[<user>@]<host>[:<port>]/<path>}, {@code git://}, {@code http[s]://}, and
 * the scp-like {@code [<user>@]<host>:<path>}), checked before it is ever handed to git.
 *
 * <p>Refused: a folder on this machine or {@code file://} (a push there runs that repository's hooks, outside the sandbox),
 * {@code <transport>::<address>} (git hands it to a helper program, {@code ext::} runs a command), ftp, a control character (git
 * 2.17.4 and 2.40.4 forbid newlines and control sequences in addresses after CVE-2020-5260, CVE-2024-50349 and CVE-2024-52006), and a
 * host, user or path that begins with a dash (git 2.7.6, CVE-2017-1000117: such a host became an option of ssh).
 */
public record GitAddress(String raw, String scheme, String user, boolean password, String host, int port, String path) {

    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?|\\[[0-9A-Fa-f:.]+\\]");
    private static final Pattern USER = Pattern.compile("[A-Za-z0-9._~%+!$&'()*,;=-]+");
    private static final Pattern HELPER = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*::");
    private static final Pattern SCHEME = Pattern.compile("^([A-Za-z][A-Za-z0-9+.-]*)://(.*)$", Pattern.DOTALL);

    /** What was refused and why, or the address. */
    public record Read(GitAddress address, String refused) { }

    /** The address, or why it is not one CodeZaiku pushes to or fetches from. */
    public static Read read(String raw) {
        if (raw == null || raw.isEmpty()) return no("it is empty");
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c < 0x20 || c == 0x7f) return no("it holds a control character");
        }
        if (raw.startsWith("-")) return no("it begins with a dash");
        if (HELPER.matcher(raw).find()) return no("git hands an address of the form <transport>::<address> to a helper program");
        for (int i = 0; i < raw.length(); i++) if (Character.isWhitespace(raw.charAt(i))) return no("it holds a space");
        Matcher m = SCHEME.matcher(raw);
        if (m.matches()) {
            String scheme = m.group(1).toLowerCase(Locale.ROOT);
            if (scheme.equals("file")) return no("it is a folder on this machine");
            if (!scheme.equals("https") && !scheme.equals("http") && !scheme.equals("ssh") && !scheme.equals("git"))
                return no("CodeZaiku uses https, http, ssh and git addresses only");
            String rest = m.group(2);
            int slash = rest.indexOf('/');
            if (slash <= 0) return no("it names no repository");
            String authority = rest.substring(0, slash), path = rest.substring(slash);
            String user = null;
            boolean password = false;
            int at = authority.lastIndexOf('@');
            if (at >= 0) {
                String info = authority.substring(0, at);
                authority = authority.substring(at + 1);
                int colon = info.indexOf(':');
                user = colon < 0 ? info : info.substring(0, colon);
                password = colon >= 0;
                if (user.isEmpty() || user.startsWith("-") || !USER.matcher(user).matches()) return no("its user name is not one git takes plainly");
            }
            String host = authority;
            int port = -1;
            int colon = authority.lastIndexOf(':');
            if (colon > 0 && authority.indexOf(']') < colon) {
                host = authority.substring(0, colon);
                String p = authority.substring(colon + 1);
                if (!p.matches("\\d{1,5}")) return no("its port is not a number");
                port = Integer.parseInt(p);
                if (port < 1 || port > 65535) return no("its port is not a port");
            }
            if (!HOST.matcher(host).matches()) return no("its host is not a name or an address");
            return checkPath(raw, scheme, user, password, host.toLowerCase(Locale.ROOT), port, path);
        }
        // the scp-like form, which git recognises "only if there are no slashes before the first colon"
        int colon = raw.indexOf(':');
        int slash = raw.indexOf('/');
        if (colon > 0 && (slash < 0 || slash > colon)) {
            String authority = raw.substring(0, colon), path = raw.substring(colon + 1);
            if (authority.length() == 1 && Character.isLetter(authority.charAt(0))) return no("it is a folder on this machine");
            String user = null;
            int at = authority.lastIndexOf('@');
            if (at >= 0) {
                user = authority.substring(0, at);
                authority = authority.substring(at + 1);
                if (user.isEmpty() || user.startsWith("-") || !USER.matcher(user).matches()) return no("its user name is not one git takes plainly");
            }
            if (!HOST.matcher(authority).matches() || authority.startsWith("[")) return no("its host is not a name");
            if (path.isEmpty() || path.startsWith("-")) return no(path.isEmpty() ? "it names no repository" : "its path begins with a dash");
            return checkPath(raw, "ssh", user, false, authority.toLowerCase(Locale.ROOT), -1, path);
        }
        return no("it is a folder on this machine");
    }

    private static Read checkPath(String raw, String scheme, String user, boolean password, String host, int port, String path) {
        String p = path.startsWith("/") ? path.substring(1) : path;
        if (p.isEmpty() || p.equals("/")) return no("it names no repository");
        if (p.startsWith("-")) return no("its path begins with a dash");
        return new Read(new GitAddress(raw, scheme, user, password, host, port, path), null);
    }

    private static Read no(String why) { return new Read(null, why); }

    /** The address as the person reads it: a password in it is replaced by stars. */
    public String shown() {
        if (!password) return raw;
        int schemeEnd = raw.indexOf("://") + 3;
        int at = raw.indexOf('@', schemeEnd);
        int colon = raw.indexOf(':', schemeEnd);
        return colon > 0 && colon < at ? raw.substring(0, colon + 1) + "***" + raw.substring(at) : raw;
    }

    /** The address with any password replaced by stars, for a raw value that may not be one CodeZaiku takes. */
    public static String shownRaw(String raw) {
        Read r = read(raw);
        if (r.address() != null) return r.address().shown();
        String t = raw == null ? "" : raw.replaceAll("[\\x00-\\x1f\\x7f]", "?");
        return t.replaceAll("(://[^/@:]*):[^/@]*@", "$1:***@");
    }

    /**
     * The repository as {@code host/path}, lower case, without a leading slash, a trailing slash or {@code .git}: what the setting
     * git.push lists. https://github.com/me/proj.git, git@github.com:me/proj and ssh://git@github.com/me/proj are all
     * {@code github.com/me/proj}.
     */
    public String repository() {
        String p = path.startsWith("/") ? path.substring(1) : path;
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        if (p.toLowerCase(Locale.ROOT).endsWith(".git")) p = p.substring(0, p.length() - 4);
        String h = host.equals("ssh.github.com") || host.equals("www.github.com") ? "github.com" : host;
        return (h + "/" + p).toLowerCase(Locale.ROOT);
    }

    /** A repository on GitHub, as gh names it. */
    public record GitHubRepo(String owner, String name) {
        @Override public String toString() { return owner + "/" + name; }
    }

    private static final Pattern GH_NAME = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9._-]{0,99})");

    /** The repository on github.com this address names, or null for any other host or a path that is not {@code owner/name}. */
    public GitHubRepo gitHub() {
        if (!host.equals("github.com") && !host.equals("www.github.com") && !host.equals("ssh.github.com")) return null;
        String p = path.startsWith("/") ? path.substring(1) : path;
        while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        if (p.toLowerCase(Locale.ROOT).endsWith(".git")) p = p.substring(0, p.length() - 4);
        String[] parts = p.split("/");
        if (parts.length != 2 || !GH_NAME.matcher(parts[0]).matches() || !GH_NAME.matcher(parts[1]).matches()) return null;
        return new GitHubRepo(parts[0], parts[1]);
    }

    /**
     * Whether a repository pattern of the setting git.push names this address: {@code host/owner/name}, where the owner or the name may
     * be {@code *}; compared in lower case, with a leading scheme, a trailing slash or {@code .git} in the pattern ignored.
     */
    public boolean listedBy(String pattern) {
        if (pattern == null) return false;
        String t = pattern.strip().toLowerCase(Locale.ROOT);
        int scheme = t.indexOf("://");
        if (scheme >= 0) t = t.substring(scheme + 3);
        int at = t.indexOf('@');
        if (at >= 0 && at < Math.max(t.indexOf('/'), 0)) t = t.substring(at + 1);
        while (t.endsWith("/")) t = t.substring(0, t.length() - 1);
        if (t.endsWith(".git")) t = t.substring(0, t.length() - 4);
        if (t.isEmpty() || t.startsWith("*")) return false;                    // a host is always named
        String[] want = t.split("/");
        String[] have = repository().split("/");
        if (want.length != have.length) return false;
        for (int i = 0; i < want.length; i++) {
            if (i == 0) { String h = want[0].replaceFirst(":\\d+$", ""); if (!h.equals(have[0].replaceFirst(":\\d+$", ""))) return false; continue; }
            if (!want[i].equals("*") && !want[i].equals(have[i])) return false;
        }
        return true;
    }

    /** The port to show and to compare with the network's rules: the one written, else the scheme's. */
    public int portOrDefault() {
        if (port > 0) return port;
        return switch (scheme) { case "https" -> 443; case "http" -> 80; case "git" -> 9418; default -> 22; };
    }
}

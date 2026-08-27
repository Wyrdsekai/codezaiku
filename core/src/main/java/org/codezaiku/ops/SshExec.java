package org.codezaiku.ops;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.codezaiku.Config;

/**
 * The box is a remote host reached over SSH — the product's real target: "point CodeZaiku at YOUR server."
 * Generalizes term_agent's local+SSH model (PLAN_CODEZAIKU_OPS.md §1).
 *
 * <p>Auth: key-based by default; if {@code CODEZAIKU_SSH_PASSWORD} is set we drive the prompt via
 * {@code sshpass}. Host-key policy is {@code accept-new} (first-connection trust, then pinned) so an
 * unattended run never hangs on an interactive {@code yes/no}.
 *
 * <p>The command is base64-wrapped ({@code echo <b64> | base64 -d | bash -l}) so ARBITRARY shell — quotes,
 * newlines, subshells — crosses the ssh arg boundary intact with zero escaping hazard, and the real remote
 * exit code is recovered from a {@code __CPEXIT:N__} sentinel (ssh's own exit would otherwise mask it).
 */
final class SshExec implements Exec {
    private static final Pattern EXIT = Pattern.compile("__CPEXIT:(-?\\d+)__");

    private final String userHost;   // "user@host" or "host"
    private final String port;       // nullable
    private final String password;   // nullable → key auth

    private SshExec(String userHost, String port) {
        this.userHost = userHost;
        this.port = port;
        String pw = Config.get("CODEZAIKU_SSH_PASSWORD");
        this.password = (pw == null || pw.isBlank()) ? null : pw;
    }

    /** Parse {@code [user@]host[:port]}. */
    static SshExec parse(String spec) {
        String userHost = spec;
        String port = null;
        int colon = spec.lastIndexOf(':');
        if (colon > 0 && spec.indexOf('@') < colon && spec.substring(colon + 1).matches("\\d+")) {
            userHost = spec.substring(0, colon);
            port = spec.substring(colon + 1);
        }
        return new SshExec(userHost, port);
    }

    @Override public String describe() { return "ssh " + userHost + (port != null ? ":" + port : ""); }

    @Override public Result run(String command, int timeoutSec) {
        String b64 = Base64.getEncoder().encodeToString(command.getBytes(StandardCharsets.UTF_8));
        String remote = "echo " + b64 + " | base64 -d | bash -l; echo __CPEXIT:$?__";
        List<String> argv = new ArrayList<>();
        if (password != null) { argv.add("sshpass"); argv.add("-p"); argv.add(password); }
        argv.add("ssh");
        argv.add("-o"); argv.add("StrictHostKeyChecking=accept-new");
        argv.add("-o"); argv.add("ConnectTimeout=10");
        argv.add("-o"); argv.add("LogLevel=ERROR");
        if (password == null) { argv.add("-o"); argv.add("BatchMode=yes"); }
        if (port != null) { argv.add("-p"); argv.add(port); }
        argv.add(userHost);
        argv.add(remote);
        Result raw = Procs.run(null, timeoutSec, argv.toArray(new String[0]));
        // Recover the remote exit code from the sentinel; strip it from the visible output.
        Matcher m = EXIT.matcher(raw.out());
        int exit = raw.exit();
        String out = raw.out();
        if (m.find()) {
            exit = Integer.parseInt(m.group(1));
            out = out.substring(0, m.start()) + out.substring(m.end());
            out = out.replaceAll("\\n\\s*$", "");
        }
        return new Result(exit, out);
    }

    @Override public String read(String path) {
        Result r = run("cat " + Procs.shq(path), 30);
        return r.ok() ? r.out() : null;
    }

    @Override public boolean isFile(String path) { return run("test -f " + Procs.shq(path), 15).ok(); }

    @Override public boolean isDir(String path) { return run("test -d " + Procs.shq(path), 15).ok(); }

    @Override public void write(String path, String content) {
        String b64 = Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));
        String parent = path.contains("/") ? path.substring(0, path.lastIndexOf('/')) : ".";
        Result r = run("mkdir -p " + Procs.shq(parent) + " && echo " + b64 + " | base64 -d > " + Procs.shq(path), 30);
        if (!r.ok()) throw new RuntimeException("ssh write failed for " + path + ": " + r.out());
    }
}

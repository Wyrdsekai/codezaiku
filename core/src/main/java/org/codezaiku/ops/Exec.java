package org.codezaiku.ops;

/**
 * Transport-agnostic command channel to ONE box. Ops mode operates on a real machine — local, over SSH,
 * or inside a container — through this single seam, so the loop, tools, and prechecks never know (or care)
 * which transport is underneath. This is the "point CodeZaiku at YOUR server" foundation
 * (PLAN_CODEZAIKU_OPS.md §1), the generalization of the container-only {@code ContainerExec} bridge.
 *
 * <p>Everything above this interface — prechecks, tools, the loop — sees only {@link #run}, {@link #read},
 * {@link #write}. Diagnosis is entirely {@link #run}/{@link #read}; only remediation writes.
 */
public interface Exec {

    /** A finished command: its merged stdout+stderr and its process exit code (-1 on transport failure). */
    record Result(int exit, String out) {
        public boolean ok() { return exit == 0; }
    }

    /** A short human label for the box this reaches ("local", "ssh <user>@<host>", "container abcd1234"). */
    String describe();

    /** Run a shell command ON THE BOX (through {@code bash -lc}), bounded by {@code timeoutSec}. */
    Result run(String command, int timeoutSec);

    /** Read a file's content from the box, or null if it does not exist / is unreadable. */
    String read(String path);

    /** True iff {@code path} is a regular file on the box. */
    boolean isFile(String path);

    /** True iff {@code path} is a directory on the box. */
    boolean isDir(String path);

    /** Write content to {@code path} on the box (creating parent dirs), replacing it whole. Remediation only. */
    void write(String path, String content);

    /**
     * Build the Exec for a target spec:
     * <pre>
     *   local                         → this machine
     *   ssh://[user@]host[:port]      → a remote host over ssh (key-based or cached password via sshpass)
     *   docker://&lt;container-id&gt;    → a container on this machine (docker exec)
     * </pre>
     * A bare host with no scheme is treated as {@code ssh://host}.
     */
    static Exec forTarget(String target) {
        if (target == null || target.isBlank() || target.equals("local")) {
            return new LocalExec();
        }
        if (target.startsWith("docker://")) {
            return new DockerExec(target.substring("docker://".length()));
        }
        if (target.startsWith("ssh://")) {
            return SshExec.parse(target.substring("ssh://".length()));
        }
        // bare "user@host" / "host" → ssh
        return SshExec.parse(target);
    }
}

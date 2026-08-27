package org.codezaiku.tools;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.codezaiku.Config;

/**
 * Env-gated bridge that routes the file + shell tools INTO a Docker container instead of the host
 * filesystem, so CodeZaiku's loop can operate on a Terminal-Bench (or any) task container from the host.
 * OFF by default — when {@code CODEZAIKU_EXEC_CONTAINER} is unset the whole class is inert and the tools
 * behave exactly as before (zero risk to the coding harness). When set, the shell tool runs commands via
 * {@code docker exec} in the container's workdir, and read/write/edit operate on container paths via
 * {@code docker exec cat} / {@code tee}.
 *
 * Config:
 *   CODEZAIKU_EXEC_CONTAINER = container id/name (activates this mode)
 *   CODEZAIKU_EXEC_WORKDIR   = working dir inside the container (default /app)
 */
public final class ContainerExec {
    private ContainerExec() { }

    private static final String CID = Config.get("CODEZAIKU_EXEC_CONTAINER");
    private static final String WORKDIR = orDefault(Config.get("CODEZAIKU_EXEC_WORKDIR"), "/app");

    public static boolean active() {
        return CID != null && !CID.isBlank();
    }

    public static String cid() { return CID; }
    public static String workdir() { return WORKDIR; }

    private static String orDefault(String v, String d) {
        return (v == null || v.isBlank()) ? d : v;
    }

    /** Resolve a tool-relative path to an absolute container path (absolute paths pass through). */
    public static String path(String rel) {
        if (rel == null || rel.isBlank()) return WORKDIR;
        return rel.startsWith("/") ? rel : WORKDIR + "/" + rel;
    }

    /** Read a file's content from the container, or null if it does not exist / is unreadable. */
    public static String read(String rel) {
        Result r = run(null, "docker", "exec", CID, "cat", path(rel));
        return r.exit == 0 ? r.out : null;
    }

    /** True if the path exists as a regular file in the container. */
    public static boolean isFile(String rel) {
        return run(null, "docker", "exec", CID, "test", "-f", path(rel)).exit == 0;
    }

    /** True if the path is a directory in the container. */
    public static boolean isDir(String rel) {
        return run(null, "docker", "exec", CID, "test", "-d", path(rel)).exit == 0;
    }

    /** Write content to a file in the container (creating parent dirs), replacing it whole. */
    public static void write(String rel, String content) {
        String p = path(rel);
        String parent = p.contains("/") ? p.substring(0, p.lastIndexOf('/')) : WORKDIR;
        run(null, "docker", "exec", CID, "mkdir", "-p", parent);
        // Pipe content to `tee <path>` inside the container (handles arbitrary bytes without shell escaping).
        run(content, "docker", "exec", "-i", CID, "sh", "-c", "cat > " + shq(p));
    }

    private static String shq(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    public record Result(int exit, String out) { }

    /** Run a host process (docker ...), optionally feeding {@code stdin}, capturing merged stdout+stderr. */
    public static Result run(String stdin, String... argv) {
        try {
            ProcessBuilder pb = new ProcessBuilder(argv).redirectErrorStream(true);
            Process proc = pb.start();
            if (stdin != null) {
                try (OutputStream os = proc.getOutputStream()) {
                    os.write(stdin.getBytes(StandardCharsets.UTF_8));
                }
            } else {
                proc.getOutputStream().close();
            }
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            proc.getInputStream().transferTo(buf);
            int exit = proc.waitFor();
            return new Result(exit, buf.toString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new Result(-1, "ContainerExec error: " + e.getMessage());
        }
    }
}

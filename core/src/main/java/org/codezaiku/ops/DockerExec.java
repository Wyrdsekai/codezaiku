package org.codezaiku.ops;

/**
 * The box is a container on this machine. Commands run via {@code docker exec}; files via
 * {@code docker exec cat} / {@code tee}. This is the transport used by the diagnosis eval — each fault
 * scenario is a container the ops loop connects to (PLAN_CODEZAIKU_OPS.md §7).
 */
final class DockerExec implements Exec {
    private final String cid;

    DockerExec(String cid) { this.cid = cid; }

    @Override public String describe() { return "container " + cid; }

    @Override public Result run(String command, int timeoutSec) {
        return Procs.run(null, timeoutSec, "docker", "exec", cid, "bash", "-lc", command);
    }

    @Override public String read(String path) {
        Result r = Procs.run(null, 30, "docker", "exec", cid, "cat", path);
        return r.ok() ? r.out() : null;
    }

    @Override public boolean isFile(String path) {
        return Procs.run(null, 15, "docker", "exec", cid, "test", "-f", path).ok();
    }

    @Override public boolean isDir(String path) {
        return Procs.run(null, 15, "docker", "exec", cid, "test", "-d", path).ok();
    }

    @Override public void write(String path, String content) {
        String parent = path.contains("/") ? path.substring(0, path.lastIndexOf('/')) : ".";
        Procs.run(null, 15, "docker", "exec", cid, "mkdir", "-p", parent);
        Result r = Procs.run(content, 30, "docker", "exec", "-i", cid, "sh", "-c", "cat > " + Procs.shq(path));
        if (!r.ok()) throw new RuntimeException("docker write failed for " + path + ": " + r.out());
    }
}

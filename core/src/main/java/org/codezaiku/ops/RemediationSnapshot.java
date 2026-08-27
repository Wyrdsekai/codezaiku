package org.codezaiku.ops;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * The R3 SAFETY guardrail — snapshot-before-remediate, rollback-on-failed-verify (PLAN §6, the design's R3
 * authority rung). A gated remediation MUTATES the target service, and a wrong fix can DESTROY it: measured
 * on cp-refstack, a model "fixing" a read-only postgres appended SQL into {@code postgresql.conf} and
 * crash-looped the container. The blast-radius guard protects BYSTANDERS; this protects the TARGET itself.
 *
 * <p>Contract: {@link #capture} the root service's restorable state BEFORE remediation; run the fix; if the
 * objective verify FAILS (or the container is dead), {@link #rollback} to the captured state and re-verify.
 * A destructive fix that fails is thus UNDONE, not left bricking the service — the precondition for letting
 * the loop auto-remediate config-mutating faults unattended.
 *
 * <p>Mechanism (works for any compose service): tar each named volume to a host backup dir, and record the
 * container's compose service + file. Rollback restores the volume tars and force-recreates the service from
 * compose — which brings back a crashed container AND reverts config/data corruption living in the volume.
 */
public final class RemediationSnapshot {
    private static final Logger log = LoggerFactory.getLogger(RemediationSnapshot.class);
    private static final String BACKUP_DIR = "/tmp/cp-rollback";

    private final Exec exec;

    public RemediationSnapshot(Exec exec) { this.exec = exec; }

    /** A captured pre-remediation state. {@code ok}=false means capture failed (no safe rollback available). */
    public record Snap(String container, String service, String composeFile,
                       List<String> volumeBackups, boolean ok) { }

    /** volume-name → destination, for the container's NAMED volumes (bind mounts are the user's own files). */
    private List<String[]> namedVolumes(String container) {
        List<String[]> vols = new ArrayList<>();
        Exec.Result r = exec.run("docker inspect " + container
                + " --format '{{range .Mounts}}{{if eq .Type \"volume\"}}{{.Name}}|{{.Destination}}\\n{{end}}{{end}}'", 15);
        if (r.ok()) {
            for (String line : r.out().split("\n")) {
                String[] p = line.trim().split("\\|");
                if (p.length == 2 && !p[0].isBlank()) vols.add(p);
            }
        }
        return vols;
    }

    /**
     * Snapshot the root service before a mutating remediation: tar each named volume to {@link #BACKUP_DIR}.
     * The compose service + file (from the container's compose labels) let rollback force-recreate it.
     */
    public Snap capture(String container) {
        String service = exec.run("docker inspect " + container
                + " --format '{{index .Config.Labels \"com.docker.compose.service\"}}'", 10).out().trim();
        String composeFile = exec.run("docker inspect " + container
                + " --format '{{index .Config.Labels \"com.docker.compose.project.config_files\"}}'", 10).out().trim();
        exec.run("mkdir -p " + BACKUP_DIR, 10);
        List<String> backups = new ArrayList<>();
        boolean ok = true;
        List<String[]> vols = namedVolumes(container);
        // CRASH-CONSISTENCY: freeze the container's processes (SIGSTOP) while we tar its volumes. Tarring a
        // LIVE database volume captures a torn, mid-write state — restoring it left postgres unable to find a
        // valid checkpoint and it PANICked on start. `docker pause` makes the on-disk snapshot equivalent to a
        // clean power-off, which the engine recovers from via its WAL. Always unpause, even on error.
        boolean paused = !vols.isEmpty() && exec.run("docker pause " + container + " 2>&1", 20).ok();
        try {
            for (String[] v : vols) {
                String vol = v[0];
                String tar = BACKUP_DIR + "/" + vol + ".tgz";
                // tar the volume via a throwaway helper container (the volume may not be mountable on the host).
                Exec.Result r = exec.run("docker run --rm -v " + vol + ":/src:ro -v " + BACKUP_DIR
                        + ":/bak alpine tar czf /bak/" + vol + ".tgz -C /src . 2>&1", 120);
                if (r.ok()) { backups.add(vol + "|" + tar); }
                else { ok = false; log.warn("snapshot: volume {} backup failed: {}", vol, r.out()); }
            }
        } finally {
            if (paused) exec.run("docker unpause " + container + " 2>&1", 20);
        }
        Snap s = new Snap(container, service, composeFile, backups, ok && !composeFile.isBlank());
        System.out.println("ops: R3 snapshot " + (s.ok() ? "captured" : "PARTIAL/failed") + " — "
                + backups.size() + " volume(s), service=" + service);
        log.info("R3 snapshot: container={} service={} composeFile={} volumes={} ok={}",
                container, service, composeFile, backups.size(), s.ok());
        return s;
    }

    /**
     * Restore the captured state: overwrite each volume from its tar, then force-recreate the service from
     * compose (brings back a crashed container and reverts volume-resident corruption). Returns true on a
     * clean restore+restart.
     */
    public boolean rollback(Snap s) {
        if (s == null || !s.ok()) {
            System.out.println("ops: R3 rollback SKIPPED — no valid snapshot (capture had failed)");
            return false;
        }
        System.out.println("ops: R3 ROLLBACK — restoring " + s.volumeBackups().size()
                + " volume(s) and recreating service '" + s.service() + "'");
        for (String vb : s.volumeBackups()) {
            String[] p = vb.split("\\|");
            String vol = p[0];
            // wipe the (corrupted) volume and restore the tar — same throwaway-helper pattern.
            Exec.Result r = exec.run("docker run --rm -v " + vol + ":/dst -v " + BACKUP_DIR
                    + ":/bak alpine sh -c 'rm -rf /dst/* /dst/.[!.]* /dst/..?* 2>/dev/null; "
                    + "tar xzf /bak/" + vol + ".tgz -C /dst' 2>&1", 120);
            if (!r.ok()) { log.warn("rollback: restore of {} failed: {}", vol, r.out()); return false; }
        }
        // CLEAR A FOREIGN CONTAINER HOLDING THE NAME. A fix is free to `docker rm` the service and
        // `docker run` its own replacement — measured on the refstack, the model did exactly that. The
        // replacement keeps the NAME but is not compose-managed, so `compose up` dies on a name conflict
        // ("Conflict. The container name is already in use"), the fallback restarts the impostor, and the
        // service comes back on the default bridge with no compose network — the app could no longer
        // resolve redis:6379 and the stack stayed broken through every later run. Removing it is safe
        // precisely BECAUSE it is not the container we snapshotted: the real one is already gone.
        String label = exec.run("docker inspect " + s.container()
                + " --format '{{index .Config.Labels \"com.docker.compose.service\"}}' 2>/dev/null", 10)
                .out().trim();
        boolean exists = !label.isEmpty() || exec.run("docker inspect " + s.container()
                + " --format '{{.Id}}' 2>/dev/null", 10).ok();
        if (exists && !label.equals(s.service())) {
            log.warn("rollback: '{}' is not the compose service we snapshotted (label='{}', expected '{}')"
                    + " — the fix replaced it; removing so compose can recreate the real one",
                    s.container(), label, s.service());
            exec.run("docker rm -f " + s.container() + " 2>&1", 60);
        }

        // Recreate the service from compose (its original image + the restored volumes) — this also revives a
        // container the fix left crashed/exited. --force-recreate replaces the current (broken) container.
        // MULTI-FILE PROJECTS: compose records `project.config_files` as a COMMA-SEPARATED list, and
        // `docker-compose.yml` + `docker-compose.override.yml` is the default convention, so this is the
        // common case rather than an exotic one. Passing the whole string to a single -f names a path
        // that does not exist, compose fails, and nothing is recreated — measured, and made WORSE by the
        // impostor removal above: the old code at least left the (broken) container in place, whereas
        // now the service ends up deleted outright. Each file needs its own -f, in order.
        StringBuilder fs = new StringBuilder();
        for (String f : s.composeFile().split(",")) {
            if (!f.isBlank()) fs.append(" -f ").append(f.trim());
        }
        Exec.Result up = exec.run("docker compose" + fs
                + " up -d --force-recreate " + s.service() + " 2>&1", 120);
        if (!up.ok()) {
            // Fallback: just restart the container in place (volume already restored).
            exec.run("docker restart " + s.container() + " 2>&1", 60);
        }

        // VERIFY THE RESTORE, and report honestly if it did not take. This method used to return true
        // unconditionally — so a rollback that left the service on the wrong network, or did not restore it
        // at all, still printed "R3 rollback applied" and told every caller the target was safe. R3 is the
        // guarantee the whole guarded rung rests on; it must not be the thing that lies.
        boolean restored = attachedToComposeNetwork(s);
        System.out.println("ops: R3 rollback " + (restored
                ? "applied (" + (up.ok() ? "recreated" : "restarted") + ")"
                : "FAILED — '" + s.container() + "' is not back on its compose network; the service is "
                        + "NOT restored and needs a human"));
        return restored;
    }

    /**
     * Whether the service is back as its compose self: the right compose-service label AND at least one
     * network that is not the default bridge.
     *
     * <p>Both halves are needed. The label alone passes for a container compose has adopted but not
     * networked; a network check alone passes for the impostor if it happens to be attached. What broke the
     * refstack was precisely a container that looked "Up" and healthy while being reachable by nobody.
     */
    private boolean attachedToComposeNetwork(Snap s) {
        Exec.Result r = exec.run("docker inspect " + s.container()
                + " --format '{{index .Config.Labels \"com.docker.compose.service\"}}|"
                + "{{range $k,$v := .NetworkSettings.Networks}}{{$k}} {{end}}' 2>/dev/null", 15);
        if (!r.ok()) return false;
        String[] parts = r.out().trim().split("\\|", 2);
        if (parts.length < 2 || !parts[0].trim().equals(s.service())) return false;
        for (String net : parts[1].trim().split("\\s+")) {
            if (!net.isBlank() && !net.equals("bridge") && !net.equals("host") && !net.equals("none")) {
                return true;
            }
        }
        return false;
    }

    /** Remove the backup tars once the outcome is settled (fix verified, or rollback done). */
    public void cleanup(Snap s) {
        if (s == null) return;
        for (String vb : s.volumeBackups()) {
            exec.run("rm -f " + vb.split("\\|", 2)[1], 10);
        }
    }
}

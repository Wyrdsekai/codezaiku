package org.codezaiku.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.codezaiku.tools.Tool;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The single read-only investigation affordance: run a shell command ON THE BOX through {@link Exec} and
 * return its raw output. Mutating commands are rejected ({@link OpsSafety}) so diagnosis can't change what
 * it measures. Output is capped so one chatty command can't blow the 9B's context (HolmesGPT's oversized-
 * result rejection, PLAN_CODEZAIKU_OPS.md §5).
 */
public final class OpsShellTool implements Tool {
    private final Exec exec;
    private final int maxChars;
    private final int timeoutSec;
    private final boolean readOnly;   // diagnosis = read-only; remediation lifts the guard (gated phase)
    // BLAST-RADIUS scope (remediation only): container/service names the fix must NOT touch — every stack
    // member EXCEPT the localized root. A docker verb that reaches one is rejected, so a fix can only affect
    // the one service it was scoped to. This is the harm guard the refstack R4 gate measured: a remediation
    // that "fixes" the incident by restarting a healthy bystander is a regression, not a fix.
    private Set<String> forbidden = Set.of();
    private String allowedLabel = "";

    public OpsShellTool(Exec exec) { this(exec, 6000, 30, true); }

    public OpsShellTool(Exec exec, int maxChars, int timeoutSec) { this(exec, maxChars, timeoutSec, true); }

    public OpsShellTool(Exec exec, int maxChars, int timeoutSec, boolean readOnly) {
        this.exec = exec;
        this.maxChars = maxChars;
        this.timeoutSec = timeoutSec;
        this.readOnly = readOnly;
    }

    /** Restrict docker actions to the localized root: {@code allowed} is what the fix MAY touch (container +
     *  service name); {@code others} are the bystanders it may not. No-op when {@code others} is empty. */
    public OpsShellTool scopeTo(String allowed, Set<String> others) {
        this.forbidden = (others == null) ? Set.of() : others;
        this.allowedLabel = allowed == null ? "" : allowed;
        return this;
    }

    // HOST-FILESYSTEM WRITE guard (scoped remediation only). A container-scoped fix must act INSIDE the
    // target via `docker exec <target> ...`, never edit host files: the model corrupted postgresql.conf,
    // neo4j.conf, and — worst — the compose file itself via host `sed -i` / `echo >>`, which the docker-only
    // blast-radius guard never saw (and which broke R3 rollback's own `docker compose up`). Commands that act
    // through a container/cluster/API (docker/kubectl/curl) are fine — a bystander is already blocked by name.
    //
    // THIS IS A DENYLIST AND CANNOT BE COMPLETE. It covers the shapes a goal-directed model actually
    // reaches for — measured, one deleted the compose file defining the fault it was asked to fix — and a
    // probe of the obvious siblings then found four more (`find -delete`, `xargs rm`, `install`, an
    // interpreter one-liner), all since added. More remain: busybox applets, a here-doc script, a compiled
    // helper. Treat this as defence in depth against a model taking the easy path, NOT as a boundary that
    // holds against one determined to get around it. The guarantees that do hold are elsewhere:
    // blast-radius scoping, R3 rollback, and the harm check.
    private String rejectHostWrite(String cmd) {
        if (forbidden.isEmpty()) return null;                         // only guard a scoped (localized) fix
        String c = cmd.trim();
        if (c.matches("(?s)^(sudo\\s+|env\\s+\\S+=\\S+\\s+)?(docker|kubectl|curl)\\b.*")) return null;  // via container/API
        boolean hostWrite = c.matches("(?s).*(>>?\\s*/(?!dev/|tmp/|proc/)).*")                 // redirect to a real host path
                || c.matches("(?s).*\\b(sed|perl)\\b[^|]*-i\\b.*")                             // in-place edit (sed -i / perl -i)
                || c.matches("(?s).*(^|\\s|\\|)\\s*(tee|dd)\\b.*")
                // The path must be ANCHORED at a token boundary — `([^|/]*\\s)?/` — so optional flags may
                // precede it but nothing may swallow its leading slash. The previous form demanded an
                // intervening token (`[^|]*\\s/`), so `rm -f /etc/x` was caught while a bare `rm /etc/x`
                // sailed through; measured, the model deleted the compose override that DEFINED the fault
                // it was asked to fix, in exactly that single-space form. Dropping the `\\s` outright is
                // wrong the other way: `[^|]*` then swallows the leading `/tmp` and matches an INNER
                // slash, so the scratch-path exemption stops applying and `rm /tmp/probe.out` is blocked.
                || destructiveOnAnyArg(c)
                || copiesOntoAHostPath(c)
                // `find <host path> ... -delete` / `-exec rm`: reaches the same files without naming a
                // destructive verb first, so the leading-verb form above never sees it.
                || c.matches("(?s).*\\bfind\\s+/(?!dev/|tmp/|proc/)\\S*.*(-delete|-exec\\s+(rm|mv|truncate)\\b).*")
                // an interpreter one-liner doing the deletion in-process, e.g.
                // `python3 -c "import os; os.remove('/etc/redis/redis.conf')"`.
                || c.matches("(?s).*\\b(python3?|perl|ruby|node)\\b.*(-c|-e)\\s.*"
                           + "(remove|unlink|rmtree|truncate|rename)\\s*\\(.*/(?!dev/|tmp/|proc/)\\S+.*")
                // `... | xargs rm`, where the paths arrive on stdin and are invisible to a path match.
                || c.matches("(?s).*\\bxargs\\s+(-\\S+\\s+)*(rm|rmdir|mv|truncate|shred)\\b.*");
        if (hostWrite) {
            System.out.println("[host-write] BLOCKED (scope=" + allowedLabel + "): " + cmd);
            return "blocked: this edits the HOST filesystem (config files, or the compose file). Fix the '"
                 + allowedLabel + "' service from INSIDE its container — `docker exec " + allowedLabel
                 + " ...` — or via its API. Do not edit host files or the compose file.";
        }
        return null;
    }


    /** A path on the HOST we protect — not a scratch or device path. */
    private static boolean hostPath(String tok) {
        return tok.startsWith("/") && !tok.startsWith("/dev/") && !tok.startsWith("/tmp/")
                && !tok.startsWith("/proc/") && !tok.equals("/dev/null");
    }

    /** Verbs that damage EVERY path they name — `mv` included, since it removes its source. */
    private static boolean destructiveOnAnyArg(String c) {
        if (!c.matches("(?s).*(^|\\s|\\|)\\s*(rm|rmdir|mv|truncate|shred|chmod|chown)\\s+.*")) return false;
        for (String tok : c.split("[\\s'\"]+")) if (hostPath(tok)) return true;
        return false;
    }

    /**
     * Verbs whose DESTINATION is what matters: `cp`, `install`, `ln` leave the source alone, so
     * `cp /etc/redis/redis.conf /tmp/backup` is a BACKUP and must stay allowed — blocking it was a false
     * positive the audit caught. Only the last path argument is a target.
     */
    private static boolean copiesOntoAHostPath(String c) {
        if (!c.matches("(?s).*(^|\\s|\\|)\\s*(cp|install|ln)\\s+.*")) return false;
        String last = "";
        for (String tok : c.split("[\\s'\"]+")) if (!tok.isBlank()) last = tok;
        return hostPath(last);
    }

    // A docker command that reaches a forbidden container (exec into it, or change its state) is out of the
    // blast radius. Read-only host commands are unaffected; the guard only fires on `docker ... <bystander>`.
    private String rejectIfOutOfScope(String cmd) {
        if (forbidden.isEmpty()) return null;
        String low = cmd.toLowerCase(Locale.ROOT);
        // DO NOT gate on the word "docker". That is how the guard was written and it left the promise
        // ("blast radius = 1") holding only against docker verbs: an audit found `podman restart api`,
        // `systemctl restart api`, `pkill -f api`, `kill -9 $(pgrep -f api)`, `nsenter ... kill 1` and
        // `curl -XPOST http://api/shutdown` all reaching a bystander untouched. The engine a fix happens
        // to use is not what makes it out of scope — touching another service is.
        //
        // So: any command that would CHANGE something and names a bystander is out of scope, plus a
        // write-shaped HTTP call aimed at one (curl is not a mutating verb by itself, and a POST to a
        // bystander's admin endpoint is exactly the kind of reach this guard exists to stop). Read-only
        // commands that merely mention a bystander stay allowed, which is what keeps `docker ps` and
        // `... | grep api` working.
        // PID-BASED REACH. A fix can name a bystander by PID rather than by name —
        // `nsenter -t 1234 -n -- kill 1`, `kill -9 1234` — and a name-scoped guard sees nothing. Rather
        // than resolve every PID (a docker inspect per container, per command, on the hot path), refuse
        // the small set of verbs whose whole purpose is to act on a process by NUMBER. A scoped fix has
        // no business doing that: it acts on its own service through docker/systemctl/the service's API,
        // all of which name what they touch and are therefore checkable.
        if (low.matches("(?s).*(^|\\s|\\|)\\s*(nsenter|kill|pkill|killall|renice|prlimit)\\s+.*\\b\\d{2,}\\b.*")
                && !low.matches("(?s).*\\bdocker\\b.*")) {
            System.out.println("[blast-radius] BLOCKED (acts on a PID, scope=" + allowedLabel + "): " + cmd);
            return "blocked: this acts on a process by PID, which cannot be checked against the blast "
                 + "radius — a number does not say which service it belongs to. Act on '" + allowedLabel
                 + "' by name instead (`docker restart " + allowedLabel + "`, `docker exec "
                 + allowedLabel + " ...`, or its own API).";
        }
        boolean writeShapedHttp = low.matches("(?s).*\\bcurl\\b.*")
                && low.matches("(?s).*(-x\\s*(post|put|delete|patch)|--request\\s+(post|put|delete|patch)"
                             + "|--data\\b|-d\\s).*");
        if (!low.matches("(?s).*\\bdocker\\b.*") && !OpsSafety.isMutating(cmd) && !writeShapedHttp) return null;
        for (String f : forbidden) {
            if (f.isBlank()) continue;
            if (low.matches("(?s).*\\b" + Pattern.quote(f.toLowerCase(Locale.ROOT)) + "\\b.*")) {
                // Log to stdout too — a blocked bystander action must be VISIBLE in the run log, not only in
                // the model's observation, or the guardrail's effect can't be verified after the fact.
                System.out.println("[blast-radius] BLOCKED (touches '" + f + "', scope=" + allowedLabel + "): " + cmd);
                return "blocked: this command touches '" + f + "', outside the blast radius. The diagnosed "
                     + "root cause is '" + allowedLabel + "' — apply the fix to THAT service only (e.g. "
                     + "`docker exec " + allowedLabel + " ...` or `docker restart " + allowedLabel + "`).";
            }
        }
        return null;
    }

    @Override public String name() { return "run_command"; }

    @Override public String description() {
        return readOnly
             ? "Run a READ-ONLY shell command on the box being diagnosed and return its output (stdout+stderr, "
             + "exit code). Use for investigation: systemctl status, journalctl, df, ss, ps, cat a log/config, "
             + "nginx -t, openssl x509. Commands that change the system are rejected in this phase."
             : "Run a shell command on the box to APPLY or VERIFY a fix (this is the approved remediation "
             + "phase — mutation is allowed). Make the smallest change that fixes the diagnosed cause, then "
             + "run a command that CONFIRMS the incident is resolved.";
    }

    @Override public ObjectNode parametersSchema(ObjectMapper j) {
        ObjectNode p = j.createObjectNode();
        p.put("type", "object");
        ObjectNode props = p.putObject("properties");
        props.putObject("command").put("type", "string")
                .put("description", "The shell command to run, e.g. 'systemctl status nginx' or 'df -h'.");
        p.putArray("required").add("command");
        return p;
    }

    @Override public String execute(JsonNode args) {
        String cmd = args.path("command").asText("").trim();
        if (cmd.isBlank()) return "ERROR: empty command";
        // ACI robustness: this shell is NON-INTERACTIVE, so interactive flags fail with "Unable to use a
        // TTY" and waste the whole turn (observed repeatedly on AIOpsLab: kubectl exec -it, docker exec -it).
        // Strip the flag — the command still does exactly what the model intended. Measured lever: the same
        // strip in the benchmark adapter moved the mitigation baseline 4/10 -> 7/10 (with more steps).
        cmd = cmd.replaceAll("((?:kubectl|docker)\\s+exec)\\s+-(?:it|ti)\\b", "$1")
                 .replaceAll("((?:kubectl|docker)\\s+exec)\\s+-i\\s+-t\\b", "$1");
        if (readOnly) {
            // TWO HALVES. The patterns answer "is this particular use a mutation"; the allowlist answers
            // "is this a kind of command diagnosis runs at all". Only the second can close the open tail
            // — a busybox applet, a compiled helper, a tool nobody thought to forbid — which is what an
            // audit by claim showed a denylist structurally cannot reach. Measured against 112 real
            // commands from actual runs, the allowlist refuses NONE of them, so it costs recon nothing.
            String reject = OpsSafety.rejectIfMutating(cmd);
            if (reject == null) reject = OpsSafety.rejectIfNotARead(cmd);
            if (reject != null) return reject + "\n(this is the diagnosis phase — investigate read-only; remediation is a separate approved step)";
        } else {
            String scope = rejectIfOutOfScope(cmd);
            if (scope != null) return scope;
            // The same allowlist idea as the read-only phase, with fix verbs added. It cannot judge a
            // USE here — `docker rm` is both a fix and a way to destroy the service, and the scope/
            // host-write/PID rules above are what judge uses — but it closes the same open tail.
            String notAFix = OpsSafety.rejectIfNotAFix(cmd);
            if (notAFix != null) {
                System.out.println("[allowlist] BLOCKED (scope=" + allowedLabel + "): " + cmd);
                return notAFix;
            }
            String hostWrite = rejectHostWrite(cmd);
            if (hostWrite != null) return hostWrite;
        }
        Exec.Result r = exec.run(cmd, timeoutSec);
        String out = r.out() == null ? "" : r.out();
        boolean truncated = out.length() > maxChars;
        if (truncated) out = out.substring(0, maxChars) + "\n… [output truncated at " + maxChars
                + " chars — narrow the command (grep/tail/head) to see the rest]";
        return "$ " + cmd + "\n[exit " + r.exit() + "]\n" + (out.isBlank() ? "(no output)" : out);
    }
}

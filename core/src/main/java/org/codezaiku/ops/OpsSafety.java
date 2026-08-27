package org.codezaiku.ops;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps the DIAGNOSIS phase read-only (opensre's {@code side_effect_level}, PLAN_CODEZAIKU_OPS.md §2):
 * a command that would mutate the box is rejected before it runs, so investigation can never accidentally
 * change what it is measuring. Remediation lifts this (gated + approved) — a separate phase.
 *
 * <p>Deliberately conservative and simple: a small denylist of mutating leading verbs + a guard on output
 * redirection to a real path. Not a security sandbox (the box is the user's own); a guardrail against the
 * model "fixing" things mid-diagnosis.
 */
final class OpsSafety {
    private OpsSafety() { }

    // Mutating command names (matched as a leading verb of any pipe/;/&&-separated segment).
    private static final Pattern MUTATOR = Pattern.compile(
            "^(rm|rmdir|mv|dd|mkfs\\S*|shred|truncate|chmod|chown|chgrp|ln|"
          + "kill|pkill|killall|reboot|shutdown|halt|poweroff|"
          + "apt|apt-get|yum|dnf|zypper|pacman|snap|"
          + "useradd|userdel|usermod|passwd|"
          + "iptables|nft|ufw|"
          + "crontab|mount|umount|swapoff|swapon|"
          + "tee|install)\\b");
    // systemctl / service state changes (status/is-active/is-failed/show/list are fine).
    private static final Pattern SVC_MUT = Pattern.compile(
            "\\b(systemctl|service)\\b.*\\b(start|stop|restart|reload|enable|disable|mask|unmask|kill)\\b");
    // package-manager installs even when not the leading verb (pip install, npm i, gem install, cargo install).
    private static final Pattern PKG_MUT = Pattern.compile(
            "\\b(pip3?|npm|pnpm|yarn|gem|cargo|go|apk|brew)\\b.*\\b(install|add|i|remove|uninstall|update|upgrade)\\b");
    // docker/compose STATE changes only. Read-only verbs (ps/logs/inspect/top/stats/events/config) and
    // `docker exec` (needed to read inside a service) are allowed. podman and nerdctl are the same CLI
    // surface under a different name — an audit found `podman restart <bystander>` reaching a service
    // the blast-radius guard was meant to protect, purely because the guard keyed on the word "docker".
    private static final Pattern DOCKER_MUT = Pattern.compile(
            "\\b(docker|podman|nerdctl)\\s+(compose\\s+)?"
          + "(run|start|stop|restart|rm|kill|pause|unpause|create|build|up|down|recreate)\\b");
    // in-place edits and redirection to a real (non-/dev, non-/tmp-scratch) absolute path.
    private static final Pattern INPLACE = Pattern.compile("\\bsed\\b[^|]*\\s-i\\b|\\bperl\\b[^|]*\\s-i\\b");
    private static final Pattern REDIR = Pattern.compile(">>?\\s*(/(?!dev/|tmp/|proc/))\\S+");
    // SERVICE-INTERNAL writes issued through `docker exec <svc> <cli> …`: docker exec is allowed (to READ
    // inside a service), but an engine CLI that MUTATES state must be blocked in the read-only diagnosis/recon
    // phase — else "read-only" recon can apply a fix (measured: it ran `redis-cli CONFIG SET maxmemory 1gb`),
    // which would let a PROPOSE-rung run mutate. Unambiguous mutating verbs only (reads like CONFIG GET,
    // SELECT, list_*, GET/_search stay allowed). The remediation phase (not read-only) is unaffected.
    private static final Pattern SVC_WRITE = Pattern.compile(
            "\\bconfig\\s+set\\b|\\bconfig\\s+rewrite\\b|\\bflushall\\b|\\bflushdb\\b|\\bshutdown\\b"                  // redis/valkey
          + "|\\b(drop|truncate)\\s+(table|database|index|schema|role|user)\\b|\\bdelete\\s+from\\b"    // SQL DDL/DML
          + "|\\balter\\s+(database|table|role|user|system)\\b|\\bcreate\\s+(database|role|user)\\b"    // SQL
          + "|\\bset_permissions\\b|\\bclear_permissions\\b|\\bset_disk_free_limit\\b|\\bset_user_tags\\b"  // rabbitmq
          + "|\\b(delete|purge)_queue\\b|\\bdetach\\s+delete\\b|\\bcypher-shell\\b[^|]*\\b(create|merge|set|delete)\\b",  // rabbitmq/neo4j
            Pattern.CASE_INSENSITIVE);

    // Mutating verbs BEHIND `docker exec` / `kubectl exec`: exec itself is allowed (reading inside a
    // service is how diagnosis works), and the leading-verb check sees only "docker" — so
    // `docker exec <c> kill -9 7` sailed through every guard and KILLED a bystander's main process
    // during a mislocalized read-only recon (measured: neo4j down, Exited 137, battery aborted; the
    // sibling `docker restart` in the same run was correctly blocked). Same verb list as MUTATOR, plus
    // in-container service controls.
    private static final Pattern EXEC_MUT = Pattern.compile(
            "\\b(docker|kubectl)\\s+exec\\b[^|;&]*\\s"
          + "(kill|pkill|killall|rm|rmdir|mv|dd|chmod|chown|truncate|shred|mkfs\\S*|tee|install|"
          + "reboot|halt|shutdown|poweroff|supervisorctl|nginx\\s+-s\\s+(stop|quit)|sv\\s+(down|stop))\\b");

    // Reaching the same files WITHOUT a destructive leading verb: `find <path> -delete`, `| xargs rm`,
    // and an interpreter one-liner doing it in-process. Found by auditing the guard against its CLAIM
    // rather than its verb list; each of these changed the box during a phase that must not.
    private static final Pattern INDIRECT_DELETE = Pattern.compile(
            "\\bfind\\b[^|]*\\s(-delete|-exec\\s+(rm|mv|truncate)\\b)"
          + "|\\bxargs\\s+(-\\S+\\s+)*(rm|rmdir|mv|truncate|shred)\\b"
          + "|\\b(python3?|perl|ruby|node)\\b[^|]*(-c|-e)\\s[^|]*\\b(remove|unlink|rmtree|rename|truncate)\\s*\\(",
            Pattern.CASE_INSENSITIVE);

    // A WRITE over HTTP. PUT/DELETE/PATCH are mutating by definition. POST is NOT — `_search` is a POST
    // and diagnosis depends on it — so POST is judged by the endpoint it targets. Measured: the refstack
    // injects its own opensearch fault with `curl -XPUT .../_settings`, and recon could do the same.
    private static final Pattern HTTP_VERB_WRITE = Pattern.compile(
            "\\bcurl\\b[^|]*(-X\\s*(put|delete|patch)\\b|--request\\s+(put|delete|patch)\\b)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern HTTP_GET = Pattern.compile(
            "-X\\s*get\\b|--request\\s+get\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTTP_POSTISH = Pattern.compile(
            "-X\\s*post\\b|--request\\s+post\\b|(^|\\s)(-d|--data(-\\S+)?)(\\s|=)", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTTP_MUTATING_ENDPOINT = Pattern.compile(
            "/_settings|/_close\\b|/_open\\b|/_forcemerge|/_delete_by_query|/shutdown\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * Verbs a READ-ONLY phase may use. Anything not here is refused as UNKNOWN.
     *
     * <p>The denylists in this file were audited by claim and gave up eighteen holes between them —
     * shell wrappers, find/xargs/interpreter deletes, a service's own API, write-shaped HTTP — while the
     * authority ladder, which is logic over a small input set, gave up none. A forbidden-verb list can
     * only ever say "not the shapes I thought of"; it cannot say the phase is incapable of mutation.
     *
     * <p>An allowlist can, and diagnosis is bounded enough to have one: across 210 distinct commands the
     * model actually issued in real runs, there were THIRTEEN distinct leading verbs — docker, find, ls,
     * cat, cd, grep, pwd, echo, which, sed, curl and two env-prefixed forms. This list is deliberately far
     * more generous than that, so an unusual-but-legitimate read still works; what it stops is the open
     * tail a denylist can never reach — a busybox applet, a compiled helper, a tool nobody listed.
     *
     * <p>Dual-use verbs stay in, because they are how diagnosis is done, and the pattern rules above
     * remain the second layer that judges HOW they are used ({@code sed -i}, {@code find -delete},
     * {@code curl -XPUT}, {@code docker exec … CONFIG SET} are all still refused).
     */
    private static final Set<String> READ_VERBS = Set.of(
            // inspection of files and text
            "cat", "head", "tail", "less", "more", "grep", "egrep", "fgrep", "zgrep", "awk", "sed", "cut",
            "tr", "sort", "uniq", "wc", "nl", "tac", "strings", "jq", "yq", "xxd", "od", "diff", "cmp",
            // the filesystem
            "ls", "find", "stat", "file", "du", "df", "readlink", "realpath", "dirname", "basename",
            "pwd", "cd", "tree", "lsof", "mount",
            // processes and the host
            "ps", "pgrep", "top", "free", "uptime", "uname", "hostname", "id", "whoami", "date", "env",
            "printenv", "nproc", "vmstat", "iostat", "sysctl", "dmesg", "who", "last",
            // the network
            "ss", "netstat", "ip", "ifconfig", "ping", "dig", "nslookup", "host", "traceroute", "curl",
            "wget", "nc", "openssl", "telnet",
            // service and container introspection (the VERB after these is judged separately)
            "docker", "docker-compose", "podman", "nerdctl", "kubectl", "systemctl", "journalctl",
            "service", "supervisorctl", "crictl", "ctr",
            // engine clients — read commands only; the SVC_WRITE rules above refuse the write ones
            "redis-cli", "valkey-cli", "psql", "mysql", "mongo", "mongosh", "cypher-shell", "rabbitmqctl",
            "opensearch-sql", "influx", "etcdctl",
            // harmless shell scaffolding
            "echo", "printf", "true", "false", "test", "[", "which", "command", "type", "sleep", "timeout",
            "xargs", "tee", "python", "python3", "perl", "ruby", "node", "bash", "sh", "for", "while",
            "if", "then", "do", "done", "fi", "case", "esac", "set", "export", "source", ".");

    /**
     * Verbs a REMEDIATION phase may additionally use.
     *
     * <p>I claimed remediation could not have an allowlist because "fixes are open-ended by nature".
     * The commands the model actually issued say otherwise: across every mutating command recorded in
     * real runs there were THREE leading verbs, and 49 of 54 were {@code docker}. It is as bounded as
     * diagnosis is.
     *
     * <p>What is genuinely different is that here the dangerous commands share verbs with the
     * legitimate ones — {@code docker rm} is both a fix and a way to destroy the service — so this list
     * cannot judge a USE. It does not have to: the blast-radius, host-write and PID rules judge uses,
     * and this closes the same open tail it closes in diagnosis (a busybox applet, a compiled helper, a
     * tool nobody listed).
     *
     * <p>Package managers are deliberately NOT here. Installing something on the host is not a
     * blast-radius-1 fix to one service, and the smallest-fix rule says so.
     */
    private static final Set<String> FIX_VERBS = Set.of(
            "mkdir", "touch", "cp", "mv", "rm", "rmdir", "chmod", "chown", "chgrp", "ln", "install",
            "truncate", "kill", "pkill", "killall", "tar", "gzip", "gunzip", "sync", "umount", "mount");

    /**
     * @return a rejection reason if {@code cmd} uses a verb neither diagnosis nor a fix would run, or
     *         null. Same purpose as {@link #rejectIfNotARead}, with the fix verbs added.
     */
    static String rejectIfNotAFix(String cmd) {
        for (String seg : shellSegments(cmd)) {
            if (seg.isBlank()) continue;
            String v = leadingVerb(seg);
            if (v.isEmpty() || v.startsWith("-") || v.startsWith("$") || v.startsWith("\"")) continue;
            if (!READ_VERBS.contains(v) && !FIX_VERBS.contains(v)) {
                return "blocked: '" + v + "' is not a command this fix should need. Act on the service "
                     + "through docker/systemctl or its own client, and read with the usual tools.";
            }
        }
        return null;
    }

    /** The leading verb of a segment, with the prefixes that do not change what runs stripped off. */
    private static String leadingVerb(String segment) {
        String s = segment.trim()
                .replaceFirst("^\\(+\\s*", "")                                   // subshell open
                .replaceFirst("^(sudo|nice|ionice|nohup|exec|time)\\s+", "")
                .replaceFirst("^(env\\s+)?(\\S+=\\S*\\s+)+", "")               // VAR=val prefixes
                .replaceFirst("^timeout\\s+\\S+\\s+", "")
                .trim();
        int sp = s.indexOf(' ');
        String v = (sp < 0 ? s : s.substring(0, sp)).trim();
        if (v.contains("/")) v = v.substring(v.lastIndexOf('/') + 1);              // /usr/bin/cat -> cat
        return v.toLowerCase(Locale.ROOT);
    }

    /**
     * @return a rejection reason if {@code cmd} uses a verb a read-only phase has no business running,
     *         or null. This is the ALLOWLIST half: it answers "is this a kind of command diagnosis does",
     *         where the patterns above answer "is this particular use of it a mutation".
     */
    /**
     * Split a command line on shell separators, IGNORING ones inside quotes or a `{{…}}` template.
     *
     * <p>A naive {@code split("\\|")} tears `grep -E 'redis_version|tcp_port'` and
     * {@code --format '{{.Config.Entrypoint}}|{{.Config.Cmd}}'} into fragments, and the fragment then
     * looks like an unknown verb. Measured against real commands: that alone accounted for most of the
     * over-blocking, and it is the kind of mistake that makes an allowlist unusable in practice.
     */
    static List<String> shellSegments(String cmd) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char quote = 0; int brace = 0;
        for (int i = 0; i < cmd.length(); i++) {
            char ch = cmd.charAt(i);
            if (quote != 0) {                       // inside quotes: copy verbatim until it closes
                cur.append(ch);
                if (ch == quote && (i == 0 || cmd.charAt(i - 1) != '\\')) quote = 0;
                continue;
            }
            if (ch == '\'' || ch == '"') { quote = ch; cur.append(ch); continue; }
            if (ch == '{' && i + 1 < cmd.length() && cmd.charAt(i + 1) == '{') { brace++; cur.append(ch); continue; }
            if (ch == '}' && i + 1 < cmd.length() && cmd.charAt(i + 1) == '}' && brace > 0) { brace--; cur.append(ch); continue; }
            if (brace == 0 && (ch == '|' || ch == ';' || ch == '\n'
                    || (ch == '&' && i + 1 < cmd.length() && cmd.charAt(i + 1) == '&'))) {
                out.add(cur.toString()); cur.setLength(0);
                if (ch == '&') i++;                 // consume the second '&'
                continue;
            }
            cur.append(ch);
        }
        out.add(cur.toString());
        return out;
    }

    static String rejectIfNotARead(String cmd) {
        for (String seg : shellSegments(cmd)) {
            if (seg.isBlank()) continue;
            String v = leadingVerb(seg);
            if (v.isEmpty() || v.startsWith("-") || v.startsWith("$") || v.startsWith("\"")) continue;
            if (!READ_VERBS.contains(v)) {
                return "blocked: '" + v + "' is not a command the read-only diagnosis phase runs. "
                     + "Investigate with the usual tools (docker ps/logs/inspect, docker exec <svc> "
                     + "<read>, cat/ls/grep/find, curl a health endpoint); the fix is applied in the "
                     + "separate remediation step.";
            }
        }
        return null;
    }

    /**
     * Whether {@code cmd} would change the box.
     *
     * <p>Same classifier the read-only guard uses, asked as a question instead of an enforcement. The
     * remediation phase is allowed to mutate, but it still needs to KNOW which of its commands did —
     * to record them in the audit trail, and to tell a wasted repeat from a legitimate one after the
     * state has moved. Its conservatism is the right direction for both callers: over-recording an
     * action in an audit is harmless, under-recording one is the defect.
     */
    static boolean isMutating(String cmd) {
        return cmd != null && !cmd.isBlank() && rejectIfMutating(cmd) != null;
    }

    /** @return a rejection reason if {@code cmd} would mutate the box, or null if it is read-only. */
    static String rejectIfMutating(String cmd) {
        String c = cmd.trim();
        // A SHELL WRAPPER hides the leading verb: `sh -c 'rm /etc/x'` presents as `sh`, and MUTATOR is
        // anchored to the leading verb of each segment, so it saw nothing. (`bash -c "systemctl restart"`
        // happened to be caught only because SVC_MUT matches anywhere.) Judge what the shell would RUN.
        Matcher w = Pattern.compile(
                "^(?:sudo\\s+)?(?:ba|da|z|k)?sh\\s+-[a-z]*c\\s+(['\"])(.*)\\1\\s*$",
                Pattern.DOTALL).matcher(c);
        if (w.matches()) {
            String inner = rejectIfMutating(w.group(2));
            if (inner != null) return inner;
        }
        if (INDIRECT_DELETE.matcher(c).find()) return "blocked: deletion via find/xargs/interpreter in the "
                + "read-only diagnosis phase — read the files, do not remove them";
        // A GET is a read whatever it targets — `curl -XGET .../_settings` is how you CHECK whether an
        // index is write-blocked, and blocking it would forbid diagnosing the very fault we ship a card
        // for. So the endpoint rule applies only to write-shaped calls.
        boolean httpWrite = HTTP_VERB_WRITE.matcher(c).find()
                || (!HTTP_GET.matcher(c).find() && HTTP_POSTISH.matcher(c).find()
                    && HTTP_MUTATING_ENDPOINT.matcher(c).find());
        if (httpWrite) return "blocked: write-shaped HTTP call in the read-only diagnosis phase "
                + "(PUT/DELETE/PATCH, or a POST to a mutating endpoint) — GET and _search are available "
                + "for investigation";
        for (String seg : c.split("\\||;|&&|\\|\\|")) {
            String s = seg.trim().replaceFirst("^(sudo|env\\s+\\S+=\\S+|nice|ionice|timeout\\s+\\S+)\\s+", "").trim();
            if (MUTATOR.matcher(s).find()) return "blocked: mutating command in read-only diagnosis phase → " + seg.trim();
        }
        if (SVC_MUT.matcher(c).find()) return "blocked: service state change in read-only diagnosis phase";
        if (PKG_MUT.matcher(c).find()) return "blocked: package install/change in read-only diagnosis phase";
        if (DOCKER_MUT.matcher(c).find()) return "blocked: docker state change in read-only diagnosis phase (use ps/logs/inspect/exec-readonly)";
        if (EXEC_MUT.matcher(c).find()) return "blocked: mutating command inside docker/kubectl exec in the "
                + "read-only diagnosis phase — read (cat/ps/ls/stat/engine-CLI reads), never kill/delete/change";
        if (INPLACE.matcher(c).find()) return "blocked: in-place file edit in read-only diagnosis phase";
        if (REDIR.matcher(c).find()) return "blocked: output redirection to a real path in read-only diagnosis phase";
        if (SVC_WRITE.matcher(c).find()) return "blocked: service-state write (e.g. CONFIG SET / DDL / "
                + "set_permissions) in the read-only diagnosis phase — investigate now (CONFIG GET, SELECT, "
                + "list_*, status); the fix is applied in the separate remediation step";
        return null;
    }
}

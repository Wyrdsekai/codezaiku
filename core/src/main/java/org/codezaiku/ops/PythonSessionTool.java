package org.codezaiku.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.codezaiku.tools.Tool;

/**
 * A PERSISTENT python session on the box — variables survive across turns.
 *
 * <p>Why: the plain shell tool spawns a fresh {@code python3 -c} every turn, so an agent analysing a large
 * dataset re-loads it EVERY TURN. On OpenRCA (a 1.1 GB trace file per day) that burned the entire turn
 * budget on reloading and 42/51 queries produced no answer at all. OpenRCA's own reference agent runs
 * model-written python inside a persistent IPython kernel, so this is PARITY with the baseline we are
 * measured against, not an extra affordance.
 *
 * <p>Talks to {@link Object} the kernel (see {@code external/openrca/kernel.py}) over a file protocol, so it
 * works through a plain one-shot exec: stage the code, atomically move it in, wait for the done-flag, read
 * the output. Enabled per-run with {@code CODEZAIKU_OPS_PYTHON_SESSION=on}.
 */
public final class PythonSessionTool implements Tool {
    private final Exec exec;
    private final int timeoutSec;
    private boolean kernelUp = false;

    public PythonSessionTool(Exec exec, int timeoutSec) {
        this.exec = exec;
        this.timeoutSec = timeoutSec;
    }

    @Override public String name() { return "python"; }

    @Override public String description() {
        return "Run python in a PERSISTENT session on this box. Variables, imports and DataFrames SURVIVE "
             + "between calls. Each result ends with a list of what is ALREADY LOADED — reuse those names "
             + "directly in your next call (loading a large CSV again costs a whole turn; the trace file is "
             + "over a gigabyte). Load each file ONCE, then query the variable. pandas and duckdb are "
             + "available. print() what you want to see.";
    }

    @Override public ObjectNode parametersSchema(ObjectMapper j) {
        ObjectNode p = j.createObjectNode();
        p.put("type", "object");
        ObjectNode props = p.putObject("properties");
        props.putObject("code").put("type", "string")
                .put("description", "Python source to execute in the persistent session. print() results.");
        p.putArray("required").add("code");
        return p;
    }

    /**
     * The kernel is normally started by the box itself (its CMD), because a {@code nohup … &} launched
     * inside a {@code docker exec} is KILLED when that exec session ends — the kernel then never exists and
     * every call silently waits out its whole timeout. This is only a fallback for a box that did not start
     * one, and it uses {@code setsid} so the process genuinely outlives the exec session.
     */
    private void ensureKernel() {
        if (kernelUp) return;
        Exec.Result r = exec.run("pgrep -f '/opt/kernel.py' >/dev/null 2>&1 && echo up", 15);
        if (!r.out().contains("up")) {
            exec.run("setsid nohup python3 -u /opt/kernel.py >/dev/null 2>&1 </dev/null & sleep 0.5", 20);
        }
        kernelUp = true;
    }

    /** True once we have confirmed a live kernel — used to fail loudly rather than time out silently. */
    private boolean kernelAlive() {
        return exec.run("pgrep -f '/opt/kernel.py' >/dev/null 2>&1 && echo up", 15).out().contains("up");
    }

    @Override public String execute(JsonNode args) {
        String code = args.path("code").asText("");
        if (code.isBlank()) return "ERROR: empty code";
        ensureKernel();
        exec.write("/tmp/k_stage.py", code);
        int polls = Math.max(10, timeoutSec * 5);   // poll every 0.2s
        Exec.Result r = exec.run(
                "rm -f /tmp/k_done /tmp/k_out.txt; mv /tmp/k_stage.py /tmp/k_in.py; "
              + "for i in $(seq 1 " + polls + "); do [ -f /tmp/k_done ] && break; sleep 0.2; done; "
              + "cat /tmp/k_out.txt 2>/dev/null; "
              + "[ -f /tmp/k_done ] || echo '[still running after " + timeoutSec
              + "s — the session is BUSY; make the next step smaller]'",
                timeoutSec + 30);
        String out = r.out() == null ? "" : r.out();
        return out.isBlank() ? "(no output — print() what you want to see)" : out;
    }
}

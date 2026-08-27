package org.codezaiku.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.codezaiku.drive.DriveClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps a growing ops conversation inside the drive's context window.
 *
 * <p>Both ops loops re-send the whole conversation every turn. Unbounded, that eventually hands the drive a
 * prompt larger than the window and llama.cpp answers HTTP 400 ({@code request (36229 tokens) exceeds the
 * available context size (32768)}), which {@link DriveClient} throws — killing the process mid-investigation.
 * On OpenRCA this was the dominant failure by a wide margin: the run CRASHED rather than concluding, and the
 * benchmark scored the missing answer as a wrong one. 129 of Bank's 136 queries died this way. It tracked
 * telemetry size (fatter observations fill the window sooner), which is what disguised a harness bug as a
 * reasoning limit — the model was never asked the question.
 *
 * <p>The remedy is the same L1 pattern the coding loop uses: near the window, replace the OLD span with an
 * LLM-written checkpoint that preserves the exact evidence, and keep recent turns verbatim.
 */
final class OpsContext {
    private static final Logger log = LoggerFactory.getLogger(OpsContext.class);

    private final DriveClient drive;
    private final ObjectMapper j;
    private final int nctx;
    private String checkpoint = "";

    // Characters per token. Prose runs ~4, but this loop's context is mostly CSV rows, timestamps and
    // floats, which tokenize far denser — a /4 estimate under-counted and walked straight into a 400.
    private static final int CHARS_PER_TOKEN = 3;

    OpsContext(DriveClient drive, ObjectMapper j, int nctx) {
        this.drive = drive;
        this.j = j;
        this.nctx = nctx;
    }

    /**
     * Compact if the conversation is nearing the window. Cuts only at an assistant boundary, so a tool result
     * is never orphaned from the call that produced it (which would break the chat template). Degrades to
     * eliding the oldest observations if the summarizer is unavailable — losing old detail beats dying.
     *
     * <p>{@code history[0]} — the incident kickoff — is PINNED and never compacted. It carries the task, any
     * candidate values the caller supplied, and the exact shape the answer must take. Summarizing it away
     * cost a run its answer format: the investigation survived, concluded, and then reported in prose the
     * scorer could not read. The conversation may forget how it got here; it may not forget what was asked.
     */
    void compact(ArrayNode history, String systemPrompt) {
        int sysTokens = systemPrompt.length() / CHARS_PER_TOKEN;
        if (sysTokens + estimateTokens(history) < (int) (nctx * 0.70)) return;

        int tailBudget = (int) (nctx * 0.30);
        int acc = 0, cut = -1;
        for (int i = history.size() - 1; i >= 2; i--) {
            acc += history.get(i).toString().length() / CHARS_PER_TOKEN;
            if (acc >= tailBudget && "assistant".equals(history.get(i).path("role").asText())) {
                cut = i;
                break;
            }
        }
        if (cut < 3) {                 // nothing safely cuttable yet — shrink in place rather than overflow
            elide(history, (int) (nctx * 0.50));
            return;
        }

        JsonNode kickoff = history.get(0);
        ArrayNode oldSpan = j.createArrayNode();
        ArrayNode tail = j.createArrayNode();
        for (int i = 1; i < history.size(); i++) {
            if (i < cut) oldSpan.add(history.get(i));
            else tail.add(history.get(i));
        }

        String summary = summarize(oldSpan);
        if (summary == null) {
            elide(history, (int) (nctx * 0.50));
            return;
        }
        checkpoint = summary;

        ObjectNode ckpt = j.createObjectNode();
        ckpt.put("role", "user");
        ckpt.put("content", "[EARLIER INVESTIGATION — compacted checkpoint]\n" + summary);

        history.removeAll();
        history.add(kickoff);
        history.add(ckpt);
        history.addAll(tail);
        log.info("compacted: {} old msgs → checkpoint ({} chars), kept kickoff + {} recent msgs",
                oldSpan.size(), summary.length(), tail.size());
    }

    /** Never ask for more output than the window has left after the prompt. */
    int outputBudget(ArrayNode messages, String systemPrompt) {
        int in = estimateTokens(messages) + systemPrompt.length() / CHARS_PER_TOKEN;
        return Math.max(512, Math.min(Math.min(nctx / 2, 4096), nctx - in - 256));
    }

    /** Does this failure mean we handed the drive a prompt bigger than its window? */
    static boolean isOverflow(Exception e) {
        String m = String.valueOf(e.getMessage());
        return m.contains("exceed_context_size") || m.contains("exceeds the available context size");
    }

    /**
     * Recover from an overflow the estimator failed to predict.
     *
     * <p>Token count can only ever be ESTIMATED from characters here, and the estimate is wrong in the
     * direction that matters: dense CSV and numeric output tokenizes far worse than prose, so a conversation
     * the estimator calls "safely under" can still be over. Tightening the ratio only moves the cliff. So the
     * drive's own 400 is treated as the authority: shrink hard and retry, rather than letting an arithmetic
     * miss end an investigation. Each call is strictly more aggressive than the last.
     */
    void shrinkHard(ArrayNode history) {
        elide(history, (int) (nctx * 0.35));
        // Still too big means the recent turns are themselves the bulk. Drop the oldest span outright, in
        // whole assistant-to-next-assistant chunks so a tool result is never separated from its call.
        //
        // The PINNED prefix survives regardless: the kickoff (what was asked, and in what form the answer is
        // due) and the checkpoint (the distilled evidence — by far the most value per token in the window).
        // Dropping either produces a run that keeps investigating but can no longer answer.
        int pinned = pinnedPrefix(history);
        while (estimateTokens(history) > (int) (nctx * 0.45) && history.size() > pinned + 2) {
            int from = pinned, to = -1;
            while (from < history.size() && !"assistant".equals(history.get(from).path("role").asText())) from++;
            for (int k = from + 1; k < history.size(); k++) {
                if ("assistant".equals(history.get(k).path("role").asText())) { to = k; break; }
            }
            if (to < 0) to = history.size();          // only one span left — drop it and stop
            if (from >= history.size() || to <= pinned) break;
            for (int k = to - 1; k >= pinned; k--) history.remove(k);
        }
        log.warn("context overflow — shrank history to ~{} est. tokens ({} msgs, {} pinned) and retrying",
                estimateTokens(history), history.size(), pinned);
    }

    /** The kickoff, plus the checkpoint once one exists. Never dropped. */
    private int pinnedPrefix(ArrayNode history) {
        boolean hasCkpt = history.size() > 1
                && history.get(1).path("content").asText("").startsWith("[EARLIER INVESTIGATION");
        return hasCkpt ? 2 : 1;
    }

    /** LLM-summarize the old span, carrying the previous checkpoint forward so nothing is lost twice. */
    private String summarize(ArrayNode oldSpan) {
        StringBuilder t = new StringBuilder();
        if (!checkpoint.isBlank()) t.append("[PREVIOUS CHECKPOINT]\n").append(checkpoint).append("\n\n");
        for (JsonNode m : oldSpan) {
            StringBuilder line = new StringBuilder(m.path("content").asText(""));
            for (JsonNode c : m.path("tool_calls")) {
                line.append(' ').append(c.path("function").path("name").asText())
                    .append('(').append(c.path("function").path("arguments").asText("")).append(')');
            }
            String s = line.toString();
            t.append(m.path("role").asText()).append(": ")
             .append(s.length() > 1500 ? s.substring(0, 1500) + "…" : s).append('\n');
        }
        // The checkpoint IS the evidence from here on, so it has to carry the literal values: an approximate
        // timestamp or a paraphrased component name cannot be used to answer, and the raw turns are gone.
        String sys = """
                You are compacting an incident investigation so it can CONTINUE without the raw transcript.
                Write a factual checkpoint, preserving details EXACTLY as they appeared:
                - EVIDENCE: every concrete finding — exact timestamps, component and host names, metric names
                  with their numeric values, error strings. Copy these verbatim.
                - DATA LAYOUT: which files exist and what columns they have, so they are not re-discovered.
                - ALREADY RUN: the commands issued and what each returned, so they are not repeated.
                - RULED OUT: hypotheses the evidence has eliminated, and why.
                - OPEN: what still has to be established to name the root cause.
                Facts only.""";
        try {
            ArrayNode sm = j.createArrayNode();
            sm.addObject().put("role", "system").put("content", sys);
            sm.addObject().put("role", "user").put("content", t.toString());
            String s = drive.chat(sm, null, Math.min(nctx / 3, 2048)).path("content").asText("");
            return s.isBlank() ? null : s;
        } catch (Exception e) {
            log.warn("compaction summarize failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Blank out the oldest tool observations, keeping the message SHAPE intact — an assistant tool_call whose
     * tool result disappeared breaks the chat template, so the messages stay and only their content shrinks.
     */
    private void elide(ArrayNode history, int target) {
        for (int i = 1; i < history.size() && estimateTokens(history) > target; i++) {
            JsonNode m = history.get(i);
            if (m.isObject() && "tool".equals(m.path("role").asText())
                    && m.path("content").asText("").length() > 200) {
                ((ObjectNode) m).put("content", "[earlier observation elided to fit the context window]");
            }
        }
        log.info("compaction fallback: elided old observations → ~{} tokens", estimateTokens(history));
    }

    private int estimateTokens(ArrayNode messages) {
        int n = 0;
        for (JsonNode m : messages) n += m.toString().length() / CHARS_PER_TOKEN;
        return n;
    }
}

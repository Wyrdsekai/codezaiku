package org.codezaiku.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.codezaiku.chat.ChatTasks;
import org.codezaiku.drive.DriveClient;
import org.codezaiku.library.Library;
import org.codezaiku.loop.FamiliarLoop;

/**
 * Agent orchestration, first rung: hand a well-scoped task to a SUB-LOOP and keep talking.
 *
 * <p>The sub-loop is the ordinary coding loop — standard tools, its own turn budget, the same
 * project root — run on a background thread through {@link ChatTasks}, so the conversation is
 * never held hostage and completion arrives the same way a background shell does: a console
 * notification, a digest in the next turn's context, and the full transcript as an artifact.
 *
 * <p><b>The cost structure this enables:</b> the chat's drive does the judging, a DIFFERENT
 * drive can do the labor. {@code CODEZAIKU_DELEGATE_DRIVE}/{@code CODEZAIKU_DELEGATE_MODEL}
 * point sub-loops somewhere else (the local 27B under a frontier chat is the intended shape —
 * frontier judgment, local labor); unset, delegation uses the chat's own drive.
 *
 * <p><b>Consent:</b> a delegated sub-loop acts with standard tools and no per-action prompts, so
 * the DELEGATION ITSELF is the consented act — one question, answered with the task text in
 * view. It is therefore absent from the plan rung's registry entirely, and the undo journal
 * declares the step not-coverable (the sub-loop mutates after the chat turn ends).
 */
public final class DelegateTool implements Tool {

    private final ChatTasks tasks;
    private final Path projectRoot;
    private final String chatDriveUrl;
    private final String chatModel;

    public DelegateTool(ChatTasks tasks, Path projectRoot, String chatDriveUrl, String chatModel) {
        this.tasks = tasks;
        this.projectRoot = projectRoot;
        this.chatDriveUrl = chatDriveUrl;
        this.chatModel = chatModel;
    }

    @Override
    public String name() {
        return "delegate";
    }

    @Override
    public String description() {
        return "Hand a well-scoped coding task to a background sub-agent working in this project, "
                + "and continue the conversation. Best for self-contained work you can specify "
                + "completely (a feature with tests, a refactor, a fix). The result arrives in a "
                + "later turn. State the FULL task — the sub-agent sees only what you write.";
    }

    @Override
    public ObjectNode parametersSchema(ObjectMapper j) {
        ObjectNode p = j.createObjectNode();
        p.put("type", "object");
        ObjectNode props = p.putObject("properties");
        props.putObject("task").put("type", "string")
                .put("description", "Complete, self-contained task description, including how to "
                        + "verify it (the sub-agent cannot ask questions).");
        props.putObject("label").put("type", "string")
                .put("description", "Short label for the task list.");
        props.putObject("kind").put("type", "string")
                .put("description", "code (default): build/change software here. research: "
                        + "investigate a question on the web and report cited findings.");
        props.putObject("max_turns").put("type", "integer")
                .put("description", "Sub-agent turn budget (default 25).");
        p.putArray("required").add("task");
        return p;
    }

    @Override
    public String execute(JsonNode args) throws Exception {
        String task = args.path("task").asText("");
        if (task.isBlank()) return "ERROR: empty task";
        String label = args.path("label").asText("");
        if (label.isBlank()) label = task.length() > 40 ? task.substring(0, 37) + "..." : task;
        int maxTurns = args.path("max_turns").asInt(25);

        String kind = args.path("kind").asText("code");
        String driveUrl = org.codezaiku.Config.get("CODEZAIKU_DELEGATE_DRIVE", chatDriveUrl);
        String model = org.codezaiku.Config.get("CODEZAIKU_DELEGATE_MODEL", chatModel);

        Path out = Files.createDirectories(projectRoot.resolve(".codezaiku"))
                .resolve("delegate-" + System.currentTimeMillis() + ".log");
        String finalLabel = label;
        // The job is registered before the thread starts so the id exists to return.
        final ChatTasks.Job[] jobRef = new ChatTasks.Job[1];
        Thread t = new Thread(() -> {
            boolean ok = false;
            String summary;
            try {
                // A research delegation runs the RESEARCH loop (web tools, depth strategy,
                // deadline turn) — the same worker shape the fan-out uses, surfaced to chat.
                FamiliarLoop.Result r;
                // The initiative channel: a colleague volunteers what the task did not ask for.
                // Sub-agents are told to flag it; the digest carries it into the conversation.
                String fullTask = task + "\n\nIf you discover something that CONTRADICTS this "
                        + "task's premise or clearly matters beyond it, begin your final summary "
                        + "with 'WORTH NOTING: <it>' before the result.";
                if ("research".equalsIgnoreCase(kind)) {
                    r = org.codezaiku.FamiliarMain.research(fullTask
                            + "\n\nEnd with the URLs of the sources you actually used.",
                            "depth", driveUrl, maxTurns);
                } else {
                    DriveClient drive = new DriveClient(driveUrl, model);
                    r = new FamiliarLoop(drive,
                            ToolRegistry.standard(projectRoot), projectRoot, fullTask, maxTurns,
                            new Library()).run();
                }
                ok = r.done();
                summary = (ok ? "done in " + r.turns() + " turns: " : "INCOMPLETE after "
                        + r.turns() + " turns: ")
                        + (r.summary() == null ? "" : r.summary());
            } catch (Exception e) {
                summary = "sub-agent crashed: " + e.getMessage();
            }
            try {
                Files.writeString(out, summary + "\n", StandardCharsets.UTF_8);
            } catch (Exception ignored) {
                // the digest still carries the summary
            }
            tasks.complete(jobRef[0], ok, summary.length() > 500
                    ? summary.substring(0, 500) + "…" : summary);
        }, "cz-delegate");
        t.setDaemon(true);
        jobRef[0] = tasks.startExternal("delegate: " + finalLabel, out, t);
        t.start();
        return "delegated as background task " + jobRef[0].id + " (" + finalLabel + ", budget "
                + maxTurns + " turns, drive " + driveUrl + "). You will be told when it finishes — "
                + "continue the conversation; do not wait.";
    }
}

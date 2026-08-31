package org.codezaiku.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.codezaiku.chat.ChatTasks;

/**
 * Start a long-running shell command WITHOUT holding the conversation for it.
 *
 * <p>Chat-only, and the counterpart of {@link ShellTool}: same consent class (a command is a
 * command — the chat layer canonicalizes this tool to shell for consent, preview and trust), but
 * the process detaches into {@link ChatTasks}, the turn continues immediately, and completion
 * arrives as a console notification plus a digest line in the next turn's context.
 *
 * <p>The undo journal marks the step NOT COVERED: a process that mutates files after the turn
 * ended cannot be promised a rewind, and an undo report that pretends otherwise is worse than
 * none (the journal's own rule).
 */
public final class RunBackgroundTool implements Tool {

    private final ChatTasks tasks;

    public RunBackgroundTool(ChatTasks tasks) {
        this.tasks = tasks;
    }

    @Override
    public String name() {
        return "run_background";
    }

    @Override
    public String description() {
        return "Run a shell command in the BACKGROUND and keep the conversation going — for "
                + "builds, test suites, downloads, anything slow. Returns a task id immediately; "
                + "the result arrives in a later turn. Use shell for quick commands.";
    }

    @Override
    public ObjectNode parametersSchema(ObjectMapper j) {
        ObjectNode p = j.createObjectNode();
        p.put("type", "object");
        ObjectNode props = p.putObject("properties");
        props.putObject("command").put("type", "string")
                .put("description", "The shell command to run in the background.");
        props.putObject("label").put("type", "string")
                .put("description", "Short human label for the task list (e.g. 'full test suite').");
        p.putArray("required").add("command");
        return p;
    }

    @Override
    public String execute(JsonNode args) throws Exception {
        String command = args.path("command").asText("");
        if (command.isBlank()) return "ERROR: empty command";
        String label = args.path("label").asText("");
        if (label.isBlank()) {
            label = command.length() > 40 ? command.substring(0, 37) + "..." : command;
        }
        ChatTasks.Job j = tasks.startShell(command, label);
        return "started background task " + j.id + " (" + label + "). Its output goes to "
                + j.outFile + "; you will be told when it finishes — do not wait for it, "
                + "continue the conversation.";
    }
}

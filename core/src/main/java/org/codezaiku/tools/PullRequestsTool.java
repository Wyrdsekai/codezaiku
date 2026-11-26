package org.codezaiku.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.codezaiku.exec.PullRequests;

import java.nio.file.Path;
import java.util.Locale;

/**
 * {@code pull_requests}: the project's pull requests on GitHub, read through the person's own gh outside the sandbox, or one opened on
 * the person's yes ({@link PullRequests}). What it reads was written by other people: its addresses give what a page's do, to
 * {@code web_fetch} and never to a command.
 */
public final class PullRequestsTool implements Tool {
    private final Path root;

    public PullRequestsTool(Path root) { this.root = root; }

    @Override public String name() { return "pull_requests"; }

    @Override
    public String description() {
        return "Read this project's pull requests on GitHub, or open one, through the person's own gh. action list (state open, closed, merged or all), "
                + "view (a pull request's description, comments and reviews, by number), diff (its changes), checks (its CI checks), or create "
                + "(title, body, head: the pushed branch with the work, base: the branch to merge into; opened once the person says yes). "
                + "remote names which remote's repository, origin by default.";
    }

    @Override
    public ObjectNode parametersSchema(ObjectMapper j) {
        ObjectNode s = j.createObjectNode().put("type", "object");
        ObjectNode p = s.putObject("properties");
        ObjectNode action = p.putObject("action").put("type", "string");
        action.putArray("enum").add("list").add("view").add("diff").add("checks").add("create");
        p.putObject("number").put("type", "integer").put("description", "the pull request's number, for view, diff and checks");
        p.putObject("state").put("type", "string").put("description", "for list: open (the default), closed, merged or all");
        p.putObject("remote").put("type", "string").put("description", "the remote whose repository on GitHub, origin by default");
        p.putObject("title").put("type", "string").put("description", "for create");
        p.putObject("body").put("type", "string").put("description", "for create: the description");
        p.putObject("head").put("type", "string").put("description", "for create: the branch with the work, already pushed");
        p.putObject("head_remote").put("type", "string").put("description", "for create, when the branch is on another remote (a fork): that remote's name");
        p.putObject("base").put("type", "string").put("description", "for create: the branch to merge into; the repository's default branch when left out");
        p.putObject("draft").put("type", "boolean").put("description", "for create: open it as a draft");
        s.putArray("required").add("action");
        return s;
    }

    @Override
    public String execute(JsonNode args) { return PullRequests.act(root, args); }

    /** What it read gives its addresses to web_fetch, as a page does; opening one gives nothing. */
    @Override
    public String givenText(JsonNode args, String answer) {
        String action = args == null ? "" : args.path("action").asText("").toLowerCase(Locale.ROOT);
        return action.equals("create") ? null : answer;
    }
}

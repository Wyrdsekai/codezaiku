package org.codezaiku.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.codezaiku.exec.GitOutside;

import java.nio.file.Path;

/**
 * {@code git_push}: a branch of the project to one of its remotes, pushed by CodeZaiku itself outside the sandbox with the person's
 * credentials ({@link GitOutside#push}). The model names a remote and a branch, never a command line; the person says yes in a chat,
 * and in a job that asks nobody the setting git.push lists where it may go.
 */
public final class GitPushTool implements Tool {
    private final Path root;

    public GitPushTool(Path root) { this.root = root; }

    @Override public String name() { return "git_push"; }

    @Override
    public String description() {
        return "Push a branch of this project to one of its git remotes. CodeZaiku runs the push itself, outside the sandbox, with the person's own git "
                + "credentials, once the person says yes. Commit with git in the shell first, then give the remote by its name (as `git remote` lists it) "
                + "and the branch by its name. Use force only when the person asked for a force push in their own words.";
    }

    @Override
    public ObjectNode parametersSchema(ObjectMapper j) {
        ObjectNode s = j.createObjectNode().put("type", "object");
        ObjectNode p = s.putObject("properties");
        p.putObject("remote").put("type", "string").put("description", "the remote's name, for example origin");
        p.putObject("branch").put("type", "string").put("description", "the branch to push, by its name; it goes to the branch of the same name on the remote");
        p.putObject("force").put("type", "boolean").put("description", "a force push: only when the person asked for one in their own words");
        s.putArray("required").add("remote").add("branch");
        return s;
    }

    @Override
    public String execute(JsonNode args) {
        return GitOutside.push(root, args.path("remote").asText(""), args.path("branch").asText(""), args.path("force").asBoolean(false));
    }
}

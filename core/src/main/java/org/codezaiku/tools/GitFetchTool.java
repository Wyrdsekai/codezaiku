package org.codezaiku.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.codezaiku.exec.GitOutside;

import java.nio.file.Path;

/**
 * {@code git_fetch}: a remote's new commits brought into the project's remote-tracking branches, fetched by CodeZaiku itself outside
 * the sandbox with the person's credentials and handed to the project by a fixed command inside it ({@link GitOutside#fetch}).
 * Merging is the model's own command.
 */
public final class GitFetchTool implements Tool {
    private final Path root;

    public GitFetchTool(Path root) { this.root = root; }

    @Override public String name() { return "git_fetch"; }

    @Override
    public String description() {
        return "Bring a git remote's new commits into this project's remote-tracking branches (origin/main and the like). CodeZaiku fetches them itself, "
                + "outside the sandbox, with the person's own git credentials. Then merge or rebase with git in the shell.";
    }

    @Override
    public ObjectNode parametersSchema(ObjectMapper j) {
        ObjectNode s = j.createObjectNode().put("type", "object");
        ObjectNode p = s.putObject("properties");
        p.putObject("remote").put("type", "string").put("description", "the remote's name, for example origin");
        p.putObject("branch").put("type", "string").put("description", "one branch of the remote, by its name; leave it out for all of them");
        s.putArray("required").add("remote");
        return s;
    }

    @Override
    public String execute(JsonNode args) {
        String branch = args.path("branch").asText("");
        return GitOutside.fetch(root, args.path("remote").asText(""), branch.isBlank() ? null : branch);
    }
}

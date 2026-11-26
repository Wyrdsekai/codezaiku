package org.codezaiku.exec;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The person's own {@code gh}, run outside the sandbox for a project's pull requests: from an empty folder of CodeZaiku's own, never
 * the project, so nothing of the project's settings steers it; the repository named with {@code --repo} from a remote's address as
 * the project held it when the job began ({@link GitRemotes}). {@code GH_REPO} and {@code GH_HOST} are taken out of its environment
 * (either would point it elsewhere), and prompts, colour, the pager and update notices are off (gh's manual, "gh help environment").
 * gh's exit codes ("gh help exit-codes"): 0 done, 1 failed, 2 cancelled, 4 "requires authentication"; gh pr checks adds 8, pending.
 */
public final class GitHubCli {
    private GitHubCli() { }

    /** What gh did: its exit code and output, or the one sentence that says what the person installs or runs. */
    public record Result(int exit, String out, String err, String problem) {
        public boolean ok() { return problem == null && exit == 0; }
    }

    public static final String NOT_INSTALLED = "GitHub's command-line program gh is not installed here. The person installs it (cli.github.com) and runs gh auth login once; "
            + "CodeZaiku then reads and opens pull requests with it.";
    public static final String NOT_SIGNED_IN = "gh is installed but not signed in to GitHub. The person runs gh auth login once; CodeZaiku then reads and opens pull requests with it.";

    /** gh with {@code args}, {@code stdin} on its standard input (null for none), for a project's job. */
    public static Result run(Path project, List<String> args, String stdin) {
        String gh = GitRemotes.gh(project);
        if (gh == null) return new Result(-1, "", "", NOT_INSTALLED);
        List<String> cmd = new ArrayList<>();
        cmd.add(gh);
        cmd.addAll(args);
        GitRemotes.Ran r = GitRemotes.run(cmd, GitRemotes.neutralDir(), env(), 300, stdin);
        if (r.exit() == 4) return new Result(4, r.out(), r.err(), NOT_SIGNED_IN);
        return new Result(r.exit(), r.out(), r.err(), null);
    }

    static Map<String, String> env() {
        Map<String, String> env = GitRemotes.personEnv();
        for (String k : List.of("GH_REPO", "GH_HOST", "GH_FORCE_TTY", "GH_DEBUG", "BROWSER", "GH_BROWSER")) env.remove(k);
        env.put("GH_PROMPT_DISABLED", "1");
        env.put("NO_COLOR", "1");
        env.put("CLICOLOR", "0");
        env.put("GH_PAGER", "cat");
        env.put("PAGER", "cat");
        env.put("GH_NO_UPDATE_NOTIFIER", "1");
        env.put("GH_NO_EXTENSION_UPDATE_NOTIFIER", "1");
        env.put("GH_SPINNER_DISABLED", "1");
        return env;
    }
}

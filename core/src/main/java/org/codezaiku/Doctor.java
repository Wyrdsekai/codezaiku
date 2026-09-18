package org.codezaiku;

import org.codezaiku.drive.DriveClient;
import org.codezaiku.ops.Exec;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Environment check — "why doesn't this work yet".
 *
 * CodeZaiku needs a model server it does not ship, and its surfaces each need different optional
 * tooling. Without this, a new user meets a stack trace from whichever component happened to be
 * missing and has to reverse-engineer the requirement. Each check therefore reports what it looked
 * for, whether it found it, and the one command that fixes it.
 *
 * Exit code is 0 when every REQUIRED check passes; optional gaps degrade a surface, not the install.
 */
final class Doctor {

    private record Check(String name, boolean required, boolean ok, String detail, String fix) { }

    /** How to install trivy on this operating system, as a command a person can copy. */
    static String trivyInstall() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String how;
        if (os.contains("mac")) how = "brew install trivy";
        else if (os.contains("win")) how = "download trivy_<version>_windows-64bit.zip from https://github.com/aquasecurity/trivy/releases, unzip it, and put trivy.exe in a folder on your PATH";
        else how = "curl -sfL https://raw.githubusercontent.com/aquasecurity/trivy/main/contrib/install.sh | sudo sh -s -- -b /usr/local/bin"
                + "   (Debian and Ubuntu can use Aqua's apt repository instead: https://trivy.dev/docs/latest/getting-started/installation/)";
        return "install trivy, then run doctor again: " + how
                + ". CodeZaiku only reports vulnerabilities a scanner found, never ones the model remembers.";
    }

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    static int run(String driveUrl) {
        List<Check> checks = new ArrayList<>();
        var exec = Exec.forTarget("local");

        Path cfg = Config.source();
        System.out.println(cfg == null
                ? "config: none found — `codezaiku init` writes a starter at " + Config.userConfigPath()
                : "config: " + cfg);

        // ── required ────────────────────────────────────────────────────────
        String java = System.getProperty("java.version", "?");
        int major = major(java);
        checks.add(new Check("java " + java, true, major >= 21,
                major >= 21 ? "" : "CodeZaiku needs Java 21 or newer",
                "install Java 21 or newer (https://adoptium.net) and make sure the `java` on your PATH is that one. "
                + "Or use the download that carries its own Java, which needs no Java on the machine: https://codezaiku.org/download/"));

        // Which shell commands actually run through. Worth naming because on Windows it is not
        // obvious: System32\bash.exe is the WSL launcher and CreateProcess finds it before anything
        // on PATH, so a bare "bash" silently dispatches every command into a Linux distribution.
        // Reporting it turns "why does this box behave differently" into a one-line answer.
        boolean wsl = org.codezaiku.exec.Shell.isWsl();
        checks.add(new Check("shell: " + org.codezaiku.exec.Shell.describe(), false, !wsl,
                wsl ? "commands run inside WSL, not natively" : "",
                "commands the model runs go through WSL, not Windows itself. If you want Windows, set CODEZAIKU_SHELL to a Windows "
                + "bash, for example Git for Windows' bash.exe. If you want WSL, run CodeZaiku inside the WSL distribution."));

        // /v1/models is the cheap probe and every local server answers it — but it is NOT what
        // CodeZaiku requires, and some hosted providers do not serve it to this credential at all.
        // Measured: api.anthropic.com answers 401 on /v1/models while its OpenAI-compatible
        // /v1/chat/completions works perfectly with the same key, so probing only the former
        // reported a working endpoint as "nothing answered" and sent the operator to fix a server
        // that was fine. Fall back to asking for one token from the endpoint we actually use.
        String driveDetail = "";
        boolean drive = httpOk(driveUrl + "/v1/models");
        if (!drive) {
            ChatProbe probe = chatProbe(driveUrl);
            drive = probe.ok();
            driveDetail = probe.detail();
        }
        // this program's own release against the latest
        String czLatest = SelfUpdate.latestCached();
        boolean czNewer = czLatest != null && ResearchZoshoInstall.compareVersions(czLatest, FamiliarMain.VERSION) > 0;
        checks.add(new Check("codezaiku " + FamiliarMain.VERSION + (czNewer ? " (" + czLatest + " is available)" : czLatest == null ? "" : " (the latest)"), false, !czNewer, czNewer ? czLatest + " is available" : "", czNewer ? SelfUpdate.howToUpdate() : ""));
        // ResearchZosho, the research library, is a separate program: say whether it is here, whether its daemon answers, and whether a newer release exists
        String rzHave = org.codezaiku.ResearchZoshoInstall.installedVersion();
        if (rzHave == null) {
            checks.add(new Check("researchzosho (the research library)", false, false, "not installed; research keeps its findings in the research memory only", "codezaiku install researchzosho"));
        } else {
            boolean answers = org.codezaiku.research.LibraryBridge.answers();
            String latest = org.codezaiku.ResearchZoshoInstall.latestCached();
            boolean newer = latest != null && org.codezaiku.ResearchZoshoInstall.compareVersions(latest, rzHave) > 0;
            checks.add(new Check("researchzosho " + rzHave + (answers ? ", daemon answering at " + org.codezaiku.research.LibraryBridge.url() : ", daemon not answering at " + org.codezaiku.research.LibraryBridge.url()),
                    false, !newer, newer ? latest + " is available" : "", newer ? "codezaiku install researchzosho  (updates it; the library and settings stay)" : answers ? "" : "the library is installed but not running: `researchzosho service install` starts it now and at every login, `researchzosho serve` runs it in this terminal"));
        }
        // no drive: when this machine can serve one on demand, that is the fix to name (ModelServer); the docker line otherwise
        String offer = drive ? null : ModelServer.offer();
        checks.add(new Check("model server at " + driveUrl, true, drive,
                drive ? "" : driveDetail.isEmpty() ? "nothing answered /v1/models" : driveDetail, driveFix(offer)));

        if (drive) {
            int ctx = 0;
            // The model's name matters here: behind llama-swap, /props names no model and answers 404, and the window
            // is at /upstream/<model>/props. With "" this check fell back to 8192 and warned, while every real run,
            // which passes the model, read the true window (found on a second machine, 2026-09-17).
            try { ctx = new DriveClient(driveUrl, Config.get("CODEZAIKU_MODEL", "local-model")).contextWindow(); } catch (Exception ignored) { }
            checks.add(new Check("model context window", true, ctx >= 8192,
                    ctx > 0 ? ctx + " tokens" : "could not determine",
                    "CodeZaiku needs at least 8192 tokens of context to work at all, and 32768 to work well. "
                    + "Start the model server with a bigger window (llama.cpp: --ctx-size 32768)."));
            // A SECOND, softer floor, because 8k passes the check above and still cannot complete a
            // single backend run. A host dispatch carries its own task preamble on top of our pinned
            // project block: one measured at 8k refused before its first turn — 9,619 tokens into
            // 8,192. Reporting only "ok, 8192 tokens" there is a green light for a configuration that
            // provably does not work, which is the exact shape of instrument this project keeps
            // getting wrong. Optional rather than required: 8k does answer a small interactive task,
            // and failing that user would be its own kind of wrong.
            checks.add(new Check("context window when another program drives CodeZaiku", false, ctx >= 12288,
                    ctx > 0 ? ctx + " tokens" : "could not determine",
                    "When an editor or another agent sends CodeZaiku a task (through run, MCP or ACP), that task comes with "
                    + "its own instructions, and they take room in the context. With 8192 tokens there was not enough room left, "
                    + "and the first request was refused. Give the model server at least 12288 tokens for that; 16384 is comfortable."));
        }

        // ── optional, per surface ───────────────────────────────────────────
        boolean docker = exec.run("docker info", 15).ok();
        checks.add(new Check("docker", false, docker,
                docker ? "" : "the ops commands (fix, serve, watch, triage, investigate) and the security check work on containers",
                "install Docker (https://docs.docker.com/get-docker/). Without it, coding, review and research still work; the ops commands and the security check do not."));

        String searx = Config.get("CODEZAIKU_SEARXNG", "http://localhost:8888");
        String brave = Config.get("CODEZAIKU_BRAVE_KEY");
        boolean haveBrave = brave != null && !brave.isBlank();
        boolean searxAnswers = httpOk(searx + "/search?q=probe&format=json");
        checks.add(new Check(haveBrave ? "web search: Brave Search key set" : "web search: SearXNG at " + searx, false, haveBrave || searxAnswers,
                haveBrave || searxAnswers ? "" : "research runs search the web with it; without it they only reach Wikipedia, Crossref and OpenAlex",
                "either a Brave Search API key (free plan at https://brave.com/search/api/): codezaiku config set CODEZAIKU_BRAVE_KEY <key>\n"
                + "      or a SearXNG server with its JSON API turned on: codezaiku config set CODEZAIKU_SEARXNG http://<host>:8888\n"
                + "      `codezaiku setup` asks for either."));

        boolean git = exec.run("git --version", 10).ok();
        checks.add(new Check("git", false, git, git ? "" : "the coding commands commit their work with it, and `run` uses it to list the files a task changed",
                "install git (https://git-scm.com/downloads) and make sure `git` is on your PATH"));

        for (String[] lsp : new String[][]{
                {"pyright", "python"}, {"rust-analyzer", "rust"}, {"gopls", "go"}, {"jdtls", "java"}}) {
            boolean has = exec.run("command -v " + lsp[0], 10).ok();
            if (!has) continue;   // only report the ones present; absence is normal
            checks.add(new Check("lsp: " + lsp[0] + " (" + lsp[1] + ")", false, true, "", ""));
        }

        boolean trivy = exec.run("command -v trivy || command -v grype", 10).ok();
        checks.add(new Check("vulnerability scanner", false, trivy,
                trivy ? "" : "without one, the security check cannot look for known vulnerabilities in container images",
                trivyInstall()));

        // ── report ──────────────────────────────────────────────────────────
        int failedRequired = 0;
        System.out.println("codezaiku doctor\n");
        for (Check c : checks) {
            String mark = c.ok() ? "  ok  " : (c.required() ? " FAIL " : " --   ");
            System.out.printf("%s %s%s%n", mark, c.name(),
                    c.detail().isBlank() ? "" : "  (" + c.detail() + ")");
            if (!c.ok() && !c.fix().isBlank())
                System.out.println("       fix: " + c.fix());
            if (!c.ok() && c.required()) failedRequired++;
        }
        System.out.println();
        if (failedRequired == 0) {
            System.out.println("ready. try:  codezaiku smoke");
            long optional = checks.stream().filter(c -> !c.required() && !c.ok()).count();
            if (optional > 0)
                System.out.println(optional + " optional component(s) missing — the surfaces above that "
                        + "need them will say so rather than failing obscurely.");
        } else {
            System.out.println(failedRequired + " required check(s) failed — fix those first.");
        }
        // the one fix the doctor can do itself: serve the model on this machine, when the person is at the keyboard to say yes
        if (!drive && offer != null && System.console() != null) {
            System.out.print("\n" + offer + " Set it up now? [Y/n] ");
            String answer = System.console().readLine();
            if (answer == null || answer.isBlank() || answer.strip().regionMatches(true, 0, "y", 0, 1)) {   // (a local named `java` shadows the package here)
                String r = ModelServer.install(null, "all", ModelServer.DEFAULT_IDLE_MINUTES, false, System.out);
                if (r.startsWith("!")) System.out.println("  not set up: " + r.substring(1));
                else { System.out.println("  serving " + r + " at " + ModelServer.URL + "; run codezaiku doctor again to see it"); return failedRequired == 1 ? 0 : 1; }
            }
        }
        return failedRequired == 0 ? 0 : 1;
    }

    /** The fix line for a missing drive: the on-demand install when this machine can do it, the server line otherwise. */
    static String driveFix(String offer) {
        if (offer != null) return offer + "\n      codezaiku model serve install   (or say yes below)";
        return "no model server answered. Run `codezaiku setup`: it finds a server running on this machine, can start one for you on a Linux\n"
                + "      machine with an NVIDIA card and Docker, or takes the address and key of a hosted API (OpenAI, DeepSeek, Gemini, OpenRouter…).\n"
                + "      To point at a server by hand: codezaiku config set CODEZAIKU_DRIVE http://<host>:<port>";
    }

    /** What a one-token request to the endpoint CodeZaiku actually uses told us. */
    record ChatProbe(boolean ok, String detail) { }

    /**
     * Ask {@code /v1/chat/completions} for a single token. This is the endpoint every surface
     * depends on, so a success here means the drive is usable no matter what {@code /v1/models}
     * says, and a failure carries the server's own words instead of a guess.
     */
    private static ChatProbe chatProbe(String base) {
        String model = Config.get("CODEZAIKU_MODEL", "local-model");
        String body = "{\"model\":\"" + model + "\",\"max_tokens\":1,"
                + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
        try {
            HttpResponse<String> r = HTTP.send(
                    org.codezaiku.drive.DriveClient.auth(
                            HttpRequest.newBuilder(URI.create(base + "/v1/chat/completions"))
                                    .timeout(Duration.ofSeconds(20))
                                    .header("Content-Type", "application/json"))
                            .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            return classify(r.statusCode(), r.body());
        } catch (Exception e) {
            return new ChatProbe(false, "nothing answered /v1/models or /v1/chat/completions");
        }
    }

    /**
     * Turn what the endpoint said into something the operator can act on. Separated from the request
     * so every branch can be exercised without a network: the whole value of this probe is that its
     * wording sends someone to the right problem, and wording is easy to regress silently.
     */
    static ChatProbe classify(int status, String body) {
        if (status >= 200 && status < 300) return new ChatProbe(true, "");
        if (status == 401 || status == 403) {
            return new ChatProbe(false, "the endpoint rejected the credential (HTTP " + status
                    + ") — check CODEZAIKU_API_KEY");
        }
        if (status == 404) {
            return new ChatProbe(false, "no /v1/chat/completions at this URL (HTTP 404) — "
                    + "CODEZAIKU_DRIVE should be the base, without /v1 or /chat/completions");
        }
        return new ChatProbe(false, "the endpoint answered HTTP " + status
                + " on /v1/chat/completions" + firstLine(body));
    }

    /** A short quotation of the server's complaint — providers usually name the real problem. */
    private static String firstLine(String body) {
        if (body == null || body.isBlank()) return "";
        String t = body.strip().replaceAll("\\s+", " ");
        return " — " + (t.length() > 160 ? t.substring(0, 160) + "…" : t);
    }

    private static boolean httpOk(String url) {
        try {
            // Authenticated like any other drive request: a hosted endpoint answers 401 without a
            // key, which this would otherwise report as "nothing answered" — sending the operator
            // hunting for a dead server instead of a missing credential.
            HttpResponse<String> r = HTTP.send(
                    org.codezaiku.drive.DriveClient.auth(
                            HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8))).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return r.statusCode() >= 200 && r.statusCode() < 400;
        } catch (Exception e) {
            return false;
        }
    }

    private static int major(String v) {
        try { return Integer.parseInt(v.split("[.\\-+]")[0]); } catch (Exception e) { return 0; }
    }

    private Doctor() { }
}

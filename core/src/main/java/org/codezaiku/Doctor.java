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
                major >= 21 ? "" : "CodeZaiku targets JDK 21",
                "install a JDK 21+ and put it on PATH"));

        // Which shell commands actually run through. Worth naming because on Windows it is not
        // obvious: System32\bash.exe is the WSL launcher and CreateProcess finds it before anything
        // on PATH, so a bare "bash" silently dispatches every command into a Linux distribution.
        // Reporting it turns "why does this box behave differently" into a one-line answer.
        boolean wsl = org.codezaiku.exec.Shell.isWsl();
        checks.add(new Check("shell: " + org.codezaiku.exec.Shell.describe(), false, !wsl,
                wsl ? "commands run inside WSL, not natively" : "",
                "set CODEZAIKU_SHELL to the shell you want (e.g. a Git Bash bash.exe), or run "
                + "CodeZaiku inside the WSL distribution if that is what you intend"));

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
        checks.add(new Check("model server at " + driveUrl, true, drive,
                drive ? "" : driveDetail.isEmpty() ? "nothing answered /v1/models" : driveDetail,
                "start any OpenAI-compatible server, e.g.\n"
                + "      docker run -d --name codezaiku-drive -p 8200:8200 \\\n"
                + "        -v /path/to/models:/models ghcr.io/ggml-org/llama.cpp:server-cuda \\\n"
                + "        -m /models/<your-model>.gguf --port 8200 --host 0.0.0.0 --jinja\n"
                + "      then: export CODEZAIKU_DRIVE=http://localhost:8200"));

        if (drive) {
            int ctx = 0;
            try { ctx = new DriveClient(driveUrl, "").contextWindow(); } catch (Exception ignored) { }
            checks.add(new Check("model context window", true, ctx >= 8192,
                    ctx > 0 ? ctx + " tokens" : "could not determine",
                    "8k is the practical floor; 32k is comfortable. Restart the server with a larger --ctx-size"));
            // A SECOND, softer floor, because 8k passes the check above and still cannot complete a
            // single backend run. A host dispatch carries its own task preamble on top of our pinned
            // project block: one measured at 8k refused before its first turn — 9,619 tokens into
            // 8,192. Reporting only "ok, 8192 tokens" there is a green light for a configuration that
            // provably does not work, which is the exact shape of instrument this project keeps
            // getting wrong. Optional rather than required: 8k does answer a small interactive task,
            // and failing that user would be its own kind of wrong.
            checks.add(new Check("context window for a host dispatch", false, ctx >= 12288,
                    ctx > 0 ? ctx + " tokens" : "could not determine",
                    "a host's task preamble sits on top of the pinned project block — 8k refuses "
                    + "before the first turn. 12k is the floor for `run`/MCP/ACP, 16k comfortable"));
        }

        // ── optional, per surface ───────────────────────────────────────────
        boolean docker = exec.run("docker info", 15).ok();
        checks.add(new Check("docker", false, docker,
                docker ? "" : "the ops and security surfaces operate on containers",
                "install docker, or use CodeZaiku only for coding/research"));

        String searx = Config.get("CODEZAIKU_SEARXNG", "http://localhost:8888");
        boolean search = httpOk(searx + "/search?q=probe&format=json");
        checks.add(new Check("search backend at " + searx, false, search,
                search ? "" : "the research surface needs it",
                "docker run -d --name codezaiku-searxng -p 8888:8080 \\\n"
                + "        -v $PWD/deploy/searxng:/etc/searxng searxng/searxng:latest\n"
                + "      (the shipped config enables the JSON API, which is off by default)"));

        boolean git = exec.run("git --version", 10).ok();
        checks.add(new Check("git", false, git, git ? "" : "the coding surface commits its work",
                "install git"));

        for (String[] lsp : new String[][]{
                {"pyright", "python"}, {"rust-analyzer", "rust"}, {"gopls", "go"}, {"jdtls", "java"}}) {
            boolean has = exec.run("command -v " + lsp[0], 10).ok();
            if (!has) continue;   // only report the ones present; absence is normal
            checks.add(new Check("lsp: " + lsp[0] + " (" + lsp[1] + ")", false, true, "", ""));
        }

        boolean trivy = exec.run("command -v trivy || command -v grype", 10).ok();
        checks.add(new Check("vulnerability scanner", false, trivy,
                trivy ? "" : "image CVE checks are skipped without one",
                "install trivy (https://trivy.dev) — CodeZaiku never infers CVE status from model memory"));

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
        return failedRequired == 0 ? 0 : 1;
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

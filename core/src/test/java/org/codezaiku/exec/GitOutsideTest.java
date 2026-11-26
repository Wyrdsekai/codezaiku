package org.codezaiku.exec;

import org.codezaiku.Config;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Push and fetch done by CodeZaiku outside the sandbox, against a repository the job poisoned: every way a job's writes to the
 * project's git folder would run a program outside or send the push elsewhere is tried twice, once against a plain {@code git push}
 * in the poisoned project (the positive control: the attack works) and once through {@link GitOutside} (it does nothing). The
 * remote is a git daemon of the test's own on the loopback; the person's home is a folder of the test's; ssh is a script that
 * writes down that it ran and connects nowhere. Every program the poisoned settings name appends its name to a file outside the
 * project, so a line there means it ran outside the sandbox.
 */
class GitOutsideTest {

    @TempDir Path base;
    Path home, person, remotes, project, marks, fired;
    String realHome;
    Process daemon;
    int port;
    Map<String, String> personEnv;
    final List<List<String>> asked = new ArrayList<>();

    @BeforeEach
    void before() throws Exception {
        Sandbox.Found real = Sandbox.probe();
        assumeTrue(real.on(), "skipped: no sandbox on this machine");
        Sandbox.forTests(real);
        for (String d : List.of("home", "person", "remotes", "project", "marks")) Files.createDirectories(base.resolve(d));
        home = base.resolve("home"); person = base.resolve("person"); remotes = base.resolve("remotes"); project = base.resolve("project"); marks = base.resolve("marks");
        fired = marks.resolve("fired.log");
        realHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        Config.invalidate();
        personEnv = new LinkedHashMap<>();
        personEnv.put("PATH", "/usr/local/bin:/usr/bin:/bin");
        personEnv.put("LANG", "C.UTF-8");
        personEnv.put("HOME", person.toString());
        personEnv.put("GIT_CONFIG_NOSYSTEM", "1");
        Sandbox.loginForTests(personEnv);
        GitRemotes.personForTests(personEnv);
        GitRemotes.forgetForTests();
        GitOutside.forgetForTests();
        Sandbox.gitIdentityForTests();
        assumeTrue(git(base, "--version").startsWith("git version"), "skipped: no git");

        // the person's own settings: their identity, and their own ssh program, which writes down that it ran and connects nowhere
        Path personsSsh = program(person.resolve("persons-ssh.sh"), "persons-ssh", 1);
        Files.writeString(person.resolve(".gitconfig"), "[user]\n\tname = Test Person\n\temail = test@example.invalid\n[core]\n\tsshCommand = " + personsSsh + "\n");

        for (String r : List.of("good.git", "evil.git")) {
            git(remotes, "init", "-q", "--bare", "-b", "main", r);
            git(remotes.resolve(r), "config", "receive.certNonceSeed", "a-made-up-seed");    // the servers take signed pushes, so a signing program is asked for
        }
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) { port = s.getLocalPort(); }
        daemon = new ProcessBuilder("git", "daemon", "--reuseaddr", "--export-all", "--enable=receive-pack", "--base-path=" + remotes, "--listen=127.0.0.1",
                "--port=" + port, remotes.toString()).redirectErrorStream(true).redirectOutput(base.resolve("daemon.log").toFile()).start();
        for (int i = 0; i < 100; i++) {
            try (Socket c = new Socket()) { c.connect(new InetSocketAddress("127.0.0.1", port), 200); break; } catch (IOException e) { Thread.sleep(50); }
        }

        // the project as the person left it: a commit, origin on the good server, pushed and fetched once
        git(project, "init", "-q", "-b", "main", ".");
        Files.writeString(project.resolve("a.txt"), "one\n");
        git(project, "add", "a.txt");
        git(project, "commit", "-qm", "first");
        git(project, "remote", "add", "origin", good());
        git(project, "remote", "add", "mirror", "ssh://stand-in.invalid/mirror.git");
        git(project, "remote", "add", "helper", "ext::sh -c touch% " + marks.resolve("ext-ran"));
        git(project, "push", "-q", "origin", "main");
        git(project, "fetch", "-q", "origin");
    }

    @AfterEach
    void after() throws IOException {
        if (daemon != null) { daemon.descendants().forEach(ProcessHandle::destroyForcibly); daemon.destroyForcibly(); }
        GitOutside.asker(project, null);
        Sandbox.forTests(null);
        Sandbox.loginForTests(null);
        GitRemotes.personForTests(null);
        GitRemotes.forgetForTests();
        GitOutside.forgetForTests();
        Sandbox.gitIdentityForTests();
        try {
            Path tmp = Sandbox.folders(project).tmp();
            if (tmp.getFileName().toString().startsWith("cz-") && !tmp.startsWith(base)) {
                try (var walk = Files.walk(tmp)) { for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p); }
            }
        } catch (IOException | RuntimeException e) { /* nothing made */ }
        System.setProperty("user.home", realHome);
        Config.invalidate();
    }

    private String good() { return "git://127.0.0.1:" + port + "/good.git"; }
    private String evil() { return "git://127.0.0.1:" + port + "/evil.git"; }

    private String git(Path dir, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true);
        pb.environment().clear();
        pb.environment().putAll(personEnv);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(60, TimeUnit.SECONDS));
        return out;
    }

    /** A program that appends "{@code name} its arguments" to the marks file and exits with {@code exit}. */
    private Path program(Path at, String name, int exit) throws IOException {
        Files.writeString(at, "#!/bin/sh\necho \"" + name + " $*\" >> '" + marks.resolve("fired.log") + "'\nexit " + exit + "\n");
        at.toFile().setExecutable(true);
        return at;
    }

    private String fired() throws IOException { return Files.exists(fired) ? Files.readString(fired) : ""; }

    /** What the job writes into the project's git folder: every setting and hook named in the design note, each a program of the test's. */
    private void poison() throws Exception {
        Path g = project.resolve(".git");
        for (String h : List.of("pre-push", "reference-transaction", "post-update", "pre-receive", "update", "post-receive", "pre-auto-gc", "post-checkout",
                "post-merge", "pre-commit", "post-commit", "fsmonitor-watchman", "post-index-change", "push-to-checkout")) program(g.resolve("hooks").resolve(h), "hook:" + h, 0);
        Files.createDirectories(g.resolve("other-hooks"));
        for (String h : List.of("pre-push", "reference-transaction")) program(g.resolve("other-hooks").resolve(h), "hooksPath:" + h, 0);
        git(project, "config", "core.hooksPath", g.resolve("other-hooks").toString());
        for (String[] kv : new String[][]{{"core.fsmonitor", "fsmonitor"}, {"core.sshCommand", "project-ssh"}, {"core.askPass", "askpass"},
                {"credential.helper", "credential-helper"}, {"uploadpack.packObjectsHook", "packObjectsHook"}, {"core.alternateRefsCommand", "alternateRefsCommand"},
                {"gpg.program", "gpg"}, {"core.gitProxy", "gitProxy"}, {"filter.evil.clean", "filter"}, {"filter.evil.smudge", "filter"}, {"diff.evil.textconv", "textconv"}}) {
            git(project, "config", "--add", kv[0], program(g.resolve(kv[1] + ".sh"), kv[1], kv[0].equals("core.sshCommand") ? 1 : 0).toString());
        }
        git(project, "config", "protocol.ext.allow", "always");
        git(project, "config", "push.recurseSubmodules", "on-demand");
        git(project, "config", "gc.auto", "1");
        Files.writeString(g.resolve("info").resolve("attributes"), "* filter=evil diff=evil\n");
        // where the push goes: the address, a push address, and a rewrite, all to the other server
        git(project, "config", "remote.origin.url", evil());
        git(project, "config", "remote.origin.pushurl", evil());
        git(project, "config", "url." + evil() + ".insteadOf", good());
        git(project, "config", "url." + evil() + ".pushInsteadOf", good());
    }

    private void commitOnFeature(String author) throws Exception {
        git(project, "checkout", "-q", "-b", "feature");
        Files.writeString(project.resolve("b.txt"), "work of the job\n");
        git(project, "add", "b.txt");
        git(project, "-c", "core.hooksPath=/dev/null", "-c", "core.fsmonitor=false", "-c", "user.name=" + author, "-c", "user.email=" + author.toLowerCase() + "@example.invalid",
                "commit", "-qm", "the job's change");
    }

    private void someoneAsksAndSays(String answer) {
        GitOutside.asker(project, (key, what, preview) -> { asked.add(preview); return answer; });
    }

    // ── the tests ───────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void aPushGoesToTheAddressOfTheJobsStartAndRunsNothingTheJobWrote() throws Exception {
        Sandbox.model(project, false);                              // the job begins: origin is the good server
        commitOnFeature("Fixer");
        poison();
        String sha = git(project, "-c", "core.fsmonitor=false", "rev-parse", "feature").strip();

        // positive control: a plain git push in the poisoned project, as the person would type it after the job
        String viaProxy = git(project, "push", "origin", "feature");
        assertTrue(fired().contains("gitProxy 127.0.0.1"), "control: the job's core.gitProxy ran for a git:// address: " + fired() + viaProxy);
        git(project, "config", "--unset", "core.gitProxy");
        git(project, "config", "push.gpgSign", "true");
        String signed = git(project, "push", "origin", "feature");
        assertTrue(fired().contains("gpg"), "control: the job's gpg.program ran for a signed push: " + fired() + signed);
        git(project, "config", "push.gpgSign", "false");
        String plain = git(project, "push", "origin", "feature");
        String controlFired = fired();
        assertTrue(controlFired.contains("hook:pre-push") || controlFired.contains("hooksPath:pre-push"), "control: the job's pre-push hook ran: " + controlFired + plain);
        assertTrue(controlFired.contains("hooksPath:reference-transaction"), "control: reference-transaction ran: " + controlFired);
        assertEquals(sha, git(remotes.resolve("evil.git"), "rev-parse", "feature").strip(), "control: the push went to the other server");
        Files.delete(fired);

        git(project, "config", "core.gitProxy", program(project.resolve(".git/gitProxy.sh"), "gitProxy", 0).toString());
        git(project, "config", "push.gpgSign", "true");

        // CodeZaiku's push, with someone to ask who says yes
        someoneAsksAndSays(null);
        String out = GitOutside.push(project, "origin", "feature", false);
        assertTrue(out.startsWith("Pushed feature to origin (" + good() + ")"), out);
        assertEquals(sha, git(remotes.resolve("good.git"), "rev-parse", "feature").strip(), "it went to the address of the job's start");
        assertEquals("", fired(), "nothing the job wrote ran outside the sandbox");
        assertEquals(1, asked.size());
        String q = String.join("\n", asked.get(0));
        assertTrue(q.contains("at " + good()) && q.contains("1 commit,") && q.contains("Files: b.txt") && q.contains("Written by: Fixer <fixer@example.invalid>"), q);
        assertTrue(q.contains("has not pushed this project there before") && q.contains("your own git settings, credentials and hooks"), q);
        assertEquals(1, GitOutside.doneSince(project, 0).size());
        assertTrue(GitOutside.lines(project, 0).get(0).startsWith("git: pushed feature to origin (" + good() + "): 1 commit"), GitOutside.lines(project, 0).toString());
    }

    @Test
    void theSshProgramIsThePersonsNeverTheProjectsAndAHelperAddressIsNotUsed() throws Exception {
        Sandbox.model(project, false);
        commitOnFeature("Test Person");
        poison();
        String plain = git(project, "push", "mirror", "feature");
        assertTrue(fired().contains("project-ssh"), "control: the job's core.sshCommand ran on a plain push: " + fired() + plain);
        git(project, "push", "helper", "feature");
        assertTrue(Files.exists(marks.resolve("ext-ran")), "control: the ext:: address ran its command on a plain push");
        Files.delete(fired);
        Files.delete(marks.resolve("ext-ran"));

        someoneAsksAndSays(null);
        String out = GitOutside.push(project, "mirror", "feature", false);
        assertTrue(out.startsWith("Not pushed"), "the person's ssh connects nowhere here: " + out);
        assertTrue(fired().contains("persons-ssh") && !fired().contains("project-ssh"), "the person's ssh program, not the job's: " + fired());
        assertTrue(fired().lines().allMatch(l -> l.startsWith("persons-ssh")), fired());
        String helper = GitOutside.push(project, "helper", "feature", false);
        assertTrue(helper.contains("does not push to helper") && helper.contains("helper program"), helper);
        assertFalse(Files.exists(marks.resolve("ext-ran")));
    }

    @Test
    void anotherRepositoryReachedThroughAlternatesOrALinkedRefIsNotCarriedOut() throws Exception {
        Path secret = base.resolve("secret");
        git(base, "init", "-q", "-b", "main", secret.toString());
        Files.writeString(secret.resolve("notes.txt"), "the person's private notes\n");
        git(secret, "add", "notes.txt");
        git(secret, "commit", "-qm", "private");
        Sandbox.model(project, false);
        Files.writeString(project.resolve(".git/objects/info/alternates"), secret.resolve(".git/objects") + "\n");
        Files.createSymbolicLink(project.resolve(".git/refs/heads/stolen"), secret.resolve(".git/refs/heads/main"));

        // positive control: git's own local fetch, its upload-pack outside the sandbox, carries the other repository out
        Path control = base.resolve("control.git");
        git(base, "init", "-q", "--bare", control.toString());
        git(control, "fetch", "-q", project.toString(), "+refs/heads/stolen:refs/heads/stolen");
        assertTrue(git(control, "show", "stolen:notes.txt").contains("private notes"), "control: the other repository came out");

        someoneAsksAndSays(null);
        String out = GitOutside.push(project, "origin", "stolen", false);
        assertTrue(out.startsWith("Nothing pushed"), out);
        assertTrue(asked.isEmpty(), "nobody was asked: nothing could be read");
        assertTrue(git(remotes.resolve("good.git"), "for-each-ref").lines().noneMatch(l -> l.contains("stolen")));
    }

    @Test
    void namesThatReadAsOptionsOrRefspecsAreRefusedBeforeGitSeesThem() throws Exception {
        Sandbox.model(project, false);
        someoneAsksAndSays(null);
        for (String b : List.of("-x", "--upload-pack=touch", "main:refs/heads/other", "+main", "refs/heads/main", "a..b", "a b", "HEAD", "x\nmain", "@{-1}"))
            assertTrue(GitOutside.push(project, "origin", b, false).startsWith("Nothing pushed"), b);
        assertTrue(GitOutside.push(project, "--receive-pack=touch", "main", false).startsWith("No remote"));
        assertTrue(GitOutside.push(project, "upstream", "main", false).contains("The remotes are: origin (" + good() + ")"));
        assertTrue(asked.isEmpty());
    }

    @Test
    void aJobThatAsksNobodyPushesOnlyWhereTheSettingListsAndNeverForces() throws Exception {
        Sandbox.model(project, false);
        commitOnFeature("Test Person");
        String off = GitOutside.push(project, "origin", "feature", false);
        assertTrue(off.startsWith("Pushing is for the person to allow") && off.contains("codezaiku config set git.push 127.0.0.1/good"), off);
        Config.set("CODEZAIKU_GIT_PUSH", "127.0.0.1/other");               // the settings file of the test's own home
        assertTrue(GitOutside.push(project, "origin", "feature", false).startsWith("Pushing is for the person"));
        Config.set("CODEZAIKU_GIT_PUSH", "127.0.0.1/good");
        assertTrue(GitOutside.push(project, "origin", "feature", true).startsWith("Nothing pushed: a force push needs"), "never a force push unasked");
        String out = GitOutside.push(project, "origin", "feature", false);
        assertTrue(out.startsWith("Pushed feature"), out);
        String doc = GitOutside.document(GitOutside.doneSince(project, 0)).toString();
        assertTrue(doc.contains("\"pushed\":[{\"remote\":\"origin\",\"address\":\"" + good()), doc);
    }

    @Test
    void aFetchBringsTheRemotesCommitsIntoRemoteTrackingBranchesAndRunsNothingOutside() throws Exception {
        Sandbox.model(project, false);
        // someone else pushes to the remote
        Path other = base.resolve("other");
        git(base, "clone", "-q", good(), other.toString());
        Files.writeString(other.resolve("c.txt"), "theirs\n");
        git(other, "add", "c.txt");
        git(other, "commit", "-qm", "their change");
        git(other, "push", "-q", "origin", "main");
        String theirs = git(other, "rev-parse", "HEAD").strip();
        poison();
        // the job pointed origin elsewhere: the fetch still reads the address of the start
        String out = GitOutside.fetch(project, "origin", null);
        assertTrue(out.startsWith("Fetched from origin (" + good() + ")") && out.contains("origin/main"), out);
        assertEquals(theirs, git(project, "-c", "core.fsmonitor=false", "rev-parse", "refs/remotes/origin/main").strip(), out);
        assertEquals("", fired(), "the project's reference-transaction hook ran in the sandbox, where it cannot write the marks folder: " + fired());
        assertEquals(theirs, git(project, "-c", "core.fsmonitor=false", "rev-parse", "refs/remotes/origin/main").strip());
        assertTrue(GitOutside.fetch(project, "origin", "no-such-branch").contains("origin has no branch no-such-branch"));
    }

    @Test
    void aShellCommandThatPushesIsToldWhichToolDoesItWithoutBeingRewritten() throws Exception {
        Sandbox.model(project, false);
        someoneAsksAndSays(null);
        String note = GitOutside.shellNote(project, "git push origin HEAD");
        assertTrue(note.contains("git_push sends a branch") && note.contains("git_fetch") && note.contains("pull_requests"), note);
        assertTrue(GitOutside.shellNote(project, "git -C . fetch origin").contains("git_fetch"));
        assertTrue(GitOutside.shellNote(project, "gh pr list").contains("pull_requests"));
        assertEquals("", GitOutside.shellNote(project, "git status && git commit -m push"));
        GitOutside.asker(project, null);
        assertEquals("", GitOutside.shellNote(project, "git push origin HEAD"), "a job that asks nobody and has no git tools gets no note");
    }

    @Test
    void thePersonsOwnGlobalPrePushHookRunsAndCanRefuseWhileTheProjectsNeverRun() throws Exception {
        Path hooks = Files.createDirectories(person.resolve("persons-hooks"));
        Path refuse = marks.resolve("refuse");
        Files.writeString(hooks.resolve("pre-push"), "#!/bin/sh\necho \"persons-pre-push $* pwd=$(pwd)\" >> '" + fired + "'\n"
                + "[ -e '" + refuse + "' ] && { echo 'persons scanner: a key-shaped string in b.txt'; printf 'in \\033[31mred\\n'; exit 1; }\nexit 0\n");
        hooks.resolve("pre-push").toFile().setExecutable(true);
        Files.writeString(person.resolve(".gitconfig"), "[core]\n\thooksPath = " + hooks + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        Sandbox.model(project, false);
        commitOnFeature("Test Person");
        poison();                                                   // the project's own hooks and hooksPath: the control showed they run on a plain push
        List<String> told = new ArrayList<>();
        GitOutside.asker(project, new GitOutside.Asker() {
            @Override public String ask(String key, String what, List<String> preview) { return null; }
            @Override public void told(String line) { told.add(line); }
        });
        Files.writeString(refuse, "");
        String no = GitOutside.push(project, "origin", "feature", false);
        assertTrue(no.startsWith("Not pushed: the person's own pre-push hook (" + hooks.resolve("pre-push") + ") refused it."), no);
        assertTrue(no.contains("persons scanner: a key-shaped string in b.txt") && no.contains("in [31mred") && !no.contains("\u001b"), "its words, without escapes: " + no);
        assertEquals(1, told.size(), "the person is told too");
        assertTrue(told.get(0).startsWith("Your own pre-push hook") && told.get(0).contains("persons scanner"), told.toString());
        assertTrue(git(remotes.resolve("good.git"), "for-each-ref").lines().noneMatch(l -> l.contains("feature")), "nothing pushed");
        Files.delete(refuse);
        String yes = GitOutside.push(project, "origin", "feature", false);
        assertTrue(yes.startsWith("Pushed feature"), yes);
        String f = fired();
        assertTrue(f.contains("persons-pre-push " + good() + " " + good()), "the person's hook ran, with the address of the job's start: " + f);
        assertTrue(f.lines().allMatch(l -> l.startsWith("persons-pre-push")), "and nothing of the project's: " + f);
        assertTrue(f.contains("pwd=" + home.resolve(".codezaiku/git").toRealPath()), "in CodeZaiku's own repository (pwd prints the real path: on macOS a temp folder is under /private/var): " + f);
    }

    @Test
    void aRelativeHooksPathOfThePersonsResolvesInsideCodeZaikusRepositoryWhereTheJobCannotPutOne() throws Exception {
        Files.writeString(person.resolve(".gitconfig"), "[core]\n\thooksPath = .persons-hooks\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        Sandbox.model(project, false);
        commitOnFeature("Test Person");
        // the job makes a folder of that name in the project and in its git folder
        for (Path d : List.of(project.resolve(".persons-hooks"), project.resolve(".git/.persons-hooks"))) {
            Files.createDirectories(d);
            program(d.resolve("pre-push"), "job-pre-push-in-" + d.getParent().getFileName(), 0);
        }
        someoneAsksAndSays(null);
        assertTrue(GitOutside.push(project, "origin", "feature", false).startsWith("Pushed feature"));
        assertEquals("", fired(), "neither of the job's ran");
        Path bare = GitOutside.bare(project);
        assertEquals(bare.resolve(".persons-hooks/pre-push").toString(),
                bare.resolve(GitRemotes.run(List.of("git", "--git-dir=" + bare, "rev-parse", "--git-path", "hooks/pre-push"), bare, GitRemotes.personEnv(), 30).out().strip()).normalize().toString(),
                "git looks for it inside CodeZaiku's own repository");
        assertFalse(Files.exists(bare.resolve(".persons-hooks")));
    }

    @Test
    void aBranchWithFilesKeptInGitLfsIsNotPushedAndNobodyIsAsked() throws Exception {
        Sandbox.model(project, false);
        someoneAsksAndSays(null);
        git(project, "checkout", "-q", "-b", "media");
        Files.writeString(project.resolve(".gitattributes"), "*.psd filter=lfs diff=lfs merge=lfs -text\n");
        Files.writeString(project.resolve("cover.psd"), "version https://git-lfs.github.com/spec/v1\noid sha256:" + "4d7a214614ab2935c943f9e0ff69d22eadbb8f32b1258daaa5e2ca24d17e2393" + "\nsize 12345\n");
        git(project, "add", ".gitattributes", "cover.psd");
        git(project, "commit", "-qm", "a picture, kept with Git LFS");
        String out = GitOutside.push(project, "origin", "media", false);
        assertTrue(out.startsWith("Nothing pushed: this project keeps large files with Git LFS (cover.psd), which CodeZaiku's push does not upload"), out);
        assertTrue(asked.isEmpty());
        // a pointer with no attributes line is found by its form; a file that only looks a little like one is not
        git(project, "checkout", "-q", "-b", "pointer-only", "main");
        Files.writeString(project.resolve("model.bin"), "version https://git-lfs.github.com/spec/v1\noid sha256:" + "a".repeat(64) + "\nsize 9\n");
        Files.writeString(project.resolve("notes.md"), "version https://git-lfs.github.com/spec/v1 is the pointer format\n");
        git(project, "add", "model.bin", "notes.md");
        git(project, "commit", "-qm", "a pointer");
        assertTrue(GitOutside.push(project, "origin", "pointer-only", false).contains("Git LFS (model.bin)"));
        git(project, "checkout", "-q", "-b", "plain", "main");
        Files.writeString(project.resolve("notes.md"), "version https://git-lfs.github.com/spec/v1 is the pointer format\n");
        git(project, "add", "notes.md");
        git(project, "commit", "-qm", "notes only");
        assertTrue(GitOutside.push(project, "origin", "plain", false).startsWith("Pushed plain"), "the control: a branch without LFS files is pushed");
    }

    @Test
    void anAcpClientIsAskedOnlyWhereTheSettingAllowsAndTheToolsFollowTheSetting() throws Exception {
        Sandbox.model(project, false);
        commitOnFeature("Test Person");
        GitOutside.hostAsker(project, (key, what, preview) -> { asked.add(preview); return null; });
        assertFalse(GitOutside.offered(project, GitOutside.personAsks(project)), "a client's session gets no git tools while git.push is off");
        assertTrue(GitOutside.push(project, "origin", "feature", false).startsWith("Pushing is for the person to allow"));
        assertTrue(asked.isEmpty(), "and its client is not asked: its yes is never the only gate");
        Config.set("CODEZAIKU_GIT_PUSH", "read");
        assertTrue(GitOutside.offered(project, false));
        assertFalse(GitOutside.pushesUnasked(), "read: fetch and pull requests, no push");
        Config.set("CODEZAIKU_GIT_PUSH", "127.0.0.1/good");
        assertTrue(GitOutside.push(project, "origin", "feature", true).startsWith("Nothing pushed: a force push needs the person's own words asking for one, in a chat"));
        assertTrue(GitOutside.push(project, "origin", "feature", false).startsWith("Pushed feature"));
        assertEquals(1, asked.size(), "listed, and the client still asked");
        GitOutside.hostAsker(project, null);
    }

    @Test
    void theRecordIsTheJobsStartAndARemoteAddedLaterIsNotThere() throws Exception {
        Sandbox.model(project, false);
        git(project, "remote", "add", "late", evil());
        GitRemotes.Record rec = GitRemotes.of(project);
        assertNotNull(rec.remote("origin"));
        assertEquals(good(), rec.remote("origin").push().raw());
        assertTrue(rec.remote("late") == null);
        assertFalse(rec.remote("helper").usable());
        assertTrue(rec.tracking().containsKey("refs/remotes/origin/main"), rec.tracking().toString());
    }
}

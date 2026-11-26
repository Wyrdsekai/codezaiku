package org.codezaiku.exec;

import org.codezaiku.Config;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * What a job's command writes into the repository's own settings, hooks and info does not outlive the command. Each kind is tried
 * twice on a real repository with real Landlock: a command of the job (in the sandbox) writes it; "the person" then runs their own
 * git outside before CodeZaiku looks (the positive control: the program the job named runs, a mark appears), the job writes it again,
 * CodeZaiku looks, and the person's git runs nothing. The programs the job names append a line to a file outside the project, which
 * a command cannot write: a line there means it ran outside the sandbox.
 */
class GitDirGuardTest {

    @TempDir Path base;
    Path home, person, project, marks, fired;
    String realHome;
    Map<String, String> personEnv;

    @BeforeEach
    void before() throws Exception {
        Sandbox.Found real = Sandbox.probe();
        assumeTrue(real.on(), "skipped: no sandbox on this machine");
        Sandbox.forTests(real);
        for (String d : List.of("home", "person", "project", "marks")) Files.createDirectories(base.resolve(d));
        home = base.resolve("home"); person = base.resolve("person"); project = base.resolve("project"); marks = base.resolve("marks");
        fired = marks.resolve("fired.log");
        realHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        Config.invalidate();
        personEnv = new LinkedHashMap<>();
        personEnv.put("PATH", "/usr/local/bin:/usr/bin:/bin");
        personEnv.put("LANG", "C.UTF-8");
        personEnv.put("HOME", person.toString());
        personEnv.put("GIT_CONFIG_NOSYSTEM", "1");
        Files.writeString(person.resolve(".gitconfig"), "[user]\n\tname = Test Person\n\temail = test@example.invalid\n[protocol \"file\"]\n\tallow = always\n");
        Sandbox.loginForTests(personEnv);
        GitRemotes.personForTests(personEnv);
        GitRemotes.forgetForTests();
        GitDirGuard.forgetForTests();
        GitDirGuard.pollMillis = 3_600_000;                       // the tests look themselves; the poller has a test of its own
        Sandbox.gitIdentityForTests();
        assumeTrue(git(base, "--version").startsWith("git version"), "skipped: no git");
        git(base, "init", "-q", "--bare", "-b", "main", project.resolve("remote.git").toString());     // in the project: a command may push to it
        git(project, "init", "-q", "-b", "main", ".");
        Files.writeString(project.resolve("a.txt"), "one\n");
        git(project, "add", "a.txt");
        git(project, "commit", "-qm", "first");
        git(project, "remote", "add", "origin", project.resolve("remote.git").toString());
        git(project, "push", "-q", "origin", "main");
    }

    @AfterEach
    void after() throws IOException {
        GitDirGuard.forgetForTests();
        GitDirGuard.pollMillis = 2000;
        Sandbox.forTests(null);
        Sandbox.loginForTests(null);
        GitRemotes.personForTests(null);
        GitRemotes.forgetForTests();
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

    /** The person's own git, outside the sandbox. */
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

    /** A command of the job, in the sandbox. */
    private String job(String script) throws Exception {
        GitDirGuard.commandStarted(project);
        Process p = Sandbox.in(project).shell(script).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(60, TimeUnit.SECONDS));
        return out;
    }

    private String fired() throws IOException { return Files.exists(fired) ? Files.readString(fired) : ""; }

    /** A program, written by the job inside the project, that appends its name to the marks file and succeeds. */
    private String program(String rel, String name) {
        return "mkdir -p \"$(dirname " + rel + ")\"; printf '#!/bin/sh\\necho \"" + name + " $*\" >> " + fired + " 2>/dev/null\\nexit 0\\n' > " + rel + "; chmod +x " + rel + "; ";
    }

    /**
     * One kind: the job's {@code write}, the person's {@code trigger} fires before CodeZaiku looks (the control), then again after
     * CodeZaiku looked it does not; {@code mark} is what the program writes.
     */
    private String kind(String write, Runnable trigger, String mark) throws Exception {
        Sandbox.model(project, false);
        String out = job(write);
        trigger.run();
        assertTrue(fired().contains(mark), "control: the person's own git ran the job's program before CodeZaiku looked: [" + fired() + "] " + out);
        Files.delete(fired);
        String note = GitDirGuard.afterCommand(project);
        assertTrue(note.contains("[note: CodeZaiku put back what changed in the repository's own git settings"), note);
        trigger.run();
        assertEquals("", fired(), "after CodeZaiku looked, the person's git runs nothing of the job's");
        return note;
    }

    private Runnable person(Path dir, String... args) { return () -> { try { git(dir, args); } catch (Exception e) { throw new RuntimeException(e); } }; }

    // ── each kind ───────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void aFileMonitorTheJobSetIsPutBack() throws Exception {
        String note = kind(program(".git/fsm.sh", "fsmonitor") + "git config core.fsmonitor \"$PWD/.git/fsm.sh\"", person(project, "status", "--short"), "fsmonitor");
        assertTrue(note.contains(".git/config (core.fsmonitor)"), note);
        assertTrue(GitDirGuard.lines(project, 0).get(1).contains("core.fsmonitor = ") && GitDirGuard.lines(project, 0).get(1).contains("git will run"), GitDirGuard.lines(project, 0).toString());
        String copy = GitDirGuard.since(project, 0).get(0).copy();
        assertTrue(Files.readString(Path.of(copy)).contains("fsmonitor"), "the job's version is kept: " + copy);
    }

    /**
     * A project opened through a link (a folder reached by a link, as /var and /tmp are on macOS, but one the sandbox's rules do not
     * name): the rules hold real paths, and on macOS a command that named the project as it was opened was refused ("deny(1)
     * file-read-metadata" of the link), the put-back helper among them, which then answered nothing and was taken to have put back.
     */
    @Test
    void aProjectOpenedThroughALinkIsPutBackAndACommandCanNameIt() throws Exception {
        Path link = Files.createSymbolicLink(base.resolve("opened-here"), project);
        Sandbox.model(link, false);
        GitDirGuard.commandStarted(link);
        Process p = Sandbox.in(link).shell(program(".git/fsm.sh", "fsmonitor") + "git config core.fsmonitor \"$PWD/.git/fsm.sh\"; cd '" + link + "' && echo cd-ok")
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(60, TimeUnit.SECONDS));
        assertTrue(out.contains("cd-ok"), "a command can go to the project as it was opened: " + out);
        String note = GitDirGuard.afterCommand(link);
        assertTrue(note.contains(".git/config (core.fsmonitor)"), note);
        assertEquals("", git(project, "config", "--get", "core.fsmonitor").strip(), "put back in the folder the link leads to");
        assertTrue(GitDirGuard.lines(link, 0).stream().noneMatch(l -> l.contains("could not be put back")), GitDirGuard.lines(link, 0).toString());
        git(project, "status", "--short");
        assertEquals("", fired(), "the person's git runs nothing of the job's");
    }

    /** A put-back whose helper gives no answer is said not to have been made, and is made at the next look. */
    @Test
    void aPutBackTheHelperDidNotAnswerIsSaidAndMadeAtTheNextLook() throws Exception {
        Sandbox.model(project, false);
        job(program(".git/fsm.sh", "fsmonitor") + "git config core.fsmonitor \"$PWD/.git/fsm.sh\"");
        try {
            GitDirGuard.pythonForApplyTests = Files.isExecutable(Path.of("/usr/bin/false")) ? "/usr/bin/false" : "/bin/false";
            GitDirGuard.afterCommand(project);
        } finally {
            GitDirGuard.pythonForApplyTests = null;
        }
        assertTrue(git(project, "config", "--get", "core.fsmonitor").contains("fsm.sh"), "the instrument: nothing was put back");
        String said = String.join("\n", GitDirGuard.lines(project, 0));
        assertTrue(said.contains("could not be put back: the helper did not answer"), said);
        job("true");
        GitDirGuard.afterCommand(project);
        assertEquals("", git(project, "config", "--get", "core.fsmonitor").strip(), "the next look puts it back");
    }

    @Test
    void aPreCommitHookTheJobWroteIsTakenOut() throws Exception {
        kind(program(".git/hooks/pre-commit", "pre-commit"), person(project, "commit", "-q", "--allow-empty", "-m", "by the person"), "pre-commit");
        assertFalse(Files.exists(project.resolve(".git/hooks/pre-commit")));
    }

    @Test
    void anSshProgramTheJobSetIsPutBack() throws Exception {
        git(project, "remote", "add", "mirror", "ssh://stand-in.invalid/mirror.git");
        kind(program(".git/ssh.sh", "project-ssh") + "git config core.sshCommand \"$PWD/.git/ssh.sh\"", person(project, "fetch", "-q", "mirror"), "project-ssh");
    }

    @Test
    void anAddressRewriteAndAChangedRemoteArePutBack() throws Exception {
        git(base, "init", "-q", "--bare", base.resolve("evil.git").toString());
        Sandbox.model(project, false);
        job("git config url." + base.resolve("evil.git") + ".pushInsteadOf " + project.resolve("remote.git") + "; git remote set-url --add --push origin " + base.resolve("evil.git"));
        assertTrue(git(project, "remote", "get-url", "--push", "--all", "origin").contains("evil.git"), "control: the person's push would go elsewhere");
        String note = GitDirGuard.afterCommand(project);
        assertTrue(note.contains("put back"), note);
        assertEquals(project.resolve("remote.git").toString(), git(project, "remote", "get-url", "--push", "--all", "origin").strip());
    }

    @Test
    void anAliasThatRunsAShellCommandIsPutBack() throws Exception {
        kind(program(".git/alias.sh", "alias") + "git config alias.st '!" + "\"$PWD/.git/alias.sh\"'", person(project, "st"), "alias");
    }

    @Test
    void aFilterSwitchedOnInInfoAttributesIsPutBack() throws Exception {
        Runnable add = () -> { try { Files.writeString(project.resolve("a.txt"), "the person's line " + System.nanoTime() + "\n"); git(project, "add", "a.txt"); } catch (Exception e) { throw new RuntimeException(e); } };
        kind(program(".git/filter.sh", "filter") + "git config filter.evil.clean \"$PWD/.git/filter.sh\"; printf '* filter=evil\\n' > .git/info/attributes", add, "filter");
        assertFalse(Files.exists(project.resolve(".git/info/attributes")) && Files.readString(project.resolve(".git/info/attributes")).contains("evil"));
    }

    @Test
    void anIncludeOfAFileOfTheProjectIsPutBack() throws Exception {
        kind(program("tools/fsm.sh", "included-fsmonitor") + "printf '[core]\\n\\tfsmonitor = %s/tools/fsm.sh\\n' \"$PWD\" > tools/settings.cfg; git config include.path ../tools/settings.cfg",
                person(project, "status", "--short"), "included-fsmonitor");
        assertTrue(Files.exists(project.resolve("tools/settings.cfg")), "the project's own file stays: only the switch is put back");
    }

    @Test
    void aHooksPathPointedAtAFolderOfTheProjectIsPutBack() throws Exception {
        kind(program(".githooks/pre-commit", "project-hooks") + "git config core.hooksPath .githooks", person(project, "commit", "-q", "--allow-empty", "-m", "x"), "project-hooks");
        assertTrue(Files.exists(project.resolve(".githooks/pre-commit")), "the shared hook stays a file of the project; switching it on is the person's");
    }

    @Test
    void aHookInASubmodulesOwnFolderIsTakenOut() throws Exception {
        Path lib = base.resolve("lib");
        git(base, "init", "-q", "-b", "main", lib.toString());
        git(lib, "commit", "-q", "--allow-empty", "-m", "lib");
        git(project, "submodule", "add", "-q", lib.toString(), "sub");
        git(project, "commit", "-qm", "a submodule");
        kind(program(".git/modules/sub/hooks/pre-commit", "submodule-hook"), person(project.resolve("sub"), "commit", "-q", "--allow-empty", "-m", "x"), "submodule-hook");
    }

    @Test
    void aWorktreesOwnSettingsArePutBack() throws Exception {
        git(project, "worktree", "add", "-q", "wt", "-b", "wtb");
        kind(program(".git/wt-fsm.sh", "worktree-fsmonitor") + "git config extensions.worktreeConfig true; printf '[core]\\n\\tfsmonitor = %s/.git/wt-fsm.sh\\n' \"$PWD\" > .git/worktrees/wt/config.worktree",
                person(project.resolve("wt"), "status", "--short"), "worktree-fsmonitor");
    }

    @Test
    void aRepositoryTheJobMakesInAFolderKeepsOnlyWhatGitInitWrites() throws Exception {
        Sandbox.model(project, false);
        job("git init -q -b main inner && cd inner && echo x > f && git add f && git commit -qm one && git checkout -q -b two && git commit -q --allow-empty -m two");
        assertEquals("", GitDirGuard.afterCommand(project), "an ordinary new repository: nothing put back");
        job(program("inner/.git/hooks/post-commit", "inner-hook") + "cd inner && git config core.fsmonitor \"$PWD/.git/hooks/post-commit\"");
        git(project.resolve("inner"), "commit", "-q", "--allow-empty", "-m", "by the person");
        assertTrue(fired().contains("inner-hook"), "control: " + fired());
        Files.delete(fired);
        assertTrue(GitDirGuard.afterCommand(project).contains("inner/.git"));
        git(project.resolve("inner"), "commit", "-q", "--allow-empty", "-m", "by the person");
        git(project.resolve("inner"), "status", "--short");
        assertEquals("", fired());
    }

    @Test
    void aProjectThatWasNoRepositoryCanBeMadeOneButItsHooksDoNotStay() throws Exception {
        Path fresh = base.resolve("fresh");
        Files.createDirectories(fresh);
        Path keep = project;
        project = fresh;
        try {
            Sandbox.model(project, false);
            job("git init -q -b main && echo x > f && git add f && git commit -qm one && git tag v1 && git branch b2");
            assertEquals("", GitDirGuard.afterCommand(project), "git init and commits: nothing put back");
            job(program(".git/hooks/pre-commit", "fresh-hook"));
            assertTrue(GitDirGuard.afterCommand(project).contains(".git/hooks/pre-commit"));
            assertFalse(Files.exists(fresh.resolve(".git/hooks/pre-commit")));
            assertTrue(Files.exists(fresh.resolve(".git/hooks/pre-commit.sample")), "git init's samples stay");
        } finally {
            try (var walk = Files.walk(Sandbox.folders(fresh).tmp())) { for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p); } catch (IOException e) { /* none */ }
            project = keep;
        }
    }

    @Test
    void ordinaryWorkLeavesNothingPutBackAndKeepsAnUpstream() throws Exception {
        Sandbox.model(project, false);
        String out = job("git checkout -q -b feat && echo b > b && git add b && git commit -qm two && git push -q -u origin feat"
                + " && git checkout -q -b feat2 --track origin/main && git switch -q main && git merge -q --no-edit feat"
                + " && echo c >> a.txt && git commit -qam three && git checkout -q feat && git rebase -q main"
                + " && echo s >> a.txt && git stash -q && git stash pop -q && git checkout -q a.txt && git tag v1 && git tag -a v2 -m v2"
                + " && git worktree add -q wt2 -b wt2b && git branch -m feat2 feat3 && git gc -q && echo done");
        assertTrue(out.contains("done"), out);
        assertEquals("", GitDirGuard.afterCommand(project), "nothing of ordinary work is put back");
        assertEquals("origin", git(project, "config", "branch.feat.remote").strip(), "an upstream stays");
        assertEquals("refs/heads/main", git(project, "config", "branch.feat3.merge").strip());
        assertTrue(Files.isRegularFile(project.resolve("wt2/.git")), "the worktree stays");
        job("git config branch.feat.remote " + project.resolve("remote.git"));
        assertTrue(GitDirGuard.afterCommand(project).contains("branch.feat.remote"), "an upstream that is an address, not a remote, is put back");
        assertEquals("origin", git(project, "config", "branch.feat.remote").strip());
    }

    @Test
    void aSwappedGitFolderIsPutBackAndAHooksLinkToTheHomeFolderIsNeverFollowed() throws Exception {
        Path secret = Files.createDirectories(person.resolve(".ssh"));
        Files.writeString(secret.resolve("id_test"), "a made-up key\n");
        Sandbox.model(project, false);
        String swapped = job("mv .git .git-real && git init -q -b main . && " + program(".git/hooks/pre-commit", "impostor") + " echo swapped");
        String swapNote = GitDirGuard.afterCommand(project);
        assertTrue(swapNote.contains(".git"), swapped + " / " + swapNote + " / " + GitDirGuard.since(project, 0));
        assertTrue(GitDirGuard.since(project, 0).stream().anyMatch(p -> p.change().contains("the project's .git is not the folder it was")), GitDirGuard.since(project, 0).toString());
        assertTrue(git(project, "log", "--oneline").contains("first"), "the project's own history is back");
        assertEquals("a made-up key\n", Files.readString(secret.resolve("id_test")));
        // a hooks folder that becomes a link to a folder of the person's: put back without reading or writing through it
        job("rm -rf .git/hooks && ln -s " + secret + " .git/hooks");
        String note = GitDirGuard.afterCommand(project);
        assertTrue(note.contains(".git/hooks"), note);
        assertTrue(Files.isDirectory(project.resolve(".git/hooks")) && !Files.isSymbolicLink(project.resolve(".git/hooks")));
        assertEquals("a made-up key\n", Files.readString(secret.resolve("id_test")), "the person's folder is untouched");
        try (var walk = Files.walk(home.resolve(".codezaiku/git-put-back"))) {
            assertTrue(walk.filter(Files::isRegularFile).noneMatch(p -> { try { return Files.readString(p).contains("made-up key"); } catch (IOException e) { return false; } }),
                    "nothing of the person's folder was read into the copies");
        }
    }

    @Test
    void inAChatThePersonIsAskedAndAYesKeepsExactlyThatChange() throws Exception {
        Sandbox.model(project, false);
        List<List<String>> asked = new ArrayList<>();
        GitDirGuard.asker(project, (key, what, preview) -> { asked.add(preview); return null; });
        job("mkdir -p .githooks && printf '#!/bin/sh\\nexit 0\\n' > .githooks/pre-commit && chmod +x .githooks/pre-commit && git config core.hooksPath .githooks");
        String note = GitDirGuard.afterCommand(project);
        assertTrue(note.contains("the person kept the change"), note);
        String q = String.join("\n", asked.get(0));
        assertTrue(q.contains("by a command of this chat") && q.contains("core.hookspath = .githooks (added): git will run the hooks in .githooks")
                && q.contains("With a yes, CodeZaiku puts the changed version back in place"), q);
        assertEquals(".githooks", git(project, "config", "core.hooksPath").strip(), "kept");
        assertEquals("", GitDirGuard.afterCommand(project), "and taken as the start: not put back again");
        GitDirGuard.asker(project, (key, what, preview) -> "no");
        job("git config core.pager cat");
        assertTrue(GitDirGuard.afterCommand(project).contains("put back"));
        assertEquals("", git(project, "config", "core.pager").strip());
    }

    @Test
    void thePollerPutsBackWhatABackgroundTaskWritesBetweenCommands() throws Exception {
        GitDirGuard.pollMillis = 200;
        Sandbox.model(project, false);
        Process bg = Sandbox.in(project).shell("sleep 1; git config core.fsmonitor /bin/true; sleep 3").start();
        try {
            long until = System.currentTimeMillis() + 6000;
            while (System.currentTimeMillis() < until && GitDirGuard.since(project, 0).isEmpty()) Thread.sleep(100);
            assertFalse(GitDirGuard.since(project, 0).isEmpty(), "the poller found it while the task still ran");
            assertEquals("", git(project, "config", "core.fsmonitor").strip());
            assertTrue(GitDirGuard.since(project, 0).get(0).whileCommand(), "said to be during a command: a process of the job was alive");
        } finally {
            bg.destroyForcibly();
            bg.waitFor(5, TimeUnit.SECONDS);
        }
        List<String> end = GitDirGuard.ended(project, 0, true);
        assertTrue(end.get(0).startsWith("git settings: commands of this job changed"), end.toString());
    }
}

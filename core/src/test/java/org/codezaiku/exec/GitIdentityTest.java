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
 * {@code git commit} in a sandboxed command works as it did before the sandbox: the person's name and address, as git reads them
 * outside for this project, are given to the run's own git settings, and nothing else of the person's git settings is (no credential
 * helper, no signing, no address rewrites). Where the person set no identity nothing is given and git says so itself. The person's
 * home here is a folder of the test's; the commits are real, under Landlock where the machine has it.
 */
class GitIdentityTest {

    @TempDir Path home;
    @TempDir Path personsHome;
    @TempDir Path project;
    String realHome;
    Path madeTmp;

    @BeforeEach
    void before() {
        realHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        Config.invalidate();
        Sandbox.gitIdentityForTests();
    }

    @AfterEach
    void after() throws IOException {
        Sandbox.forTests(null);
        Sandbox.loginForTests(null);
        Sandbox.gitIdentityForTests();
        if (madeTmp != null && Files.isDirectory(madeTmp) && madeTmp.getFileName().toString().startsWith("cz-")) {
            try (var walk = Files.walk(madeTmp)) {
                for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
            }
        }
        System.setProperty("user.home", realHome);
        Config.invalidate();
    }

    private static String git(Path dir, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("git", "-C", dir.toString()));
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        pb.environment().keySet().removeIf(k -> k.startsWith("GIT_"));
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(30, TimeUnit.SECONDS));
        return out;
    }

    /** The machine's sandbox, the person's home a folder of the test's holding {@code gitconfig}, and a repository to commit in. */
    private void setUp(String gitconfig) throws Exception {
        Sandbox.Found real = Sandbox.probe();
        assumeTrue(real.on(), "skipped: no sandbox on this machine");
        assumeTrue(git(project, "--version").startsWith("git version"), "skipped: no git");
        Sandbox.forTests(real);
        Files.writeString(personsHome.resolve(".gitconfig"), gitconfig);
        Map<String, String> login = new LinkedHashMap<>();
        login.put("PATH", "/usr/local/bin:/usr/bin:/bin");
        login.put("LANG", "C.UTF-8");
        login.put("HOME", personsHome.toString());
        Sandbox.loginForTests(login);
        madeTmp = Sandbox.folders(project).tmp();
        git(project, "init", "-q", ".");
    }

    private String sh(String script) throws Exception {
        Process p = Sandbox.in(project).shell(script).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(60, TimeUnit.SECONDS));
        return out;
    }

    @Test
    void inTheSandboxACommitIsThePersonsWithNoSettingAndNothingElseOfTheirGitSettingsComes() throws Exception {
        setUp("""
                [user]
                	name = Test Person
                	email = test@example.invalid
                	signingkey = ABCDEF0123456789
                [commit]
                	gpgSign = true
                [credential]
                	helper = store
                [url "https://mirror.example.invalid/"]
                	insteadOf = https://github.com/
                """);
        String out = sh("git commit --allow-empty -qm first 2>&1; git log -1 --format='by=%an <%ae>'; echo name=$(git config user.name);"
                + " echo helper=[$(git config credential.helper)]; echo sign=[$(git config commit.gpgsign)]; echo rewrite=[$(git config --get-regexp '^url\\.')]");
        assertTrue(out.contains("by=Test Person <test@example.invalid>"), "the commit is the person's: " + out);
        assertTrue(out.contains("name=Test Person"), "git config user.name answers inside too: " + out);
        assertTrue(out.contains("helper=[]") && out.contains("sign=[]") && out.contains("rewrite=[]"), "nothing else of theirs: " + out);
        assertFalse(Files.readString(personsHome.resolve(".gitconfig")).isEmpty(), "the instrument: the person's settings were there to leak");

        git(project, "config", "user.name", "Project Name");
        Sandbox.gitIdentityForTests();
        String again = sh("git commit --allow-empty -qm second 2>&1; git log -1 --format='by=%an <%ae>'");
        assertTrue(again.contains("by=Project Name <test@example.invalid>"), "the project's own setting first: " + again);
    }

    /**
     * A hosted model's job on 2026-11-24 committed with {@code git -c user.name="Fixer" -c user.email="fixer@example.com" commit}: a
     * command's own settings won over the run's git config. With the person's identity in the four variables git reads first, the
     * commit is the person's; {@code --author=} still sets the author, as git says. The control is the same command with the four
     * variables taken out of its environment: the old behaviour, Fixer.
     */
    @Test
    void aCommandsOwnUserSettingDoesNotWinButAnExplicitAuthorStillDoes() throws Exception {
        setUp("[user]\n\tname = Test Person\n\temail = test@example.invalid\n");
        String fixer = "git -c user.name=\"Fixer\" -c user.email=\"fixer@example.com\" commit --allow-empty -qm fix 2>&1;"
                + " git log -1 --format='by=%an <%ae> committed=%cn <%ce>'; echo name=$(git config user.name)";
        String out = sh(fixer);
        assertTrue(out.contains("by=Test Person <test@example.invalid> committed=Test Person <test@example.invalid>"), "the person's: " + out);
        assertTrue(out.contains("name=Test Person"), "git config user.name still answers inside: " + out);

        Process p = Sandbox.in(project).env(e -> e.keySet().removeAll(List.of("GIT_AUTHOR_NAME", "GIT_AUTHOR_EMAIL", "GIT_COMMITTER_NAME", "GIT_COMMITTER_EMAIL")))
                .shell(fixer).redirectErrorStream(true).start();
        String control = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(60, TimeUnit.SECONDS));
        assertTrue(control.contains("by=Fixer <fixer@example.com> committed=Fixer <fixer@example.com>"), "control, the config file alone: " + control);

        String asked = sh("git commit --allow-empty -qm asked --author='Asked For <asked@example.invalid>' 2>&1; git log -1 --format='by=%an <%ae> committed=%cn <%ce>'");
        assertTrue(asked.contains("by=Asked For <asked@example.invalid> committed=Test Person <test@example.invalid>"), "--author as git says: " + asked);
    }

    @Test
    void whereThePersonSetNoIdentityNothingIsGivenAndGitSaysSo() throws Exception {
        setUp("[core]\n\tautocrlf = false\n");
        String out = sh("git commit --allow-empty -qm first 2>&1; echo exit=$?");
        assumeTrue(!out.contains("exit=0"), "skipped: git here gives an identity of its own when none is set (on macOS it makes one from the account and the machine's name)");
        assertTrue(out.contains("Author identity unknown") || out.contains("Please tell me who you are"), out);
        Path runConfig = Sandbox.folders(project).home().resolve(".config/git/config");
        assertFalse(Files.exists(runConfig) && Files.readString(runConfig).contains("[user]"), "no identity written");
        assertTrue(sh("echo vars=[$GIT_AUTHOR_NAME$GIT_AUTHOR_EMAIL$GIT_COMMITTER_NAME$GIT_COMMITTER_EMAIL]").contains("vars=[]"), "and nothing passed");
    }

    /**
     * A sandboxed command's git asks no credential helper of the system's (nor the person's, whose settings it does not read): the run's
     * own settings file empties git's list of helpers, which gitcredentials(7) documents for an empty value, and git reads that file after
     * the system's. On macOS the settings that come with Apple's git name the keychain's helper, and it was asked after every fetch
     * through the proxy. The system's settings here are a file of the test's naming a helper of its own that writes down that it was
     * asked; its first, empty line clears whatever helper the machine's own git brings, so no real helper is ever started. The control
     * is the same command reading no file of the run's ({@code GIT_CONFIG_GLOBAL=/dev/null}): the helper is asked.
     */
    @Test
    void aSandboxedCommandsGitAsksNoCredentialHelperOfTheSystems() throws Exception {
        setUp("[user]\n\tname = Test Person\n\temail = test@example.invalid\n");
        Path helper = project.resolve("helper.sh");
        Files.writeString(helper, "#!/bin/sh\necho \"asked $1\" >> '" + project.resolve("asked.txt") + "'\n");
        assertTrue(helper.toFile().setExecutable(true));
        Path system = project.resolve("system-gitconfig");
        Files.writeString(system, "[credential]\n\thelper =\n\thelper = " + helper + "\n");
        Map<String, String> login = new LinkedHashMap<>();
        login.put("PATH", "/usr/local/bin:/usr/bin:/bin");
        login.put("LANG", "C.UTF-8");
        login.put("HOME", personsHome.toString());
        login.put("GIT_CONFIG_SYSTEM", system.toString());
        Sandbox.loginForTests(login);
        String fill = "printf 'protocol=https\\nhost=cz-trial.invalid\\n\\n' | GIT_TERMINAL_PROMPT=0 git credential fill 2>&1; echo helpers=[$(git config --get-all credential.helper | tr '\\n' ',')]";
        String out = sh(fill);
        assertFalse(Files.exists(project.resolve("asked.txt")), "no helper asked inside: " + out);
        assertTrue(out.contains("terminal prompts disabled"), "git found no helper and no terminal: " + out);

        String control = sh("export GIT_CONFIG_GLOBAL=/dev/null; " + fill);
        assertTrue(Files.exists(project.resolve("asked.txt")) && Files.readString(project.resolve("asked.txt")).contains("asked get"),
                "control, without the run's settings file: the system's helper is asked: " + control);
    }
}

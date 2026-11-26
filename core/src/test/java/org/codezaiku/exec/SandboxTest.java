package org.codezaiku.exec;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.codezaiku.Config;
import org.codezaiku.tools.PathScope;
import org.codezaiku.tools.ShellTool;
import org.codezaiku.tools.ToolRegistry;
import org.codezaiku.verify.ProjectTests;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The commands a coding job runs are sandboxed to the project folder. A path check in the tools cannot see a path a script builds
 * while it runs, and with a hosted model whatever a command prints goes to the host; the operating system closes it.
 *
 * <p>The first tests are of the parts that need no sandbox: the rules built for a run, the environment a command gets, and what
 * happens where the machine has none. The tests named "inTheSandbox" start real commands under the machine's own sandbox (Landlock,
 * or sandbox-exec on macOS) and the ones named "underLandlock" need Landlock; each is skipped, with the reason said, on a machine
 * without. They use the shell, the Python that runs the Landlock helper and the JDK the suite runs on, and nothing else of the
 * machine.
 */
class SandboxTest {

    @TempDir Path home;
    @TempDir Path project;
    @TempDir Path outside;
    String realHome;
    Path madeTmp;

    @BeforeEach
    void before() {
        realHome = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        Config.invalidate();
    }

    @AfterEach
    void after() throws IOException {
        Sandbox.forTests(null);
        Sandbox.loginForTests(null);
        Sandbox.model(project, false);
        if (madeTmp != null && Files.isDirectory(madeTmp) && madeTmp.getFileName().toString().startsWith("cz-")) {
            try (var walk = Files.walk(madeTmp)) {
                for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
            }
        }
        System.setProperty("user.home", realHome);
        Config.invalidate();
    }

    private static final Sandbox.Found NONE = new Sandbox.Found(Sandbox.Kind.NONE, "this kernel has no Landlock", 0, false, null);

    private Sandbox.Folders folders() throws IOException {
        Sandbox.Folders f = Sandbox.folders(project);
        madeTmp = f.tmp();
        return f;
    }

    private SandboxRules rules(Map<String, String> env, boolean mac) throws IOException {
        return SandboxRules.of(project, folders(), env, List.of(), List.of(), List.of(home), mac, true);
    }

    // ── the rules built for a run ───────────────────────────────────────────────────────────────────────────────────

    @Test
    void theProjectAndTheRunsOwnFoldersAreWrittenAndTheHomeIsNot() throws Exception {
        Sandbox.Folders f = folders();
        SandboxRules r = rules(Map.of("PATH", "/usr/bin:/bin"), false);
        assertTrue(r.writes(project.resolve("src/Main.java")), "the project: " + r.readWrite());
        assertTrue(r.writes(f.home().resolve(".m2/settings.xml")) && r.writes(f.cache().resolve("gradle")) && r.writes(f.tmp().resolve("x")),
                "the run's home, caches and temp folder: " + r.readWrite());
        assertTrue(f.home().startsWith(home.resolve(".codezaiku/envs")), "the run's home is under CodeZaiku's own folder: " + f.home());
        Files.writeString(home.resolve("notes.txt"), "mine");
        assertFalse(r.reads(home.resolve("notes.txt")), "a file of the person's home is not read");
        assertFalse(r.reads(home.resolve(".codezaiku/config")), "nor CodeZaiku's settings, which hold keys");
        Files.writeString(outside.resolve("canary.txt"), "x");
        assertFalse(r.reads(outside.resolve("canary.txt")), "nor a file in another folder");
        assertTrue(r.reads(Path.of("/usr/bin/env")) && !r.writes(Path.of("/usr/bin/env")), "the system is read, not written");
        for (Path p : r.readOnly()) assertFalse(SandboxRules.real(home).startsWith(p), "no rule opens the home or a folder around it: " + p);
        for (Path p : r.readWrite()) assertFalse(SandboxRules.real(home).startsWith(p), "no rule opens the home or a folder around it: " + p);
    }

    @Test
    void aToolchainUnderTheHomeIsOpenedByItsOwnFoldersOnly() throws Exception {
        Path py = Files.createDirectories(home.resolve("tools/python/bin")).getParent();
        Files.createDirectories(py.resolve("lib/python3"));
        Files.writeString(Files.createDirectories(py.resolve("private")).resolve("token.txt"), "t");
        Path local = Files.createDirectories(home.resolve(".local/bin")).getParent();
        Files.createDirectories(local.resolve("lib"));
        Files.writeString(Files.createDirectories(local.resolve("share/keyrings")).resolve("login.keyring"), "k");
        Path jdk = Files.createDirectories(home.resolve("jdks/21/bin")).getParent();
        Files.createDirectories(home.resolve("bin"));
        Map<String, String> env = Map.of("PATH", py.resolve("bin") + ":" + local.resolve("bin") + ":" + home.resolve("bin") + ":/usr/bin", "JAVA_HOME", jdk.toString());
        SandboxRules r = rules(env, false);
        assertTrue(r.reads(py.resolve("bin/python3")) && r.reads(py.resolve("lib/python3/os.py")), "bin and the folders beside it: " + r.readOnly());
        assertFalse(r.reads(py.resolve("private/token.txt")), "not what else lies in the toolchain's folder");
        assertTrue(r.reads(local.resolve("bin/tool")) && r.reads(local.resolve("lib/x")), "~/.local/bin and lib: " + r.readOnly());
        assertFalse(r.reads(local.resolve("share/keyrings/login.keyring")), "~/.local/share is the person's own data");
        assertTrue(r.reads(jdk.resolve("conf/security/java.security")), "a folder JAVA_HOME names, all of it: " + r.readOnly());
        assertTrue(r.reads(home.resolve("bin/mytool")), "~/bin itself: " + r.readOnly());
        Files.writeString(home.resolve("notes.txt"), "mine");
        assertFalse(r.reads(home.resolve("notes.txt")), "and never the home around it");
    }

    @Test
    void aGitWorktreeMayWriteTheRepositoryItsGitFilePointsAt() throws Exception {
        Path repo = Files.createDirectories(outside.resolve("main/.git/worktrees/wt"));
        Files.writeString(repo.resolve("commondir"), "../..\n");
        Files.writeString(project.resolve(".git"), "gitdir: " + repo + "\n");
        Files.writeString(outside.resolve("main/README.md"), "the main checkout's own file");
        SandboxRules r = rules(Map.of("PATH", "/usr/bin"), false);
        assertTrue(r.writes(repo.resolve("index")) && r.writes(outside.resolve("main/.git/objects/ab/cd")), "git's folders: " + r.readWrite());
        assertFalse(r.reads(outside.resolve("main/README.md")), "not the other checkout's files");
    }

    @Test
    void theRulesGoToTheHelperAsOptionsAndToMacOsAsAProfile() throws Exception {
        Sandbox.Folders f = folders();
        SandboxRules linux = rules(Map.of("PATH", "/usr/bin"), false);
        List<String> args = linux.landlockArgs();
        assertEquals("--", args.get(args.size() - 1), "the command follows: " + args);
        assertTrue(args.contains("--no-unix"), "UNIX sockets closed: " + args);
        assertEquals("--rw", args.get(args.indexOf(SandboxRules.real(project).toString()) - 1), "the project is --rw: " + args);
        assertFalse(SandboxRules.of(project, f, Map.of(), List.of(), List.of(), List.of(home), false, false).landlockArgs().contains("--no-unix"), "and open when the setting says so");
        Path named = Files.createDirectories(outside.resolve("a \"quoted\" name"));
        String profile = SandboxRules.of(project, f, Map.of("PATH", "/usr/bin"), List.of(named), List.of(), List.of(home), true, true).seatbeltProfile();
        assertTrue(profile.startsWith("(version 1)\n(deny default)"), "everything refused first: " + profile);
        assertTrue(profile.contains("(subpath \"" + SandboxRules.real(project) + "\")"), "then the project: " + profile);
        assertTrue(profile.contains("a \\\"quoted\\\" name"), "a quote in a path is escaped: " + profile);
        assertFalse(profile.contains("(subpath \"" + SandboxRules.real(home) + "\")"), "the home is not opened: " + profile);
        assertFalse(profile.contains("(allow network-outbound)\n"), "socket files outside the run's folders stay refused");
    }

    // ── the environment ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void whatLooksLikeACredentialIsNotPassedAndTheRunGetsAHomeOfItsOwn() throws Exception {
        Sandbox.Folders f = folders();
        Map<String, String> base = new LinkedHashMap<>();
        for (String secret : List.of("OPENAI_API_KEY", "GH_TOKEN", "AWS_SECRET_ACCESS_KEY", "AWS_ACCESS_KEY_ID", "DB_PASSWORD", "MYSQL_PWD", "NPM_AUTH",
                "GOOGLE_APPLICATION_CREDENTIALS", "SSH_AUTH_SOCK", "DBUS_SESSION_BUS_ADDRESS", "XDG_RUNTIME_DIR", "DOCKER_HOST", "CODEZAIKU_DRIVE")) base.put(secret, "v");
        base.put("DATABASE_URL", "postgres://app:hunter2secret@db.example/app");
        base.put("SOME_SETTING", "ghp_" + "abcdefghijklmnopqrstuvwxyz0123456789");   // a value of a token's shape, put together here: no literal of that shape stands in a shipped file
        for (String plain : List.of("PATH", "LANG", "PWD", "GIT_AUTHOR_NAME", "KEYBOARD_LAYOUT", "MONKEY", "TERM", "EMAIL")) base.put(plain, "v");
        base.put("EMAIL", "someone@example.org");
        base.put("NEEDED_TOKEN", "t");
        Map<String, String> env = SandboxEnv.build(base, f, Set.of("NEEDED_TOKEN"), List.of(home));
        for (String secret : List.of("OPENAI_API_KEY", "GH_TOKEN", "AWS_SECRET_ACCESS_KEY", "AWS_ACCESS_KEY_ID", "DB_PASSWORD", "MYSQL_PWD", "NPM_AUTH",
                "GOOGLE_APPLICATION_CREDENTIALS", "SSH_AUTH_SOCK", "DBUS_SESSION_BUS_ADDRESS", "XDG_RUNTIME_DIR", "DOCKER_HOST", "CODEZAIKU_DRIVE", "DATABASE_URL", "SOME_SETTING"))
            assertFalse(env.containsKey(secret), secret + " is not passed");
        for (String plain : List.of("PATH", "LANG", "PWD", "GIT_AUTHOR_NAME", "KEYBOARD_LAYOUT", "MONKEY", "TERM", "EMAIL")) assertTrue(env.containsKey(plain), plain + " is passed");
        assertEquals("t", env.get("NEEDED_TOKEN"), "a name the person listed in sandbox.env is passed");
        assertEquals(f.home().toString(), env.get("HOME"));
        assertEquals(f.tmp().toString(), env.get("TMPDIR"));
        for (String cache : List.of("XDG_CACHE_HOME", "GRADLE_USER_HOME", "npm_config_cache", "PIP_CACHE_DIR", "CARGO_HOME", "GOMODCACHE"))
            assertTrue(Path.of(env.get(cache)).startsWith(f.cache()), cache + " is the run's own: " + env.get(cache));
        assertTrue(env.get("JAVA_TOOL_OPTIONS").contains("-Duser.home=" + f.home()) && env.get("JAVA_TOOL_OPTIONS").contains("-Djava.io.tmpdir=" + f.tmp()),
                "the JVM takes its home from the account, so it is told: " + env.get("JAVA_TOOL_OPTIONS"));
        assertTrue(SandboxEnv.removed(base, Set.of()).contains("NEEDED_TOKEN"), "and without the setting it is taken out too");
    }

    @Test
    void aToolManagersFolderUnderTheHomeIsNamedToItAndReadOnly() throws Exception {
        Files.createDirectories(home.resolve(".rustup/toolchains"));
        Sandbox.Folders f = folders();
        Map<String, String> env = SandboxEnv.build(Map.of("PATH", "/usr/bin"), f, Set.of(), List.of(home));
        assertEquals(home.resolve(".rustup").toString(), env.get("RUSTUP_HOME"), "rustup looks under HOME, which has moved");
        assertFalse(env.containsKey("PYENV_ROOT"), "a manager that is not installed gets no variable");
        SandboxRules r = SandboxRules.of(project, f, env, List.of(), List.of(), List.of(home), false, true);
        assertTrue(r.reads(home.resolve(".rustup/toolchains/stable/bin/cargo")) && !r.writes(home.resolve(".rustup/settings.toml")), r.readOnly().toString());
    }

    @Test
    void aCacheFolderOfThePersonsChoosingHoldsTheCachesOfEveryProject() throws Exception {
        Path shared = outside.resolve("cache");
        Config.set("sandbox.cache", shared.toString());
        Sandbox.Folders f = folders();
        assertEquals(shared, f.cache());
        Map<String, String> env = SandboxEnv.build(Map.of(), f, Set.of(), List.of(home));
        assertTrue(env.get("GRADLE_USER_HOME").startsWith(shared.toString()) && env.get("PIP_CACHE_DIR").startsWith(shared.toString()), env.toString());
        assertEquals(f.home().toString(), env.get("HOME"), "the home stays the project's own");
        assertTrue(Files.isSymbolicLink(f.home().resolve(".m2")), "Maven's repository, found under the home alone, is a link into it");
        assertTrue(rules(env, false).writes(shared.resolve("gradle/caches")), "and it is written");
    }

    // ── where there is no sandbox ───────────────────────────────────────────────────────────────────────────────────

    @Test
    void theSuiteItselfRunsWithTheSandboxOff() {
        assumeTrue("off".equals(System.getenv("CODEZAIKU_SANDBOX")), "under the Gradle test task");
        assertFalse(Sandbox.found().on(), "the test task switches it off for the tests written before it");
        assertTrue(Sandbox.found().why().contains("sandbox = off"), Sandbox.found().why());
    }

    @Test
    void withALocalModelAndNoSandboxCommandsRunAsBeforeAndTheFirstLinesSaySo() throws Exception {
        Sandbox.forTests(NONE);
        Sandbox.model(project, false);
        assertTrue(Sandbox.commandsRun(project));
        ProcessBuilder pb = Sandbox.in(project).shell("echo hi");
        assertEquals(List.of(Shell.command(), "-lc", "echo hi"), pb.command(), "the shell as it was, no helper before it");
        assertEquals(project.toFile(), pb.directory());
        List<String> lines = Sandbox.lines(project, false);
        assertEquals(1, lines.size(), lines.toString());
        assertTrue(lines.get(0).startsWith("sandbox: none (this kernel has no Landlock)") && lines.get(0).contains("not sandboxed"), lines.get(0));
        ToolRegistry tools = ToolRegistry.standard(project);
        assertTrue(tools.has("shell"), tools.names().toString());
        assertNull(tools.noCommands());
    }

    @Test
    void withAHostedModelAndNoSandboxNoCommandRunsAndTheFirstLinesSayWhyAndWhatToDo() throws Exception {
        Sandbox.forTests(NONE);
        Sandbox.model(project, true);
        assertFalse(Sandbox.commandsRun(project));
        IOException refused = assertThrows(IOException.class, () -> Sandbox.in(project).shell("echo hi"));
        assertTrue(refused.getMessage().contains("no sandbox") && refused.getMessage().contains("hosted"), refused.getMessage());
        assertThrows(IOException.class, () -> Sandbox.in(project).command("git", "status"));
        assertEquals(List.of("sh", "-c", "command -v rg"), Sandbox.in(project).fixed().command("sh", "-c", "command -v rg").command(),
                "the harness's own search still runs: its words are fixed and it only reads");

        ToolRegistry tools = ToolRegistry.standard(project);
        assertFalse(tools.has("shell"), "the tool that runs a command is not on the table: " + tools.names());
        assertTrue(tools.has("read_file") && tools.has("write_file") && tools.has("edit_file") && tools.has("search_code"), "the file tools are: " + tools.names());
        String said = tools.execute("shell", new ObjectMapper().createObjectNode().put("command", "ls"));
        assertTrue(said.startsWith("ERROR: commands are not run in this job"), "a model that calls it anyway is told why: " + said);
        assertFalse(ToolRegistry.readOnly(project, null).has("shell"), "nor on a review's table");
        assertFalse(ToolRegistry.research(project).has("shell"), "nor on a research run's");

        Files.writeString(project.resolve("requirements.txt"), "pytest\n");
        Files.createDirectories(project.resolve("tests"));
        Files.writeString(project.resolve("tests/test_x.py"), "def test_x():\n    assert True\n");
        assertFalse(ProjectTests.verdict(project).ran(), "and the project's tests are not run by the harness either");

        List<String> lines = Sandbox.lines(project, true);
        assertEquals(2, lines.size(), lines.toString());
        assertTrue(lines.get(0).contains("hosted") && lines.get(0).contains("runs no commands"), lines.get(0));
        assertTrue(lines.get(1).contains("Linux 5.13") && lines.get(1).contains("local model"), lines.get(1));

        Sandbox.model(project, false);
        assertTrue(ToolRegistry.standard(project).has("shell"), "a chat that goes back to a local model has its shell again");
    }

    @Test
    void withASandboxAHostedModelsJobHasItsCommands() throws Exception {
        Sandbox.forTests(new Sandbox.Found(Sandbox.Kind.LANDLOCK, null, 8, true, "/usr/bin/python3"));
        Sandbox.loginForTests(Map.of("PATH", "/usr/bin:/bin"));
        Sandbox.model(project, true);
        Config.set("sandbox.network", "open");          // the command as built, without the network's options: those are SandboxNetTest's
        assertTrue(Sandbox.commandsRun(project));
        assertTrue(ToolRegistry.standard(project).has("shell"));
        ProcessBuilder pb = Sandbox.in(project).shell("echo hi");
        madeTmp = Sandbox.folders(project).tmp();
        List<String> cmd = pb.command();
        assertEquals(List.of("/usr/bin/python3", "-I", "-S"), cmd.subList(0, 3), cmd.toString());
        assertTrue(cmd.get(3).endsWith(".py") && Files.isRegularFile(Path.of(cmd.get(3))), "the helper, written out: " + cmd.get(3));
        assertEquals(List.of("--", Shell.command(), "-c", "echo hi"), cmd.subList(cmd.size() - 4, cmd.size()), "then the command, in a plain shell: " + cmd);
        assertEquals(Sandbox.folders(project).home().toString(), pb.environment().get("HOME"));
        List<String> lines = Sandbox.lines(project, true);
        assertTrue(lines.get(0).startsWith("sandbox: on (Landlock)") && lines.get(0).contains(SandboxRules.real(project).toString()), lines.toString());
        assertEquals(2, lines.size(), "sockets are closed, so nothing more is said but the network's line: " + lines);
        assertTrue(lines.get(1).startsWith("sandbox: network open"), lines.toString());
        Config.set("sandbox.sockets", "open");
        lines = Sandbox.lines(project, true);
        assertTrue(lines.size() == 3 && lines.get(1).contains("local socket services stay reachable"), lines.toString());
    }

    // ── real commands, where the machine has a sandbox ──────────────────────────────────────────────────────────────

    private record Ran(int exit, String out) { }

    /** Switch the machine's own sandbox on for this test, or skip it and say why. */
    private Sandbox.Found sandbox() throws IOException {
        Sandbox.Found real = Sandbox.probe();
        assumeTrue(real.on(), "skipped: no sandbox on this machine (" + real.why() + ")");
        Sandbox.forTests(real);
        // the environment a login shell would give, kept to what every machine has, with a key in it that must not arrive
        Map<String, String> login = new LinkedHashMap<>();
        login.put("PATH", "/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin");
        login.put("LANG", "C.UTF-8");
        login.put("PROJECT_API_KEY", "key-that-must-not-arrive");
        Sandbox.loginForTests(login);
        madeTmp = Sandbox.folders(project).tmp();
        return real;
    }

    /** As above where the kernel has Landlock, else skipped. Returns the Python that runs the helper. */
    private String landlock() throws IOException {
        Sandbox.Found real = Sandbox.probe();
        assumeTrue(real.kind() == Sandbox.Kind.LANDLOCK, "skipped: no Landlock on this machine (" + (real.kind() == Sandbox.Kind.NONE ? real.why() : real.kind()) + ")");
        return sandbox().launcher();
    }

    /** What the operating system says when it refuses: Linux's words, or macOS's. */
    private static boolean refused(String out) {
        return out.contains("Permission denied") || out.contains("Operation not permitted");
    }

    private Ran sh(String script) throws Exception {
        Process p = Sandbox.in(project).shell(script).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(120, TimeUnit.SECONDS), "the command ended");
        return new Ran(p.exitValue(), out);
    }

    @Test
    void inTheSandboxACommandReadsAndWritesInTheProjectAndIsRefusedAFileOutsideIt() throws Exception {
        sandbox();
        String secret = "canary-" + System.nanoTime();
        Path canary = Files.writeString(outside.resolve("canary.txt"), secret);

        Ran inside = sh("echo hello > made.txt && cat made.txt && mkdir -p sub && mv made.txt sub/ && ls sub");
        assertEquals(0, inside.exit(), inside.out());
        assertTrue(inside.out().contains("hello") && Files.isRegularFile(project.resolve("sub/made.txt")), inside.out());

        Ran direct = sh("cat " + canary);
        assertTrue(direct.exit() != 0 && refused(direct.out()), "a path written outright: " + direct.out());
        assertFalse(direct.out().contains(secret), direct.out());

        // a path the command puts together while it runs: what the tools' path check cannot see
        Ran built = sh("d=" + canary.getParent() + "; n=canary; cat \"$d/$n.txt\"");
        assertTrue(built.exit() != 0 && refused(built.out()) && !built.out().contains(secret), "a path built at run time: " + built.out());

        Ran listed = sh("ls " + outside + "; ln -s " + canary + " link.txt && cat link.txt; echo more >> " + canary);
        assertTrue(refused(listed.out()) && !listed.out().contains(secret), "listing, a link out of the project, a write: " + listed.out());
        assertEquals(secret, Files.readString(canary), "and the file is as it was");

        Ran env = sh("echo home=$HOME; echo key=${PROJECT_API_KEY:-none}; echo marker=$CODEZAIKU_SANDBOXED; touch \"$HOME/x\" \"$TMPDIR/y\" && echo own-folders-written");
        assertTrue(env.out().contains("home=" + Sandbox.folders(project).home()) && env.out().contains("key=none") && env.out().contains("marker=1")
                && env.out().contains("own-folders-written"), env.out());
    }

    @Test
    void underLandlockAPathAProgramBuildsWhileItRunsIsRefusedToo() throws Exception {
        String python = landlock();
        String secret = "canary-" + System.nanoTime();
        Path canary = Files.writeString(outside.resolve("canary.txt"), secret);
        // the escape that was found: python3 -c joining the pieces of a path
        Ran built = sh(python + " -c \"import os; p = os.sep.join(['" + String.join("','", canary.toString().substring(1).split("/")) + "']); print(open(os.sep + p).read())\"");
        assertTrue(built.exit() != 0 && built.out().contains("PermissionError"), built.out());
        assertFalse(built.out().contains(secret), built.out());
        Ran listed = sh(python + " -c \"import os; print(os.listdir(os.sep.join(['', 'proc'])))\"; ls /tmp");
        assertTrue(listed.out().contains("PermissionError") && listed.out().contains("Permission denied"), "nor the machine's processes or its temp folder: " + listed.out());
    }

    @Test
    void underLandlockAUnixSocketCannotBeMadeSoSocketServicesAreOutOfReach() throws Exception {
        String python = landlock();
        assumeTrue(Sandbox.probe().socketFilter(), "skipped: no socket filter for this processor type");
        Ran r = sh(python + " -c \"import socket\ntry:\n    socket.socket(socket.AF_UNIX)\n    print('made')\nexcept PermissionError as e:\n    print('refused')\n"
                + "socket.socket(socket.AF_INET).close(); a, b = socket.socketpair(); print('network-and-pair-ok')\"");
        assertTrue(r.out().contains("refused") && r.out().contains("network-and-pair-ok") && !r.out().contains("made"), r.out());
    }

    @Test
    void inTheSandboxASmallJavaProjectBuildsAndItsTestPasses() throws Exception {
        sandbox();
        Path javaHome = Path.of(System.getProperty("java.home"));
        assumeTrue(Files.isExecutable(javaHome.resolve("bin/javac")), "skipped: the suite runs on a Java without javac");
        Files.createDirectories(project.resolve("src/demo"));
        Files.writeString(project.resolve("src/demo/Calc.java"), "package demo;\npublic class Calc { public static int add(int a, int b) { return a + b; } }\n");
        Files.writeString(project.resolve("src/demo/CalcTest.java"), """
                package demo;
                import java.nio.file.Files;
                import java.nio.file.Path;
                public class CalcTest {
                    public static void main(String[] args) throws Exception {
                        Path t = Files.createTempFile("calc", ".txt");
                        Files.writeString(t, String.valueOf(Calc.add(1, 2)));
                        if (!Files.readString(t).equals("3")) throw new AssertionError("1 + 2");
                        System.out.println("TESTS PASSED home=" + System.getProperty("user.home"));
                    }
                }
                """);
        Ran r = sh(javaHome.resolve("bin/javac") + " -d out src/demo/*.java && " + javaHome.resolve("bin/java") + " -cp out demo.CalcTest");
        assertEquals(0, r.exit(), r.out());
        assertTrue(r.out().contains("TESTS PASSED home=" + Sandbox.folders(project).home()), "built, run, and the JVM's home is the run's own: " + r.out());
        assertTrue(Files.isRegularFile(project.resolve("out/demo/Calc.class")));
    }

    @Test
    void underLandlockASmallPythonProjectsTestsPass() throws Exception {
        String python = landlock();
        Files.writeString(project.resolve("calc.py"), "def add(a, b):\n    return a + b\n");
        Files.writeString(project.resolve("test_calc.py"), """
                import tempfile, unittest
                from calc import add
                class CalcTest(unittest.TestCase):
                    def test_add(self):
                        with tempfile.NamedTemporaryFile("w+") as f:
                            f.write(str(add(1, 2))); f.seek(0)
                            self.assertEqual(f.read(), "3")
                if __name__ == "__main__":
                    unittest.main()
                """);
        Ran r = sh(python + " -m unittest -v test_calc 2>&1");
        assertEquals(0, r.exit(), r.out());
        assertTrue(r.out().contains("OK") && r.out().contains("test_add"), r.out());
    }

    @Test
    void inTheSandboxTheShellToolSaysARefusalIsTheSandboxs() throws Exception {
        sandbox();
        Path canary = Files.writeString(outside.resolve("canary.txt"), "canary-text");
        ShellTool shell = new ShellTool(new PathScope(project));
        String ok = shell.execute(new ObjectMapper().createObjectNode().put("command", "echo fine > f.txt && cat f.txt"));
        assertTrue(ok.startsWith("exit=0") && ok.contains("fine") && !ok.contains("sandbox"), ok);
        String refused = shell.execute(new ObjectMapper().createObjectNode().put("command", "cat " + canary));
        assertTrue(refused(refused) && refused.contains("[note: commands here run in a sandbox"), refused);
        assertFalse(refused.contains("canary-text"), refused);
    }
}

package org.codezaiku.chat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.UncheckedIOException;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The real program, started as a host starts it: `codezaiku chat` with its input a pipe, and an interrupt sent to the process. Until
 * 0.3.21 the turn went on to the end of the step in flight (a model answering, a command that does not end), and SIGTERM left the
 * command running after the program ended. The model is a stand-in on the loopback; the command is `sleep` with a number of the test's
 * own, so that what is left running can be found and ended.
 */
class ChatInterruptProcessTest {

    @TempDir Path tmp;
    HttpServer model;
    final AtomicInteger requests = new AtomicInteger();
    final CountDownLatch asked = new CountDownLatch(1);
    volatile String mode = "hang";
    final long secs = 7000 + (System.nanoTime() % 900);
    final List<Process> started = new ArrayList<>();

    @BeforeEach void standIn() throws IOException {
        Assumptions.assumeFalse(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"), "signals are sent with kill");
        model = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        model.setExecutor(Executors.newCachedThreadPool());
        model.createContext("/", x -> {
            String path = x.getRequestURI().getPath();
            if (!x.getRequestMethod().equals("POST")) { reply(x, path.contains("props") ? "{\"default_generation_settings\":{\"n_ctx\":32768}}" : "{\"data\":[{\"id\":\"stub\"}]}"); return; }
            String body = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (!body.contains("\"tools\"")) {   // the plan step: in "plan" the model is still writing it while the test runs
                if (mode.equals("plan")) { asked.countDown(); try { Thread.sleep(120_000); } catch (InterruptedException e) { return; } }
                reply(x, "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2}}");
                return;
            }
            int n = requests.incrementAndGet();
            asked.countDown();
            if (mode.equals("slow")) {   // the model answering for a long time: the reply never comes while the test runs
                try { Thread.sleep(120_000); } catch (InterruptedException e) { return; }
            }
            String command = mode.equals("git") ? plantAndHang() : "sleep " + secs;
            String call;
            try {
                call = n == 1 ? JSON.writeValueAsString(Map.of("name", "shell", "arguments", JSON.writeValueAsString(Map.of("command", command))))
                        : "{\"name\":\"task_done\",\"arguments\":\"{\\\"summary\\\":\\\"done\\\"}\"}";
            } catch (IOException e) { throw new UncheckedIOException(e); }
            reply(x, "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":[{\"id\":\"c" + n + "\",\"type\":\"function\",\"function\":" + call
                    + "}]},\"finish_reason\":\"tool_calls\"}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}");
        });
        model.start();
    }

    @AfterEach void down() {
        for (Process p : started) { p.descendants().forEach(ProcessHandle::destroyForcibly); p.destroyForcibly(); }
        sleeps().forEach(ProcessHandle::destroyForcibly);
        if (model != null) model.stop(0);
    }

    static void reply(HttpExchange x, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", "application/json");
        x.sendResponseHeaders(200, b.length);
        try (OutputStream o = x.getResponseBody()) { o.write(b); }
    }

    static final ObjectMapper JSON = new ObjectMapper();

    /**
     * What a job's command could leave in a repository for the person's own git to run: a setting written into .git/config (a made-up,
     * harmless name here) and a hook that only touches a marker in the test's folder; then the command does not end.
     */
    String plantAndHang() {
        return "git config --local codezaikutest.planted yes && printf '#!/bin/sh\\ntouch " + tmp.resolve("hook-ran") + "\\n' > .git/hooks/post-checkout"
                + " && chmod +x .git/hooks/post-checkout && sleep " + secs;
    }

    Path proj() { return tmp.resolve("proj"); }

    boolean planted() throws IOException {
        return Files.readString(proj().resolve(".git/config")).contains("planted") || Files.exists(proj().resolve(".git/hooks/post-checkout"));
    }

    /** A repository of the test's own, as git init makes it; its settings as they were, to compare with after. */
    String gitInit() throws Exception {
        Files.createDirectories(proj());
        assertEquals(0, new ProcessBuilder("git", "init", "-q", "-b", "main").directory(proj().toFile()).inheritIO().start().waitFor());
        return Files.readString(proj().resolve(".git/config"));
    }

    /** The settings and hooks are as they were before the job: the setting gone, the hook gone, and the hook never ran. */
    void asTheyWere(String config, String said) throws Exception {
        assertTrue(waitFor(() -> { try { return !planted(); } catch (IOException e) { return false; } }, 10_000), "put back: " + said);
        assertEquals(config, Files.readString(proj().resolve(".git/config")), said);
        assertFalse(Files.exists(tmp.resolve("hook-ran")));
    }

    /** The test's own command, wherever it runs. */
    List<ProcessHandle> sleeps() {
        String n = String.valueOf(secs);
        return ProcessHandle.allProcesses().filter(h -> h.info().arguments().map(a -> a.length == 1 && a[0].equals(n)).orElse(false)
                && h.info().command().map(c -> c.endsWith("/sleep")).orElse(false)).toList();
    }

    /** The program as a host starts it: the test's own classes, a home of its own, the stand-in model, input a pipe, output read here. */
    Process start(String... args) throws IOException { return startWith("DEFAULT", args); }

    /**
     * A program that starts the JVM with SIGINT as {@code disposition} says (DEFAULT or IGNORE), whatever this test inherited: a suite
     * started from a shell that ignores SIGINT (a background job of a shell without job control) passes it on to every process below,
     * and a JVM started so takes no handler for it. A shell cannot undo an ignore it was started with; perl and GNU env can. Where
     * neither is, the test is skipped and says why: it would depend on how the suite was started.
     */
    static List<String> withInterrupt(String disposition) {
        if (has("perl", "-e", "1")) return List.of("perl", "-e", "$SIG{INT} = '" + disposition + "'; exec @ARGV or die \"exec: $!\"");
        String flag = disposition.equals("DEFAULT") ? "--default-signal=INT" : "--ignore-signal=INT";
        if (has("env", flag, "true")) return List.of("env", flag);
        Assumptions.abort("neither perl nor an env that takes " + flag + " is on this machine, so the program cannot be started with SIGINT "
                + disposition.toLowerCase(Locale.ROOT) + " whatever the suite inherited; an interrupt test here would depend on how the suite was started");
        return List.of();
    }

    static boolean has(String... cmd) {
        try { Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start(); return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0; }
        catch (IOException e) { return false; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
    }

    Process startWith(String disposition, String... args) throws IOException {
        Path home = tmp.resolve("home"), proj = tmp.resolve("proj");
        Files.createDirectories(home);
        Files.createDirectories(proj);
        if (!Files.exists(proj.resolve("README.md"))) Files.writeString(proj.resolve("README.md"), "# p\n");
        List<String> cmd = new ArrayList<>(withInterrupt(disposition));
        cmd.addAll(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Duser.home=" + home, "-cp", System.getProperty("java.class.path"), "org.codezaiku.FamiliarMain"));
        for (String a : args) cmd.add(a.equals("PROJ") ? proj.toString() : a);
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(proj.toFile());
        if (stderrApart) pb.redirectError(tmp.resolve("stderr.txt").toFile()); else pb.redirectErrorStream(true);
        pb.environment().put("CODEZAIKU_DRIVE", "http://127.0.0.1:" + model.getAddress().getPort());
        pb.environment().put("CODEZAIKU_MODEL", "stub");
        pb.environment().put("CODEZAIKU_CHAT_MODE", "yolo");
        pb.environment().put("CODEZAIKU_UPDATE", "off");
        pb.environment().put("CODEZAIKU_CHAT_DIR", tmp.resolve("home/.codezaiku/chat").toString());   // the suite's own is shared by every test
        Process p = pb.start();
        started.add(p);
        return p;
    }

    /** Set by a test that reads stdout alone: stderr then goes to stderr.txt in the test's folder. */
    boolean stderrApart;

    /** The lines of the record of spending (~/.codezaiku/spend.jsonl in the program's home) for one kind of job. */
    List<JsonNode> ledger(String kind) throws IOException {
        Path f = tmp.resolve("home/.codezaiku/spend.jsonl");
        List<JsonNode> out = new ArrayList<>();
        if (!Files.exists(f)) return out;
        for (String l : Files.readAllLines(f)) if (!l.isBlank()) { JsonNode n = JSON.readTree(l); if (n.path("kind").asText().equals(kind)) out.add(n); }
        return out;
    }

    /** The chat's session files in the program's home: the state file and the transcript, or null where there is none. */
    Path sessionFile(String suffix) throws IOException {
        Path dir = tmp.resolve("home/.codezaiku/chat");
        if (!Files.isDirectory(dir)) return null;
        try (var walk = Files.walk(dir)) {
            // the session's own files lie in the project's folder; below it are copies of the project's files (undo) and logs
            return walk.filter(f -> dir.equals(f.getParent().getParent()))
                    .filter(f -> { String n = f.getFileName().toString(); return n.endsWith(suffix) && (suffix.equals(".log.jsonl") || !n.endsWith(".handoff.md") && !n.endsWith(".log.jsonl")); })
                    .findFirst().orElse(null);
        }
    }

    /** Everything the program printed, read as it comes. */
    static StringBuffer collect(Process p) {
        StringBuffer out = new StringBuffer();
        Thread t = new Thread(() -> {
            try (InputStream in = p.getInputStream()) {
                byte[] b = new byte[4096]; int n;
                while ((n = in.read(b)) > 0) out.append(new String(b, 0, n, StandardCharsets.UTF_8));
            } catch (IOException ignored) { }
        });
        t.setDaemon(true); t.start();
        return out;
    }

    static boolean waitFor(BooleanSupplier c, long ms) throws InterruptedException {
        long until = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < until) { if (c.getAsBoolean()) return true; Thread.sleep(100); }
        return c.getAsBoolean();
    }

    static void signal(Process p, String sig) throws Exception {
        assertEquals(0, new ProcessBuilder("kill", "-" + sig, String.valueOf(p.pid())).start().waitFor());
    }

    @Test
    void anInterruptStopsACommandThatDoesNotEndAndTheChatGoesOn() throws Exception {
        Process chat = start("chat", "PROJ");
        StringBuffer out = collect(chat);
        assertTrue(waitFor(() -> out.toString().contains("> "), 60_000), out.toString());
        chat.getOutputStream().write("Do the task.\n".getBytes(StandardCharsets.UTF_8));
        chat.getOutputStream().flush();
        assertTrue(waitFor(() -> !sleeps().isEmpty(), 60_000), "the command started: " + out);
        long t0 = System.currentTimeMillis();
        signal(chat, "INT");
        assertTrue(waitFor(() -> sleeps().isEmpty(), 5_000), "the command is ended within seconds, not left to its time limit: " + out);
        assertTrue(waitFor(() -> out.toString().contains("What should change?"), 5_000), "the turn is over and the chat asks: " + out);
        assertTrue(System.currentTimeMillis() - t0 < 5_000);
        assertTrue(out.toString().contains("stopping the turn"), out.toString());
        chat.getOutputStream().write("/quit\n".getBytes(StandardCharsets.UTF_8));
        chat.getOutputStream().flush();
        assertTrue(chat.waitFor(30, TimeUnit.SECONDS), "the chat is still usable and /quit ends it: " + out);
        assertEquals(0, chat.exitValue());
        List<JsonNode> spent = ledger("chat");
        assertEquals(1, spent.size(), "what the chat spent is recorded once at its ordinary end: " + spent);
        assertTrue(spent.get(0).path("prompt").asLong() >= 10, spent.toString());
    }

    @Test
    void anInterruptStopsAModelThatIsStillAnswering() throws Exception {
        mode = "slow";
        Process chat = start("chat", "PROJ");
        StringBuffer out = collect(chat);
        assertTrue(waitFor(() -> out.toString().contains("> "), 60_000), out.toString());
        chat.getOutputStream().write("Do the task.\n".getBytes(StandardCharsets.UTF_8));
        chat.getOutputStream().flush();
        assertTrue(asked.await(60, TimeUnit.SECONDS), "the model was asked");
        Thread.sleep(500);
        signal(chat, "INT");
        assertTrue(waitFor(() -> out.toString().contains("What should change?"), 5_000), "the turn ends within seconds, not when the model answers: " + out);
        Thread.sleep(1_500);
        assertEquals(1, requests.get(), "the stopped request is not sent again");
        chat.getOutputStream().close();   // the end of the input ends the chat
        assertTrue(chat.waitFor(30, TimeUnit.SECONDS), out.toString());
        List<JsonNode> spent = ledger("chat");
        assertEquals(1, spent.size(), spent.toString());
        assertEquals(1, spent.get(0).path("unreported").asInt(), "the call cut short had no counts from the server and is recorded as one without them: " + spent);
    }

    @Test
    void anInterruptWhileTheModelWritesThePlanStopsTheTurnAndNotTheChat() throws Exception {
        mode = "plan";
        Process chat = start("chat", "PROJ");
        StringBuffer out = collect(chat);
        assertTrue(waitFor(() -> out.toString().contains("> "), 60_000), out.toString());
        chat.getOutputStream().write("Build a command-line tool that counts the words in a file.\n".getBytes(StandardCharsets.UTF_8));
        chat.getOutputStream().flush();
        assertTrue(asked.await(60, TimeUnit.SECONDS), "the plan was asked for: " + out);
        Thread.sleep(500);
        signal(chat, "INT");
        assertTrue(waitFor(() -> out.toString().contains("stopped before the work began"), 5_000), "the turn stops: " + out);
        assertTrue(chat.isAlive(), "the chat goes on: " + out);
        chat.getOutputStream().write("/quit\n".getBytes(StandardCharsets.UTF_8));
        chat.getOutputStream().flush();
        assertTrue(chat.waitFor(30, TimeUnit.SECONDS), out.toString());
        assertEquals(0, chat.exitValue());
    }

    @Test
    void sigtermLeavesNoCommandRunningForTheChatOrForACodingJob() throws Exception {
        for (String[] args : new String[][]{{"chat", "PROJ"}, {"code", "PROJ", "Do the task."}}) {
            requests.set(0);
            Process p = start(args);
            StringBuffer out = collect(p);
            if (args[0].equals("chat")) {
                assertTrue(waitFor(() -> out.toString().contains("> "), 60_000), out.toString());
                p.getOutputStream().write("Do the task.\n".getBytes(StandardCharsets.UTF_8));
                p.getOutputStream().flush();
            }
            assertTrue(waitFor(() -> !sleeps().isEmpty(), 60_000), args[0] + ": the command started: " + out);
            signal(p, "TERM");
            assertTrue(p.waitFor(30, TimeUnit.SECONDS), args[0] + " ended");
            assertTrue(waitFor(() -> sleeps().isEmpty(), 5_000), args[0] + ": nothing it started is left running");
        }
    }

    /** Starts a chat, asks the task, and waits until the command has written into .git and hangs. */
    Process chatThatPlanted(StringBuffer[] out) throws Exception {
        mode = "git";
        Process chat = start("chat", "PROJ");
        out[0] = collect(chat);
        StringBuffer o = out[0];
        assertTrue(waitFor(() -> o.toString().contains("> "), 60_000), o.toString());
        chat.getOutputStream().write("Do the task.\n".getBytes(StandardCharsets.UTF_8));
        chat.getOutputStream().flush();
        assertTrue(waitFor(() -> { try { return planted() && !sleeps().isEmpty(); } catch (IOException e) { return false; } }, 60_000), "the command wrote into .git: " + o);
        return chat;
    }

    @Test
    void oneInterruptStopsTheTurnAndWhatTheCommandWroteIntoGitIsPutBack() throws Exception {
        String config = gitInit();
        StringBuffer[] out = new StringBuffer[1];
        Process chat = chatThatPlanted(out);
        signal(chat, "INT");
        assertTrue(waitFor(() -> sleeps().isEmpty(), 5_000), "the turn stops and its command with it: " + out[0]);
        asTheyWere(config, out[0].toString());
        chat.getOutputStream().write("n\n/quit\n/quit\n".getBytes(StandardCharsets.UTF_8));   // keep the change? no; then leave
        chat.getOutputStream().flush();
        assertTrue(chat.waitFor(30, TimeUnit.SECONDS), out[0].toString());
        asTheyWere(config, out[0].toString());
    }

    @Test
    void sigtermPutsBackWhatTheCommandWroteIntoGitBeforeTheProgramIsGone() throws Exception {
        String config = gitInit();
        StringBuffer[] out = new StringBuffer[1];
        Process chat = chatThatPlanted(out);
        signal(chat, "TERM");
        assertTrue(chat.waitFor(30, TimeUnit.SECONDS));
        assertFalse(planted(), "put back by the time the program is gone: " + out[0]);
        asTheyWere(config, out[0].toString());
        assertTrue(sleeps().isEmpty());
    }

    @Test
    void twoInterruptsEndTheChatAndWhatTheCommandWroteIntoGitIsPutBack() throws Exception {
        String config = gitInit();
        StringBuffer[] out = new StringBuffer[1];
        Process chat = chatThatPlanted(out);
        signal(chat, "INT");
        signal(chat, "INT");
        assertTrue(chat.waitFor(30, TimeUnit.SECONDS));
        assertEquals(130, chat.exitValue());
        assertFalse(planted(), "put back by the time the program is gone: " + out[0]);
        asTheyWere(config, out[0].toString());
        assertTrue(sleeps().isEmpty());
    }

    @Test
    void startedWithInterruptsIgnoredTheChatSaysSoAndSigtermStillEndsItCleanly() throws Exception {
        String config = gitInit();
        mode = "git";
        Process chat = startWith("IGNORE", "chat", "PROJ");
        StringBuffer out = collect(chat);
        assertTrue(waitFor(() -> out.toString().contains("> "), 60_000), out.toString());
        assertTrue(out.toString().contains("started with interrupts ignored") && !out.toString().contains("ctrl-C stops a turn"), "the banner says so: " + out);
        chat.getOutputStream().write("Do the task.\n".getBytes(StandardCharsets.UTF_8));
        chat.getOutputStream().flush();
        assertTrue(waitFor(() -> { try { return planted() && !sleeps().isEmpty(); } catch (IOException e) { return false; } }, 60_000), out.toString());
        signal(chat, "INT");
        Thread.sleep(1_500);
        assertTrue(chat.isAlive() && !sleeps().isEmpty() && !out.toString().contains("stopping"), "SIGINT does nothing to a process started with it ignored: " + out);
        signal(chat, "TERM");
        assertTrue(chat.waitFor(30, TimeUnit.SECONDS));
        assertTrue(waitFor(() -> sleeps().isEmpty(), 5_000), "nothing it started is left running");
        asTheyWere(config, out.toString());
    }

    /** A chat with a turn under way, its command running: what the person asked and what it spent so far are written by now. */
    Process chatWithATurnUnderWay(StringBuffer[] out) throws Exception {
        Process chat = start("chat", "PROJ");
        out[0] = collect(chat);
        StringBuffer o = out[0];
        assertTrue(waitFor(() -> o.toString().contains("> "), 60_000), o.toString());
        chat.getOutputStream().write("Do the task.\n".getBytes(StandardCharsets.UTF_8));
        chat.getOutputStream().flush();
        assertTrue(waitFor(() -> !sleeps().isEmpty(), 60_000), "the command started: " + o);
        return chat;
    }

    /** The turn that was under way is in the session's files, written as stopped, and what the chat spent is on the ledger once. */
    void theTurnAndItsSpendAreKept(String how, String said) throws Exception {
        Path state = sessionFile(".md"), transcript = sessionFile(".log.jsonl");
        assertNotNull(state, how + ": the session's state file is written: " + said);
        assertTrue(Files.readString(state).contains("- turns: 1"), how + ": the turn is counted: " + Files.readString(state));
        String t = Files.readString(transcript);
        assertTrue(t.contains("Do the task.") && t.contains("the chat was ended during this turn"), how + ": the transcript has the turn, ended: " + t);
        List<JsonNode> spent = ledger("chat");
        assertEquals(1, spent.size(), how + ": recorded once: " + spent);
        assertTrue(spent.get(0).path("prompt").asLong() >= 10 && spent.get(0).path("completion").asLong() >= 5, how + ": with the answer's tokens: " + spent);
    }

    @Test
    void sigtermDuringATurnKeepsTheTurnAndWhatTheChatSpent() throws Exception {
        StringBuffer[] out = new StringBuffer[1];
        Process chat = chatWithATurnUnderWay(out);
        signal(chat, "TERM");
        assertTrue(chat.waitFor(30, TimeUnit.SECONDS));
        theTurnAndItsSpendAreKept("SIGTERM", out[0].toString());
    }

    @Test
    void twoInterruptsKeepTheTurnAndWhatTheChatSpent() throws Exception {
        StringBuffer[] out = new StringBuffer[1];
        Process chat = chatWithATurnUnderWay(out);
        signal(chat, "INT");
        signal(chat, "INT");
        assertTrue(chat.waitFor(30, TimeUnit.SECONDS));
        assertEquals(130, chat.exitValue());
        theTurnAndItsSpendAreKept("two interrupts", out[0].toString());
    }

    @Test
    void sigtermWhileTheModelIsAnsweringRecordsThatAnswerAsOneWithoutCounts() throws Exception {
        mode = "slow";
        Process chat = start("chat", "PROJ");
        StringBuffer out = collect(chat);
        assertTrue(waitFor(() -> out.toString().contains("> "), 60_000), out.toString());
        chat.getOutputStream().write("Do the task.\n".getBytes(StandardCharsets.UTF_8));
        chat.getOutputStream().flush();
        assertTrue(asked.await(60, TimeUnit.SECONDS), "the model was asked");
        Thread.sleep(500);
        signal(chat, "TERM");
        assertTrue(chat.waitFor(30, TimeUnit.SECONDS));
        List<JsonNode> spent = ledger("chat");
        assertEquals(1, spent.size(), out.toString());
        assertEquals(1, spent.get(0).path("unreported").asInt(), "the answer that never came: " + spent);
    }

    @Test
    void aCodingJobEndedByASignalSaysItsLastLinesOnStdoutAndRecordsWhatItSpent() throws Exception {
        String config = gitInit();
        mode = "git";
        stderrApart = true;
        for (String[] sig : new String[][]{{"TERM", "143"}, {"INT", "130"}}) {
            Files.deleteIfExists(tmp.resolve("home/.codezaiku/spend.jsonl"));
            requests.set(0);
            Process p = start("code", "PROJ", "Do the task.");
            StringBuffer out = collect(p);
            assertTrue(waitFor(() -> { try { return planted() && !sleeps().isEmpty(); } catch (IOException e) { return false; } }, 60_000), "the command wrote into .git: " + out);
            signal(p, sig[0]);
            assertTrue(p.waitFor(30, TimeUnit.SECONDS), sig[0]);
            assertEquals(Integer.parseInt(sig[1]), p.exitValue(), sig[0] + ": the exit code says a signal ended it");
            String o = out.toString(), err = Files.readString(tmp.resolve("stderr.txt"));
            assertTrue(o.contains("stopped: the job was ended before it finished"), sig[0] + ": a line says it was stopped: " + o);
            assertTrue(o.contains("stopped: the job was ended before it finished\nmodel: ") && o.contains("10 prompt + 5 completion tokens"), sig[0] + ": the model line with what was spent: " + o);
            assertTrue(o.contains("CodeZaiku put back:") && o.contains("codezaikutest"), sig[0] + ": what was put back, on stdout: " + o);
            assertFalse(err.contains("CodeZaiku put back:"), sig[0] + ": not again on stderr: " + err);
            assertEquals(1, o.split("stopped: the job was ended", -1).length - 1, sig[0] + ": said once: " + o);
            List<JsonNode> spent = ledger("code");
            assertEquals(1, spent.size(), sig[0] + ": " + spent);
            assertEquals("stopped", spent.get(0).path("status").asText(), spent.toString());
            assertTrue(spent.get(0).path("prompt").asLong() >= 10, spent.toString());
            asTheyWere(config, o);
        }
    }

    @Test
    void runEndedBySigtermRecordsWhatItSpentAndItsOutputIsStillOneDocument() throws Exception {
        stderrApart = true;
        Process p = start("run", "--workspace", "PROJ", "--output-format", "json", "--text", "Do the task.");
        StringBuffer out = collect(p);
        assertTrue(waitFor(() -> !sleeps().isEmpty(), 60_000), "the command started: " + out);
        signal(p, "TERM");
        assertTrue(p.waitFor(30, TimeUnit.SECONDS));
        JsonNode doc = JSON.readTree(out.toString());   // the whole of stdout, one JSON document: the end's lines are not printed here
        assertEquals("failed", doc.path("status").asText(), out.toString());
        assertTrue(doc.path("interrupted").asBoolean(), "the document says a signal ended the job: " + out);
        List<JsonNode> spent = ledger("code");
        assertEquals(1, spent.size(), spent.toString());
        assertEquals("stopped", spent.get(0).path("status").asText(), spent.toString());
        assertTrue(spent.get(0).path("prompt").asLong() >= 10, spent.toString());
    }
}

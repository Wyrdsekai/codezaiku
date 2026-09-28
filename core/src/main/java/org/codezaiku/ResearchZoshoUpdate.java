package org.codezaiku;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.codezaiku.SelfUpdate.Result;

/**
 * Keeping ResearchZosho up to date from CodeZaiku by asking its own updater. Once ResearchZosho is installed, only its
 * updater replaces its files: it takes its own lock, so it never collides with another program that updates it
 * (Wyrdsekai does); it keeps a build that carries its own Java as one; it restarts its own service; and on Windows it
 * finishes the swap after it has ended. CodeZaiku runs {@code researchzosho update now --json} and says in plain words
 * what came of it.
 *
 * <p>A ResearchZosho from before 0.5.0 has {@code update now} but no {@code --json}: it prints a line and exits 0 when it
 * updated and 1 otherwise, "already" included. Its words and exit code are read instead, and it is not given the flag,
 * which it does not know.
 */
public final class ResearchZoshoUpdate {

    private ResearchZoshoUpdate() { }

    /** The first ResearchZosho whose updater answers in JSON. */
    static final String JSON_SINCE = "0.5.0";

    /** What ResearchZosho's updater said: the same fields as its `update now --json`. */
    public record Answer(Result result, String from, String to, boolean finishesAfterExit, String note) { }

    /** What `researchzosho update --json` says, or for an older one, what its --version says. */
    public record Status(String installed, String latest, boolean newer, String mode, String root, boolean updating, boolean fromJson) { }

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Ask the updater behind {@code launcher} for the latest release, or {@code version}. Its progress lines go to {@code progress}. */
    public static Answer now(Path launcher, String version, PrintStream progress) {
        String have = ResearchZoshoInstall.installedVersion(launcher);
        boolean json = have != null && ResearchZoshoInstall.compareVersions(have, JSON_SINCE) >= 0;
        List<String> cmd = new ArrayList<>(List.of(launcher.toString(), "update", "now"));
        if (version != null) cmd.add(version);
        if (json) cmd.add("--json");
        // no time limit: the updater bounds its own downloads, and stopping it halfway through the swap would be worse than waiting
        Ran r = run(cmd, progress, !json, 0);
        if (r.error() != null) return new Answer(Result.FAILED, have, have, false, "could not run " + launcher + ": " + r.error());
        JsonNode doc = json ? parse(r.out()) : null;
        if (doc != null && doc.has("result")) {
            Result res = Result.of(doc.path("result").asText());
            if (res == null) res = fromCode(r.code());
            return new Answer(res, text(doc, "from", have), text(doc, "to", have), doc.path("finishesAfterExit").asBoolean(false), text(doc, "note", ""));
        }
        return fromText(r.code(), r.out(), have);
    }

    /** An older updater's answer, read from its words and its exit code. "already" in its words means it was current. */
    static Answer fromText(int code, String said, String have) {
        boolean already = Pattern.compile("\\balready\\b", Pattern.CASE_INSENSITIVE).matcher(said).find();
        Result res = code == Result.BUSY.code ? Result.BUSY : code == Result.NOT_HERE.code ? Result.NOT_HERE
                : already ? Result.CURRENT : code == 0 ? Result.UPDATED : Result.FAILED;
        String to = have;
        Matcher m = Pattern.compile("(?:updated to|already|updating \\S+ (?:to|→))\\s+v?(\\d+\\.\\d+\\.\\d+)").matcher(said);
        while (m.find()) to = m.group(1);
        return new Answer(res, have, to, false, lastLine(said));
    }

    /** A result word this version does not know: the exit code says as much as the contract promises. */
    private static Result fromCode(int code) {
        if (code == Result.BUSY.code) return Result.BUSY;
        if (code == Result.NOT_HERE.code) return Result.NOT_HERE;
        return code == 0 ? Result.UPDATED : Result.FAILED;
    }

    /** What happened, in a sentence for the person who asked. */
    public static String words(Answer a) {
        String said = a.note() == null || a.note().isBlank() ? "" : " Its updater says: " + sentence(a.note());
        return switch (a.result()) {
            case UPDATED -> "ResearchZosho was updated" + (a.from() != null && a.to() != null && !a.from().equals(a.to()) ? " from " + a.from() + " to " + a.to() : "") + "." + said;
            case CURRENT -> "ResearchZosho " + (a.from() != null ? a.from() + " " : "") + "is up to date; nothing to do.";
            case BUSY -> "ResearchZosho is being updated by another program now; nothing to do.";
            case NOT_HERE -> "ResearchZosho cannot update itself where it is installed." + said;
            case FAILED -> "ResearchZosho was not updated." + said + " The installed version stays. researchzosho update now tries again and shows each step.";
        };
    }

    /** Ask the updater behind {@code launcher} where it stands, without changing anything. */
    public static Status status(Path launcher) { return status(launcher, ResearchZoshoInstall::latestVersion); }

    /** {@code latest} is asked only for an older ResearchZosho, whose updater cannot say. */
    static Status status(Path launcher, Supplier<String> latest) {
        Ran r = run(List.of(launcher.toString(), "update", "--json"), null, false, 120);
        JsonNode doc = r.error() == null ? parse(r.out()) : null;
        if (doc != null && "researchzosho".equals(doc.path("program").asText())) {
            return new Status(text(doc, "installed", null), text(doc, "latest", null), doc.path("newer").asBoolean(false),
                    text(doc, "mode", null), text(doc, "root", null), doc.path("updating").asBoolean(false), true);
        }
        // an older one answers `update --json` with its usage; its version still says what is installed
        String installed = ResearchZoshoInstall.installedVersion(launcher);
        String newest = latest.get();
        boolean newer = installed != null && newest != null && ResearchZoshoInstall.compareVersions(newest, installed) > 0;
        return new Status(installed, newest, newer, null, null, false, false);
    }

    /** The lines `codezaiku update` shows for ResearchZosho. */
    public static String statusText(Status s, Path launcher) {
        StringBuilder b = new StringBuilder("ResearchZosho\n");
        b.append("  installed: ").append(s.installed() == null ? "a version that does not say which" : s.installed())
                .append(" at ").append(s.root() != null ? s.root() : launcher).append('\n');
        b.append("  latest:    ").append(s.latest() == null ? "unknown (could not reach GitHub)" : s.latest()).append('\n');
        if (s.mode() != null) b.append("  mode:      ").append(s.mode()).append(" (ResearchZosho's own setting: researchzosho update auto on|off)").append('\n');
        if (s.updating()) b.append("  An update of ResearchZosho is running now.\n");
        if (s.newer()) b.append("  A newer release is out. codezaiku update now has ResearchZosho's own updater install it; the library and settings stay.\n");
        return b.toString();
    }

    // ── running the other program ─────────────────────────────────────────────────────────────────────────────────

    record Ran(int code, String out, String error) { }

    /**
     * Run {@code cmd}: its error stream line by line to {@code progress} (or nowhere), its output kept, and also shown when
     * {@code showOut} (an older updater writes its progress there). {@code seconds} 0 waits for as long as it runs.
     */
    static Ran run(List<String> cmd, PrintStream progress, boolean showOut, long seconds) {
        try {
            Process p = new ProcessBuilder(cmd).start();
            p.getOutputStream().close();
            StringBuffer out = new StringBuffer();
            Thread err = pump(p.getErrorStream(), progress, null);
            Thread std = pump(p.getInputStream(), showOut ? progress : null, out);
            if (seconds > 0) {
                if (!p.waitFor(seconds, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    return new Ran(-1, out.toString(), "it did not answer within " + seconds + " seconds");
                }
            } else {
                p.waitFor();
            }
            // a helper it started may hold its streams open after it has ended; what it wrote before it ended is here by now
            err.join(5000);
            std.join(5000);
            return new Ran(p.exitValue(), out.toString(), null);
        } catch (IOException e) {
            return new Ran(-1, "", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Ran(-1, "", "interrupted");
        }
    }

    private static Thread pump(InputStream in, PrintStream to, StringBuffer keep) {
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (keep != null) keep.append(line).append('\n');
                    if (to != null) to.println(line);
                }
            } catch (IOException ignored) { }
        }, "researchzosho-output");
        t.setDaemon(true);
        t.start();
        return t;
    }

    /** The JSON document in what a command printed: all of it, or its last line that is one; null when there is none. */
    static JsonNode parse(String said) {
        String s = said == null ? "" : said.strip();
        List<String> tries = new ArrayList<>();
        if (s.startsWith("{")) tries.add(s);
        String[] lines = s.split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) if (lines[i].strip().startsWith("{")) { tries.add(lines[i].strip()); break; }
        for (String t : tries) {
            try {
                JsonNode n = JSON.readTree(t);
                if (n != null && n.isObject()) return n;
            } catch (IOException notJson) { }
        }
        return null;
    }

    private static String text(JsonNode doc, String field, String dflt) {
        JsonNode n = doc.get(field);
        return n == null || n.isNull() ? dflt : n.asText();
    }

    private static String lastLine(String said) {
        String[] lines = said == null ? new String[0] : said.strip().split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) if (!lines[i].isBlank()) return lines[i].strip();
        return "";
    }

    private static String sentence(String s) {
        String t = s.strip();
        return t.endsWith(".") || t.endsWith("!") || t.endsWith("?") ? t : t + ".";
    }
}

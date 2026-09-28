package org.codezaiku;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.codezaiku.SelfUpdate.Outcome;
import org.codezaiku.SelfUpdate.Result;

/**
 * {@code codezaiku update}: CodeZaiku's own update, and ResearchZosho's asked of its own updater. Each program's own
 * updater replaces its files, under its own lock, so this command and another program that keeps both up to date
 * (Wyrdsekai) never write into each other's work: the second to come finds the first busy, or finds the files current.
 *
 * <p>With {@code --json} the command is for a program that updates CodeZaiku: one JSON document on stdout about
 * CodeZaiku alone, progress on stderr, and the exit code of CodeZaiku's update. That program asks ResearchZosho's
 * updater itself, as this command does for a person.
 */
final class UpdateCommand {

    private UpdateCommand() { }

    /** The two updaters, and where ResearchZosho is. The tests pass their own. */
    interface Parts {
        Outcome selfNow(String version, PrintStream progress);
        String selfStatus();
        String selfStatusJson();
        /** The installed researchzosho command, or null. */
        Path researchZosho();
        ResearchZoshoUpdate.Status researchZoshoStatus(Path launcher);
    }

    static Parts live() {
        return new Parts() {
            @Override public Outcome selfNow(String version, PrintStream progress) { return SelfUpdate.now(version, progress); }
            @Override public String selfStatus() { return SelfUpdate.status(); }
            @Override public String selfStatusJson() { return SelfUpdate.statusJson(); }
            @Override public Path researchZosho() { return ResearchZoshoInstall.installed(); }
            @Override public ResearchZoshoUpdate.Status researchZoshoStatus(Path launcher) { return ResearchZoshoUpdate.status(launcher); }
        };
    }

    static final String USAGE = "usage: codezaiku update [status | now [version] | auto on|off] [--json]";

    /** {@code args} as the command line gives them, "update" first. Returns the exit code. */
    static int run(String[] args, PrintStream out, PrintStream err, Parts parts) {
        boolean json = Arrays.asList(args).contains("--json");
        List<String> rest = new ArrayList<>();
        for (int i = 1; i < args.length; i++) if (!args[i].equals("--json")) rest.add(args[i]);
        String op = rest.isEmpty() ? "status" : rest.get(0);
        switch (op) {
            case "status" -> {
                if (rest.size() > 1) { err.println(USAGE); return 2; }
                if (json) { out.println(parts.selfStatusJson()); return 0; }
                out.print(parts.selfStatus());
                Path rz = parts.researchZosho();
                if (rz != null) { out.println(); out.print(ResearchZoshoUpdate.statusText(parts.researchZoshoStatus(rz), rz)); }
                return 0;
            }
            case "now" -> {
                String version = null;
                for (String a : rest.subList(1, rest.size())) {
                    if (version == null && a.matches("\\d+\\.\\d+\\.\\d+")) version = a;
                    else { err.println(USAGE); return 2; }
                }
                if (json) {
                    // one document on stdout; the progress lines go to stderr so the output stays one document
                    Outcome o = parts.selfNow(version, err);
                    out.println(SelfUpdate.outcomeJson(o));
                    return o.result().code;
                }
                return both(version, out, parts);
            }
            case "auto" -> {
                if (rest.size() != 2 || !(rest.get(1).equals("on") || rest.get(1).equals("off"))) { err.println("usage: codezaiku update auto on|off"); return 2; }
                try { Config.set("CODEZAIKU_UPDATE", rest.get(1).equals("on") ? "auto" : "check"); } catch (Exception e) { err.println("could not save: " + e.getMessage()); return 1; }
                out.println(rest.get(1).equals("on")
                        ? "Auto-update is on. A chat installs a newer CodeZaiku when it starts, for the next start. ResearchZosho keeps its own setting: researchzosho update auto on|off."
                        : "Auto-update is off. doctor and the chat say when a newer release exists; codezaiku update now installs it.");
                return 0;
            }
            default -> { err.println(USAGE); return 2; }
        }
    }

    /**
     * A person's `update now`: CodeZaiku first, then ResearchZosho by its own updater when it is installed. Neither stops
     * the other, and both outcomes are said. The exit code is 1 when either failed, else CodeZaiku's.
     */
    static int both(String version, PrintStream out, Parts parts) {
        Outcome self = parts.selfNow(version, out);
        out.println(self.note());
        boolean failed = self.result() == Result.FAILED;
        Path rz = parts.researchZosho();
        if (rz != null) {
            out.println();
            out.println("Asking ResearchZosho's own updater for its latest release; the library and settings stay.");
            ResearchZoshoUpdate.Answer a = ResearchZoshoUpdate.now(rz, null, out);
            out.println(ResearchZoshoUpdate.words(a));
            failed |= a.result() == Result.FAILED;
        }
        return failed ? 1 : self.result().code;
    }
}

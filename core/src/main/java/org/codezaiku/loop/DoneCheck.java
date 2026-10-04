package org.codezaiku.loop;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The check the goal states: a command, and what its output must show for the work to be done.
 *
 * <p>The model declares it once, in the goal's own words, before the first turn. The harness runs it — not the model — whenever a
 * program the goal names changes, shows the model what came out, and when every condition holds, the work is done. Runs that
 * completed in the past had the harness as the judge (a dev slice with hidden labels, scored by the harness); a task given as a
 * brief got none of that, and its runs measured one example at a time to the turn limit (fifteen runs, 2026-09-30/10-01). A check
 * can be a test command that must exit 0, or a command that prints numbers, each held to a bound.
 *
 * <p>A check can come with a <b>prepare</b> command: a program that does the slow step the check depends on (reading every
 * video, extracting features, building an index) once for every input and stores the results in files, which the check then reads.
 * The harness runs it once, and again only when that program changes. A check that computes everything each time took 8 to 20
 * minutes a run on a video task, so a run got a handful of verdicts; the control run that finished the same task had stored the
 * slow step's results once, by itself, and tried its changes against them in seconds (2026-10-02).
 */
final class DoneCheck {

    /** {@code name op value}: the number printed after {@code name} in the output must satisfy {@code op value}. */
    record Condition(String name, String op, double value) {
        boolean holds(double found) {
            return switch (op) {
                case "<" -> found < value;
                case "<=" -> found <= value;
                case ">" -> found > value;
                case ">=" -> found >= value;
                default -> found == value;
            };
        }
        @Override public String toString() { return name + " " + op + " " + trim(value); }
    }

    /** What a run of the check came to. */
    record Outcome(int exit, String output, Map<String, Double> found, boolean passes, String report, long seconds, int turn) { }

    private static final Pattern RUN = Pattern.compile("(?im)^\\s*run:\\s*(.+?)\\s*$");
    private static final Pattern PASS = Pattern.compile("(?im)^\\s*pass:\\s*(.+?)\\s*$");
    private static final Pattern PREPARE = Pattern.compile("(?im)^\\s*prepare:\\s*(.+?)\\s*$");
    private static final Pattern CONDITION = Pattern.compile("^([A-Za-z][\\w .%-]{0,40}?)\\s*(<=|>=|==|=|<|>)\\s*([-+]?\\d+(?:\\.\\d+)?)\\s*%?$");
    private static final Pattern EXIT_ZERO = Pattern.compile("(?i)^exit(?:\\s+code)?\\s*(?:==?|is)?\\s*0$");
    static final int OUTPUT_KEEP = 1500;

    final String command;
    final List<Condition> conditions;
    /** The command that does the check's slow step once and stores its results; null when none is declared. */
    final String prepare;

    private DoneCheck(String command, List<Condition> conditions, String prepare) {
        this.command = command;
        this.conditions = conditions;
        this.prepare = prepare;
    }

    /**
     * The block in the planning reply:
     * <pre>
     * DONE CHECK:
     * run: python evaluate.py
     * pass: median_abs_err &lt; 5
     * pass: measured &gt;= 30
     * prepare: python prepare.py
     * </pre>
     * {@code pass: exit 0} alone means the command has to succeed. Null when there is no block, or {@code run: none}.
     */
    static DoneCheck parse(String reply) {
        if (reply == null) return null;
        int at = reply.toUpperCase(Locale.ROOT).indexOf("DONE CHECK");
        if (at < 0) return null;
        String block = reply.substring(at);
        int end = block.indexOf("\n\n");
        if (end > 0) block = block.substring(0, end);
        Matcher r = RUN.matcher(block);
        if (!r.find()) return null;
        String command = r.group(1).replaceAll("^`|`$", "").strip();
        if (command.isEmpty() || command.equalsIgnoreCase("none") || command.equalsIgnoreCase("n/a")) return null;
        List<Condition> conditions = new ArrayList<>();
        Matcher p = PASS.matcher(block);
        while (p.find()) {
            String text = p.group(1).replaceAll("^`|`$", "").strip();
            if (EXIT_ZERO.matcher(text).matches()) continue;
            Matcher c = CONDITION.matcher(text);
            if (!c.matches()) continue;
            String op = c.group(2).equals("=") ? "==" : c.group(2);
            conditions.add(new Condition(c.group(1).strip(), op, Double.parseDouble(c.group(3))));
        }
        return new DoneCheck(command, List.copyOf(conditions), prepareIn(block, command));
    }

    /**
     * The {@code prepare:} line of a reply: its command, or null for none, for {@code none}, and for a command that is the check
     * itself (a prepare step that is the check would run the slow thing twice).
     */
    static String prepareIn(String reply, String checkCommand) {
        if (reply == null) return null;
        Matcher m = PREPARE.matcher(reply);
        if (!m.find()) return null;
        String command = m.group(1).replaceAll("^`|`$", "").strip();
        if (command.isEmpty() || command.equalsIgnoreCase("none") || command.equalsIgnoreCase("n/a") || command.startsWith("<")) return null;
        return command.equals(checkCommand) ? null : command;
    }

    /** This check with a prepare command (declared after the check turned out to be slow). */
    DoneCheck withPrepare(String command) {
        return new DoneCheck(this.command, conditions, command);
    }

    /** Judge a run of the command: exit 0, and every condition's number found in the output and within its bound. */
    Outcome judge(int exit, String output, long seconds, int turn) {
        Map<String, Double> found = new LinkedHashMap<>();
        boolean passes = exit == 0;
        StringBuilder report = new StringBuilder("`" + command + "` → " + (exit == 124 ? "timed out" : "exit " + exit));
        if (exit != 0) passes = false;
        for (Condition c : conditions) {
            Double v = numberAfter(c.name, output);
            if (v == null) {
                passes = false;
                report.append("; ").append(c.name).append(": not printed (the output must show it as `").append(c.name).append(": <number>`)");
            } else {
                found.put(c.name, v);
                boolean ok = c.holds(v);
                passes &= ok;
                report.append("; ").append(c.name).append(" ").append(trim(v)).append(" (needs ").append(c.op).append(" ").append(trim(c.value)).append(ok ? ": yes)" : ": NO)");
            }
        }
        report.append(passes ? " — PASSES" : " — does not pass yet");
        return new Outcome(exit, output, found, passes, report.toString(), seconds, turn);
    }

    /**
     * Is {@code a} a better run of the check than {@code b}? More conditions met wins; then the first condition's number, in its own
     * direction; a run with no number, or a failed command, is worst. Null counts as nothing to beat.
     */
    boolean better(Outcome a, Outcome b) {
        if (a == null) return false;
        if (b == null) return a.exit() == 0 && (conditions.isEmpty() || !a.found().isEmpty());
        if (a.passes() != b.passes()) return a.passes();
        int ma = met(a), mb = met(b);
        if (ma != mb) return ma > mb;
        if (conditions.isEmpty()) return false;
        Condition first = conditions.get(0);
        Double va = a.found().get(first.name()), vb = b.found().get(first.name());
        if (va == null || vb == null) return va != null && vb == null;
        return first.op().startsWith("<") ? va < vb : first.op().startsWith(">") ? va > vb : Math.abs(va - first.value()) < Math.abs(vb - first.value());
    }

    private int met(Outcome o) {
        if (o.exit() != 0) return -1;
        int n = 0;
        for (Condition c : conditions) {
            Double v = o.found().get(c.name());
            if (v != null && c.holds(v)) n++;
        }
        return n;
    }

    /** The union of this check's conditions and another's (by name), keeping this check's command. */
    DoneCheck withConditionsOf(DoneCheck other) {
        if (other == null) return this;
        List<Condition> all = new ArrayList<>(conditions);
        for (Condition c : other.conditions) {
            boolean have = false;
            for (Condition mine : conditions) have |= mine.name().equalsIgnoreCase(c.name());
            if (!have) all.add(c);
        }
        return new DoneCheck(command, List.copyOf(all), prepare);
    }

    /**
     * The last number printed for the name (underscores and spaces in the name match either), or null. The number has to follow
     * the name directly — a quote, a colon or an equals sign, spaces, an opening bracket and nothing else in between. A looser
     * match read a result that was not there: for {@code {"median_abs_error_deg": null, ..., "within_5deg_count": 0}} it skipped
     * the null and took the 5 out of the next key's name, so an evaluation that measured nothing was reported, and ranked, as a
     * median error of 5 (2026-10-01 and 10-02).
     */
    static Double numberAfter(String name, String output) {
        if (output == null) return null;
        StringBuilder key = new StringBuilder();
        for (String part : name.strip().split("[ _]+")) {
            if (key.length() > 0) key.append("[ _]+");
            key.append(Pattern.quote(part));
        }
        Matcher m = Pattern.compile("(?i)(?<![\\w])" + key + "(?![\\w])[\"'`*]{0,3}\\s*[:=]?\\s*[\"'`*(\\[]{0,2}\\s*([-+]?\\d+(?:\\.\\d+)?)(?![\\w])").matcher(output);
        Double last = null;
        while (m.find()) last = Double.parseDouble(m.group(1));
        return last;
    }

    /** The block for the system prompt. */
    String pinned(Outcome last) { return pinned(last, null); }

    /** {@code prepared}: where the prepare command stands ("not written yet", "ran at turn 12 (410 s), exit 0"); null when none is declared. */
    String pinned(Outcome last, String prepared) {
        StringBuilder sb = new StringBuilder("\n\nDONE CHECK (declared from the goal; the harness runs it when a named program changes "
                + "and the work is done when it passes):\n run: `").append(command).append("`\n pass when: ")
                .append(conditions.isEmpty() ? "it exits 0" : String.join(" and ", conditions.stream().map(Condition::toString).toList()));
        if (last != null) sb.append("\n last run by the harness, turn ").append(last.turn()).append(": ").append(last.report());
        else sb.append("\n not run yet");
        if (prepare != null) sb.append("\n prepare: `").append(prepare).append("` — does the check's slow step once and stores the results in files, which the check "
                + "and your programs read; the harness runs it when it changes").append(prepared == null ? "" : "; " + prepared);
        return sb.append('\n').toString();
    }

    private static String trim(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}

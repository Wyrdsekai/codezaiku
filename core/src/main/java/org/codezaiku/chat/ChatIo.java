package org.codezaiku.chat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

/**
 * The terminal seam.
 *
 * <p>Deliberately an interface-shaped thing rather than JLine calls sprinkled through
 * {@link ChatRepl}, for the reason wyrdsekai's {@code WyrdSession} exists: the render loop should
 * speak to a seam, never to a concrete transport. Today both implementations are local — a real
 * terminal and a pipe. If a chat ever runs against a server, that is a third implementation here and
 * no change at all in the REPL.
 *
 * <p>The pipe case is not a nicety. {@code codezaiku chat < script.txt} is how this gets tested
 * without a tty, and a REPL that only works interactively cannot be regression-tested at all.
 */
public interface ChatIo extends AutoCloseable {

    /** One line, or null at end of input. */
    String readLine(String prompt);

    void println(String s);

    /** Without a newline — for streamed deltas. */
    default void print(String s) { println(s); }

    /**
     * The model's inner voice — reasoning, streamed while it thinks. HIDDEN unless the person opts
     * in, and when shown it must be unmistakable: dimming alone was not — the operator watched a dimmed
     * reasoning stream and asked why the model was still talking to itself. The label does what the
     * styling could not; the dimming is now only reinforcement.
     */
    default void printThinking(String s) { print(s); }

    /** A line that may arrive while the person is TYPING — a background task finishing. The
     *  default is a plain println; the JLine implementation prints ABOVE the live prompt so the
     *  person's half-typed input survives. */
    default void notifyLine(String s) { println(s); }

    /** Printed once at the start of a thinking burst, before any {@link #printThinking}. */
    default void printThinkingStart() { println("[thinking]"); }

    /**
     * Run {@code onInterrupt} when the person presses ctrl-C, until the returned handle is closed.
     *
     * <p>Exists because a turn that cannot be stopped is the worst thing in a chat. Between messages
     * ctrl-C clears the line, which JLine gives us for free; DURING a turn nobody is reading input at
     * all, so the only way out was to wait — and a ten-minute turn you cannot abandon is how someone
     * decides the tool is not worth using. Measured on myself first.
     *
     * <p>A no-op where signals are not available (a pipe), which is correct: a scripted run has
     * nobody to press anything.
     */
    default AutoCloseable onInterrupt(Runnable handler) {
        return () -> { };
    }

    @Override void close();

    /**
     * A real terminal when there is one, a plain reader when stdin is a pipe.
     *
     * <p>The tty check happens BEFORE JLine is asked for anything. Letting JLine discover the pipe
     * itself works, but it logs {@code WARNING: Unable to create a system terminal} to stderr on the
     * way — and stderr is the first thing a caller quotes when explaining a failure, so the first
     * thing there must be the reason, not our terminal library clearing its throat. Same reasoning as
     * the {@code --enable-native-access} flag that silences Lucene's four warning lines at the source.
     */
    static ChatIo open() {
        if (System.console() == null) return new Plain();
        try {
            Terminal t = TerminalBuilder.builder().system(true).build();
            if (t.getType() != null && t.getType().startsWith(Terminal.TYPE_DUMB)) {
                t.close();
                return new Plain();
            }
            return new Jline(t);
        } catch (IOException e) {
            return new Plain();
        }
    }

    /** Interactive: history, line editing, and ctrl-C that cancels the line rather than the session. */
    final class Jline implements ChatIo {
        private final Terminal terminal;
        private final LineReader reader;

        Jline(Terminal terminal) {
            this.terminal = terminal;
            // History survives the process, beside the sessions — ↑ on a fresh start recalls last
            // week's prompts, which little-coder ships and which costs one variable here.
            this.reader = LineReaderBuilder.builder().terminal(terminal)
                    .variable(LineReader.HISTORY_FILE,
                            java.nio.file.Path.of(System.getProperty("user.home"),
                                    ".codezaiku", "chat", "history"))
                    .build();
        }

        @Override public void print(String s) {
            terminal.writer().print(s);
            terminal.writer().flush();
        }

        @Override public void printThinking(String s) {
            terminal.writer().print("\u001b[2m" + s + "\u001b[0m");
            terminal.writer().flush();
        }

        @Override public void printThinkingStart() {
            terminal.writer().print("\u001b[2m[thinking]\u001b[0m\n");
            terminal.writer().flush();
        }

        @Override public void notifyLine(String s) {
            // printAbove redraws the prompt and any half-typed line underneath the message —
            // the reason this interface method exists.
            try {
                reader.printAbove(s);
            } catch (RuntimeException e) {
                terminal.writer().println(s);
                terminal.writer().flush();
            }
        }

        @Override public String readLine(String prompt) {
            try {
                return reader.readLine(prompt);
            } catch (UserInterruptException e) {
                // ctrl-C clears the line and stays in the conversation, like a shell. Parity with
                // ssh, and the reason a running turn needs its own way to be stopped rather than
                // overloading this key.
                return "";
            } catch (EndOfFileException e) {
                return null;                       // ctrl-D leaves
            }
        }

        @Override public void println(String s) {
            terminal.writer().println(s);
            terminal.writer().flush();
        }

        @Override public AutoCloseable onInterrupt(Runnable handler) {
            var previous = terminal.handle(Terminal.Signal.INT, sig -> handler.run());
            // Restore whatever was there rather than assuming the default: JLine installs its own
            // handler for line editing, and leaving ours in place would break ctrl-C between turns.
            return () -> terminal.handle(Terminal.Signal.INT, previous);
        }

        @Override public void close() {
            try { terminal.close(); } catch (IOException ignored) { }
        }
    }

    /** Non-interactive: pipes, scripts, CI. */
    final class Plain implements ChatIo {
        private final BufferedReader in =
                new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));

        @Override public String readLine(String prompt) {
            try {
                System.out.print(prompt);
                System.out.flush();
                String l = in.readLine();
                if (l != null) System.out.println(l);   // echo, so a transcript reads as a dialogue
                return l;
            } catch (IOException e) {
                return null;
            }
        }

        @Override public void println(String s) { System.out.println(s); }

        @Override public void print(String s) { System.out.print(s); System.out.flush(); }

        /**
         * SIGINT via the JDK's own handler, so ctrl-C stops a turn even without a JLine terminal.
         *
         * <p>The first version made this a no-op on the grounds that "a scripted run has nobody to
         * press anything". That was wrong twice over. A person can perfectly well run with stdin
         * redirected and a real terminal in front of them — and, more to the point, it made the
         * feature <b>untestable</b>: the acceptance battery drives everything through a pipe, so a
         * no-op here means the one thing ctrl-C is for can never be checked. A capability that only
         * works where nothing can observe it is indistinguishable from one that does not work.
         *
         * <p>{@code sun.misc.Signal} lives in {@code jdk.unsupported}, which is present in every
         * standard JDK and exists precisely for this. Reflection rather than a direct call so that a
         * runtime without it degrades to the old no-op instead of failing to start.
         */
        @Override public AutoCloseable onInterrupt(Runnable handler) {
            try {
                Class<?> sig = Class.forName("sun.misc.Signal");
                Class<?> sigHandler = Class.forName("sun.misc.SignalHandler");
                Object intSig = sig.getConstructor(String.class).newInstance("INT");
                Object proxy = java.lang.reflect.Proxy.newProxyInstance(
                        getClass().getClassLoader(), new Class<?>[]{sigHandler},
                        (pr, m, a) -> { if ("handle".equals(m.getName())) handler.run(); return null; });
                Object previous = sig.getMethod("handle", sig, sigHandler)
                        .invoke(null, intSig, proxy);
                return () -> sig.getMethod("handle", sig, sigHandler).invoke(null, intSig, previous);
            } catch (Throwable t) {
                return () -> { };
            }
        }

        @Override public void close() { }
    }
}

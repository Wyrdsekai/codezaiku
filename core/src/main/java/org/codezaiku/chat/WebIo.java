package org.codezaiku.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.regex.Pattern;

/**
 * The chat's io answered by a web page. What the chat prints goes into a numbered transcript the page polls; what the chat asks
 * for with {@link #readLine} is shown on the page as the pending prompt, and the answer is whatever the page sends next. The loop
 * itself is the terminal chat's, untouched: the page is only where the words come from and go to.
 */
public final class WebIo implements ChatIo {
    /** One line of the transcript: {@code out} the chat's words, {@code think} its thinking, {@code notify} a background line, {@code me} what the person sent, {@code prompt} a question the chat asked. */
    public record Entry(long seq, String kind, String text, String who) { public Entry(long seq, String kind, String text) { this(seq, kind, text, ""); } }
    /** What the page sees: the lines after {@code since}, the line being written, the thinking being written, the prompt waiting for an answer (null while the chat works), the status line. */
    public record Snapshot(List<Entry> entries, String partial, String thinking, String pending, String status, long last) { }

    private final List<Entry> entries = new ArrayList<>();
    private long seq = 0;
    private final StringBuilder partial = new StringBuilder(), thinking = new StringBuilder();
    /** A line from the page and who sent it, for the transcript: the page says it when several people share a chat. */
    private record Said(String line, String who) { }
    private final LinkedBlockingQueue<Said> in = new LinkedBlockingQueue<>();
    private volatile String pending, status = "";
    private volatile Runnable interrupt;

    /** The terminal's colour and style codes, which the chat prints for a screen: not for a page. */
    private static final Pattern ANSI = Pattern.compile("\\u001B\\[[0-9;]*[A-Za-z]");
    static String plain(String s) { return s == null ? "" : ANSI.matcher(s).replaceAll(""); }

    private synchronized void add(String kind, String text) { entries.add(new Entry(++seq, kind, plain(text))); }
    private synchronized void flush() {
        if (thinking.length() > 0) { entries.add(new Entry(++seq, "think", thinking.toString())); thinking.setLength(0); }
        if (partial.length() > 0) { entries.add(new Entry(++seq, "out", partial.toString())); partial.setLength(0); }
    }

    @Override public String readLine(String prompt) {
        flush();
        pending = prompt;
        add("prompt", prompt);
        String line;
        Said said;
        try { said = in.take(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); pending = null; return null; }
        line = said.line();
        pending = null;
        synchronized (this) { entries.add(new Entry(++seq, "me", line, said.who())); }
        return line;
    }
    @Override public void println(String s) { synchronized (this) { if (thinking.length() > 0) { entries.add(new Entry(++seq, "think", thinking.toString())); thinking.setLength(0); } entries.add(new Entry(++seq, "out", plain(partial + s))); partial.setLength(0); } }
    @Override public void print(String s) { synchronized (this) { partial.append(plain(s)); } }
    @Override public void printThinking(String s) { synchronized (this) { thinking.append(plain(s)); } }
    @Override public void printThinkingStart() { }
    @Override public void notifyLine(String s) { add("notify", s); }
    @Override public void status(String s) { status = s == null ? "" : s; }
    @Override public void clearStatus() { status = ""; }
    @Override public AutoCloseable onInterrupt(Runnable handler) { interrupt = handler; return () -> { if (interrupt == handler) interrupt = null; }; }
    @Override public void close() { }

    /** A line of an earlier conversation, placed before the chat says anything: the page opens on where the project got to. */
    public void history(String kind, String text) { add(kind, text); }

    /** What the page sends: the next message, or the answer to the prompt that waits. */
    public void say(String line) { say(line, ""); }
    /** What the page sends, with who sent it. */
    public void say(String line, String who) { in.offer(new Said(line == null ? "" : line, who == null ? "" : who)); }
    /** The page's Stop button: what ctrl-C does in the terminal, when a turn is running. */
    public boolean stop() { Runnable r = interrupt; if (r == null) return false; r.run(); return true; }
    /** The prompt waiting for an answer, or null while the chat works. */
    public String pending() { return pending; }

    public synchronized Snapshot snapshot(long since) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) if (e.seq() > since) out.add(e);
        return new Snapshot(out, partial.toString(), thinking.toString(), pending, status, seq);
    }
}

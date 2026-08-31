package org.codezaiku.chat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import org.slf4j.LoggerFactory;

/**
 * Chat's logging: quiet screen, complete file.
 *
 * <h2>The design in one sentence</h2>
 *
 * The threshold lives on the CONSOLE APPENDER, never on the root logger — so the conversation stays
 * clean while a per-run file keeps everything, and {@code /logging info} can bring the screen back
 * without having thrown anything away.
 *
 * <p>The first cut got this wrong by setting the ROOT level to WARN, which filters events before
 * any appender sees them: the screen went quiet and so did the file that was supposed to be the
 * diagnostic record. Root stays at INFO; the console gets a filter.
 *
 * <h2>Why a file at all</h2>
 *
 * the operator's workflow, stated directly: run a chat, something odd happens, get the session id, open
 * the log for that run. So every chat process writes
 * {@code ~/.codezaiku/chat/<project-id>/logs/<timestamp>.log}, the session id is logged INTO it the
 * moment a session starts, and {@code /sessionid} prints both — the id and the file — so the two
 * halves of a diagnosis can always be joined.
 */
final class ChatLogs {

    private static final String CONSOLE_APPENDER = "STDOUT";

    private Path file;
    private ch.qos.logback.classic.LoggerContext ctx;
    private ch.qos.logback.core.filter.Filter<ch.qos.logback.classic.spi.ILoggingEvent> consoleFilter;

    Path file() { return file; }

    /**
     * Console to WARN, everything to a fresh file. Best-effort: a logging backend we do not
     * recognise, or an unwritable directory, leaves the chat working with logs as they were.
     */
    void init(Path logDir) {
        try {
            if (!(LoggerFactory.getILoggerFactory() instanceof ch.qos.logback.classic.LoggerContext lc)) return;
            this.ctx = lc;
            var root = lc.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);

            // The file first, so nothing is lost even if the console part fails.
            Files.createDirectories(logDir);
            file = logDir.resolve("chat-" + LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss")) + ".log");
            var enc = new ch.qos.logback.classic.encoder.PatternLayoutEncoder();
            enc.setContext(lc);
            enc.setPattern("%d{HH:mm:ss} %-5level %logger{0} - %msg%n");
            enc.start();
            var fa = new ch.qos.logback.core.FileAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
            fa.setContext(lc);
            fa.setName("chat-file");
            fa.setFile(file.toString());
            fa.setEncoder(enc);
            fa.start();
            root.addAppender(fa);

            console("warn");
        } catch (Throwable ignored) {
            file = null;
        }
    }

    /**
     * Set what reaches the screen: {@code off}, {@code error}, {@code warn}, {@code info},
     * {@code debug}. Debug also lowers the root so debug events exist to show (and to file).
     *
     * @return a human line describing the result, or null if the level is not one of ours
     */
    String console(String level) {
        if (ctx == null) return "logging is not adjustable with this backend";
        String l = level == null ? "" : level.strip().toUpperCase(Locale.ROOT);
        if (!l.matches("OFF|ERROR|WARN|INFO|DEBUG")) return null;
        var root = ctx.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        var console = root.getAppender(CONSOLE_APPENDER);
        if (console == null) return "no console appender to adjust";
        // Root only ever LOWERS (debug needs the events to exist); it never rises above INFO, or
        // the file would go quiet along with the screen — the first cut's bug.
        root.setLevel("DEBUG".equals(l) ? ch.qos.logback.classic.Level.DEBUG
                : ch.qos.logback.classic.Level.INFO);
        if (consoleFilter != null) console.getCopyOfAttachedFiltersList(); // no removeFilter API; clear + re-add
        console.clearAllFilters();
        var f = new ch.qos.logback.classic.filter.ThresholdFilter();
        f.setContext(ctx);
        f.setLevel("OFF".equals(l) ? "OFF" : l);
        f.start();
        console.addFilter(f);
        consoleFilter = f;
        return "console logging: " + l.toLowerCase(Locale.ROOT)
                + (file != null ? "   (the file always gets everything: " + file + ")" : "");
    }
}

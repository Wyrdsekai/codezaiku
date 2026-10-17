package org.codezaiku.drive;

/**
 * The settings of java.net.http that every HTTP client in the process shares. They are read once, when the first connection is
 * made, so they are set at each entry point before anything connects.
 *
 * <p>llama.cpp's server closes a keep-alive connection that has been idle for 5 seconds. The JVM keeps a pooled connection for
 * 20 minutes by default and reuses it, so a request sent at that moment went down a connection the server was closing. Usually
 * the request failed at once ("HTTP/1.1 header parser received no bytes") and the turn was lost; through Docker's port proxy the
 * request was taken and the reply never came, and the loop waited out its whole call limit (2026-10-06: 24 minutes, twice in forty
 * calls; a soak test put every such failure exactly 5 seconds after the previous reply). The pool now drops an idle connection
 * after {@value #KEEPALIVE_SECONDS} seconds, before the server does. A {@code -Djdk.httpclient.keepalive.timeout=} on the command
 * line wins. {@link DriveClient} also sends a request once more when a pooled connection turns out to be dead.
 */
public final class HttpSettings {

    public static final String KEEPALIVE = "jdk.httpclient.keepalive.timeout";
    public static final String KEEPALIVE_SECONDS = "3";

    private HttpSettings() { }

    /** Sets the pool's idle limit unless the command line set one. Returns the value in force. */
    public static String apply() {
        if (System.getProperty(KEEPALIVE) == null) System.setProperty(KEEPALIVE, KEEPALIVE_SECONDS);
        return System.getProperty(KEEPALIVE);
    }
}

package org.codezaiku.tools;

/** Test access to the bridge's fence from another package. */
public final class McpBridgeToolFence {
    private McpBridgeToolFence() { }
    public static String of(String server, String tool, String out) { return McpBridgeTool.fenced(server, tool, out); }
}

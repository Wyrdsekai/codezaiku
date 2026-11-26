package org.codezaiku.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Set;

/**
 * One affordance the familiar can call. The schema is advertised to the drive; {@link #execute}
 * runs the real action and returns the RAW observation (faithful — no summarizing, cause-first
 * on failure). The ACI lives here: e.g. the write tool size-caps content.
 */
public interface Tool {
    String name();

    String description();

    /** The JSON-Schema {@code parameters} object for this tool. */
    ObjectNode parametersSchema(ObjectMapper j);

    /** Execute against real disk/shell and return the observation text the model will see. */
    String execute(JsonNode args) throws Exception;

    /**
     * Whether this tool opens what a model's call names, or hands it to a program that may: such a call may hold only addresses that
     * were given ({@link GivenAddresses}), and is refused before it runs otherwise. A search's words are not held to this.
     */
    default boolean opensAddresses() { return false; }

    /** The arguments this tool takes an address in, beside the ones every tool does ({@code url}, {@code address}, {@code urls}, {@code feed}). */
    default Set<String> addressArguments(JsonNode args) { return Set.of(); }

    /** Whether this tool belongs to another program's MCP server, whose {@code address} means what that server means by it. */
    default boolean othersTool() { return false; }

    /**
     * The part of an answer whose addresses are given from now on, or null for none. By default, the answers of the web and the
     * literature ({@link GivenAddresses#FROM_OUTSIDE}); a tool that reads the project's files gives what a file held before the job
     * began; a command's output and anything a model wrote give nothing.
     */
    default String givenText(JsonNode args, String answer) { return GivenAddresses.FROM_OUTSIDE.contains(name()) ? answer : null; }
}

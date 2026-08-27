package org.codezaiku.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

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
}

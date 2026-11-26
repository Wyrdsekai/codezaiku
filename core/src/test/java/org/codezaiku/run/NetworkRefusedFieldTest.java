package org.codezaiku.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.codezaiku.exec.NetProxy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code networkRefused} in the result document of {@code codezaiku run} and the ACP result: the connections a run's commands tried
 * that the closed network refused. Added only: present only when something was refused, after every field there was, and the rest of
 * the document is the same with it or without it.
 */
class NetworkRefusedFieldTest {

    static NetProxy.Try t(String host, int port, boolean let, String rule) {
        return new NetProxy.Try(1L, Path.of("/w"), "git push origin main", host, port, let, rule);
    }

    @Test
    void theRefusedTriesAreListedAndTheRestOfTheDocumentIsUnchanged(@TempDir Path ws) {
        Map<String, Integer> served = new LinkedHashMap<>();
        served.put("DeepInfra", 7); served.put("Novita", 2);
        List<NetProxy.Try> tries = List.of(t("pypi.org", 443, true, "registry (pip)"), t("github.com", 443, false, "the project's git remote: not let in by default"),
                t("example.org", 443, false, "not let in"));
        ObjectNode before = ResultDocument.of(ws, "untested").files(List.of("a.py")).turns(3).summary("done").model("m").build();
        ObjectNode withServed = ResultDocument.of(ws, "untested").files(List.of("a.py")).turns(3).summary("done").model("m").servedBy(served).build();
        ObjectNode withBoth = ResultDocument.of(ws, "untested").files(List.of("a.py")).turns(3).summary("done").model("m").servedBy(served)
                .networkRefused(tries).build();
        assertFalse(before.has("networkRefused"), "nothing refused: no field");
        JsonNode refused = withBoth.get("networkRefused");
        assertEquals(2, refused.size(), "only the refused ones: " + refused);
        assertEquals("github.com", refused.get(0).get("host").asText());
        assertEquals(443, refused.get(0).get("port").asInt());
        assertEquals("git push origin main", refused.get(0).get("command").asText());
        assertEquals("the project's git remote: not let in by default", refused.get(0).get("rule").asText());

        // remove the new field and the document before it is there, key for key; remove both new fields and the old one is
        ObjectNode lessRefused = withBoth.deepCopy(); lessRefused.remove("networkRefused");
        assertEquals(withServed, lessRefused, "servedBy where it is, every other key as it was");
        ObjectNode lessBoth = lessRefused.deepCopy(); lessBoth.remove("servedBy");
        assertEquals(before, lessBoth, "the old document, key for key and value for value");
        List<String> keys = new ArrayList<>(); withBoth.fieldNames().forEachRemaining(keys::add);
        List<String> old = new ArrayList<>(); before.fieldNames().forEachRemaining(old::add);
        assertEquals(old, keys.subList(0, old.size()), "the fields there were come first, in their order");
        assertEquals(List.of("servedBy", "networkRefused"), keys.subList(old.size(), keys.size()), "then servedBy, then networkRefused, last");
        ObjectNode onlyRefused = ResultDocument.of(ws, "untested").files(List.of("a.py")).turns(3).summary("done").model("m").networkRefused(tries).build();
        ObjectNode less = onlyRefused.deepCopy(); less.remove("networkRefused");
        assertEquals(before, less, "and without servedBy, the same");
    }

    @Test
    void theRunsOwnDocumentCarriesItWhenItsCommandsWereRefused(@TempDir Path ws) {
        JsonNode d = new RunVerb.State(RunVerb.Args.parse(new String[]{"--text", "t"}), ws, System.out).document("untested", null, false);
        assertFalse(d.has("networkRefused"), "a run whose commands were refused nothing: the document as before");
        assertTrue(d.has("status") && d.has("files"));
    }
}

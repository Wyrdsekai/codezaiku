package org.codezaiku.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportFindingToolTest {

    private static final ObjectMapper J = new ObjectMapper();

    private static final String DIFF = """
            --- a/app/handler.py
            +++ b/app/handler.py
            @@ -10,6 +10,7 @@ def setup():
                 config = load_config()
            +    cache = Cache(ttl=0)
                 logger.info("ready")
            """;

    private static ObjectNode args(String sev, String file, String code, String problem, String fix) {
        ObjectNode a = J.createObjectNode();
        if (sev != null) a.put("severity", sev);
        if (file != null) a.put("file", file);
        if (code != null) a.put("existing_code", code);
        if (problem != null) a.put("problem", problem);
        if (fix != null) a.put("fix", fix);
        return a;
    }

    @Test void anchorsAReportedFindingAndSaysWhere() throws Exception {
        var t = new ReportFindingTool(DIFF, f -> null);
        String out = t.execute(args("high", "app/handler.py", "cache = Cache(ttl=0)",
                "ttl=0 disables caching", "set a real ttl"));

        assertTrue(out.contains("app/handler.py:11"), "the tool result must tell the model where it landed: " + out);
        assertEquals(1, t.findings().size());
        assertTrue(t.findings().get(0).located());
        assertEquals(11, t.findings().get(0).anchor().get().startLine());
    }

    /**
     * An unlocatable quote is RECORDED and the model is TOLD — not rejected. Refusing the model's
     * action to enforce a harness preference is the thing this project does not do; the feedback is
     * evidence, which the model may act on or not.
     */
    @Test void recordsAnUnlocatableFindingAndSaysSo() throws Exception {
        var t = new ReportFindingTool(DIFF, f -> null);
        String out = t.execute(args("high", "app/handler.py", "os.system(user_input)",
                "command injection", "use subprocess"));

        assertTrue(out.contains("NOT found"), "the model must be told the quote did not locate: " + out);
        assertEquals(1, t.findings().size(), "it must still be recorded, not dropped");
        assertFalse(t.findings().get(0).located());
        assertFalse(out.startsWith("ERROR"), "not locating is not an error");
    }

    /** Falls back to file content when the quote is real but outside the diff. */
    @Test void fallsBackToFileContent() throws Exception {
        var t = new ReportFindingTool(DIFF, f -> "line one\nsecret = \"hunter2\"\nline three\n");
        t.execute(args("high", "app/other.py", "secret = \"hunter2\"", "hardcoded secret", "use env"));
        assertTrue(t.findings().get(0).located());
        assertEquals(2, t.findings().get(0).anchor().get().startLine());
    }

    @Test void rejectsIncompleteCallsWithoutRecording() throws Exception {
        var t = new ReportFindingTool(DIFF, f -> null);
        assertTrue(t.execute(args("high", "app/handler.py", null, "problem", null)).startsWith("ERROR"));
        assertTrue(t.execute(args("high", null, "cache = Cache(ttl=0)", "problem", null)).startsWith("ERROR"));
        assertTrue(t.execute(args("high", "app/handler.py", "cache = Cache(ttl=0)", "  ", null)).startsWith("ERROR"));
        assertTrue(t.findings().isEmpty(), "a rejected call must record nothing");
    }

    /** Models say "critical"/"minor"/"nit"; the schema says high/med/low. Normalise rather than lose. */
    @Test void normalisesSeverityVariants() throws Exception {
        var t = new ReportFindingTool(DIFF, f -> null);
        t.execute(args("CRITICAL", "a.py", "cache = Cache(ttl=0)", "x", null));
        t.execute(args("nit", "a.py", "cache = Cache(ttl=0)", "x", null));
        t.execute(args("moderate", "a.py", "cache = Cache(ttl=0)", "x", null));
        assertEquals("high", t.findings().get(0).severity());
        assertEquals("low", t.findings().get(1).severity());
        assertEquals("med", t.findings().get(2).severity());
    }

    @Test void schemaDeclaresWhatTheLoopNeeds() {
        var t = new ReportFindingTool(DIFF, f -> null);
        ObjectNode s = t.parametersSchema(J);
        assertEquals("object", s.path("type").asText());
        for (String k : new String[]{"severity", "file", "existing_code", "problem", "fix"}) {
            assertTrue(s.path("properties").has(k), "schema must declare " + k);
        }
        String required = s.path("required").toString();
        assertTrue(required.contains("existing_code"), "the code quote is what anchors — it is required");
        assertFalse(required.contains("\"fix\""), "a fix suggestion is optional");
    }

    /** The fix is folded into the rendered body so the report reads as one statement. */
    @Test void rendersProblemAndFixTogether() throws Exception {
        var t = new ReportFindingTool(DIFF, f -> null);
        t.execute(args("high", "app/handler.py", "cache = Cache(ttl=0)", "ttl=0 disables caching", "set a real ttl"));
        String rendered = ReviewReport.render(t.findings());
        assertTrue(rendered.contains("ttl=0 disables caching — set a real ttl"), rendered);
        assertTrue(rendered.contains("app/handler.py:11"), rendered);
    }
}

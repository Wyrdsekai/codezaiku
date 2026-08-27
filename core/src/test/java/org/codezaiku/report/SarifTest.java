package org.codezaiku.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import org.codezaiku.ops.SecurityScan;
import org.codezaiku.review.DiffAnchor;
import org.codezaiku.review.ReviewReport;

import java.net.URI;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SARIF is only worth emitting if consumers accept it, and they reject a run for structural reasons
 * that are invisible when eyeballing the JSON — an undeclared ruleId being the usual one. So these
 * assert the contract, not just that a document came out.
 */
class SarifTest {

    private static final ObjectMapper J = new ObjectMapper();

    private static JsonNode parse(String s) {
        try { return J.readTree(s); } catch (Exception e) { throw new AssertionError("invalid JSON", e); }
    }

    private static ReviewReport.Finding review(String sev, String file, Integer start, Integer end) {
        Optional<DiffAnchor.Anchor> a = start == null ? Optional.empty()
                : Optional.of(new DiffAnchor.Anchor(start, end, DiffAnchor.Side.NEW));
        return new ReviewReport.Finding(sev, file, "snippet", "the problem", a);
    }

    @Test void emitsAValidSarifSkeleton() {
        JsonNode d = parse(Sarif.toJson(Sarif.fromReview(List.of(review("high", "a/b.py", 10, 12))),
                "CodeZaiku", "0.1.0"));
        assertEquals("2.1.0", d.get("version").asText());
        assertTrue(d.get("$schema").asText().contains("sarif-2.1.0"));
        assertEquals(1, d.get("runs").size());
        assertEquals("CodeZaiku", d.at("/runs/0/tool/driver/name").asText());
    }

    /** The version went out as a hardcoded "0.1.0" until 0.1.1 — valid SARIF, wrong tool version. */
    @Test void reportsTheToolVersionItWasGiven() {
        JsonNode d = parse(Sarif.toJson(Sarif.fromReview(List.of(review("high", "a.py", 1, 1))),
                "CodeZaiku", org.codezaiku.FamiliarMain.VERSION));
        assertEquals(org.codezaiku.FamiliarMain.VERSION, d.at("/runs/0/tool/driver/version").asText());
    }

    /** Consumers REJECT a run whose result references a ruleId not declared in driver.rules. */
    @Test void everyRuleIdUsedIsDeclaredExactlyOnce() {
        var findings = List.of(review("high", "a.py", 1, 1), review("low", "b.py", 2, 2));
        JsonNode d = parse(Sarif.toJson(Sarif.fromReview(findings), "CodeZaiku", "0.1.0"));
        var rules = d.at("/runs/0/tool/driver/rules");
        assertEquals(1, rules.size(), "one rule, not one per finding: " + rules);
        assertEquals("codezaiku/review-finding", rules.get(0).get("id").asText());
        for (JsonNode r : d.at("/runs/0/results")) {
            assertEquals("codezaiku/review-finding", r.get("ruleId").asText());
        }
    }

    @Test void mapsSeverityToSarifLevels() {
        assertEquals("error", Sarif.level("high"));
        assertEquals("warning", Sarif.level("med"));
        assertEquals("note", Sarif.level("low"));
        assertEquals("warning", Sarif.level("something-unexpected"), "unknown must not be dropped");
        assertEquals("warning", Sarif.level(null));
    }

    /** An unanchored finding must NOT be given a fabricated line — the same rule the console follows. */
    @Test void omitsTheRegionWhenTheFindingWasNeverAnchored() {
        JsonNode d = parse(Sarif.toJson(Sarif.fromReview(List.of(review("high", "a/b.py", null, null))),
                "CodeZaiku", "0.1.0"));
        JsonNode phys = d.at("/runs/0/results/0/locations/0/physicalLocation");
        assertEquals("a/b.py", phys.at("/artifactLocation/uri").asText());
        assertTrue(phys.at("/region").isMissingNode(), "no region for an unlocated finding: " + phys);
    }

    @Test void keepsTheLineRangeWhenAnchored() {
        JsonNode d = parse(Sarif.toJson(Sarif.fromReview(List.of(review("med", "a/b.py", 10, 14))),
                "CodeZaiku", "0.1.0"));
        JsonNode region = d.at("/runs/0/results/0/locations/0/physicalLocation/region");
        assertEquals(10, region.get("startLine").asInt());
        assertEquals(14, region.get("endLine").asInt());
    }

    /** A posture finding is about a container or a port. Inventing a file path would be a lie. */
    @Test void securityFindingsUseLogicalLocationsNotFiles() {
        var f = new SecurityScan.Finding("docker-socket-mount", "web-api", "high",
                "mounts /var/run/docker.sock", "5.31");
        JsonNode d = parse(Sarif.toJson(Sarif.fromSecurity(List.of(f)), "CodeZaiku", "0.1.0"));
        JsonNode loc = d.at("/runs/0/results/0/locations/0");
        assertTrue(loc.at("/physicalLocation").isMissingNode(), "must not fabricate a file: " + loc);
        assertEquals("web-api", loc.at("/logicalLocations/0/name").asText());
        assertEquals("codezaiku/docker-socket-mount", d.at("/runs/0/results/0/ruleId").asText());
    }

    /**
     * helpUri must be a URI. An earlier version appended "(control 5.31)" to the URL, which is not
     * one — caught by reading the generated document rather than by any assertion.
     */
    @Test void helpUriIsAUriAndTheCisControlGoesInTheDescription() {
        var f = new SecurityScan.Finding("weak-credential", "db POSTGRES_PASSWORD", "high",
                "a default value", "5.10");
        JsonNode d = parse(Sarif.toJson(Sarif.fromSecurity(List.of(f)), "CodeZaiku", "0.1.0"));
        String uri = d.at("/runs/0/tool/driver/rules/0/helpUri").asText();
        assertFalse(uri.contains(" "), "helpUri must not contain spaces: " + uri);
        assertEquals(URI.create(uri).getScheme(), "https", "must parse as a URI");
        assertTrue(d.at("/runs/0/tool/driver/rules/0/shortDescription/text").asText().contains("CIS 5.10"),
                "the control belongs in the description");
    }

    @Test void distinctChecksBecomeDistinctRules() {
        var a = new SecurityScan.Finding("privileged-container", "x", "high", "d", "5.4");
        var b = new SecurityScan.Finding("weak-credential", "y", "med", "d", "5.10");
        JsonNode d = parse(Sarif.toJson(Sarif.fromSecurity(List.of(a, b)), "CodeZaiku", "0.1.0"));
        assertEquals(2, d.at("/runs/0/tool/driver/rules").size());
    }

    @Test void emptyFindingsStillProduceAValidRun() {
        JsonNode d = parse(Sarif.toJson(List.of(), "CodeZaiku", "0.1.0"));
        assertEquals(0, d.at("/runs/0/results").size());
        assertFalse(d.at("/runs/0/tool/driver/name").isMissingNode(), "the tool block is still required");
    }

    /** Messages carry model-written prose; it must survive JSON encoding intact. */
    @Test void escapesQuotesAndNewlinesInMessages() {
        var f = new ReviewReport.Finding("high", "a.py", "s",
                "uses \"eval\" here\nand a newline\ttab", Optional.empty());
        JsonNode d = parse(Sarif.toJson(Sarif.fromReview(List.of(f)), "CodeZaiku", "0.1.0"));
        assertEquals("uses \"eval\" here\nand a newline\ttab",
                d.at("/runs/0/results/0/message/text").asText());
    }
}

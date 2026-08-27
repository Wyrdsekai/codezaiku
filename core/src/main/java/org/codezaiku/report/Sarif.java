package org.codezaiku.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.codezaiku.ops.SecurityScan;
import org.codezaiku.review.ReviewReport;

/**
 * Emits findings as SARIF 2.1.0 — the OASIS interchange format for static-analysis results.
 *
 * <p>Both surfaces that produce findings are REPORT-ONLY by design: they exist to tell a human
 * something, and until now they told it to a terminal. That requires someone to be watching. SARIF is
 * what GitHub code scanning, DefectDojo, the VS Code viewer and most vulnerability managers already
 * ingest, so the same output lands where a team already looks — annotated on the pull request rather
 * than scrolled past in a log.
 *
 * <p>This changes nothing about finding QUALITY. It is interop, and the honest claim for it is that it
 * makes existing output consumable rather than better.
 *
 * <p>The two surfaces locate their findings differently and the format is followed rather than forced:
 * a review finding names a file and line range and gets a {@code physicalLocation}; a posture finding
 * is about a container or a port, which has no file, and gets a {@code logicalLocation}. Inventing a
 * file path for the latter would put a fabricated location in a report someone acts on.
 */
public final class Sarif {

    /** One finding, in the shape SARIF needs. */
    public record Result(String ruleId, String level, String message,
                         Optional<String> file, int startLine, int endLine,
                         Optional<String> logicalName, Optional<String> helpUri,
                         Optional<String> ruleLabel) {

        /** A finding located in source. */
        public static Result inFile(String ruleId, String level, String message,
                                    String file, int startLine, int endLine) {
            return new Result(ruleId, level, message, Optional.of(file), startLine, endLine,
                    Optional.empty(), Optional.empty(), Optional.empty());
        }

        /** A finding about a thing that is not a file — a container, a port, a host. */
        public static Result about(String ruleId, String level, String message,
                                   String logicalName, String helpUri, String ruleLabel) {
            return new Result(ruleId, level, message, Optional.empty(), 0, 0,
                    Optional.of(logicalName), Optional.ofNullable(helpUri),
                    Optional.ofNullable(ruleLabel));
        }
    }

    private static final ObjectMapper J = new ObjectMapper();

    /** SARIF severities. Anything unrecognised becomes a warning rather than being dropped. */
    public static String level(String severity) {
        if (severity == null) return "warning";
        return switch (severity.toLowerCase(Locale.ROOT)) {
            case "high", "critical", "error" -> "error";
            case "low", "info", "note" -> "note";
            default -> "warning";
        };
    }

    /** Serialize to a SARIF 2.1.0 document. */
    public static String toJson(List<Result> results, String toolName, String version) {
        ObjectNode root = J.createObjectNode();
        root.put("$schema", "https://json.schemastore.org/sarif-2.1.0.json");
        root.put("version", "2.1.0");

        ObjectNode run = root.putArray("runs").addObject();
        ObjectNode driver = run.putObject("tool").putObject("driver");
        driver.put("name", toolName);
        driver.put("version", version == null ? "unknown" : version);   // never a stale literal
        driver.put("informationUri", "https://github.com/Wyrdsekai/codezaiku");

        // Every ruleId a result references must be declared once in driver.rules, or consumers
        // (GitHub among them) reject the run. Collected from the results rather than hand-listed, so
        // the two cannot drift apart.
        Map<String, Result> rules = new LinkedHashMap<>();
        for (Result r : results) rules.putIfAbsent(r.ruleId(), r);
        ArrayNode ruleArr = driver.putArray("rules");
        for (Map.Entry<String, Result> e : rules.entrySet()) {
            ObjectNode rule = ruleArr.addObject();
            rule.put("id", e.getKey());
            String label = e.getValue().ruleLabel()
                    .map(l -> describe(e.getKey()) + " (" + l + ")")
                    .orElseGet(() -> describe(e.getKey()));
            rule.putObject("shortDescription").put("text", label);
            // helpUri must be a URI. An earlier version appended "(control 5.31)" to the URL, which
            // is not one — a consumer validating it would reject or mangle the link. The control
            // number belongs in the description.
            e.getValue().helpUri().ifPresent(u -> rule.put("helpUri", u));
        }

        ArrayNode out = run.putArray("results");
        for (Result r : results) {
            ObjectNode res = out.addObject();
            res.put("ruleId", r.ruleId());
            res.put("level", r.level());
            res.putObject("message").put("text", r.message());
            ObjectNode loc = res.putArray("locations").addObject();
            if (r.file().isPresent()) {
                ObjectNode phys = loc.putObject("physicalLocation");
                phys.putObject("artifactLocation").put("uri", r.file().get());
                // A region is emitted only when the finding was actually ANCHORED. Writing
                // startLine:1 for an unlocated finding would be a fabricated location in a report
                // someone acts on — the same rule the console output follows.
                if (r.startLine() > 0) {
                    ObjectNode region = phys.putObject("region");
                    region.put("startLine", r.startLine());
                    if (r.endLine() >= r.startLine()) region.put("endLine", r.endLine());
                }
            } else if (r.logicalName().isPresent()) {
                loc.putArray("logicalLocations").addObject()
                        .put("name", r.logicalName().get())
                        .put("kind", "resource");
            }
        }
        try {
            return J.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("could not serialize SARIF", e);
        }
    }

    /** A readable name for a rule id, since ours are kebab-case slugs rather than prose. */
    private static String describe(String ruleId) {
        String tail = ruleId.contains("/") ? ruleId.substring(ruleId.indexOf('/') + 1) : ruleId;
        String words = tail.replace('-', ' ').replace('_', ' ').trim();
        return words.isEmpty() ? ruleId : Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    /** Adapt review findings: file + line range, one rule since a review has no taxonomy. */
    public static List<Result> fromReview(List<ReviewReport.Finding> findings) {
        List<Result> out = new ArrayList<>();
        for (var f : findings) {
            int start = f.anchor().map(a -> a.startLine()).orElse(0);
            int end = f.anchor().map(a -> a.endLine()).orElse(0);
            out.add(Result.inFile("codezaiku/review-finding", level(f.severity()),
                    f.body(), f.file(), start, end));
        }
        return out;
    }

    /** Adapt posture findings: the check IS the rule, the subject is a resource, CIS is the help. */
    public static List<Result> fromSecurity(List<SecurityScan.Finding> findings) {
        List<Result> out = new ArrayList<>();
        for (var f : findings) {
            boolean hasCis = f.cis() != null && !f.cis().isBlank();
            String help = hasCis ? "https://www.cisecurity.org/benchmark/docker" : null;
            String label = hasCis ? "CIS " + f.cis() : null;
            out.add(Result.about("codezaiku/" + f.check(), level(f.severity()),
                    f.detail(), f.subject(), help, label));
        }
        return out;
    }

    private Sarif() { }
}

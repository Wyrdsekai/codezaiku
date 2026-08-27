package org.codezaiku.gate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads a gate from DATA ({@code /gates/<name>.json}) and selects the archetype engine. Adding a
 * fixture — in any language — is now a JSON spec + an existing engine, not new Java (L7: general by
 * construction). Authoring these specs from each fixture's acceptance criteria is the
 * RequestNormalizer's job later; for now they're hand-written data per fixture.
 */
public final class GateLoader {
    private static final ObjectMapper J = new ObjectMapper();

    private GateLoader() {
    }

    /** Load the named gate spec and build its archetype engine. */
    public static Gate gate(String name) {
        JsonNode root;
        try {
            root = readSpec(name);
        } catch (IOException e) {
            throw new RuntimeException("failed to load gate spec " + name, e);
        }
        String archetype = root.path("archetype").asText("http-service");
        String specName = root.path("name").asText(name);
        return switch (archetype) {
            case "http-service" -> new BehaviorDoorGate(httpSpec(specName, root));
            case "cli" -> new CliGate(specName, cliSteps(root));
            // future: "headless" (game scene / signals)
            default -> throw new IllegalArgumentException(
                    "no gate engine for archetype '" + archetype + "' (fixture " + name + ")");
        };
    }

    /**
     * A compile-only sub-gate: the gate's build/parse step (id build|loads|compile, else the first
     * step) as a 1-step CliGate. Used per sub-task in decomposition — "does it still compile?" —
     * reusing the fixture's own build command. CLI archetype only.
     */
    public static Gate compileGate(String name) {
        JsonNode root;
        try {
            root = readSpec(name);
        } catch (IOException e) {
            throw new RuntimeException("failed to load gate spec " + name, e);
        }
        if (!"cli".equals(root.path("archetype").asText("http-service"))) {
            throw new IllegalArgumentException("compileGate is CLI-archetype only (fixture " + name + ")");
        }
        List<CliStep> steps = cliSteps(root);
        CliStep build = steps.stream()
                .filter(s -> s.id().matches("build|loads|compile"))
                .findFirst().orElse(steps.isEmpty() ? null : steps.get(0));
        if (build == null) throw new IllegalArgumentException("no build step in gate " + name);
        return new CliGate(root.path("name").asText(name) + "-compile", List.of(build));
    }

    private static GateSpec httpSpec(String name, JsonNode root) {
        List<HttpClaim> claims = new ArrayList<>();
        for (JsonNode c : root.path("claims")) {
            claims.add(new HttpClaim(
                    c.path("id").asText(),
                    c.path("method").asText("GET"),
                    c.path("path").asText(),
                    c.hasNonNull("body") ? c.get("body").asText() : null,
                    c.path("expectStatus").asInt(200),
                    bodyCheck(c.path("bodyCheck")),
                    c.hasNonNull("capture") ? c.get("capture").asText() : null,
                    c.hasNonNull("containsDerived") ? c.get("containsDerived").asText() : null,
                    strList(c.path("containsDerivedAll"))));
        }
        return new GateSpec(name, "http-service",
                root.path("boot").asText(),
                root.path("readinessPath").asText("/"),
                root.path("bootTimeoutSec").asInt(180),
                claims);
    }

    private static List<CliStep> cliSteps(JsonNode root) {
        List<CliStep> steps = new ArrayList<>();
        for (JsonNode s : root.path("steps")) {
            List<Integer> expect = new ArrayList<>();
            JsonNode ex = s.path("expectExit");
            if (ex.isArray()) ex.forEach(n -> expect.add(n.asInt()));
            else if (ex.isInt()) expect.add(ex.asInt());
            else expect.add(0);
            steps.add(new CliStep(
                    s.path("id").asText(),
                    s.path("cmd").asText(),
                    s.path("timeoutSec").asInt(300),
                    expect,
                    s.hasNonNull("contains") ? s.get("contains").asText() : null,
                    s.hasNonNull("notContains") ? s.get("notContains").asText() : null,
                    s.hasNonNull("containsDerived") ? s.get("containsDerived").asText() : null,
                    strList(s.path("containsDerivedAll"))));
        }
        return steps;
    }

    /** A bundled fixture name ({@code /gates/<name>.json}) or a filesystem path to a spec.json. */
    private static JsonNode readSpec(String name) throws IOException {
        if (name.endsWith(".json")) {
            return J.readTree(Files.readString(Path.of(name)));
        }
        try (InputStream in = GateLoader.class.getResourceAsStream("/gates/" + name + ".json")) {
            if (in == null) throw new IllegalArgumentException("no gate spec at /gates/" + name + ".json");
            return J.readTree(in);
        }
    }

    /** Parse a JSON array of strings (e.g. {@code containsDerivedAll}); empty list if missing/not-array. */
    private static List<String> strList(JsonNode n) {
        List<String> out = new ArrayList<>();
        if (n != null && n.isArray()) n.forEach(e -> out.add(e.asText()));
        return out;
    }

    private static BodyCheck bodyCheck(JsonNode bc) {
        if (bc.isMissingNode() || bc.isNull()) return BodyCheck.ANY;
        String arg = bc.path("arg").asText("");
        return switch (bc.path("kind").asText("any")) {
            case "jsonArray" -> BodyCheck.jsonArray();
            case "contains" -> BodyCheck.contains(arg);
            case "hasField" -> BodyCheck.hasField(arg);
            default -> BodyCheck.ANY;
        };
    }
}

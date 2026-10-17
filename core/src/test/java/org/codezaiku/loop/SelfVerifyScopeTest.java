package org.codezaiku.loop;

import com.fasterxml.jackson.databind.JsonNode;
import org.codezaiku.drive.DriveClient;
import org.codezaiku.tools.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The self-verify reflection judges the model's own work: a project's pre-existing placeholder module ("for demo purposes") is never
 * flagged as the model's fake core, while a file the model wrote that says so is. Measured on the maintenance battery (2026-10-16): the
 * whole-tree scan flagged the app's own stubs in 116 of 175 runs and sent every model off rewriting them.
 */
class SelfVerifyScopeTest {

    private static List<String> reflections(StubDrive stub) {
        List<String> out = new ArrayList<>();
        for (JsonNode req : stub.requests) {
            JsonNode msgs = req.path("messages");
            String last = msgs.get(msgs.size() - 1).path("content").asText("");
            if (last.contains("HARNESS CHECK")) out.add(last);
        }
        return out;
    }

    @Test
    void aProjectsOwnPlaceholderIsNotTheModelsFakeCore(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("app"));
        Files.writeString(tmp.resolve("app/analytics.py"), "def predict(emails):\n    # Simple dummy implementation\n    # For demo purposes, we'll just return a few predictions\n    return []\n");
        Files.writeString(tmp.resolve("app/main.py"), "from app.analytics import predict\n");
        Files.writeString(tmp.resolve("app/routes.py"), "def route():\n    return 'ok'\n");
        try (StubDrive stub = new StubDrive(req -> {
            int n = stub_count(req);
            if (n == 1) return StubDrive.calls("write_file", "{\"path\":\"app/routes.py\",\"content\":\"def route():\\n    return 'fixed'\\n\"}");
            return StubDrive.calls("task_done", "{\"summary\":\"fixed the route\"}");
        })) {
            FamiliarLoop.Result r = new FamiliarLoop(new DriveClient(stub.url(), "t"), ToolRegistry.standard(tmp, null), tmp, "Fix the route.", 8, null, null).run();
            assertTrue(r.done(), r.summary());
            for (String m : reflections(stub)) assertFalse(m.contains("appears to FAKE") || m.contains("STUBS or simulates"), "the app's own placeholder was flagged: " + m);
        }
    }

    @Test
    void aFileTheModelWroteThatAdmitsFakingIsFlagged(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("README.md"), "# a project\n");
        try (StubDrive stub = new StubDrive(req -> {
            int n = stub_count(req);
            if (n == 1) return StubDrive.calls("write_file", "{\"path\":\"core.py\",\"content\":\"def train():\\n    # for demo purposes this does not actually train anything\\n    return 0\\n\"}");
            return StubDrive.calls("task_done", "{\"summary\":\"done\"}");
        })) {
            new FamiliarLoop(new DriveClient(stub.url(), "t"), ToolRegistry.standard(tmp, null), tmp, "Train the model.", 8, null, null).run();
            List<String> found = reflections(stub);
            assertTrue(found.stream().anyMatch(m -> m.contains("appears to FAKE") && m.contains("core.py")), "the model's own faked core is flagged: " + found);
        }
    }

    /** How many assistant turns the request already holds, plus one: the turn being asked for. */
    private static int stub_count(JsonNode req) {
        int n = 0;
        for (JsonNode m : req.path("messages")) if (m.path("role").asText("").equals("assistant")) n++;
        return n + 1;
    }
}

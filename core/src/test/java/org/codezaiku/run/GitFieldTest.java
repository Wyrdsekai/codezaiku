package org.codezaiku.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.codezaiku.exec.GitDirGuard;
import org.codezaiku.exec.GitOutside;
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

/**
 * {@code git} in the result document of {@code codezaiku run} and the ACP result: what CodeZaiku pushed and the pull requests it opened
 * for the run. Added only: present only when something was done, after every field there was, servedBy and networkRefused included.
 */
class GitFieldTest {

    @Test
    void whatWasPushedAndOpenedComesLastAndTheRestIsUnchanged(@TempDir Path ws) {
        Map<String, Integer> served = new LinkedHashMap<>();
        served.put("DeepInfra", 7);
        List<NetProxy.Try> refused = List.of(new NetProxy.Try(1L, ws, "curl x", "example.org", 443, false, "not let in"));
        List<GitOutside.Act> acts = List.of(
                new GitOutside.Act(2L, ws, "push", "origin", "https://github.com/me/proj.git", "fix", null, "a".repeat(40), 3, false, null),
                new GitOutside.Act(3L, ws, "pull request", "origin", "github.com/me/proj", "fix", null, null, 0, false, "https://github.com/me/proj/pull/8"));
        ObjectNode before = ResultDocument.of(ws, "untested").files(List.of("a.py")).summary("done").model("m").servedBy(served).networkRefused(refused).build();
        ObjectNode with = ResultDocument.of(ws, "untested").files(List.of("a.py")).summary("done").model("m").servedBy(served).networkRefused(refused).git(acts).build();
        assertFalse(before.has("git"), "nothing pushed: no field");
        JsonNode g = with.get("git");
        assertEquals("{\"remote\":\"origin\",\"address\":\"https://github.com/me/proj.git\",\"branch\":\"fix\",\"from\":null,\"to\":\"" + "a".repeat(40)
                + "\",\"commits\":3,\"force\":false}", g.get("pushed").get(0).toString());
        assertEquals("https://github.com/me/proj/pull/8", g.get("pullRequests").get(0).get("url").asText());
        ObjectNode less = with.deepCopy(); less.remove("git");
        assertEquals(before, less, "the document without it, key for key");
        List<String> keys = new ArrayList<>(); with.fieldNames().forEachRemaining(keys::add);
        assertEquals(List.of("servedBy", "networkRefused", "git"), keys.subList(keys.size() - 3, keys.size()), "last, after the two fields added before it");
    }

    @Test
    void whatWasPutBackInTheRepositorysOwnSettingsComesAfterItAndTheRestIsUnchanged(@TempDir Path ws) {
        List<GitOutside.Act> acts = List.of(new GitOutside.Act(2L, ws, "push", "origin", "https://github.com/me/proj.git", "fix", null, "a".repeat(40), 3, false, null));
        List<GitDirGuard.PutBack> back = List.of(new GitDirGuard.PutBack(3L, ws, ".git/config", "core.fsmonitor = /x (added): git will run /x whenever you run git status",
                "/home/someone/.codezaiku/git-put-back/proj-1/t/.git/config", true, null));
        ObjectNode before = ResultDocument.of(ws, "untested").files(List.of("a.py")).summary("done").git(acts).build();
        ObjectNode with = ResultDocument.of(ws, "untested").files(List.of("a.py")).summary("done").git(acts).gitPutBack(back).build();
        assertFalse(before.has("gitPutBack"), "nothing put back: no field");
        assertEquals("[{\"path\":\".git/config\",\"change\":\"core.fsmonitor = /x (added): git will run /x whenever you run git status\","
                + "\"copy\":\"/home/someone/.codezaiku/git-put-back/proj-1/t/.git/config\"}]", with.get("gitPutBack").toString());
        ObjectNode less = with.deepCopy(); less.remove("gitPutBack");
        assertEquals(before, less, "the document without it, key for key");
        List<String> keys = new ArrayList<>(); with.fieldNames().forEachRemaining(keys::add);
        assertEquals(List.of("git", "gitPutBack"), keys.subList(keys.size() - 2, keys.size()), "last");
    }
}

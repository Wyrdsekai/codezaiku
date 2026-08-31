package org.codezaiku.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The search tool exists to spend fewer tokens than the shell — so its bounds are the contract. */
class SearchCodeToolTest {

    private static final ObjectMapper J = new ObjectMapper();

    private String run(Path root, String pattern, String path) throws Exception {
        var t = new SearchCodeTool(new PathScope(root));
        var args = J.createObjectNode().put("pattern", pattern);
        if (path != null) args.put("path", path);
        return t.execute(args);
    }

    @Test
    void findsMatchesAsPathLineText(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("a.py"), "x = 1\nretry_count = 3\n");
        Files.createDirectories(root.resolve("sub"));
        Files.writeString(root.resolve("sub/b.py"), "print(retry_count)\n");
        String out = run(root, "retry_count", null);
        assertThat(out).contains("a.py:2:").contains("sub/b.py:1:");
    }

    @Test
    void noMatchesIsAnAnswerNotAnError(@TempDir Path root) throws Exception {
        // grep/rg exit 1 on "no matches" — the house rule: judge output, not exit codes.
        Files.writeString(root.resolve("a.py"), "x = 1\n");
        assertThat(run(root, "zzz_not_here", null)).startsWith("no matches");
    }

    @Test
    void theResultIsCappedAndSaysWhatWasDropped(@TempDir Path root) throws Exception {
        var sb = new StringBuilder();
        for (int i = 0; i < 100; i++) sb.append("needle line ").append(i).append('\n');
        Files.writeString(root.resolve("big.txt"), sb.toString());
        String out = run(root, "needle", null);
        assertThat(out.lines().count()).isLessThanOrEqualTo(SearchCodeTool.MAX_MATCHES + 1L);
        assertThat(out).contains("more matches");
    }

    @Test
    void anEscapingPathIsRefusedWithTheScopesOwnWords(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("a.py"), "x\n");
        assertThat(run(root, "x", "/etc")).startsWith("ERROR:");
    }

    @Test
    void gitAndBuildDirectoriesAreNeverSearched(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve(".git"));
        Files.writeString(root.resolve(".git/config"), "needle\n");
        Files.createDirectories(root.resolve("build"));
        Files.writeString(root.resolve("build/out.txt"), "needle\n");
        Files.writeString(root.resolve("real.py"), "needle\n");
        String out = run(root, "needle", null);
        assertThat(out).contains("real.py").doesNotContain(".git").doesNotContain("build/");
    }
}

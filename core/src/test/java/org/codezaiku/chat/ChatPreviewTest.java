package org.codezaiku.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * The preview is what makes an approval prompt a decision rather than a rubber stamp, so what it
 * shows — and what it refuses to hide — is pinned.
 */
class ChatPreviewTest {

    private static final ObjectMapper J = new ObjectMapper();

    @Test
    void anEditShowsBothSidesOfTheChange() {
        var args = J.createObjectNode()
                .put("path", "Client.java")
                .put("old_string", "return http.execute(r);")
                .put("new_string", "return retry(() -> http.execute(r));");
        assertThat(ChatPreview.of("edit_file", args))
                .containsExactly("- return http.execute(r);",
                                 "+ return retry(() -> http.execute(r));");
    }

    @Test
    void aNewFileShowsItsContentAndItsSize() {
        // "347 lines" is the number that makes someone look properly at what they thought was a
        // small change.
        var args = J.createObjectNode().put("path", "x.py").put("content", "a\nb\nc\n");
        assertThat(ChatPreview.of("write_file", args))
                .contains("+ a", "+ b", "+ c")
                .anySatisfy(l -> assertThat(l).contains("4 lines"));
    }

    @Test
    void aLongCommandIsWrappedRatherThanTruncated() {
        // The dangerous part of a long command line is usually at the END. Cutting the tail off
        // hides exactly the thing the person is being asked about.
        String tail = "&& rm -rf /important";
        String cmd = "docker run --rm " + "-v /a/b/c:/x ".repeat(12) + tail;
        var out = ChatPreview.of("shell", J.createObjectNode().put("command", cmd));
        assertThat(String.join("", out)).contains("rm -rf /important");
    }

    @Test
    void aVeryLongChangeIsElidedInTheMIDDLEAndSaysHowMuch() {
        var sb = new StringBuilder();
        for (int i = 0; i < 100; i++) sb.append("line ").append(i).append('\n');
        var out = ChatPreview.of("write_file", J.createObjectNode().put("content", sb.toString()));
        assertThat(out).hasSizeLessThan(20);
        assertThat(out).anySatisfy(l -> assertThat(l).contains("more lines"));
        // Head AND tail survive: a change that only showed its beginning would hide the end, which
        // is where the surprise usually is.
        assertThat(String.join("\n", out)).contains("line 0").contains("line 99");
    }

    @Test
    void aReadNeedsNoPreviewBecauseItIsNeverAskedAbout() {
        assertThat(ChatPreview.of("read_file", J.createObjectNode().put("path", "x"))).isEmpty();
    }

    @Test
    void missingArgumentsDoNotThrow() {
        // A malformed tool call must produce a poor preview, never an exception that ends the turn.
        assertThat(ChatPreview.of("edit_file", null)).isEmpty();
        assertThat(ChatPreview.of("shell", J.createObjectNode())).hasSize(1);
    }
}

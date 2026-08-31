package org.codezaiku.drive;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The incremental decoder that lets a person watch the answer appear. Fragments split ANYWHERE —
 * that is how tool-call deltas actually arrive — so every boundary case is a split point here.
 */
class SummaryStreamTest {

    private String decode(String field, List<String> fragments) {
        var out = new StringBuilder();
        var s = new DriveClient.SummaryStream(field, out::append);
        fragments.forEach(s::feed);
        return out.toString();
    }

    @Test
    void theWholeThingInOneFragment() {
        assertThat(decode("summary", List.of("{\"summary\": \"all done\"}"))).isEqualTo("all done");
    }

    @Test
    void splitInsideTheKeyInsideTheValueAndInsideAnEscape() {
        assertThat(decode("summary", List.of(
                "{\"sum", "mary\"", ": \"line one\\", "nline two\"}")))
                .isEqualTo("line one\nline two");
    }

    @Test
    void aUnicodeEscapeSplitMidSequence() {
        assertThat(decode("summary", List.of("{\"summary\": \"a\\u00", "e9b\"}"))).isEqualTo("aéb");
    }

    @Test
    void escapedQuotesDoNotEndTheValue() {
        assertThat(decode("summary", List.of("{\"summary\": \"say \\\"hi\\\" now\"}")))
                .isEqualTo("say \"hi\" now");
    }

    @Test
    void otherFieldsBeforeTheNeedleAreIgnored() {
        assertThat(decode("reason", List.of(
                "{\"detail\": \"not this\", \"reason\": \"the real one\"}")))
                .isEqualTo("the real one");
    }

    @Test
    void nothingAfterTheClosingQuoteLeaks() {
        assertThat(decode("summary", List.of("{\"summary\": \"done\", \"files\": [\"a\",\"b\"]}")))
                .isEqualTo("done");
    }
}

package org.codezaiku.acp;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ACP `_meta` key is a wire contract, and the rename moved it with no alias.
 *
 * The env-var fallback cannot reach a JSON key, so this needed its own shim. The failure it
 * prevents is the quiet kind: a host still reading `_meta.codeplane` gets a missing node rather
 * than an error, the turn reports success, and every typed artifact degrades to scraping text.
 * Nothing in the transcript looks wrong.
 *
 * Both names are emitted for the whole 0.x line; `codezaiku` alone at 1.0.
 */
class AcpMetaKeyContractTest {

    @Test
    void bothTheNewAndTheOldKeyAreNamed() {
        assertEquals("codezaiku", AcpServer.META_KEY);
        assertEquals("codeplane", AcpServer.META_KEY_LEGACY);
    }

    @Test
    void theResultDocumentIsWrittenUnderBoth() throws Exception {
        String src = Files.readString(Path.of("src/main/java/org/codezaiku/acp/AcpServer.java"));
        int at = src.indexOf("putObject(\"_meta\")");
        assertTrue(at > 0, "the _meta emission site moved — this test is pinned to it");
        String block = src.substring(at, Math.min(src.length(), at + 260));
        assertTrue(block.contains("META_KEY") && block.contains("META_KEY_LEGACY"),
                "both keys must be set on the same _meta object:\n" + block);
    }
}

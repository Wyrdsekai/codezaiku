package org.codezaiku.ops;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A matched card must also be RUNNABLE on the target it matched against.
 *
 * <p>The two-layer keyword/signature trigger cannot decide this, and assuming it could was the actual
 * defect. Host-tier cards declare {@code match: linux, host, systemd, systemctl, service} — and
 * {@code host} is true of every target, so {@code host-systemd-unit-failure} matched on a Mac however
 * honestly the environment was described. Measured by set-diff over the real card corpus: replacing the
 * hardcoded {@code "linux systemd host docker"} with probed tokens removed exactly ZERO systemctl-bearing
 * cards from a Mac. The token was never how they were getting in.
 *
 * <p>A card that cannot run is worse than no card — the model follows a confident procedure into
 * "command not found" and spends the run on it.
 */
class OpsKnowledgeApplicabilityTest {

    private static OpsKnowledge.Card card(String body, String... match) {
        return new OpsKnowledge.Card(List.of(match), List.of("unit entered failed state"),
                "rescue", "validated", List.of(), body, "test-card.md");
    }

    private static final OpsKnowledge.Card SYSTEMD = card(
            "Restart the failed unit:\n    systemctl restart <unit>\n    journalctl -u <unit> -n 50",
            "linux", "host", "systemd");
    private static final OpsKnowledge.Card DOCKER = card(
            "Bring the container back:\n    docker start <container>", "redis", "cache");

    private static final String LINUX = "host linux systemd docker";
    private static final String MAC = "host macos darwin launchd docker kubectl k8s kubernetes";
    private static final String PROBE = "unit entered failed state";

    @Test
    void aSystemctlProcedureIsRecognisedAsUnrunnableOnAMac() {
        assertEquals("systemctl", OpsKnowledge.missingTool(SYSTEMD, MAC));
    }

    @Test
    void theFilterIsOnByDefaultNowThatItHasEarnedIt() {
        // Earned by a positive controlled flip on certstack: with it OFF, host-tls-cert-expiry (a
        // systemctl procedure) won the fast path on a macOS docker host and SKIPPED the diagnosis loop;
        // with it ON the run fell back to recon. Costs the certified refstack nothing — the cards it
        // withholds there are kubernetes-only and could never fire on that stack anyway.
        assertEquals("systemctl", OpsKnowledge.withheldTool(SYSTEMD, MAC),
                "CODEZAIKU_OPS_CARD_APPLICABILITY defaults on");
        assertNull(OpsKnowledge.matchSignature(List.of(SYSTEMD), "host", PROBE, MAC),
                "an unrunnable procedure must not win the fast path");
    }

    @Test
    void theSameCardIsOfferedOnLinuxWhereItRuns() {
        assertNull(OpsKnowledge.missingTool(SYSTEMD, LINUX));
        assertNotNull(OpsKnowledge.matchSignature(List.of(SYSTEMD), "host", PROBE, LINUX));
    }

    @Test
    void aDockerProcedureStillMatchesOnAMacBecauseDockerRunsThere() {
        // The filter is about the tool, not the platform — Docker Desktop is real on a Mac.
        assertNull(OpsKnowledge.missingTool(DOCKER, MAC));
        assertNotNull(OpsKnowledge.matchSignature(List.of(DOCKER), "cache", PROBE, MAC));
    }

    @Test
    void aTargetWeDidNotProbeIsNeverFilteredOn() {
        // Unknown capability must not silently suppress every card — the old behaviour is the fallback.
        assertNull(OpsKnowledge.missingTool(SYSTEMD, null));
        assertNull(OpsKnowledge.missingTool(SYSTEMD, ""));
        assertNotNull(OpsKnowledge.matchSignature(List.of(SYSTEMD), "host", PROBE, null));
        assertNotNull(OpsKnowledge.matchSignature(List.of(SYSTEMD), "host", PROBE),
                "the three-argument form keeps its old contract");
    }

    @Test
    void aCardNamingAnAvailableToolAlongsideAMissingOneStaysRunnable() {
        // Cards routinely offer an alternative path ("or, on kubernetes, kubectl exec …"). Requiring
        // EVERY named tool discarded 12 cards on the certified refstack, validated ones included.
        OpsKnowledge.Card both = card("Run `docker restart <c>`; on k8s use `kubectl rollout restart`", "cache");
        assertNull(OpsKnowledge.missingTool(both, "host linux systemd docker"));
    }

    @Test
    void aCardWhoseOnlyPathIsMissingIsUnrunnable() {
        OpsKnowledge.Card k8sOnly = card("Run `kubectl exec <pod> -- valkey-cli CONFIG GET requirepass`", "redis");
        assertEquals("kubectl", OpsKnowledge.missingTool(k8sOnly, "host linux systemd docker"));
    }

    @Test
    void aRunnableCardStillWinsWhenAnUnrunnableOneScoresHigher() {
        // The unrunnable card matches MORE signature strings; it must still lose, not merely rank lower.
        OpsKnowledge.Card strongButUnrunnable = new OpsKnowledge.Card(
                List.of("host"), List.of("unit entered failed state", "activating auto-restart"),
                "rescue", "validated", List.of(), "systemctl restart <unit>", "strong.md");
        OpsKnowledge.Card weakButRunnable = new OpsKnowledge.Card(
                List.of("cache"), List.of("unit entered failed state"),
                "rescue", "validated", List.of(), "docker start <container>", "weak.md");
        assertEquals("systemctl", OpsKnowledge.missingTool(strongButUnrunnable, MAC));
        assertNull(OpsKnowledge.missingTool(weakButRunnable, MAC),
                "so once the filter is enabled the weaker-scoring but RUNNABLE card is the one left");
    }

    // ---- the DECLARED platform, which outranks sniffing the body ---------------------------------

    private static OpsKnowledge.Card declaring(String body, List<String> platforms) {
        return new OpsKnowledge.Card(List.of("redis"), List.of("noauth authentication required"),
                "rescue", "validated", platforms, body, "declared.md");
    }

    @Test
    void aCardThatDeclaresBothPlatformsRunsOnEither() {
        // redis-auth-server as it now stands: k8s steps AND docker steps, declared.
        OpsKnowledge.Card both = declaring(
                "`docker exec <c> redis-cli CONFIG GET requirepass`, or `kubectl exec <pod> -- valkey-cli …`",
                List.of("kubernetes", "docker"));
        assertNull(OpsKnowledge.missingTool(both, "host linux systemd docker"));
        assertNull(OpsKnowledge.missingTool(both, "host linux kubectl kubernetes"));
    }

    @Test
    void aDeclaredKubernetesCardIsWithheldFromADockerOnlyBox() {
        // The state redis-auth-server was in: status:validated, but kubectl-only steps reaching a docker
        // stack. Validation was earned on AIOpsLab, which is Kubernetes; it did not transfer.
        OpsKnowledge.Card k8sOnly = declaring("`kubectl exec <pod> -- valkey-cli CONFIG GET requirepass`",
                List.of("kubernetes"));
        assertEquals("kubernetes", OpsKnowledge.missingTool(k8sOnly, "host linux systemd docker"));
    }

    @Test
    void theDeclarationBeatsTheBodyInBothDirections() {
        // Body says docker, declaration says kubernetes-only: believe the author, not the grep.
        OpsKnowledge.Card mislabelledByBody = declaring(
                "Read `docker inspect <c>` for context, then `kubectl rollout restart deploy/<d>`",
                List.of("kubernetes"));
        assertEquals("kubernetes", OpsKnowledge.missingTool(mislabelledByBody, "host linux docker"),
                "a docker mention in prose does not make a kubernetes procedure runnable");

        // Body names no tool at all, declaration says docker: still constrained.
        OpsKnowledge.Card noTools = declaring("Raise the memory limit and restart the service.",
                List.of("docker"));
        assertEquals("docker", OpsKnowledge.missingTool(noTools, "host linux systemd"));
        assertNull(OpsKnowledge.missingTool(noTools, "host linux systemd docker"));
    }

    @Test
    void anUndeclaredCardStillFallsBackToItsBody() {
        // 47 of 76 cards declare nothing; the heuristic has to keep covering them.
        assertEquals("systemctl", OpsKnowledge.missingTool(SYSTEMD, MAC));
    }

    @Test
    void toolNamesAreMatchedOnWordBoundaries() {
        // A hyphen IS a boundary, so this card genuinely names the tool and has no other path.
        assertEquals("systemctl", OpsKnowledge.missingTool(
                card("Follow the systemctl-based recovery for the unit.", "cache"), MAC));
        // A tool name buried inside a longer word is not a use of that tool.
        assertNull(OpsKnowledge.missingTool(
                card("Check the journalctlx custom log shipper's queue.", "cache"), MAC));
        // Naming an AVAILABLE tool alongside a missing one keeps the card runnable.
        assertNull(OpsKnowledge.missingTool(
                card("See the systemctl-style notes; run: docker restart <c>", "cache"), MAC));
    }
}

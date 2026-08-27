package org.codezaiku.ops;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The environment tokens a card's {@code match:} keywords are gated on.
 *
 * <p>These were the constant {@code "linux systemd host docker "} — true of the reference stack and
 * false of every other target CodeZaiku now reaches. The consequence is not a missed card but a WRONG
 * one: a host-tier systemd card passes the keyword gate on a Mac and hands the model a
 * {@code systemctl} procedure that cannot run there. A card that never matches is recoverable; a
 * confident procedure for the wrong init system is not.
 *
 * <p>The Linux case is pinned too, because it is the certified path: the probed token set must still
 * contain everything the old constant asserted, or R4 card eligibility changes underneath us.
 */
class TargetOsStackTokensTest {

    /** An Exec that answers only the two probes stackTokens makes, from a scripted target. */
    private static final class FakeExec implements Exec {
        private final String uname;
        private final String tools;
        FakeExec(String uname, String tools) { this.uname = uname; this.tools = tools; }
        @Override public String describe() { return "fake"; }
        @Override public Result run(String command, int timeoutSec) {
            if (command.contains("uname")) return new Result(0, uname);
            if (command.contains("command -v")) return new Result(0, tools);
            return new Result(0, "");
        }
        @Override public String read(String path) { return null; }
        @Override public boolean isFile(String path) { return false; }
        @Override public boolean isDir(String path) { return false; }
        @Override public void write(String path, String content) { }
    }

    @Test
    void aMacTargetIsNotDescribedAsLinuxWithSystemd() {
        String s = TargetOs.stackTokens(new FakeExec("Darwin", "launchctl\ndocker\n"));
        assertFalse(s.contains("systemd"),
                "a Mac has no systemctl — claiming systemd is what hands the model an unrunnable procedure");
        assertFalse(s.contains("linux"), "'linux' must not appear for a Darwin target");
        assertTrue(s.contains("macos"));
        assertTrue(s.contains("launchd"));
        assertTrue(s.contains("docker"), "Docker Desktop is real on a Mac and its cards should stay eligible");
    }

    @Test
    void theCertifiedLinuxStackKeepsEveryTokenTheOldConstantAsserted() {
        String s = TargetOs.stackTokens(new FakeExec("Linux", "systemctl\ndocker\n"));
        for (String token : new String[]{"linux", "systemd", "host", "docker"}) {
            assertTrue(s.contains(token), "refstack eligibility depends on '" + token + "'");
        }
    }

    @Test
    void aSystemdLessLinuxHostDoesNotClaimSystemd() {
        // A container host or a distro without systemd — the token has to follow the probe, not the OS.
        String s = TargetOs.stackTokens(new FakeExec("Linux", "docker\n"));
        assertTrue(s.contains("linux"));
        assertTrue(s.contains("docker"));
        assertFalse(s.contains("systemd"));
    }

    @Test
    void aKubernetesTargetEarnsTheKeywordsK8sCardsActuallyDeclare() {
        // 8 cards match on each of "kubernetes"/"kubectl"/"k8s"; a kubectl-bearing target must hit them.
        String s = TargetOs.stackTokens(new FakeExec("Linux", "systemctl\ndocker\nkubectl\n"));
        assertTrue(s.contains("kubectl"));
        assertTrue(s.contains("k8s"));
        assertTrue(s.contains("kubernetes"));
    }

    @Test
    void anUnidentifiableTargetNamesNoOsRatherThanGuessingOne() {
        String s = TargetOs.stackTokens(new FakeExec("", "docker\n"));
        assertFalse(s.contains("linux"));
        assertFalse(s.contains("macos"));
        assertTrue(s.contains("host"));
        assertTrue(s.contains("docker"), "what it demonstrably has is still worth saying");
    }

    @Test
    void aNullExecIsTheHostTierAndNothingElse() {
        assertTrue(TargetOs.stackTokens(null).contains("host"));
        assertFalse(TargetOs.stackTokens(null).contains("linux"));
    }
}

package org.codezaiku.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The session is the thing that was actually missing, so the properties that make it safe to re-seed
 * from get pinned: it is bounded, it survives a move, and it carries unfinished work.
 */
class ChatSessionTest {

    // Restore, never clear: clearProperty("user.home") DELETES the JVM's real home for every
    // test that runs after this class in the same JVM (caught 2026-09-01 — LibraryStore.open()
    // NPE'd in a test that never touched sessions). Tests that fake home put the truth back.
    private static final String REAL_HOME = System.getProperty("user.home");

    private ChatSession session(Path root) {
        return ChatSession.start(root, "add retry handling to the client");
    }

    @Test
    void restateIsEmptyOnTheFirstTurn(@TempDir Path root) {
        // A preamble saying "nothing has happened yet" spends tokens to say nothing, and tokens are
        // the scarce thing: our system prompt and tool schemas already cost ~2k before the task.
        assertThat(session(root).restate()).isEmpty();
    }

    @Test
    void restateCarriesWhatWasDecided(@TempDir Path root) {
        var s = session(root);
        s.topic("add retry handling");
        s.decided("wrap http.execute in a bounded retry");
        s.sawFile("Client.java");
        s.verdict("4 passed, 0 failed");
        assertThat(s.restate())
                .contains("add retry handling")
                .contains("wrap http.execute in a bounded retry")
                .contains("Client.java")
                .contains("4 passed");
    }

    @Test
    void unfinishedWorkIsCarriedAndNamedAsOutstanding(@TempDir Path root) {
        // The measured failure this exists for: told "do it", a model read a file instead; told "now
        // run the tests" on the next turn it ran them, and the edit never happened. A verb cannot do
        // this — it has one goal and runs until it is met. In a chat, every turn can displace
        // unfinished work, so it has to survive the next thing the person says.
        var s = session(root);
        s.pending("wrap http.execute in a retry");
        assertThat(s.restate()).contains("NOT DONE YET").contains("wrap http.execute in a retry");
        s.resolved("wrap http.execute in a retry");
        assertThat(s.restate()).doesNotContain("NOT DONE YET");
    }

    @Test
    void stateIsBoundedSoItCannotBecomeATranscript(@TempDir Path root) {
        // The whole design rests on this. A field that grows without bound quietly rebuilds
        // "accumulate", which is what the -39% multi-turn degradation is a property of.
        var s = session(root);
        for (int i = 0; i < 100; i++) s.decided("decision " + i);
        for (int i = 0; i < 100; i++) s.sawFile("File" + i + ".java");
        for (int i = 0; i < 100; i++) s.pending("todo " + i);

        assertThat(s.decisions()).hasSize(ChatSession.MAX_DECISIONS);
        assertThat(s.files()).hasSize(ChatSession.MAX_FILES);
        assertThat(s.pending()).hasSize(ChatSession.MAX_PENDING);
        // The newest survive, the oldest go.
        assertThat(s.decisions()).contains("decision 99").doesNotContain("decision 0");
        // And the loss is reported, never silent.
        assertThat(s.dropped()).isGreaterThan(0);
    }

    @Test
    void restatingSomethingMovesItRatherThanDuplicatingIt(@TempDir Path root) {
        var s = session(root);
        s.decided("use exponential backoff");
        s.decided("cap retries at 3");
        s.decided("use exponential backoff");
        assertThat(s.decisions()).containsExactly("cap retries at 3", "use exponential backoff");
    }

    @Test
    void projectIdIsAFileInTheProjectSoSessionsSurviveAMove(@TempDir Path root) throws Exception {
        // FamiliarMemory keys its store by a hash of the ABSOLUTE PATH, so moving a checkout
        // silently orphans it. This does not repeat that.
        String id = ChatSession.projectId(root);
        assertThat(id).isNotBlank();
        assertThat(Files.readString(root.resolve(".codezaiku").resolve("project-id"))).contains(id);

        Path moved = root.resolveSibling("moved-" + root.getFileName());
        Files.move(root, moved);
        assertThat(ChatSession.projectId(moved)).isEqualTo(id);
    }

    @Test
    void theStateFileSaysItIsNotYoursToEdit(@TempDir Path root) {
        // We rewrite it after every turn, so an edit would be clobbered. Saying so in the file is
        // cheaper than a merge conflict inside the thing that decides what the model sees.
        var s = session(root);
        s.decided("cap retries at 3");
        assertThat(s.toMarkdown()).contains("overwritten").contains("cap retries at 3");
    }

    @Test
    void discardRemovesBothFilesSoAFailedRunLeavesNothing(@TempDir Path root) throws Exception {
        System.setProperty("user.home", root.toString());
        try {
            var s = ChatSession.start(root, "can you build me a web crawler");
            s.log("user", "can you build me a web crawler");
            s.save();
            assertThat(Files.exists(s.stateFile())).isTrue();
            assertThat(Files.exists(s.transcriptFile())).isTrue();
            s.discard();
            assertThat(Files.exists(s.stateFile())).isFalse();
            assertThat(Files.exists(s.transcriptFile())).isFalse();
        } finally {
            System.setProperty("user.home", REAL_HOME);
        }
    }

    @Test
    void aNoteFromThePersonOutranksTheDerivedStateAndSurvivesEverything(@TempDir Path root) {
        // Measured need: asked to "remember the deploy target", the model echoed to the shell,
        // diff-verified the tree, and tried to write a README. Pinning a fact is the person's act,
        // costs no model turn, rides ahead of the derived state, and carries through save/load AND
        // onboarding into a new session.
        System.setProperty("user.home", root.toString());
        try {
            var s = session(root);
            s.note("deploy target is staging-eu-3, owner is payments");
            s.decided("something the harness derived");
            String r = s.restate();
            assertThat(r.indexOf("staging-eu-3")).isLessThan(r.indexOf("something the harness derived"));
            s.turnDone(); s.save();
            var back = ChatSession.load(root, s.id()).orElseThrow();
            assertThat(back.notes()).containsExactly("deploy target is staging-eu-3, owner is payments");
            var next = ChatSession.onboardFrom(root, back);
            assertThat(next.restate()).contains("staging-eu-3");
            back.unnote("deploy target is staging-eu-3, owner is payments");
            assertThat(back.notes()).isEmpty();
        } finally {
            System.setProperty("user.home", REAL_HOME);
        }
    }

    @Test
    void aSavedSessionReadsBackWithNOTHING_LOST(@TempDir Path root) {
        // The markdown IS the format — there is no machine-only copy beside it. That is only safe if
        // the writer and the reader stay in step, so this is the check that keeps them honest:
        // round-trip a fully populated session and compare what the MODEL would see. Comparing
        // restate() rather than fields is deliberate — restate() is the thing that actually reaches
        // the model, and a field that survives the trip but never reaches the prompt is not memory.
        System.setProperty("user.home", root.toString());
        try {
            var a = ChatSession.start(root, "add retry handling to the client");
            a.topic("add retry handling to the client");
            a.decided("wrap http.execute in a bounded retry");
            a.decided("cap retries at 3, no delay");
            a.pending("update the README");
            a.sawFile("Client.java");
            a.sawFile("RetryPolicy.java");
            a.verdict("6 passed, 0 failed");
            a.turnDone();
            a.turnDone();
            a.save();

            var b = ChatSession.load(root, a.id()).orElseThrow();
            assertThat(b.restate()).isEqualTo(a.restate());
            assertThat(b.turns()).isEqualTo(2);
            assertThat(b.decisions()).containsExactlyElementsOf(a.decisions());
            assertThat(b.pending()).containsExactlyElementsOf(a.pending());
            assertThat(b.files()).containsExactlyInAnyOrderElementsOf(a.files());
        } finally {
            System.setProperty("user.home", REAL_HOME);
        }
    }

    @Test
    void loadingAMissingSessionIsEmptyRatherThanABlankOneWearingItsName(@TempDir Path root) {
        System.setProperty("user.home", root.toString());
        try {
            assertThat(ChatSession.load(root, "2026-01-01-nope")).isEmpty();
        } finally {
            System.setProperty("user.home", REAL_HOME);
        }
    }

    @Test
    void listReportsSessionsNewestFirst(@TempDir Path root) {
        System.setProperty("user.home", root.toString());
        try {
            var older = new ChatSession("2026-08-01-older", root, "older one");
            older.decided("x"); older.turnDone(); older.save();
            var newer = new ChatSession("2026-08-27-newer", root, "newer one");
            newer.decided("y"); newer.turnDone(); newer.turnDone(); newer.save();

            var all = ChatSession.list(root);
            assertThat(all).hasSize(2);
            assertThat(all.get(0)[0]).isEqualTo("2026-08-27-newer");   // newest first
            assertThat(all.get(0)[1]).isEqualTo("newer one");
            assertThat(all.get(0)[2]).isEqualTo("2");
        } finally {
            System.setProperty("user.home", REAL_HOME);
        }
    }

    @Test
    void onboardingSeedsANewSessionAndLeavesTheOldOneAlone(@TempDir Path root) {
        System.setProperty("user.home", root.toString());
        try {
            var old = ChatSession.start(root, "build the retry layer");
            old.topic("build the retry layer");
            old.decided("cap retries at 3");
            old.pending("update the README");
            old.sawFile("Client.java");
            old.turnDone(); old.save();

            var fresh = ChatSession.onboardFrom(root, old);
            // New identity — resuming continues a session; onboarding starts one that KNOWS.
            assertThat(fresh.id()).isNotEqualTo(old.id());
            assertThat(fresh.turns()).isZero();
            // The state carries; the model sees where the work stood.
            assertThat(fresh.restate())
                    .contains("cap retries at 3")
                    .contains("update the README")
                    .contains("Client.java")
                    .contains("onboarded from [[" + old.id() + "]]");
            // And the source is untouched — it stays a readable record of where things ended.
            assertThat(ChatSession.load(root, old.id())).isPresent();
        } finally {
            System.setProperty("user.home", REAL_HOME);
        }
    }

    @Test
    void aHandoffCarriesTheNoteAndNamesThePickupCommand(@TempDir Path root) throws Exception {
        System.setProperty("user.home", root.toString());
        try {
            var s = ChatSession.start(root, "build the retry layer");
            s.decided("cap retries at 3");
            s.turnDone();
            Path f = s.writeHandoff("the flaky test is test_backoff — start there");
            String text = Files.readString(f);
            assertThat(text)
                    .contains("the flaky test is test_backoff")      // the human part
                    .contains("cap retries at 3")                     // the state
                    .contains("chat --from " + s.id());               // how to pick it up
        } finally {
            System.setProperty("user.home", REAL_HOME);
        }
    }

    @Test
    void aHandoffFileIsNotListedAsASession(@TempDir Path root) throws Exception {
        // The battery found this: the handoff whose own text says "pick this up with --from <id>"
        // made <id> ambiguous, because the listing counted the handoff as a second session.
        System.setProperty("user.home", root.toString());
        try {
            var s = ChatSession.start(root, "trust me");
            s.decided("x"); s.turnDone(); s.save();
            s.writeHandoff("note");
            assertThat(ChatSession.list(root)).hasSize(1);
        } finally {
            System.setProperty("user.home", REAL_HOME);
        }
    }

    @Test
    void savingAndLoggingSurviveAnUnwritableStore(@TempDir Path root) throws Exception {
        // An unwritable store must not end a conversation mid-turn.
        Path blocked = root.resolve("nope");
        Files.createFile(blocked);                       // a FILE where the store dir would go
        var s = new ChatSession("x", root, "t");
        System.setProperty("user.home", blocked.toString());
        try {
            s.decided("something");
            s.save();
            s.log("user", "hello");                      // must not throw
        } finally {
            System.setProperty("user.home", REAL_HOME);
        }
    }
}

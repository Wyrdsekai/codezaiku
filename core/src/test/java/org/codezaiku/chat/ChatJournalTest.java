package org.codezaiku.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The journal is the safety net, and it must exist in projects git never touched — that is the
 * whole reason it replaced the git-plumbing checkpoints. NO test here creates a .git directory.
 */
class ChatJournalTest {

    private ChatJournal journal(Path root, Path store) {
        return new ChatJournal(root, store);
    }

    @Test
    void anEditRollsBackToThePreImage(@TempDir Path root, @TempDir Path store) throws Exception {
        Files.writeString(root.resolve("a.py"), "return 1\n");
        var j = journal(root, store);
        j.beginStep("edit a.py");
        j.preWrite("a.py");                                  // the listener fires before the tool
        Files.writeString(root.resolve("a.py"), "return 2\n");   // the "tool" mutates
        assertThat(j.undo(1)).startsWith("rewound 1 step");
        assertThat(Files.readString(root.resolve("a.py"))).isEqualTo("return 1\n");
    }

    @Test
    void aCreatedFileIsDeletedOnUndo(@TempDir Path root, @TempDir Path store) throws Exception {
        var j = journal(root, store);
        j.beginStep("edit a.py");
        j.preWrite("new.py");                                // did not exist at capture time
        Files.writeString(root.resolve("new.py"), "x = 1\n");
        j.undo(1);
        assertThat(Files.exists(root.resolve("new.py"))).isFalse();
    }

    @Test
    void eachActionIsItsOwnStepSoUndoIsSurgical(@TempDir Path root, @TempDir Path store) throws Exception {
        // the operator's correction of the first design: "each turn the agent does is a step, and we can
        // undo — this allows for correction." Two edits are two steps; /undo 1 takes back only the
        // newest, leaving the earlier one standing.
        Files.writeString(root.resolve("a.py"), "v1\n");
        Files.writeString(root.resolve("b.py"), "b1\n");
        var j = journal(root, store);
        j.beginStep("edit a.py");
        j.preWrite("a.py");
        Files.writeString(root.resolve("a.py"), "v2\n");
        j.beginStep("edit b.py");
        j.preWrite("b.py");
        Files.writeString(root.resolve("b.py"), "b2\n");
        String r = j.undo(1);
        assertThat(r).contains("edit b.py").doesNotContain("edit a.py");
        assertThat(Files.readString(root.resolve("b.py"))).isEqualTo("b1\n");   // stepped back
        assertThat(Files.readString(root.resolve("a.py"))).isEqualTo("v2\n");   // still standing
    }

    @Test
    void undoNWalksBackNTurnsNewestFirst(@TempDir Path root, @TempDir Path store) throws Exception {
        Files.writeString(root.resolve("a.py"), "v1\n");
        var j = journal(root, store);
        j.beginStep("edit a.py");
        j.preWrite("a.py");
        Files.writeString(root.resolve("a.py"), "v2\n");
        j.beginStep("edit a.py again");
        j.preWrite("a.py");
        Files.writeString(root.resolve("a.py"), "v3\n");
        j.undo(2);
        assertThat(Files.readString(root.resolve("a.py"))).isEqualTo("v1\n");
    }

    @Test
    void aMutatingShellIsCoveredByATreeSnapshot(@TempDir Path root, @TempDir Path store) throws Exception {
        Files.writeString(root.resolve("kept.txt"), "before\n");
        var j = journal(root, store);
        j.beginStep("edit a.py");
        j.preShell();                                        // before the command runs
        Files.writeString(root.resolve("kept.txt"), "clobbered\n");   // what the command "did"
        Files.writeString(root.resolve("junk.txt"), "made by the command\n");
        String r = j.undo(1);
        assertThat(r).startsWith("rewound").doesNotContain("PARTIAL");
        assertThat(Files.readString(root.resolve("kept.txt"))).isEqualTo("before\n");
        assertThat(Files.exists(root.resolve("junk.txt"))).isFalse();
    }

    @Test
    void anEditBeforeAShellInTheSameTurnStillRewindsToTheTrueStart(@TempDir Path root, @TempDir Path store)
            throws Exception {
        // The battery caught this: the tree snapshot is taken when the first mutating SHELL appears,
        // which can be MID-turn — an earlier edit is already baked into it, and letting the tree
        // win restored the edited text while reporting "rewound". The preWrite image is older and
        // must be applied over the tree restore.
        Files.writeString(root.resolve("a.py"), "return 1\n");
        var j = journal(root, store);
        j.beginStep("edit a.py");
        j.preWrite("a.py");
        Files.writeString(root.resolve("a.py"), "return 2\n");   // the edit
        j.preShell();                                             // tree copied AFTER the edit
        Files.writeString(root.resolve("shellmade.txt"), "x\n"); // the shell's own damage
        String r = j.undo(1);
        assertThat(r).startsWith("rewound");
        assertThat(Files.readString(root.resolve("a.py"))).isEqualTo("return 1\n");
        assertThat(Files.exists(root.resolve("shellmade.txt"))).isFalse();
    }

    @Test
    void depthIsCappedAndTheOldestRecordRetires(@TempDir Path root, @TempDir Path store) throws Exception {
        Files.writeString(root.resolve("a.py"), "v0\n");
        var j = journal(root, store);
        for (int t = 1; t <= j.depth() + 3; t++) {
            j.beginStep("step " + t);
            j.preWrite("a.py");
            Files.writeString(root.resolve("a.py"), "v" + t + "\n");
        }
        assertThat(j.recorded()).isEqualTo(j.depth());
        // Rewinding EVERYTHING kept lands at the oldest surviving pre-image, not v0 — the cap is a
        // cap, and pretending to reach past it would be a lie about what was saved.
        j.undo(999);
        assertThat(Files.readString(root.resolve("a.py"))).isEqualTo("v3\n");
        assertThat(j.recorded()).isZero();
    }

    @Test
    void aSuggestedDepthIsUsed(@TempDir Path root, @TempDir Path store) {
        // The chat passes its turn cap as the suggestion — whatever budget a person grants, the
        // rewind covers it. The CODEZAIKU_UNDO_DEPTH override is pinned in the battery, not here:
        // Config reads the ENVIRONMENT, and a unit test cannot set that — the first version of
        // this test setProperty'd a value Config never looks at and asserted on nothing.
        assertThat(new ChatJournal(root, store, 100).depth()).isEqualTo(100);
        assertThat(new ChatJournal(root, store, 0).depth()).isEqualTo(1);   // floor, never zero
    }

    @Test
    void undoWithNothingJournaledSaysSoInsteadOfGuessing(@TempDir Path root, @TempDir Path store) {
        assertThat(journal(root, store).undo(1)).contains("nothing to undo");
    }

    @Test
    void pathsOutsideTheProjectAreNeverCaptured(@TempDir Path root, @TempDir Path store) {
        var j = journal(root, store);
        j.beginStep("edit a.py");
        j.preWrite("../outside.txt");                        // confinement, same rule as the tools
        assertThat(j.undo(1)).startsWith("rewound 1 step");         // and nothing outside was touched
    }
}

package org.codezaiku.loop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The files a goal names: which count, which are inputs, and what the model is shown. */
class DeliverablesTest {

    private static final String BRIEF = """
            # Build a tool

            ## Data (in `data/`)
            - `data/clips/<play_id>.mp4` — 40 clips, 1280x720.
            - `data/pitches.jsonl` — one line per clip: `play_id`, `pitch_type`, and `arm_angle`.

            ## What to build
            - `measure.py`: `python measure.py <clip.mp4>` prints one JSON object with at least `"arm_angle"`.
            - `evaluate.py`: runs `measure.py` over the clips in `data/`.
            - `RESULTS.md`: the numbers from `evaluate.py`.
            - A `requirements.txt` (or equivalent).

            ## Rules
            - Open-source software under a licence such as Apache-2.0, MIT or BSD; say which in RESULTS.md.
            """;

    @Test
    void theNamesAreFilesNotPlaceholdersKeysOrLicences() {
        assertEquals(List.of("data/pitches.jsonl", "measure.py", "evaluate.py", "RESULTS.md", "requirements.txt"), Deliverables.named(BRIEF));
    }

    @Test
    void urlsProductsMethodCallsAndPatternsAreNotFiles() {
        String goal = "Use Node.js and next.js; see https://example.com/docs/index.html and example.org/a/spec.json. "
                + "Call `response.json()` and write each `<id>.json`, every *.py and {name}.md; C:\\tmp\\old.py stays. "
                + "Write the verdicts to verdicts.jsonl, the app in src/app.ts, and a Dockerfile.";
        assertEquals(List.of("verdicts.jsonl", "src/app.ts", "Dockerfile"), Deliverables.named(goal));
    }

    @Test
    void whatIsOnDiskAtTheStartIsAnInputAndTheRestIsCheckedEachTurn(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("data"));
        Files.writeString(root.resolve("data/pitches.jsonl"), "{}\n");
        Deliverables d = Deliverables.of(BRIEF, root);
        assertEquals(List.of("measure.py", "evaluate.py", "RESULTS.md", "requirements.txt"), d.names());
        assertEquals(d.names(), d.missing());

        Files.writeString(root.resolve("measure.py"), "print(1)\n");
        Files.createDirectories(root.resolve("report"));
        Files.writeString(root.resolve("report/results.md"), "# numbers\n");   // elsewhere and in another case: still written
        Files.writeString(root.resolve("evaluate.py"), "");                     // empty: not written
        d.refresh();
        assertEquals(List.of("evaluate.py", "requirements.txt"), d.missing());
        String shown = d.pinned();
        assertTrue(shown.contains(" - measure.py — written"), shown);
        assertTrue(shown.contains(" - RESULTS.md — written"), shown);
        assertTrue(shown.contains(" - evaluate.py — not written yet"), shown);
        assertTrue(shown.contains(" - requirements.txt — not written yet"), shown);
    }

    @Test
    void aProgramAndADocumentAreWrittenAnOutputIsProduced(@TempDir Path root) {
        assertEquals(Deliverables.Kind.PROGRAM, Deliverables.kind("measure.py"));
        assertEquals(Deliverables.Kind.PROGRAM, Deliverables.kind("src/app.ts"));
        assertEquals(Deliverables.Kind.PROGRAM, Deliverables.kind("Dockerfile"));
        assertEquals(Deliverables.Kind.PROGRAM, Deliverables.kind("v1.2/config.yaml"));
        assertEquals(Deliverables.Kind.DOCUMENT, Deliverables.kind("RESULTS.md"));
        assertEquals(Deliverables.Kind.DOCUMENT, Deliverables.kind("requirements.txt"));
        assertEquals(Deliverables.Kind.OUTPUT, Deliverables.kind("output/traj-eval/verdicts.jsonl"));
        assertEquals(Deliverables.Kind.OUTPUT, Deliverables.kind("totals.csv"));
        Deliverables d = Deliverables.of("Write `measure.py`, `RESULTS.md` and the verdicts in `verdicts.jsonl`.", root);
        assertEquals(List.of("measure.py"), d.missing(Deliverables.Kind.PROGRAM));
        assertEquals(List.of("measure.py", "RESULTS.md"), d.missing(Deliverables.Kind.PROGRAM, Deliverables.Kind.DOCUMENT));
        assertEquals(List.of("measure.py", "RESULTS.md", "verdicts.jsonl"), d.missing());
    }

    @Test
    void aGoalThatNamesNothingToWriteShowsNothing(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("utils.py"), "x = 1\n");
        Deliverables d = Deliverables.of("Fix the off-by-one bug in utils.py and keep the tests green.", root);
        assertTrue(d.isEmpty());
        assertEquals("", d.pinned());
        d.refresh();
        assertTrue(d.missing().isEmpty());
        assertFalse(Deliverables.none().pinned().contains("FILES"));
    }
}

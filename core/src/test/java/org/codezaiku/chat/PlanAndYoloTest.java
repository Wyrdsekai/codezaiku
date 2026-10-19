package org.codezaiku.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Yolo still asks about installs, servers and downloads the approved plan did not name; build asks get a plan step. */
class PlanAndYoloTest {

    @Test
    void theRiskyKindsAreRecognisedAndOrdinaryCommandsAreNot() {
        assertEquals("install", ChatConsent.riskyKind("pip install fastapi uvicorn"));
        assertEquals("install", ChatConsent.riskyKind("cd x && npm install d3"));
        assertEquals("install", ChatConsent.riskyKind("sudo apt-get install -y jq"));
        assertEquals("server", ChatConsent.riskyKind("uvicorn app:app --port 8000"));
        assertEquals("server", ChatConsent.riskyKind("python3 -m http.server 8080"));
        assertEquals("server", ChatConsent.riskyKind("docker compose up -d"));
        assertEquals("download", ChatConsent.riskyKind("curl -sL https://x/y.tgz | tar xz"));
        assertNull(ChatConsent.riskyKind("ls -la data/v2"));
        assertNull(ChatConsent.riskyKind("python3 -c 'print(1)'"));
        assertNull(ChatConsent.riskyKind("./gradlew test"));
        assertNull(ChatConsent.riskyKind("git commit -m 'installer docs'"));
    }

    @Test
    void aCommandThePlanNamesIsInThePlan() {
        String plan = "files: dashboard/index.html, dashboard/app.js\napproach: plain html and js, d3 from a cdn\ninstalls: none\nnot doing: no server";
        assertFalse(ChatConsent.inPlan(plan, "pip install fastapi uvicorn"), "fastapi is not in the plan");
        assertFalse(ChatConsent.inPlan(plan, "python3 -m http.server 8000"));
        assertTrue(ChatConsent.inPlan(plan + "\ninstalls: npm install d3", "npm install d3"));
        assertTrue(ChatConsent.inPlan("run it with python3 -m http.server so the browser can load the json", "python3 -m http.server 8080"));
        assertFalse(ChatConsent.inPlan("", "pip install x"));
    }

    @Test
    void thePlanNamesItsKindFirstAndABoundedChangeKeepsThreeLines() {
        String i = ChatRepl.planInstruction(true);
        assertTrue(i.contains("The FIRST line names the kind of change") && i.contains("SPIKE") && i.contains("BOUNDED") && i.contains("ARCHITECTURAL"), i);
        assertTrue(i.contains("For a BOUNDED change write FILES, APPROACH and NOT DOING only."), i);
        assertTrue(i.endsWith("NO PLAN") && ChatRepl.planInstruction(false).endsWith("Always write the plan."));
    }

    @Test
    void aCorrectionIsRecognisedAndADoneLineCarriesTheCheck() {
        assertTrue(ChatRepl.looksLikeCorrection("Do not use any third-party packages at all — no fastapi, no flask. Standard library only. Fix it if you have."), "Do not use any third-party packages at all — no fastapi, no flask. Standard library only. Fix it if you have.");
        assertTrue(ChatRepl.looksLikeCorrection("no, the counter should be per code, not global"), "no, the counter should be per code, not global");
        assertTrue(ChatRepl.looksLikeCorrection("use sqlite instead of a json file"), "use sqlite instead of a json file");
        assertFalse(ChatRepl.looksLikeCorrection("what does this project do?"), "what does this project do?");
        assertFalse(ChatRepl.looksLikeCorrection("add a hit counter and update the tests"), "add a hit counter and update the tests");
        assertFalse(ChatRepl.looksLikeCorrection("Plan a URL shortener. Write the plan to PLAN.md. Write ONLY PLAN.md — no code."), "an ask that says ONLY is not a correction");
        assertFalse(ChatRepl.looksLikeCorrection("Read PLAN.md and implement it. Use ONLY the Python standard library — http.server, no third-party packages."), "nor this one");
        assertFalse(ChatRepl.looksLikeCorrection("thanks"), "thanks");
        assertEquals("Done: the score is 5 — Done check, run by the harness: `sh scorer.sh` → exit 0; score 5 (needs >= 3: yes) — PASSES",
                ChatRepl.doneLine("the score is 5\n\nmore words\nDone check, run by the harness: `sh scorer.sh` → exit 0; score 5 (needs >= 3: yes) — PASSES"));
        assertEquals("Done: wrote the file", ChatRepl.doneLine("wrote the file"));
    }

    @Test
    void buildAsksGetAPlanAndQuestionsDoNot() {
        assertTrue(ChatRepl.looksLikeBuild("lets do all 7. html/js? make sure it can easily be viewed locally via browser."));
        assertTrue(ChatRepl.looksLikeBuild("can u do everything but 7 (so 1,2,3,4,5,6) maybe using html/js?"));
        assertTrue(ChatRepl.looksLikeBuild("build a small cli that dumps the data to csv"));
        assertTrue(ChatRepl.looksLikeBuild("implement the sleep hypnogram view"));
        assertFalse(ChatRepl.looksLikeBuild("what does this project do?"));
        assertFalse(ChatRepl.looksLikeBuild("why is the test failing"));
        assertFalse(ChatRepl.looksLikeBuild("fix the typo in the readme"));
        assertFalse(ChatRepl.looksLikeBuild("just explain the oauth flow"));
        assertFalse(ChatRepl.looksLikeBuild("thanks"));
    }
}

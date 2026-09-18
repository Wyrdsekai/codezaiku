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

package org.codezaiku.chat;

import org.codezaiku.chat.ChatConsent.Answer;
import org.codezaiku.chat.ChatConsent.Mode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The chat's question when the sandbox's closed network refused a command a host: it names the host and the command that asked, and
 * says in plain words what a yes lets a command do; a yes is for this chat; "never" is not asked again; plan mode runs no command and
 * asks nothing. Asked in yolo too: the person said "stop asking", not "send my code anywhere".
 */
class NetworkConsentTest {

    record Asked(String what, List<String> preview) { }

    static ChatConsent consent(Mode mode, List<Asked> asked, Answer... answers) {
        int[] i = {0};
        return new ChatConsent(mode, (what, preview) -> {
            asked.add(new Asked(what, preview));
            return answers[Math.min(i[0]++, answers.length - 1)];
        });
    }

    @Test
    void theQuestionNamesTheHostTheCommandAndWhatAYesLetsItDo() {
        List<Asked> asked = new ArrayList<>();
        ChatConsent c = consent(Mode.ASK, asked, Answer.YES);
        assertTrue(c.letHostIn("github.com", 443, "git push origin main", true));
        Asked q = asked.get(0);
        assertEquals("let this chat's commands reach github.com", q.what());
        String shown = String.join("\n", q.preview());
        assertTrue(shown.contains("A command asked to reach github.com, a git remote of this project:") && shown.contains("git push origin main"), shown);
        assertTrue(shown.contains("may send anything to github.com, the project's code included, until the chat ends"), shown);
        assertTrue(shown.contains("codezaiku config set sandbox.hosts github.com"), "how to make it permanent: " + shown);
        assertTrue(consent(Mode.ASK, new ArrayList<>(), Answer.YES).letHostIn("data.example.org", 8443, "curl x", false));
    }

    @Test
    void aNoIsANoNeverIsNotAskedAgainAndPlanModeAsksNothing() {
        List<Asked> asked = new ArrayList<>();
        ChatConsent c = consent(Mode.ASK, asked, Answer.NO, Answer.NO_ALWAYS, Answer.YES);
        assertFalse(c.letHostIn("a.example.org", 443, "curl a", false));
        assertFalse(c.letHostIn("b.example.org", 443, "curl b", false));
        assertFalse(c.letHostIn("b.example.org", 443, "curl b again", false), "never: kept for the session");
        assertEquals(2, asked.size(), "and not asked the second time");
        List<Asked> none = new ArrayList<>();
        assertFalse(consent(Mode.PLAN, none, Answer.YES).letHostIn("a.example.org", 443, "curl a", false));
        assertTrue(none.isEmpty(), "plan mode runs no command and asks nothing");
        List<Asked> yolo = new ArrayList<>();
        assertTrue(consent(Mode.YOLO, yolo, Answer.YES).letHostIn("a.example.org", 443, "curl a", false));
        assertEquals(1, yolo.size(), "yolo still asks about the network");
    }
}

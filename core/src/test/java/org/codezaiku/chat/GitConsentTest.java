package org.codezaiku.chat;

import org.codezaiku.chat.ChatConsent.Answer;
import org.codezaiku.chat.ChatConsent.Mode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The chat's question before CodeZaiku pushes or opens a pull request outside the sandbox: asked in yolo too, nothing in plan mode,
 * "always" kept for the same repository and branch for the session but never for a force push, "never" not asked again.
 */
class GitConsentTest {

    static ChatConsent consent(Mode mode, List<String> asked, Answer... answers) {
        int[] i = {0};
        return new ChatConsent(mode, (what, preview) -> { asked.add(what); return answers[Math.min(i[0]++, answers.length - 1)]; });
    }

    static final List<String> Q = List.of("CodeZaiku would push the branch fix of this project to origin, at https://github.com/me/proj.git.");

    @Test
    void askedInEveryModeThatActsAndInPlanModeNothingIsPushed() {
        List<String> asked = new ArrayList<>();
        assertNull(consent(Mode.YOLO, asked, Answer.YES).gitAct("git push github.com/me/proj fix", "push fix to origin", Q));
        assertEquals(1, asked.size(), "yolo still asks: a push sends the work off this machine");
        assertNull(consent(Mode.AUTO_EDIT, asked, Answer.YES).gitAct("git push github.com/me/proj fix", "push fix to origin", Q));
        assertEquals(2, asked.size());
        List<String> plan = new ArrayList<>();
        assertTrue(consent(Mode.PLAN, plan, Answer.YES).gitAct("git push github.com/me/proj fix", "push fix to origin", Q).startsWith("plan mode"));
        assertTrue(plan.isEmpty());
    }

    @Test
    void alwaysHoldsForTheSameBranchButNeverForAForcePushAndNeverIsKept() {
        List<String> asked = new ArrayList<>();
        ChatConsent c = consent(Mode.ASK, asked, Answer.YES_ALWAYS, Answer.YES_ALWAYS, Answer.YES, Answer.NO_ALWAYS);
        assertNull(c.gitAct("git push github.com/me/proj fix", "push fix to origin", Q));
        assertNull(c.gitAct("git push github.com/me/proj fix", "push fix to origin", Q));
        assertEquals(1, asked.size(), "always: the same repository and branch are not asked again this session");
        assertNull(c.gitAct("git push github.com/me/proj fix force", "push fix to origin, a force push", Q));
        assertNull(c.gitAct("git push github.com/me/proj fix force", "push fix to origin, a force push", Q));
        assertEquals(3, asked.size(), "a force push is asked every time, always or not");
        assertTrue(c.gitAct("git push github.com/me/proj other", "push other to origin", Q).startsWith("the user declined"));
        assertTrue(c.gitAct("git push github.com/me/proj other", "push other to origin", Q).startsWith("the user declined"));
        assertEquals(4, asked.size(), "never: not asked again");
    }
}

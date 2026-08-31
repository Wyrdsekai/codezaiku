package org.codezaiku.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.codezaiku.chat.ChatConsent.Answer;
import org.codezaiku.chat.ChatConsent.Mode;
import org.codezaiku.tools.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Consent is the safety story now, so these pin it directly: what gets asked, what does not, what a
 * standing answer covers, and — for {@link Mode#PLAN} — that the guarantee is structural rather than
 * a promise to ask.
 */
class ChatConsentTest {

    private static final ObjectMapper J = new ObjectMapper();

    /** Records what it was asked and answers from a script. */
    private static final class Script implements ChatConsent.Prompter {
        final List<String> asked = new ArrayList<>();
        private final List<Answer> answers;
        private int i = 0;

        Script(Answer... a) { this.answers = List.of(a); }

        final List<List<String>> previews = new ArrayList<>();

        @Override public Answer ask(String what, List<String> preview) {
            asked.add(what);
            previews.add(preview);
            return answers.get(Math.min(i++, answers.size() - 1));
        }
    }

    private static com.fasterxml.jackson.databind.JsonNode shell(String cmd) {
        return J.createObjectNode().put("command", cmd);
    }

    private static com.fasterxml.jackson.databind.JsonNode path(String p) {
        return J.createObjectNode().put("path", p);
    }

    // ---- what is never asked about --------------------------------------------------------------

    @Test
    void readingIsNeverInterrupted() {
        var s = new Script(Answer.NO);
        var c = new ChatConsent(Mode.ASK, s);
        assertThat(c.permit("read_file", path("Client.java"))).isNull();
        assertThat(c.permit("shell", shell("ls -la"))).isNull();
        assertThat(c.permit("shell", shell("git status"))).isNull();
        // A chat that stops to ask before reading a file is unusable, and reading cannot lose work.
        assertThat(s.asked).isEmpty();
    }

    @Test
    void aMutatingShellCommandIsAskedAbout() {
        var s = new Script(Answer.YES);
        var c = new ChatConsent(Mode.ASK, s);
        assertThat(c.permit("shell", shell("rm -rf build"))).isNull();
        assertThat(s.asked).hasSize(1);
    }

    @Test
    void aChainIsOnlyInspectiveIfEveryPartOfItIs() {
        // `ls && rm -rf /` must be asked about. Judging a chain by its first word is how that gets
        // missed.
        var s = new Script(Answer.NO);
        var c = new ChatConsent(Mode.ASK, s);
        assertThat(c.permit("shell", shell("ls && rm -rf /"))).isNotNull();
        assertThat(c.permit("shell", shell("git status | grep modified"))).isNull();
    }

    @Test
    void aShellRedirectIsAWriteWhateverItStartsWith() {
        // `cat > main.py` is the same write as the write tool and gets the same question.
        var s = new Script(Answer.NO);
        var c = new ChatConsent(Mode.ASK, s);
        assertThat(c.permit("shell", shell("cat > main.py"))).isNotNull();
        assertThat(c.permit("shell", shell("ls 2>&1"))).isNull();   // an fd-dup is not a write
    }

    @Test
    void anythingNotOnTheInspectListIsAskedAbout() {
        // Fails closed. `npm test` is harmless and still asked about, because "runs an arbitrary
        // command" is the thing a person wants a say in.
        var s = new Script(Answer.YES);
        var c = new ChatConsent(Mode.ASK, s);
        assertThat(c.permit("shell", shell("npm test"))).isNull();
        assertThat(s.asked).containsExactly("run `npm test`");
    }

    @Test
    void anEmptyOrAbsentCommandCountsAsMutating() {
        // Unknown shape must fail closed, not open.
        var s = new Script(Answer.NO);
        var c = new ChatConsent(Mode.ASK, s);
        assertThat(c.permit("shell", shell(""))).isNotNull();
        assertThat(c.permit("shell", null)).isNotNull();
    }

    // ---- standing answers -----------------------------------------------------------------------

    @Test
    void alwaysIsRememberedAndNotAskedTwice() {
        var s = new Script(Answer.YES_ALWAYS);
        var c = new ChatConsent(Mode.ASK, s);
        assertThat(c.permit("shell", shell("npm test"))).isNull();
        assertThat(c.permit("shell", shell("npm test --watch"))).isNull();
        assertThat(s.asked).hasSize(1);           // asked once, allowed twice
    }

    @Test
    void aStandingYesForOneCommandDoesNotCoverAnother() {
        // The reason standing answers are keyed by WHAT WAS ASKED and not by tool: "always allow
        // git commit" must never silently also permit git push. Taken from ACP, which got it right.
        var s = new Script(Answer.YES_ALWAYS, Answer.NO);
        var c = new ChatConsent(Mode.ASK, s);
        assertThat(c.permit("shell", shell("git commit -m x"))).isNull();
        assertThat(c.permit("shell", shell("git push origin main"))).isNotNull();
        assertThat(s.asked).containsExactly("run `git commit`", "run `git push`");
    }

    @Test
    void aStandingYesForAChainCoversTheWholeChainNotItsFirstPart() {
        // `mvn -q test && git commit` keyed on its first two words would let one "always" silently
        // permit `mvn -q test && rm -rf /`. Found by reading a rendered prompt, not by a test.
        var s = new Script(Answer.YES_ALWAYS, Answer.NO);
        var c = new ChatConsent(Mode.ASK, s);
        assertThat(c.permit("shell", shell("mvn -q test && git commit -am x"))).isNull();
        assertThat(c.permit("shell", shell("mvn -q test && rm -rf /"))).isNotNull();
        assertThat(s.asked.get(0)).isEqualTo("run `mvn -q` && `git commit`");
        assertThat(s.asked.get(1)).isEqualTo("run `mvn -q` && `rm -rf`");
    }

    @Test
    void neverIsRememberedToo() {
        var s = new Script(Answer.NO_ALWAYS);
        var c = new ChatConsent(Mode.ASK, s);
        assertThat(c.permit("shell", shell("rm -rf /"))).isNotNull();
        assertThat(c.permit("shell", shell("rm -rf /tmp/x"))).isNotNull();
        assertThat(s.asked).hasSize(1);
    }

    @Test
    void forgetClearsStandingAnswers() {
        var s = new Script(Answer.YES_ALWAYS);
        var c = new ChatConsent(Mode.ASK, s);
        c.permit("shell", shell("npm test"));
        c.forget();
        c.permit("shell", shell("npm test"));
        assertThat(s.asked).hasSize(2);
    }

    @Test
    void approvingOneFileDoesNotApproveTheTree() {
        var s = new Script(Answer.YES_ALWAYS, Answer.NO);
        var c = new ChatConsent(Mode.ASK, s);
        assertThat(c.permit("edit_file", path("Client.java"))).isNull();
        assertThat(c.permit("edit_file", path("pom.xml"))).isNotNull();
    }

    // ---- modes -----------------------------------------------------------------------------------

    @Test
    void autoEditLetsEditsThroughButStillAsksBeforeRunning() {
        var s = new Script(Answer.YES);
        var c = new ChatConsent(Mode.AUTO_EDIT, s);
        assertThat(c.permit("edit_file", path("Client.java"))).isNull();
        assertThat(s.asked).isEmpty();
        c.permit("shell", shell("rm -rf build"));
        assertThat(s.asked).hasSize(1);
    }

    @Test
    void allSwitchesTheSessionToYoloAndAllowsTheAction() {
        // "Stop asking me" lives on the prompt because a slash command typed there abandons the
        // turn — /mode yolo was unreachable exactly when someone wanted it. Session-scoped only.
        var s = new Script(Answer.YES_ALL, Answer.NO);
        var c = new ChatConsent(Mode.ASK, s);
        assertThat(c.permit("shell", shell("rm -rf build"))).isNull();
        assertThat(c.mode()).isEqualTo(Mode.YOLO);
        assertThat(c.permit("write_file", path("anything.py"))).isNull();
        assertThat(s.asked).hasSize(1);                    // asked once, never again
    }

    @Test
    void yoloNeverAsks() {
        var s = new Script(Answer.NO);
        var c = new ChatConsent(Mode.YOLO, s);
        assertThat(c.permit("shell", shell("rm -rf /"))).isNull();
        assertThat(c.permit("write_file", path("x"))).isNull();
        assertThat(s.asked).isEmpty();
    }

    @Test
    void planRefusesWithoutAskingAndExplainsWhy() {
        var s = new Script(Answer.YES);
        var c = new ChatConsent(Mode.PLAN, s);
        String denial = c.permit("edit_file", path("Client.java"));
        assertThat(denial).isNotNull().contains("plan mode");
        assertThat(s.asked).isEmpty();          // nothing to ask: PLAN never acts
    }

    @Test
    void planModeIsStructural_theWriteToolsDoNotEvenExist(@TempDir Path root) {
        // The consent check above is a second line. The first is that PLAN runs on the read-only
        // registry, so `write_file` is never in the request the model sees. A mode that only
        // promised to refuse would look identical to one that works, right up until the promise
        // was not kept — and the wyrdsekai access audit found exactly that shape of hole: a consent
        // model fully built, with nothing wired to it.
        var names = java.util.stream.StreamSupport
                .stream(ToolRegistry.readOnly(root, null).toolsArray(J).spliterator(), false)
                .map(n -> n.path("function").path("name").asText())
                .toList();
        assertThat(names).contains("read_file").doesNotContain("write_file", "edit_file");
    }

    @Test
    void anUnknownModeFallsBackToAskRatherThanSomethingPermissive() {
        assertThat(Mode.fromConfig("yoloo")).isEqualTo(Mode.ASK);
        assertThat(Mode.fromConfig("")).isEqualTo(Mode.ASK);
        assertThat(Mode.fromConfig(null)).isEqualTo(Mode.ASK);
        assertThat(Mode.fromConfig("auto-edit")).isEqualTo(Mode.AUTO_EDIT);
        assertThat(Mode.fromConfig("YOLO")).isEqualTo(Mode.YOLO);
    }

    @Test
    void endOfInputAtAPromptIsANoNotAYes() {
        // The live-conversation bug: a scripted session ran out of input at an approval prompt and
        // the resulting blank was read as consent, so an unapproved shell command ran. Consent must
        // fail closed when there is nobody left to ask.
        var c = new ChatConsent(Mode.ASK, (what, preview) -> Answer.NO);
        assertThat(c.permit("shell", shell("rm -rf build"))).isNotNull();
    }

    // ---- persistent scopes ----------------------------------------------------------------------

    @Test
    void trustedAnswersSurviveIntoANewConsent(@TempDir Path dir) {
        var pf = dir.resolve("store").resolve("consent");
        var gf = dir.resolve("global").resolve("consent");
        var first = new ChatConsent(Mode.ASK, new Script(Answer.YES_ALWAYS), pf, gf);
        first.permit("shell", shell("npm test"));
        assertThat(first.trust(ChatConsent.Scope.PROJECT)).isEqualTo(1);

        // A brand-new consent — a new session — loads it back and never asks.
        var probe = new Script(Answer.NO);
        var second = new ChatConsent(Mode.ASK, probe, pf, gf);
        assertThat(second.permit("shell", shell("npm test"))).isNull();
        assertThat(probe.asked).isEmpty();
    }

    @Test
    void aPersistedDenyHoldsToo(@TempDir Path dir) {
        var pf = dir.resolve("consent");
        var first = new ChatConsent(Mode.ASK, new Script(Answer.NO_ALWAYS), pf, null);
        first.permit("shell", shell("rm -rf build"));
        first.trust(ChatConsent.Scope.PROJECT);

        var second = new ChatConsent(Mode.ASK, new Script(Answer.YES), pf, null);
        assertThat(second.permit("shell", shell("rm -rf build"))).isNotNull();
    }

    @Test
    void theSessionAnswerBeatsThePersistedOne(@TempDir Path dir) throws Exception {
        // Most specific wins. A person saying "no" NOW outranks a yes they persisted last month.
        var pf = dir.resolve("consent");
        java.nio.file.Files.writeString(pf, "allow run `npm test`\n");
        var c = new ChatConsent(Mode.ASK, new Script(Answer.NO), pf, null);
        assertThat(c.permit("shell", shell("npm test"))).isNull();      // persisted allow holds...
        // ...until the session says otherwise about something it was actually asked. (The persisted
        // allow means it is never asked, so drive the session answer in directly via a fresh key.)
        var probe = new Script(Answer.NO_ALWAYS);
        var c2 = new ChatConsent(Mode.ASK, probe, pf, null);
        c2.permit("shell", shell("npm audit"));                          // session says never
        assertThat(c2.permit("shell", shell("npm audit"))).isNotNull();
        assertThat(probe.asked).hasSize(1);
    }

    @Test
    void aMalformedConsentLineIsIgnoredNotGuessedAt(@TempDir Path dir) throws Exception {
        // A junk line must never become an allowance — unreadable consent means "ask".
        var pf = dir.resolve("consent");
        java.nio.file.Files.writeString(pf, "alow run `rm -rf`\nrun `git push`\nallow\n");
        var probe = new Script(Answer.NO);
        var c = new ChatConsent(Mode.ASK, probe, pf, null);
        assertThat(c.permit("shell", shell("rm -rf x"))).isNotNull();
        assertThat(probe.asked).hasSize(1);                              // still asked
    }

    @Test
    void aDenialReadsAsAnObservationTheModelCanActOn() {
        // Not an exception and not a scolding: the model should try something else, per the
        // Listener contract — "denial comes back to the model as an ordinary observation".
        var c = new ChatConsent(Mode.ASK, new Script(Answer.NO));
        assertThat(c.permit("shell", shell("rm -rf build")))
                .contains("declined")
                .contains("Do something else");
    }
}

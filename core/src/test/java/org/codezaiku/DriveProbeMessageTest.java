package org.codezaiku;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * `doctor` used to probe only `/v1/models` and report anything else as "nothing answered". Measured
 * against api.anthropic.com: it answers 401 there while its OpenAI-compatible
 * `/v1/chat/completions` works perfectly with the same key — so a working hosted endpoint was
 * reported as a dead server, sending the operator to fix something that was fine.
 *
 * The probe now asks the endpoint CodeZaiku actually uses, and these pin the wording, because the
 * entire value of the check is that it sends someone to the right problem.
 */
class DriveProbeMessageTest {

    private record Result(boolean ok, String detail) { }

    private static Result classify(int status, String body) throws Exception {
        Method m = Doctor.class.getDeclaredMethod("classify", int.class, String.class);
        m.setAccessible(true);
        Object r = m.invoke(null, status, body);
        Method ok = r.getClass().getDeclaredMethod("ok");
        Method detail = r.getClass().getDeclaredMethod("detail");
        ok.setAccessible(true);
        detail.setAccessible(true);
        return new Result((boolean) ok.invoke(r), (String) detail.invoke(r));
    }

    @Test
    void aWorkingEndpointPasses() throws Exception {
        assertTrue(classify(200, "{\"choices\":[]}").ok());
    }

    @Test
    void aRejectedCredentialSaysSoRatherThanNothingAnswered() throws Exception {
        for (int status : new int[]{401, 403}) {
            Result r = classify(status, "{\"error\":\"invalid x-api-key\"}");
            assertFalse(r.ok());
            assertTrue(r.detail().contains("CODEZAIKU_API_KEY"),
                    "a rejected credential must name the setting that fixes it, got: " + r.detail());
            assertFalse(r.detail().contains("nothing answered"),
                    "the endpoint DID answer — saying otherwise sends the operator to the wrong problem");
        }
    }

    @Test
    void aUrlThatAlreadyCarriesV1IsNamedAsSuch() throws Exception {
        Result r = classify(404, "not found");
        assertFalse(r.ok());
        assertTrue(r.detail().contains("without /v1"),
                "a 404 here is almost always a doubled /v1; say so, got: " + r.detail());
    }

    @Test
    void anyOtherStatusQuotesTheServerRatherThanGuessing() throws Exception {
        Result r = classify(429, "{\"error\":{\"message\":\"rate limit exceeded\"}}");
        assertFalse(r.ok());
        assertTrue(r.detail().contains("429"), "the status belongs in the message");
        assertTrue(r.detail().contains("rate limit exceeded"),
                "the provider named the real problem; quote it rather than guessing");
    }

    @Test
    void theMissingDriveFixNamesTheOnDemandInstallWhenThisMachineCanServeOne() {
        String offer = "Serve gpt-oss-20b on this machine on demand: it downloads once (about 14 GB), starts when a run needs it, and stops after 20 idle minutes.";
        assertTrue(Doctor.driveFix(offer).startsWith(offer) && Doctor.driveFix(offer).contains("codezaiku model serve install"), Doctor.driveFix(offer));
        String plain = Doctor.driveFix(null);
        assertTrue(plain.contains("docker run") && plain.contains("model serve install"), plain);
    }
}

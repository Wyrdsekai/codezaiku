package org.codezaiku.drive.aws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import org.codezaiku.Config;
/**
 * The AWS credentials of the person at this machine, from where they already keep them. Nothing of theirs is stored by us.
 * <ol>
 *   <li>The AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY and AWS_SESSION_TOKEN environment variables, when no profile is named.</li>
 *   <li>The AWS command line: {@code aws configure export-credentials --format process}. Whatever works in their {@code aws}
 *       command works here: single sign-on, an assumed role, a chain of roles, keys in a file, a machine's own role.</li>
 * </ol>
 * Temporary credentials are asked for again shortly before they run out. When AWS does not say when that is, they are kept for an
 * hour at most, which is AWS's own rule for such credentials, and {@link #forget} drops them at once when AWS refuses them.
 */
public final class AwsCredentials {

    private AwsCredentials() { }

    /** {@code expires} is null when AWS did not say. */
    public record Keys(String accessKeyId, String secretKey, String sessionToken, Instant expires, Instant fetched) {
        boolean fresh(Instant now) {
            if (expires != null) return now.isBefore(expires.minus(Duration.ofMinutes(5)));
            return sessionToken == null || sessionToken.isBlank() || now.isBefore(fetched.plus(Duration.ofHours(1)));
        }
    }

    /** What could not be done, said so that the person can put it right. */
    public static final class Unavailable extends RuntimeException { public Unavailable(String message) { super(message); } }

    private static final Map<String, Keys> HELD = new ConcurrentHashMap<>();
    private static final ObjectMapper J = new ObjectMapper();

    /** {@code profile}: the name of a profile in their AWS configuration, or "" for their default. */
    public static Keys get(String profile) { return get(profile, System.getenv(), AwsCredentials::askTheCommandLine, Instant.now()); }

    interface CommandLine { String export(String profile) throws Exception; }

    static Keys get(String profile, Map<String, String> env, CommandLine cli, Instant now) {
        String p = profile == null ? "" : profile.strip();
        Keys held = HELD.get(p);
        if (held != null && held.fresh(now)) return held;
        Keys keys = null;
        if (p.isEmpty() && !env.getOrDefault("AWS_ACCESS_KEY_ID", "").isBlank() && !env.getOrDefault("AWS_SECRET_ACCESS_KEY", "").isBlank())
            keys = new Keys(env.get("AWS_ACCESS_KEY_ID").strip(), env.get("AWS_SECRET_ACCESS_KEY").strip(), env.getOrDefault("AWS_SESSION_TOKEN", "").strip(), null, now);
        if (keys == null) {
            String out;
            try { out = cli.export(p); }
            catch (Unavailable e) { throw e; }
            catch (Exception e) { throw new Unavailable("The AWS command line could not be run (" + e.getMessage() + "). Install it (https://aws.amazon.com/cli/), sign in with `aws configure sso` or `aws configure`, and try again."); }
            keys = parse(out, now);
        }
        HELD.put(p, keys);
        return keys;
    }

    /** AWS refused these credentials: the next request asks for new ones. */
    public static void forget(String profile) { HELD.remove(profile == null ? "" : profile.strip()); }

    /** The JSON that {@code export-credentials --format process} writes. Expiration is there only when the command line knows it. */
    static Keys parse(String json, Instant now) {
        try {
            JsonNode n = J.readTree(json);
            String id = n.path("AccessKeyId").asText(""), secret = n.path("SecretAccessKey").asText("");
            if (id.isBlank() || secret.isBlank()) throw new Unavailable("The AWS command line gave no credentials. Sign in first: `aws sso login`, or `aws configure` for keys.");
            Instant expires = null;
            String e = n.path("Expiration").asText("");
            if (!e.isBlank()) { try { expires = Instant.parse(e); } catch (DateTimeParseException ignored) { expires = OffsetDateTime.parse(e).toInstant(); } }
            return new Keys(id.strip(), secret.strip(), n.path("SessionToken").asText("").strip(), expires, now);
        } catch (Unavailable u) { throw u; }
        catch (Exception e) { throw new Unavailable("The AWS command line answered something that is not credentials. Run `aws configure export-credentials --format process` yourself to see what it says."); }
    }

    private static String askTheCommandLine(String profile) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(Config.get("CODEZAIKU_AWS_CLI", "aws"), "configure", "export-credentials", "--format", "process"));
        if (!profile.isEmpty()) { cmd.add("--profile"); cmd.add(profile); }
        Process proc = new ProcessBuilder(cmd).redirectErrorStream(false).start();
        proc.getOutputStream().close();
        String out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String err = new String(proc.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        if (!proc.waitFor(60, TimeUnit.SECONDS)) { proc.destroyForcibly(); throw new Unavailable("The AWS command line did not answer within a minute."); }
        if (proc.exitValue() != 0) throw new Unavailable("The AWS command line could not give credentials" + (profile.isEmpty() ? "" : " for the profile \"" + profile + "\"") + ": " + (err.isEmpty() ? "it ended with code " + proc.exitValue() : err.lines().reduce((a, b) -> b).orElse(err).replaceAll("[.\\s]+$", ""))
                + ". If you sign in through your organisation, run `aws sso login" + (profile.isEmpty() ? "" : " --profile " + profile) + "` and try again.");
        return out;
    }
}

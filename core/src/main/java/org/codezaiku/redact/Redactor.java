package org.codezaiku.redact;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masks credentials and personal data in text before it leaves the machine or lands on disk.
 *
 * <p>Two exits needed this and had neither. Every tool result — {@code docker logs}, a read file, a
 * fetched page — is fed straight into the model turn, and every ops action is appended to the audit
 * trail. Service logs routinely carry connection strings with inline passwords, and a {@code .env} or
 * a config file read for context carries whatever is in it. So the harness was shipping secrets to
 * the drive and writing them to {@code audit.jsonl}, in a project whose own knowledge pack teaches
 * people to look for exactly these patterns.
 *
 * <p>This is a DIFFERENT job from {@code StackLocalizer.neutralizeLogText}, and both are applied.
 * That one defuses instruction-shaped text so attacker-written logs cannot steer the agent; this one
 * masks values so secrets do not travel. Injection defence and disclosure defence.
 *
 * <p><b>Masking is shape-aware on purpose.</b> A rule broad enough to catch every secret also mangles
 * ordinary code, and a review or a fix built on mangled source is worse than one built on a leaked
 * password. So a value is left alone when it is plainly not a secret: a placeholder, an environment
 * lookup, a variable reference, or too short to be key material. The counts are returned so the
 * caller can tell the model that masking happened — an unexplained {@code ‹redacted›} in a log is
 * confusing, and a model that thinks the password is literally that string will act on it.
 */
public final class Redactor {

    /** Redacted text plus how many values were masked, by rule. */
    public record Result(String text, int count, List<String> rules) {
        public boolean redacted() { return count > 0; }

        /** A one-line note for the model, or "" when nothing was masked. */
        public String note() {
            if (count == 0) return "";
            return "\n[" + count + " secret/PII value(s) masked as ‹redacted› by the harness before you saw "
                    + "this — the real values are NOT available to you. Rules: " + String.join(", ", rules) + "]";
        }
    }

    private static final String MASK = "‹redacted›";

    private record Rule(String id, Pattern pattern, int valueGroup) { }

    /**
     * Values that are obviously not secrets. Checked against the captured VALUE, so a rule can stay
     * simple: {@code password=os.environ["PG_PASS"]} matches the assignment shape but is skipped here.
     */
    private static final Pattern NOT_A_SECRET = Pattern.compile(
            "(?i)^(?:"
            + "\\$\\{?[a-z_][\\w.]*\\}?"                    // $VAR / ${VAR}
            + "|(?:os\\.environ|process\\.env|env)\\b.*"    // env lookups
            + "|<[^>]*>|\\{\\{[^}]*\\}\\}"                  // <PLACEHOLDER> / {{template}}
            + "|(?:your|my|the)[-_ ]?\\w*"                  // your-token, my_password
            + "|change ?me|changeit|placeholder|redacted|example|sample|dummy|test|none|null|nil|empty"
            + "|password|passwd|secret|token|apikey|api[-_]key|xxx+|\\*+|\\.\\.\\.+"
            + ")$");

    /** Below this a "secret" is a flag value or a short word, not key material. */
    private static final int MIN_SECRET_LEN = 6;

    private static final List<Rule> RULES = List.of(
            // Whole key blocks: mask the body, keep the header so the reader knows what was there.
            new Rule("private-key",
                    Pattern.compile("(-----BEGIN (?:RSA |EC |DSA |OPENSSH |PGP )?PRIVATE KEY-----)"
                            + "([\\s\\S]*?)(-----END)"), 2),
            // Vendor-prefixed tokens are unambiguous — no placeholder check needed.
            new Rule("aws-key-id", Pattern.compile("\\b(AKIA[0-9A-Z]{16})\\b"), 1),
            new Rule("github-token",
                    Pattern.compile("\\b((?:ghp|gho|ghs|ghu|ghr)_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})\\b"), 1),
            new Rule("slack-token", Pattern.compile("\\b(xox[baprs]-[A-Za-z0-9-]{10,})\\b"), 1),
            new Rule("openai-key", Pattern.compile("\\b(sk-(?:ant-)?[A-Za-z0-9_-]{20,})\\b"), 1),
            new Rule("hf-token", Pattern.compile("\\b(hf_[A-Za-z0-9]{20,})\\b"), 1),
            new Rule("google-key", Pattern.compile("\\b(AIza[0-9A-Za-z_-]{35})\\b"), 1),
            new Rule("jwt", Pattern.compile("\\b(eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,})\\b"), 1),
            // Authorization headers, however the value is shaped.
            new Rule("auth-header",
                    Pattern.compile("(?i)(authorization\\s*[:=]\\s*(?:bearer|basic|token)?\\s*)([^\\s\"',;]{8,})"), 2),
            // scheme://user:PASSWORD@host — the classic way a password reaches a log.
            new Rule("url-credential",
                    Pattern.compile("([a-zA-Z][a-zA-Z0-9+.-]*://[^\\s:/@]+:)([^\\s@/]+)(@)"), 2),
            // password=... / api_key: "..." — the broadest rule, so the placeholder guard matters most here.
            // The key may be QUOTED (`{"password": "x"}`), which an audit found leaking: the rule
            // required the key name to sit directly against its separator, and a closing quote broke it.
            new Rule("assigned-credential",
                    Pattern.compile("(?i)((?:pass(?:wo?rd)?|pwd|secret|token|api[_-]?key|access[_-]?key|"
                            + "auth[_-]?token|client[_-]?secret|private[_-]?key)[\"']?\\s*[:=]\\s*[\"']?)"
                            + "([^\\s\"',;}]{4,})"), 2),
            // CREDENTIALS PASSED AS COMMAND-LINE FLAGS, which take a SPACE and so never matched the
            // key=value rule above. This is the shape that matters most here: our own ops runs handle
            // redis passwords constantly (`--requirepass`, and the card tells the model to use
            // `redis-cli -a <pass>`), and the audit file is what gets attached to a ticket.
            new Rule("password-flag",
                    Pattern.compile("(?i)((?:--requirepass|--password|--passwd|--pass|--auth|--token)"
                            + "(?:\\s+|=))([^\\s\"';|]{2,})"), 2),
            new Rule("engine-cli-auth-flag",
                    Pattern.compile("(?i)((?:redis-cli|valkey-cli|keydb-cli)[^|;]*?\\s-a\\s+)"
                            + "([^\\s\"';|]{2,})"), 2),
            new Rule("mysql-password-flag",           // -p sticks to its value: `mysql -psecret`
                    Pattern.compile("(?i)(\\bmysql(?:dump|admin)?\\b[^|;]*?\\s-p)([^\\s\"';|]{2,})"), 2),
            new Rule("basic-auth-flag",               // curl -u user:secret
                    Pattern.compile("(?i)((?:^|\\s)-u\\s+[^\\s:\"']+:)([^\\s\"';|]{2,})"), 2),
            new Rule("runtime-config-secret",         // CONFIG SET requirepass <value>
                    Pattern.compile("(?i)(\\bconfig\\s+set\\s+(?:requirepass|masterauth)\\s+)"
                            + "([^\\s\"';|]{2,})"), 2),
            // PII: keep the domain, mask who. An address is often the only personal datum in a log,
            // and the domain is usually the part that carries diagnostic meaning.
            new Rule("email",
                    Pattern.compile("\\b([A-Za-z0-9._%+-]{1,64})(@[A-Za-z0-9.-]+\\.[A-Za-z]{2,})\\b"), 1)
    );

    /** Mask every secret-shaped value in {@code text}. Never throws; null in, null out. */
    public static Result redact(String text) {
        if (text == null || text.isEmpty()) return new Result(text, 0, List.of());
        String out = text;
        int total = 0;
        List<String> fired = new ArrayList<>();

        for (Rule rule : RULES) {
            Matcher m = rule.pattern().matcher(out);
            StringBuilder sb = new StringBuilder();
            int hits = 0;
            while (m.find()) {
                String value = m.group(rule.valueGroup());
                if (!shouldMask(rule.id(), value)) {
                    m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
                    continue;
                }
                StringBuilder rep = new StringBuilder();
                for (int g = 1; g <= m.groupCount(); g++) {
                    String piece = m.group(g);
                    if (piece == null) continue;
                    rep.append(g == rule.valueGroup() ? MASK : piece);
                }
                m.appendReplacement(sb, Matcher.quoteReplacement(rep.toString()));
                hits++;
            }
            m.appendTail(sb);
            if (hits > 0) { out = sb.toString(); total += hits; fired.add(rule.id()); }
        }
        return new Result(out, total, List.copyOf(fired));
    }

    /** Convenience for callers that only want the text. */
    public static String scrub(String text) { return redact(text).text(); }

    private static boolean shouldMask(String ruleId, String value) {
        if (value == null || value.isBlank()) return false;
        String v = value.strip();
        // A key block body is whitespace-heavy base64; the length floor and placeholder list do not apply.
        if (ruleId.equals("private-key")) return v.length() > 20;
        // The local part of an address is short by nature, and the vendor-prefixed rules already
        // proved their shape — only the guessy rules get the placeholder and length checks.
        if (ruleId.equals("email")) return true;
        if (v.length() < MIN_SECRET_LEN) return false;
        return !NOT_A_SECRET.matcher(v).matches();
    }

    private Redactor() { }
}

package org.codezaiku.gate;

import java.util.function.Predicate;
import java.util.regex.Pattern;

/** A labeled assertion on a response body — the label appears in the gate evidence. */
public record BodyCheck(String label, Predicate<String> test) {
    public static final BodyCheck ANY = new BodyCheck("any", b -> true);

    public static BodyCheck jsonArray() {
        return new BodyCheck("json-array", b -> b.strip().startsWith("["));
    }

    public static BodyCheck contains(String s) {
        return new BodyCheck("contains \"" + s + "\"", b -> b.contains(s));
    }

    public static BodyCheck hasField(String field) {
        Pattern p = Pattern.compile("(?s).*\"" + Pattern.quote(field) + "\"\\s*:.*");
        return new BodyCheck("has field \"" + field + "\"", b -> p.matcher(b).matches());
    }
}

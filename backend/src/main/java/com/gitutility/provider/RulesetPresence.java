package com.gitutility.provider;

/**
 * Live GitHub ruleset lookup. {@code state} is {@code missing}, {@code active}, {@code disabled}, or {@code unknown}.
 */
public record RulesetPresence(String state, Long id, String name, String detail) {

    public static final String MISSING = "missing";
    public static final String ACTIVE = "active";
    public static final String DISABLED = "disabled";
    public static final String UNKNOWN = "unknown";

    public static RulesetPresence missing(String name) {
        return missing(name, null);
    }

    public static RulesetPresence missing(String name, String detail) {
        return new RulesetPresence(MISSING, null, name, detail);
    }

    public static RulesetPresence found(String name, long id, String enforcement) {
        if ("active".equalsIgnoreCase(enforcement)) {
            return new RulesetPresence(ACTIVE, id > 0 ? id : null, name, null);
        }
        String detail = enforcement == null || "disabled".equalsIgnoreCase(enforcement)
                ? null
                : "GitHub enforcement is " + enforcement + ".";
        return new RulesetPresence(DISABLED, id > 0 ? id : null, name, detail);
    }

    public static RulesetPresence unknown(String name, String detail) {
        return new RulesetPresence(UNKNOWN, null, name, detail);
    }
}

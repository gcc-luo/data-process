package com.example.governance.assets.driver;

import java.time.ZoneId;
import java.util.Map;

/** Whitelist enforcement for user-supplied driver parameters; backend is authoritative. */
public final class ProfileParams {
    private ProfileParams() {}
    public static void validate(DatabaseProfile profile, Map<String, String> options) {
        var specs = profile.extraParams();
        for (var entry : options.entrySet()) {
            String key = entry.getKey(); String value = entry.getValue();
            if ("user".equals(key) || "password".equals(key)) throw new IllegalArgumentException("Parameter not allowed: " + key);
            var spec = specs.get(key);
            if (spec == null) throw new IllegalArgumentException("Parameter not allowed: " + key);
            if (value == null || !valid(spec, value)) throw new IllegalArgumentException("Invalid value for parameter: " + key);
        }
    }
    static boolean valid(DatabaseProfile.ParamSpec spec, String value) {
        return switch (spec.kind()) {
            case BOOLEAN -> "true".equals(value) || "false".equals(value);
            case MILLIS -> integer(value, 0, 3_600_000);
            case SECONDS -> integer(value, 0, 3600);
            case PREFETCH -> integer(value, 1, 10_000);
            case ENUM -> spec.values() != null && spec.values().contains(value);
            case ZONE -> ZoneId.getAvailableZoneIds().contains(value) || value.matches("[+-]\\d{2}:\\d{2}");
            case IDENTIFIER -> value.matches("[A-Za-z0-9_$]{1,128}");
            case TEXT -> !value.isBlank() && value.length() <= 128 && value.chars().noneMatch(c -> c < 32);
        };
    }
    private static boolean integer(String value, int min, int max) {
        try { int n = Integer.parseInt(value); return n >= min && n <= max; } catch (NumberFormatException ex) { return false; }
    }
}

package com.example.batch.common.context;

import com.example.batch.common.enums.RunMode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Normalizes the runtime launch intent so launch / retry / recover / compensate
 * flows all write the same canonical key into payload and execution context.
 */
public final class RunModeSupport {

    public static final String RUN_MODE = "run_mode";
    /**
     * Legacy alias kept for compatibility with older JSON payloads.
     */
    public static final String LEGACY_RUN_MODE = "runMode";

    private RunModeSupport() {
    }

    public static Map<String, Object> copyWithDefault(Map<String, Object> source, RunMode defaultMode) {
        Map<String, Object> copy = new LinkedHashMap<>();
        if (source != null) {
            copy.putAll(source);
        }
        RunMode resolved = resolve(copy);
        putCanonical(copy, resolved == null ? defaultMode : resolved);
        return copy;
    }

    public static RunMode resolve(Map<String, Object> attributes) {
        return RunMode.fromCode(resolveCode(attributes)).orElse(null);
    }

    public static String resolveCode(Map<String, Object> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return null;
        }
        String normalized = normalize(attributes.get(RUN_MODE));
        if (normalized != null) {
            return normalized;
        }
        return normalize(attributes.get(LEGACY_RUN_MODE));
    }

    public static void putCanonical(Map<String, Object> target, RunMode runMode) {
        if (target == null || runMode == null) {
            return;
        }
        target.put(RUN_MODE, runMode.code());
        target.remove(LEGACY_RUN_MODE);
    }

    private static String normalize(Object value) {
        if (value == null) {
            return null;
        }
        return RunMode.fromCode(String.valueOf(value))
                .map(RunMode::code)
                .orElse(null);
    }
}

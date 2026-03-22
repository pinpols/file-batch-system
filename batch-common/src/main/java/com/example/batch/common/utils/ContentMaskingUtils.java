package com.example.batch.common.utils;

import java.util.regex.Pattern;

/**
 * Best-effort redaction for previews, logs, and bad-record payloads. Not a replacement for field-level
 * tokenization; keeps obvious digit sequences and email-like tokens from appearing verbatim.
 */
public final class ContentMaskingUtils {

    private static final Pattern DIGIT_RUNS = Pattern.compile("\\d{4,}");
    private static final Pattern EMAIL_LIKE = Pattern.compile(
            "[\\w.+-]+@[\\w.-]+\\.[A-Za-z]{2,}",
            Pattern.CASE_INSENSITIVE);

    private ContentMaskingUtils() {
    }

    public static String maskPlainText(String text, String ruleSetCode) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String masked = DIGIT_RUNS.matcher(text).replaceAll("****");
        masked = EMAIL_LIKE.matcher(masked).replaceAll("***@***");
        if (ruleSetCode != null && ruleSetCode.toUpperCase().contains("STRICT")) {
            masked = masked.replaceAll("(?i)\\b(name|address|phone)\\s*[:=]\\s*[^\\s,;]+", "$1=***");
        }
        return masked;
    }

    public static String maskPlainText(String text) {
        return maskPlainText(text, null);
    }
}

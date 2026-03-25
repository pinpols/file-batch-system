package com.example.batch.common.utils;

import java.util.regex.Pattern;

/**
 * Best-effort redaction for previews, logs, and bad-record payloads. Not a replacement for
 * field-level tokenization; keeps obvious digit sequences and email-like tokens from appearing
 * verbatim.
 *
 * <p>Supported rule set codes (case-insensitive, multiple can be combined with "_" or by containing
 * the keyword):
 * <ul>
 *   <li>{@code STRICT} — name/address/phone key=value pairs</li>
 *   <li>{@code PCI} — card expiry patterns (MM/YY, MM/YYYY); digit runs and emails already
 *       cover card numbers and CVV</li>
 *   <li>{@code GDPR} — IP addresses, UK/US postal codes</li>
 * </ul>
 *
 * <p>All named rule sets imply STRICT masking for named fields.
 */
public final class ContentMaskingUtils {

    private static final Pattern DIGIT_RUNS = Pattern.compile("\\d{4,}");
    private static final Pattern EMAIL_LIKE = Pattern.compile(
            "[\\w.+-]+@[\\w.-]+\\.[A-Za-z]{2,}",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NAMED_FIELDS = Pattern.compile(
            "(?i)\\b(name|address|phone)\\s*[:=]\\s*[^\\s,;]+");
    private static final Pattern CARD_EXPIRY = Pattern.compile(
            "\\b(0[1-9]|1[0-2])/(\\d{2}|\\d{4})\\b");
    private static final Pattern IPV4 = Pattern.compile(
            "\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b");
    private static final Pattern UK_POSTCODE = Pattern.compile(
            "\\b[A-Z]{1,2}\\d[\\dA-Z]?\\s?\\d[A-Z]{2}\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern US_ZIP = Pattern.compile(
            "\\b\\d{5}(?:-\\d{4})?\\b");

    private ContentMaskingUtils() {
    }

    /**
     * Apply masking rules to {@code text} based on the given {@code ruleSetCode}.
     * {@code null} ruleSetCode applies only the baseline digit-run and email redaction.
     */
    public static String maskPlainText(String text, String ruleSetCode) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        // Baseline: mask digit runs (card numbers, IDs, phone numbers) and emails
        String masked = DIGIT_RUNS.matcher(text).replaceAll("****");
        masked = EMAIL_LIKE.matcher(masked).replaceAll("***@***");

        if (ruleSetCode == null) {
            return masked;
        }
        String code = ruleSetCode.toUpperCase(java.util.Locale.ROOT);

        // STRICT / PCI / GDPR all mask named fields
        if (code.contains("STRICT") || code.contains("PCI") || code.contains("GDPR")) {
            masked = NAMED_FIELDS.matcher(masked).replaceAll("$1=***");
        }

        // PCI: additionally mask card expiry patterns (digit runs already handle card numbers)
        if (code.contains("PCI")) {
            masked = CARD_EXPIRY.matcher(masked).replaceAll("**/**");
        }

        // GDPR: additionally mask IP addresses and postal codes
        if (code.contains("GDPR")) {
            masked = IPV4.matcher(masked).replaceAll("*.*.*.*");
            masked = UK_POSTCODE.matcher(masked).replaceAll("*** ***");
            masked = US_ZIP.matcher(masked).replaceAll("*****");
        }

        return masked;
    }

    public static String maskPlainText(String text) {
        return maskPlainText(text, null);
    }
}

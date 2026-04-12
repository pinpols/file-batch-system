package com.example.batch.common.utils;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 面向预览、日志、坏件载荷的尽力脱敏，不能替代字段级令牌化；避免长数字串、类邮箱片段原样外露。
 *
 * <p>支持的规则集代码（大小写不敏感，可用 "_" 组合或关键字包含）：
 *
 * <ul>
 *   <li>{@code STRICT}：姓名/地址/电话等 key=value
 *   <li>{@code PCI}：卡有效期（MM/YY、MM/YYYY）；长数字与邮箱规则已覆盖卡号、CVV 等
 *   <li>{@code GDPR}：IPv4、英/美邮编形态
 * </ul>
 *
 * <p>命名规则集均隐含对命名字段采用 STRICT 脱敏。
 */
public final class ContentMaskingUtils {

  private static final Pattern DIGIT_RUNS = Pattern.compile("\\d{4,}");
  private static final Pattern EMAIL_LIKE =
      Pattern.compile("[\\w.+-]+@[\\w.-]+\\.[A-Za-z]{2,}", Pattern.CASE_INSENSITIVE);
  private static final Pattern NAMED_FIELDS =
      Pattern.compile("(?i)\\b(name|address|phone)\\s*[:=]\\s*[^\\s,;]+");
  private static final Pattern CARD_EXPIRY =
      Pattern.compile("\\b(0[1-9]|1[0-2])/(\\d{2}|\\d{4})\\b");
  private static final Pattern IPV4 = Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b");
  private static final Pattern UK_POSTCODE =
      Pattern.compile("\\b[A-Z]{1,2}\\d[\\dA-Z]?\\s?\\d[A-Z]{2}\\b", Pattern.CASE_INSENSITIVE);
  private static final Pattern US_ZIP = Pattern.compile("\\b\\d{5}(?:-\\d{4})?\\b");

  private ContentMaskingUtils() {}

  /** 按指定 {@code ruleSetCode} 对 {@code text} 执行脱敏，{@code null} 时仅应用基线数字串和邮箱脱敏。 */
  public static String maskPlainText(String text, String ruleSetCode) {
    if (text == null || text.isEmpty()) {
      return text;
    }
    // 基线：脱敏连续数字串（卡号、证件号、电话）及邮箱
    String masked = DIGIT_RUNS.matcher(text).replaceAll("****");
    masked = EMAIL_LIKE.matcher(masked).replaceAll("***@***");

    if (ruleSetCode == null) {
      return masked;
    }
    String code = ruleSetCode.toUpperCase(Locale.ROOT);

    // STRICT / PCI / GDPR 均对命名字段脱敏
    if (code.contains("STRICT") || code.contains("PCI") || code.contains("GDPR")) {
      masked = NAMED_FIELDS.matcher(masked).replaceAll("$1=***");
    }

    // PCI：额外脱敏卡有效期（卡号已由数字串规则覆盖）
    if (code.contains("PCI")) {
      masked = CARD_EXPIRY.matcher(masked).replaceAll("**/**");
    }

    // GDPR：额外脱敏 IP 地址和邮政编码
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

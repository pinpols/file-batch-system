package com.example.batch.common.utils;

/**
 * 项目内部字符串工具。替代 {@code org.springframework.util.StringUtils}——全仓仅用到 {@code hasText}
 * 一个方法，没必要为了它保留外部依赖入口；同时和 {@link ConsoleTextSanitizer} / {@link EncodingUtils} 等 batch-common
 * 自研工具统一风格，避免"两套 StringUtils"混淆。
 *
 * <p>语义和 Spring 版本完全等价：{@code null} / 空 / 全空白 → {@code false}，其他情况 → {@code true}。
 */
public final class Texts {

  private Texts() {}

  /** 字符序列是否非空且含至少一个非空白字符。 */
  public static boolean hasText(CharSequence str) {
    if (str == null) {
      return false;
    }
    int len = str.length();
    if (len == 0) {
      return false;
    }
    for (int i = 0; i < len; i++) {
      if (!Character.isWhitespace(str.charAt(i))) {
        return true;
      }
    }
    return false;
  }

  /** String 重载，避免调用方因 {@code null} 字面量歧义触发 {@code CharSequence} 路径的 NPE。 */
  public static boolean hasText(String str) {
    return hasText((CharSequence) str);
  }
}

package com.example.batch.common.utils;

import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import org.springframework.util.StringUtils;

/**
 * 字符集归一工具。配合 CLAUDE.md §字符编码 约束：全系统内部一律 UTF-8，导入边界允许外部以
 * 别名声明（{@code utf8} / {@code UTF8} / {@code utf-8} / {@code UTF-8}），本工具统一把所有
 * 别名归一为 {@link StandardCharsets#UTF_8} 的规范名，并对未识别字符集快速失败。
 *
 * <p>禁止业务代码直接写 {@code Charset.forName("UTF-8")} 或字符串字面量 {@code "UTF-8"}：
 *
 * <ul>
 *   <li>需要 {@link Charset} 对象 → 用 {@link StandardCharsets#UTF_8}
 *   <li>需要字符集名（写入 file_record.charset 等字段） → 用 {@link #UTF_8}
 *   <li>需要归一用户输入 → 用 {@link #normalize(String)}
 *   <li>导出路径断言 UTF-8 → 用 {@link #requireUtf8(String)}
 * </ul>
 */
public final class EncodingUtils {

  /** 系统内部字符集规范名；等价于 {@code StandardCharsets.UTF_8.name()}。 */
  public static final String UTF_8 = StandardCharsets.UTF_8.name();

  private EncodingUtils() {}

  /**
   * 把用户/配置输入的字符集名归一为 JDK 规范名。
   *
   * <p>空/空白输入返回 {@link #UTF_8}；非法字符集名抛 {@link IllegalArgumentException}。
   */
  public static String normalize(String raw) {
    if (!StringUtils.hasText(raw)) {
      return UTF_8;
    }
    try {
      return Charset.forName(raw.trim()).name();
    } catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
      throw new IllegalArgumentException("unsupported charset: " + raw, e);
    }
  }

  /** 归一并返回对应 {@link Charset}；空/空白返回 {@link StandardCharsets#UTF_8}。 */
  public static Charset resolve(String raw) {
    if (!StringUtils.hasText(raw)) {
      return StandardCharsets.UTF_8;
    }
    try {
      return Charset.forName(raw.trim());
    } catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
      throw new IllegalArgumentException("unsupported charset: " + raw, e);
    }
  }

  /** 归一后判断是否为 UTF-8；空/空白视为 UTF-8（默认值）。 */
  public static boolean isUtf8(String raw) {
    if (!StringUtils.hasText(raw)) {
      return true;
    }
    try {
      return UTF_8.equals(Charset.forName(raw.trim()).name());
    } catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
      return false;
    }
  }

  /** 导出路径强制 UTF-8；非 UTF-8 抛 {@link IllegalArgumentException}。 */
  public static void requireUtf8(String raw) {
    if (!isUtf8(raw)) {
      throw new IllegalArgumentException(
          "only UTF-8 is allowed here (system internal contract): " + raw);
    }
  }
}

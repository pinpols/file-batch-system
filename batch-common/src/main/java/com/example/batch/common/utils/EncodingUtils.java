package com.example.batch.common.utils;

import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;

/**
 * 字符集归一工具。配合 CLAUDE.md §字符编码 约束：全系统内部一律 UTF-8，导入边界允许外部以 别名声明（{@code utf8} / {@code UTF8} / {@code
 * utf-8} / {@code UTF-8}），本工具统一把所有 别名归一为 {@link StandardCharsets#UTF_8} 的规范名，并对未识别字符集快速失败。
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
    if (!Texts.hasText(raw)) {
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
    if (!Texts.hasText(raw)) {
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
    if (!Texts.hasText(raw)) {
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

  /** UTF-8 BOM = EF BB BF（3 字节）。 */
  private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

  /**
   * S-1.8：剥离 UTF-8 BOM 前缀。返回一个包装后的流，前 3 个字节是 BOM 就吞掉，否则透传。
   *
   * <p>外部导出工具（Windows Excel "CSV UTF-8"、部分文本编辑器）写出 UTF-8 文件时会自动加 BOM；系统内部读取这些文件做 parse 时，若不剥 BOM
   * 会让首字段混入 {@code \uFEFF} 字符，后续 header 匹配 / 数值解析全部失败。{@code PreprocessStep.resolveCharset} 自己
   * 实现过一份剥 BOM 逻辑，此处抽出供其他编码转换点复用。
   *
   * <p>要求传入 <b>可 mark/reset 或 {@link PushbackInputStream}</b>——本方法内部用 PushbackInputStream
   * 包装；调用方不需要再关心。返回流的 close 会级联关闭底层。
   */
  public static InputStream stripUtf8Bom(InputStream in) throws IOException {
    if (in == null) {
      return InputStream.nullInputStream();
    }
    PushbackInputStream pb =
        in instanceof PushbackInputStream p ? p : new PushbackInputStream(in, 3);
    byte[] prefix = new byte[3];
    int read = pb.read(prefix);
    if (read <= 0) {
      return pb;
    }
    boolean isBom =
        read == 3
            && prefix[0] == UTF8_BOM[0]
            && prefix[1] == UTF8_BOM[1]
            && prefix[2] == UTF8_BOM[2];
    if (!isBom) {
      pb.unread(prefix, 0, read);
    }
    return pb;
  }
}

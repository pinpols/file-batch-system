package com.example.batch.sdk.handler.builtin.support;

import java.util.ArrayList;
import java.util.List;

/**
 * 分隔符文本的单行编解码(RFC4180 风格,单物理行为一条记录)。
 *
 * <p>解析规则:字段以 {@code delimiter} 分隔;{@code quote} 包裹的字段内,分隔符为字面量,连续两个引号 {@code ""} 表示一个字面引号。
 * 编码规则:字段含分隔符 / 引号 / 换行时用引号包裹并把内部引号双写。
 *
 * <p><b>限制</b>:不支持跨行的引号字段(一条记录 = 一个物理行)。需要嵌入换行的复杂 CSV 请自行扩展或用专门库。
 */
public final class DelimitedCodec {

  private DelimitedCodec() {}

  /** 解析一行为字段列表。 */
  public static List<String> parse(String line, char delimiter, char quote) {
    List<String> out = new ArrayList<>();
    StringBuilder field = new StringBuilder();
    boolean inQuotes = false;
    int i = 0;
    int n = line.length();
    while (i < n) {
      char c = line.charAt(i);
      if (inQuotes) {
        if (c == quote) {
          if (i + 1 < n && line.charAt(i + 1) == quote) {
            field.append(quote);
            i += 2;
          } else {
            inQuotes = false;
            i++;
          }
        } else {
          field.append(c);
          i++;
        }
      } else if (c == quote) {
        inQuotes = true;
        i++;
      } else if (c == delimiter) {
        out.add(field.toString());
        field.setLength(0);
        i++;
      } else {
        field.append(c);
        i++;
      }
    }
    out.add(field.toString());
    return out;
  }

  /** 编码字段列表为一行(必要时加引号转义)。 */
  public static String encode(List<String> fields, char delimiter, char quote) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < fields.size(); i++) {
      if (i > 0) {
        sb.append(delimiter);
      }
      String f = fields.get(i) == null ? "" : fields.get(i);
      boolean needQuote =
          f.indexOf(delimiter) >= 0
              || f.indexOf(quote) >= 0
              || f.indexOf('\n') >= 0
              || f.indexOf('\r') >= 0;
      if (needQuote) {
        sb.append(quote);
        sb.append(f.replace(String.valueOf(quote), "" + quote + quote));
        sb.append(quote);
      } else {
        sb.append(f);
      }
    }
    return sb.toString();
  }
}

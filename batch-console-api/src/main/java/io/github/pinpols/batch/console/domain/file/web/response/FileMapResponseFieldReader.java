package io.github.pinpols.batch.console.domain.file.web.response;

import java.util.Map;

/**
 * 将 file 域编排内部代理回传的 {@code Map<String,Object>} 转换为稳定响应字段。 与批次1~3 各域 {@code *ResponseFieldReader}
 * 同一约定，独立放在 file 域避免跨包 package-private 依赖。 命名带 {@code Map} 前缀以区别本包内已有的实体投影 response。
 */
final class FileMapResponseFieldReader {

  private FileMapResponseFieldReader() {}

  static Object value(Map<String, ?> row, String... keys) {
    for (String key : keys) {
      if (row.containsKey(key)) {
        return row.get(key);
      }
    }
    return null;
  }

  static String stringValue(Map<String, ?> row, String... keys) {
    Object value = value(row, keys);
    return value == null ? null : value.toString();
  }

  static Long longValue(Map<String, ?> row, String... keys) {
    Object value = value(row, keys);
    return value instanceof Number number
        ? number.longValue()
        : value == null ? null : Long.valueOf(value.toString());
  }
}

package io.github.pinpols.batch.sdk.handler.builtin.support;

/**
 * 分隔符文件格式,供 {@code FileImportHandler} 与 {@code QueryExportHandler} 共用。
 *
 * @param delimiter 字段分隔符(默认 {@code ,})
 * @param quote 引号字符(字段内含分隔符 / 引号 / 换行时用其包裹,内部引号双写转义;默认 {@code "})
 * @param header 是否有表头行:Import=跳过首行;Export=写出列名首行(默认 true)
 */
public record DelimitedFormat(char delimiter, char quote, boolean header) {

  /** 默认:逗号分隔、双引号、含表头。 */
  public static DelimitedFormat defaults() {
    return new DelimitedFormat(',', '"', true);
  }

  /** 指定分隔符,其余取默认(双引号、含表头)。 */
  public static DelimitedFormat of(char delimiter) {
    return new DelimitedFormat(delimiter, '"', true);
  }
}

package io.github.pinpols.batch.worker.exports.stage.format;

public interface ExportFormatStrategy {

  // S112 抑制：导出格式 SPI 刻意声明宽泛 throws Exception。
  @SuppressWarnings("java:S112")
  String formatType();

  @SuppressWarnings("java:S112")
  long generate(ExportFormatContext ctx) throws Exception;
}

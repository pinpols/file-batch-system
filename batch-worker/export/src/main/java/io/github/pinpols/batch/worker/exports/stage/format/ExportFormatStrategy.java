package io.github.pinpols.batch.worker.exports.stage.format;

import io.github.pinpols.batch.common.constants.BatchFileConstants;
import io.github.pinpols.batch.common.enums.DictEnum;
import io.github.pinpols.batch.common.enums.FileTemplateFormat;
import io.github.pinpols.batch.common.utils.EmptyChecks;

public interface ExportFormatStrategy {

  // S112 抑制：导出格式 SPI 刻意声明宽泛 throws Exception。
  @SuppressWarnings("java:S112")
  String formatType();

  default String fileSuffix() {
    return fileSuffixFor(formatType());
  }

  default String contentType() {
    return contentTypeFor(formatType());
  }

  static String fileSuffixFor(String formatType) {
    FileTemplateFormat format = parseFormat(formatType);
    if (EmptyChecks.isNull(format)) {
      return BatchFileConstants.JSON_SUFFIX;
    }
    return switch (format) {
      case DELIMITED -> BatchFileConstants.CSV_SUFFIX;
      case EXCEL -> BatchFileConstants.XLSX_SUFFIX;
      case FIXED_WIDTH -> BatchFileConstants.TXT_SUFFIX;
      default -> BatchFileConstants.JSON_SUFFIX;
    };
  }

  static String contentTypeFor(String formatType) {
    FileTemplateFormat format = parseFormat(formatType);
    if (EmptyChecks.isNull(format)) {
      return BatchFileConstants.CONTENT_TYPE_JSON;
    }
    return switch (format) {
      case DELIMITED -> BatchFileConstants.CONTENT_TYPE_CSV;
      case EXCEL -> BatchFileConstants.CONTENT_TYPE_EXCEL;
      case FIXED_WIDTH -> BatchFileConstants.CONTENT_TYPE_TEXT_UTF8;
      case XML -> BatchFileConstants.CONTENT_TYPE_XML;
      default -> BatchFileConstants.CONTENT_TYPE_JSON;
    };
  }

  private static FileTemplateFormat parseFormat(String formatType) {
    return DictEnum.fromCode(FileTemplateFormat.class, formatType);
  }

  @SuppressWarnings("java:S112")
  long generate(ExportFormatContext ctx) throws Exception;
}

package io.github.pinpols.batch.common.file;

import io.github.pinpols.batch.common.constants.BatchFileConstants;
import io.github.pinpols.batch.common.enums.DictEnum;
import io.github.pinpols.batch.common.enums.FileTemplateFormat;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import lombok.Builder;

/** 导出文件名解析器。Console 预览与 Worker 运行时必须共用此实现。 */
public final class ExportFileNameResolver {

  private ExportFileNameResolver() {}

  @Builder
  public record Input(
      String namingRule,
      String fileFormatType,
      String bizType,
      String bizDate,
      String tenantId,
      String batchNo,
      String region,
      String version) {}

  public static String resolve(Input input) {
    String normalizedBatchNo = defaultText(input.batchNo(), "batch");
    String normalizedVersion = defaultText(input.version(), "v1");
    if (hasText(input.namingRule())) {
      return input
          .namingRule()
          .replace("${bizType}", defaultText(input.bizType(), "export"))
          .replace("${bizDate}", defaultText(input.bizDate(), ""))
          .replace("${tenantId}", defaultText(input.tenantId(), ""))
          .replace("${batchNo}", normalizedBatchNo)
          .replace("${region}", defaultText(input.region(), ""))
          .replace("${version}", normalizedVersion);
    }
    return defaultText(input.bizType(), "export")
        + "_"
        + defaultText(input.bizDate(), "")
        + "_"
        + normalizedBatchNo
        + extension(input.fileFormatType());
  }

  static String extension(String fileFormatType) {
    FileTemplateFormat format = DictEnum.fromCode(
        FileTemplateFormat.class, defaultText(fileFormatType, FileTemplateFormat.JSON.code()));
    if (EmptyChecks.isNull(format)) {
      return BatchFileConstants.JSON_SUFFIX;
    }
    return switch (format) {
      case DELIMITED -> BatchFileConstants.CSV_SUFFIX;
      case EXCEL -> BatchFileConstants.XLSX_SUFFIX;
      case FIXED_WIDTH -> BatchFileConstants.TXT_SUFFIX;
      case XML -> BatchFileConstants.XML_SUFFIX;
      default -> BatchFileConstants.JSON_SUFFIX;
    };
  }

  private static boolean hasText(String value) {
    return EmptyChecks.isNotBlank(value);
  }

  private static String defaultText(String value, String fallback) {
    return hasText(value) ? value : fallback;
  }
}

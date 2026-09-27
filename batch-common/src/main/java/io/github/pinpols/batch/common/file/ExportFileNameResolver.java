package io.github.pinpols.batch.common.file;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import java.util.Locale;

/** 导出文件名解析器。Console 预览与 Worker 运行时必须共用此实现。 */
public final class ExportFileNameResolver {

  private ExportFileNameResolver() {}

  public static String resolve(
      String namingRule,
      String fileFormatType,
      String bizType,
      String bizDate,
      String tenantId,
      String batchNo,
      String region,
      String version) {
    String normalizedBatchNo = defaultText(batchNo, "batch");
    String normalizedVersion = defaultText(version, "v1");
    if (hasText(namingRule)) {
      return namingRule
          .replace("${bizType}", defaultText(bizType, "export"))
          .replace("${bizDate}", defaultText(bizDate, ""))
          .replace("${tenantId}", defaultText(tenantId, ""))
          .replace("${batchNo}", normalizedBatchNo)
          .replace("${region}", defaultText(region, ""))
          .replace("${version}", normalizedVersion);
    }
    return defaultText(bizType, "export")
        + "_"
        + defaultText(bizDate, "")
        + "_"
        + normalizedBatchNo
        + extension(fileFormatType);
  }

  static String extension(String fileFormatType) {
    return switch (defaultText(fileFormatType, "JSON").toUpperCase(Locale.ROOT)) {
      case "DELIMITED" -> ".csv";
      case "EXCEL" -> ".xlsx";
      case "FIXED_WIDTH" -> ".txt";
      case "XML" -> ".xml";
      default -> ".json";
    };
  }

  private static boolean hasText(String value) {
    return EmptyChecks.isNotBlank(value);
  }

  private static String defaultText(String value, String fallback) {
    return hasText(value) ? value : fallback;
  }
}

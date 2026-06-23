package io.github.pinpols.batch.common.plugin;

import java.util.Map;

/** GENERATE-stage context for {@link ExportDataPlugin}. */
public record ExportDataContext(
    String tenantId,
    String jobCode,
    String batchNo,
    String templateCode,
    Map<String, Object> templateConfig,
    Map<String, Object> exportSnapshot,
    int partitionNo,
    int partitionCount) {

  /** 兼容构造器:不关心分片的调用点(如 RegisterStep.onRegistered)默认单片 1/1。 */
  public ExportDataContext(
      String tenantId,
      String jobCode,
      String batchNo,
      String templateCode,
      Map<String, Object> templateConfig,
      Map<String, Object> exportSnapshot) {
    this(tenantId, jobCode, batchNo, templateCode, templateConfig, exportSnapshot, 1, 1);
  }
}

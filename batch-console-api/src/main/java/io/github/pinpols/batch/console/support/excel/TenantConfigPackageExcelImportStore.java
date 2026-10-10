package io.github.pinpols.batch.console.support.excel;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 租户配置包 Excel 上传会话存储。持有各业务 sheet 的原始行数据直至 apply 完成。 */
public interface TenantConfigPackageExcelImportStore {

  String save(PackageExcelSession session);

  PackageExcelSession get(String uploadToken);

  void remove(String uploadToken);

  @SuppressWarnings("PMD.ExcessiveParameterList") // 上传会话字段与固定配置包 sheet 一一对应，避免无类型行集合。
  record PackageExcelSession(
      String fileName,
      String tenantId,
      Instant uploadedAt,
      List<Map<String, String>> resourceQueueRows,
      List<Map<String, String>> businessCalendarRows,
      List<Map<String, String>> batchWindowRows,
      List<Map<String, String>> jobRows,
      List<Map<String, String>> jobMonitoringPolicyRows,
      List<Map<String, String>> fileChannelRows,
      List<Map<String, String>> fileTemplateRows,
      List<Map<String, String>> pipelineRows,
      List<Map<String, String>> pipelineStepRows,
      List<Map<String, String>> workflowDefinitionRows,
      List<Map<String, String>> workflowNodeRows,
      List<Map<String, String>> workflowEdgeRows) {

    public PackageExcelSession(
        String fileName,
        String tenantId,
        Instant uploadedAt,
        List<Map<String, String>> resourceQueueRows,
        List<Map<String, String>> businessCalendarRows,
        List<Map<String, String>> batchWindowRows,
        List<Map<String, String>> jobRows,
        List<Map<String, String>> fileChannelRows,
        List<Map<String, String>> fileTemplateRows,
        List<Map<String, String>> pipelineRows,
        List<Map<String, String>> pipelineStepRows,
        List<Map<String, String>> workflowDefinitionRows,
        List<Map<String, String>> workflowNodeRows,
        List<Map<String, String>> workflowEdgeRows) {
      this(
          fileName,
          tenantId,
          uploadedAt,
          resourceQueueRows,
          businessCalendarRows,
          batchWindowRows,
          jobRows,
          List.of(),
          fileChannelRows,
          fileTemplateRows,
          pipelineRows,
          pipelineStepRows,
          workflowDefinitionRows,
          workflowNodeRows,
          workflowEdgeRows);
    }
  }
}

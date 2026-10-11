package io.github.pinpols.batch.console.support.excel;

import io.github.pinpols.batch.common.utils.EmptyChecks;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 租户配置包 Excel 上传会话存储。持有各业务 sheet 的原始行数据直至 apply 完成。 */
public interface TenantConfigPackageExcelImportStore {

  String save(PackageExcelSession session);

  PackageExcelSession get(String uploadToken);

  PreviewSnapshot issuePreviewSnapshot(String uploadToken, long configurationRevision);

  default PreviewSnapshot issuePreviewSnapshot(String uploadToken) {
    return issuePreviewSnapshot(uploadToken, 0L);
  }

  boolean isApplyInProgress(String uploadToken);

  boolean claimForApply(String uploadToken, String previewToken, String applyOwnerToken);

  long configurationRevisionForApply(String uploadToken, String applyOwnerToken);

  boolean replaceSession(
      String uploadToken,
      String expectedPreviewToken,
      PackageExcelSession session,
      String replacementPreviewToken,
      long configurationRevision);

  default boolean replaceSession(
      String uploadToken,
      String expectedPreviewToken,
      PackageExcelSession session,
      String replacementPreviewToken) {
    return replaceSession(uploadToken, expectedPreviewToken, session, replacementPreviewToken, 0L);
  }

  void completeApply(String uploadToken, String applyOwnerToken);

  void releaseApply(String uploadToken, String applyOwnerToken);

  void remove(String uploadToken);

  record PreviewSnapshot(
      PackageExcelSession session, String previewToken, long configurationRevision) {

    public PreviewSnapshot(PackageExcelSession session, String previewToken) {
      this(session, previewToken, 0L);
    }
  }

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
      List<Map<String, String>> workflowEdgeRows,
      String uploadedBy,
      Set<String> permissionScope) {

    public PackageExcelSession withUploadContext(String actorId, Set<String> authorities) {
      return new PackageExcelSession(
          fileName,
          tenantId,
          uploadedAt,
          resourceQueueRows,
          businessCalendarRows,
          batchWindowRows,
          jobRows,
          jobMonitoringPolicyRows,
          fileChannelRows,
          fileTemplateRows,
          pipelineRows,
          pipelineStepRows,
          workflowDefinitionRows,
          workflowNodeRows,
          workflowEdgeRows,
          actorId,
          EmptyChecks.isNull(authorities) ? Set.of() : Set.copyOf(authorities));
    }

    public PackageExcelSession(
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
      this(
          fileName,
          tenantId,
          uploadedAt,
          resourceQueueRows,
          businessCalendarRows,
          batchWindowRows,
          jobRows,
          jobMonitoringPolicyRows,
          fileChannelRows,
          fileTemplateRows,
          pipelineRows,
          pipelineStepRows,
          workflowDefinitionRows,
          workflowNodeRows,
          workflowEdgeRows,
          null,
          Set.of());
    }

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
          workflowEdgeRows,
          null,
          Set.of());
    }
  }
}

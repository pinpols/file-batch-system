package io.github.pinpols.batch.console.application.contract.response.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("租户配置包响应排除已退役 Pipeline 字段")
class TenantConfigPackageExcelContractTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @DisplayName("配置预览不暴露旧 Pipeline 迁移说明")
  @Test
  void shouldPreviewDoesNotExposeLegacyPipelineMigrationNotes() throws Exception {
    TenantConfigPackageExcelPreviewResponse response = new TenantConfigPackageExcelPreviewResponse(
        "upload-token", "preview-token", "package.xlsx", 0, 0, 0, List.of(), List.of(), List.of());

    JsonNode json = objectMapper.readTree(objectMapper.writeValueAsBytes(response));

    assertThat(json.has("migrationNotes")).isFalse();
  }

  @DisplayName("配置应用响应不暴露退役 Pipeline 写入计数")
  @Test
  void shouldApplyDoesNotExposeRetiredPipelineWriteCounters() throws Exception {
    TenantConfigPackageExcelApplyResponse response = TenantConfigPackageExcelApplyResponse.builder()
        .uploadToken("upload-token")
        .tenantId("tenant-a")
        .resourceQueueInserted(0)
        .resourceQueueUpdated(0)
        .businessCalendarInserted(0)
        .businessCalendarUpdated(0)
        .batchWindowInserted(0)
        .batchWindowUpdated(0)
        .jobInserted(0)
        .jobUpdated(0)
        .channelInserted(0)
        .channelUpdated(0)
        .fileTemplateInserted(0)
        .fileTemplateUpdated(0)
        .workflowInserted(0)
        .workflowUpdated(0)
        .build();

    JsonNode json = objectMapper.readTree(objectMapper.writeValueAsBytes(response));

    assertThat(json.has("pipelineInserted")).isFalse();
    assertThat(json.has("pipelineUpdated")).isFalse();
  }
}

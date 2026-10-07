package io.github.pinpols.batch.console.infrastructure.excel;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 防漂移单测：锁定配置包 11 个 sheet 与共享 schema 的列定义。
 *
 * <p>新增字段时应优先改 {@link ConfigPackageExcelSchema}，配置包导入导出与模板下载统一从这里派生，避免多处维护字符串列表。
 */
@DisplayName("配置包 Excel 列定义:各 sheet 与共享 schema 的一致性")
class ConfigPackageExcelSchemaConsistencyTest {

  @Test
  @DisplayName("配置包写出列:11 个 sheet 全部取自共享 schema 定义")
  void shouldUseSharedSchemaColumns_whenWritingWorkbook() {
    assertThat(ConfigPackageExcelWorkbookWriter.RESOURCE_QUEUE_COLUMNS)
        .isEqualTo(ConfigPackageExcelSchema.ResourceQueue.COLUMNS);
    assertThat(ConfigPackageExcelWorkbookWriter.BUSINESS_CALENDAR_COLUMNS)
        .isEqualTo(ConfigPackageExcelSchema.BusinessCalendar.COLUMNS);
    assertThat(ConfigPackageExcelWorkbookWriter.BATCH_WINDOW_COLUMNS)
        .isEqualTo(ConfigPackageExcelSchema.BatchWindow.COLUMNS);
    assertThat(ConfigPackageExcelWorkbookWriter.JOB_COLUMNS)
        .isEqualTo(ConfigPackageExcelSchema.JobDefinition.COLUMNS);
    assertThat(ConfigPackageExcelWorkbookWriter.CHANNEL_COLUMNS)
        .isEqualTo(ConfigPackageExcelSchema.FileChannel.COLUMNS);
    assertThat(ConfigPackageExcelWorkbookWriter.FILE_TEMPLATE_COLUMNS)
        .isEqualTo(ConfigPackageExcelSchema.FileTemplate.COLUMNS);
    assertThat(ConfigPackageExcelWorkbookWriter.PIPELINE_COLUMNS)
        .isEqualTo(ConfigPackageExcelSchema.PipelineDefinition.COLUMNS);
    assertThat(ConfigPackageExcelWorkbookWriter.STEP_COLUMNS)
        .isEqualTo(ConfigPackageExcelSchema.PipelineStep.COLUMNS);
    assertThat(ConfigPackageExcelWorkbookWriter.WF_DEF_COLUMNS)
        .isEqualTo(ConfigPackageExcelSchema.WorkflowDefinition.COLUMNS);
    assertThat(ConfigPackageExcelWorkbookWriter.WF_NODE_COLUMNS)
        .isEqualTo(ConfigPackageExcelSchema.WorkflowNode.COLUMNS);
    assertThat(ConfigPackageExcelWorkbookWriter.WF_EDGE_COLUMNS)
        .isEqualTo(ConfigPackageExcelSchema.WorkflowEdge.COLUMNS);
  }

  @Test
  @DisplayName("作业定义列顺序:依赖作业编码列位于调度表达式与日历编码之间")
  void shouldKeepDependsOnColumnNearSchedule_whenSchemaChanges() {
    assertThat(ConfigPackageExcelSchema.JobDefinition.COLUMNS)
        .containsSubsequence("schedule_expr", "depends_on_job_code", "calendar_code");
  }
}

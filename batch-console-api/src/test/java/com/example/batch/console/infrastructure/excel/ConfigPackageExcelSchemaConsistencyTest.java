package com.example.batch.console.infrastructure.excel;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.batch.console.infrastructure.config.DefaultConsoleBatchWindowExcelApplicationService;
import com.example.batch.console.infrastructure.config.DefaultConsoleResourceQueueExcelApplicationService;
import com.example.batch.console.infrastructure.file.DefaultConsoleFileChannelExcelApplicationService;
import com.example.batch.console.infrastructure.file.DefaultConsoleFileTemplateExcelApplicationService;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 防漂移单测：锁定配置包 11 个 sheet 与已复用同一 schema 的独立 Excel 模板列定义。
 *
 * <p>新增字段时应优先改 {@link ConfigPackageExcelSchema}，再按业务差异显式派生独立模板列，避免导入导出、 模板下载和配置包下载各自维护一份字符串列表。
 */
class ConfigPackageExcelSchemaConsistencyTest {

  @Test
  void configPackageWorkbookColumnsUseSharedSchema() {
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
  void standaloneTemplateColumnsStayAlignedWithSharedSchema() {
    assertThat(staticListField(DefaultConsoleResourceQueueExcelApplicationService.class, "COLUMNS"))
        .isEqualTo(ConfigPackageExcelSchema.ResourceQueue.COLUMNS);
    assertThat(staticListField(DefaultConsoleBatchWindowExcelApplicationService.class, "COLUMNS"))
        .isEqualTo(ConfigPackageExcelSchema.BatchWindow.COLUMNS);
    assertThat(staticListField(DefaultConsoleFileChannelExcelApplicationService.class, "COLUMNS"))
        .isEqualTo(ConfigPackageExcelSchema.FileChannel.COLUMNS);
    assertThat(staticListField(DefaultConsoleFileTemplateExcelApplicationService.class, "COLUMNS"))
        .isEqualTo(ConfigPackageExcelSchema.FileTemplate.COLUMNS);
    assertThat(PipelineExcelWorkbookWriter.PIPELINE_COLUMNS)
        .isEqualTo(ConfigPackageExcelSchema.PipelineDefinition.COLUMNS);
    assertThat(PipelineExcelWorkbookWriter.STEP_COLUMNS)
        .isEqualTo(ConfigPackageExcelSchema.PipelineStep.COLUMNS);
  }

  @Test
  void standaloneBusinessCalendarUsesHolidaySheetInsteadOfInlineHolidaysColumn() {
    List<String> expectedCalendarColumns =
        ConfigPackageExcelSchema.BusinessCalendar.COLUMNS.stream()
            .filter(
                column -> !ConfigPackageExcelSchema.BusinessCalendar.COL_HOLIDAYS.equals(column))
            .toList();

    assertThat(BusinessCalendarExcelWorkbookWriter.CALENDAR_COLUMNS)
        .isEqualTo(expectedCalendarColumns);
  }

  @SuppressWarnings("unchecked")
  private static List<String> staticListField(Class<?> owner, String fieldName) {
    try {
      Field field = owner.getDeclaredField(fieldName);
      field.setAccessible(true);
      return (List<String>) field.get(null);
    } catch (ReflectiveOperationException ex) {
      throw new AssertionError(
          "Cannot read static field " + owner.getSimpleName() + "." + fieldName, ex);
    }
  }
}

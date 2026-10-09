package io.github.pinpols.batch.console.infrastructure.config;

import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelSchema.COL_COMPLETION_DEADLINE_DAY_OFFSET;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelSchema.COL_COMPLETION_DEADLINE_LOCAL_TIME;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelSchema.COL_COMPLETION_DEADLINE_SEVERITY;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelSchema.COL_DEPENDENCY_COMPLETION_WINDOW_SECONDS;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelSchema.COL_JOB_CODE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelSchema.COL_SOFT_RUNTIME_SECONDS;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelSchema.COL_SOFT_RUNTIME_SEVERITY;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelSchema.COL_START_GRACE_SECONDS;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelSchema.COL_START_GRACE_SEVERITY;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelSchema.COL_TENANT_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.github.pinpols.batch.console.domain.job.entity.JobDefinitionEntity;
import io.github.pinpols.batch.console.domain.workflow.mapper.PipelineStepDefinitionMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowEdgeMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowNodeMapper;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("配置包监控策略导出投影:列名与作业策略字段保持一致")
class TenantConfigPackageRowProjectionsMonitoringPolicyTest {

  @Test
  @DisplayName("策略实体的全部字段投影到监控策略 sheet")
  void shouldProjectMonitoringPolicyColumns() {
    TenantConfigPackageRowProjections projections = new TenantConfigPackageRowProjections(
        mock(PipelineStepDefinitionMapper.class),
        mock(WorkflowNodeMapper.class),
        mock(WorkflowEdgeMapper.class));
    JobDefinitionEntity job = new JobDefinitionEntity();
    job.setTenantId("tenant-a");
    job.setJobCode("JOB_DAILY");
    job.setSoftRuntimeSeconds(1200);
    job.setSoftRuntimeSeverity("ERROR");
    job.setStartGraceSeconds(300);
    job.setStartGraceSeverity("WARN");
    job.setCompletionDeadlineLocalTime(LocalTime.of(4, 0));
    job.setCompletionDeadlineDayOffset(1);
    job.setDependencyCompletionWindowSeconds(0);
    job.setCompletionDeadlineSeverity("CRITICAL");

    List<Map<String, Object>> rows = projections.toJobMonitoringPolicyRows(List.of(job));

    assertThat(rows).singleElement().satisfies(row -> {
      assertThat(row).containsEntry(COL_TENANT_ID, "tenant-a");
      assertThat(row).containsEntry(COL_JOB_CODE, "JOB_DAILY");
      assertThat(row).containsEntry(COL_SOFT_RUNTIME_SECONDS, 1200);
      assertThat(row).containsEntry(COL_SOFT_RUNTIME_SEVERITY, "ERROR");
      assertThat(row).containsEntry(COL_START_GRACE_SECONDS, 300);
      assertThat(row).containsEntry(COL_START_GRACE_SEVERITY, "WARN");
      assertThat(row).containsEntry(COL_COMPLETION_DEADLINE_LOCAL_TIME, LocalTime.of(4, 0));
      assertThat(row).containsEntry(COL_COMPLETION_DEADLINE_DAY_OFFSET, 1);
      assertThat(row).containsEntry(COL_DEPENDENCY_COMPLETION_WINDOW_SECONDS, 0);
      assertThat(row).containsEntry(COL_COMPLETION_DEADLINE_SEVERITY, "CRITICAL");
    });
  }
}

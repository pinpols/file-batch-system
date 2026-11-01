package io.github.pinpols.batch.console.infrastructure.config;

import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelSchema.COL_COMPLETION_DEADLINE_DAY_OFFSET;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelSchema.COL_COMPLETION_DEADLINE_LOCAL_TIME;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelSchema.COL_COMPLETION_DEADLINE_SEVERITY;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelSchema.COL_CONFIG_JSON;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelSchema.COL_DEPENDENCY_COMPLETION_WINDOW_SECONDS;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelSchema.COL_JOB_CODE;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelSchema.COL_SOFT_RUNTIME_SECONDS;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelSchema.COL_SOFT_RUNTIME_SEVERITY;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelSchema.COL_START_GRACE_SECONDS;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelSchema.COL_START_GRACE_SEVERITY;
import static io.github.pinpols.batch.console.infrastructure.excel.ConfigPackageExcelSchema.COL_TENANT_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.console.domain.file.view.FileChannelConfigQueryView;
import io.github.pinpols.batch.console.domain.job.view.JobDefinitionView;
import io.github.pinpols.batch.console.domain.workflow.entity.WorkflowDefinitionEntity;
import io.github.pinpols.batch.console.domain.workflow.entity.WorkflowNodeEntity;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowEdgeMapper;
import io.github.pinpols.batch.console.domain.workflow.mapper.WorkflowNodeMapper;
import io.github.pinpols.batch.console.domain.workflow.query.WorkflowNodeQuery;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("配置包监控策略导出投影:列名与作业策略字段保持一致")
class TenantConfigPackageRowProjectionsMonitoringPolicyTest {

  @Test
  @DisplayName("配置包导出清空渠道 JSON，避免凭据随工作簿外流")
  void shouldOmitChannelConfigurationFromTenantExport() {
    TenantConfigPackageRowProjections projections = new TenantConfigPackageRowProjections(
        mock(WorkflowNodeMapper.class), mock(WorkflowEdgeMapper.class));
    FileChannelConfigQueryView source = FileChannelConfigQueryView.builder()
        .channelCode("PARTNER_SFTP")
        .configJson("{\"sftp_password\":\"plain-text-secret\"}")
        .build();

    List<Map<String, Object>> rows = projections.toExportableChannelRows(List.of(source));

    assertThat(rows).singleElement().satisfies(row -> {
      assertThat(row).containsEntry("channel_code", "PARTNER_SFTP");
      assertThat(row).containsEntry(COL_CONFIG_JSON, "");
    });
    assertThat(source.configJson()).isEqualTo("{\"sftp_password\":\"plain-text-secret\"}");
  }

  @Test
  @DisplayName("策略实体的全部字段投影到监控策略 sheet")
  void shouldProjectMonitoringPolicyColumns() {
    TenantConfigPackageRowProjections projections = new TenantConfigPackageRowProjections(
        mock(WorkflowNodeMapper.class), mock(WorkflowEdgeMapper.class));
    JobDefinitionView job = new JobDefinitionView();
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

    List<Map<String, Object>> rows = projections.toJobMonitoringPolicyRows(List.of(job), true);

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

  @Test
  @DisplayName("工作流配置包投影保留独立的单作业关联与调度字段")
  void shouldProjectWorkflowAssociationAndSchedule() {
    TenantConfigPackageRowProjections projections = new TenantConfigPackageRowProjections(
        mock(WorkflowNodeMapper.class), mock(WorkflowEdgeMapper.class));
    WorkflowDefinitionEntity workflow = new WorkflowDefinitionEntity();
    workflow.setTenantId("tenant-a");
    workflow.setWorkflowCode("WF_JOB_A");
    workflow.setWorkflowName("Job A default flow");
    workflow.setWorkflowType("DAG");
    workflow.setVersion(2);
    workflow.setSingleJobDefinitionId(41L);
    workflow.setScheduleEnabled(true);
    workflow.setScheduleType("CRON");
    workflow.setScheduleExpr("0 0 2 * * ?");
    workflow.setTimezone("Asia/Shanghai");
    workflow.setTriggerMode("SCHEDULED");
    workflow.setCalendarCode("CN_WORKDAY");
    workflow.setDependsOnJobCode("JOB_PREVIOUS");

    List<Map<String, Object>> rows =
        projections.toWfDefRows(List.of(workflow), Map.of(41L, "JOB_A"));

    assertThat(rows).singleElement().satisfies(row -> {
      assertThat(row).containsEntry("single_job_code", "JOB_A");
      assertThat(row).containsEntry("schedule_enabled", true);
      assertThat(row).containsEntry("schedule_type", "CRON");
      assertThat(row).containsEntry("schedule_expr", "0 0 2 * * ?");
      assertThat(row).containsEntry("timezone", "Asia/Shanghai");
      assertThat(row).containsEntry("trigger_mode", "SCHEDULED");
      assertThat(row).containsEntry("calendar_code", "CN_WORKDAY");
      assertThat(row).containsEntry("depends_on_job_code", "JOB_PREVIOUS");
    });
  }

  @Test
  @DisplayName("作业和 Workflow 节点导出递归遮罩动态 JSON 凭据")
  void shouldMaskCredentialsFromDynamicJsonExportRows() {
    WorkflowNodeMapper nodeMapper = mock(WorkflowNodeMapper.class);
    TenantConfigPackageRowProjections projections =
        new TenantConfigPackageRowProjections(nodeMapper, mock(WorkflowEdgeMapper.class));

    JobDefinitionView job = new JobDefinitionView();
    job.setDefaultParams("{\"password\":\"job-secret\",\"region\":\"west\"}");
    assertThat(projections.toJobRows(List.of(job), true))
        .singleElement()
        .extracting(row -> row.get("default_params"))
        .isEqualTo("{\"password\":\"****\",\"region\":\"west\"}");

    WorkflowDefinitionEntity definition = new WorkflowDefinitionEntity();
    definition.setId(33L);
    definition.setWorkflowCode("FLOW");
    definition.setVersion(1);
    WorkflowNodeEntity node = new WorkflowNodeEntity();
    node.setNodeCode("NODE");
    node.setNodeParams("{\"nested\":{\"clientSecret\":\"node-secret\"}}");
    when(nodeMapper.selectByQuery(any(WorkflowNodeQuery.class))).thenReturn(List.of(node));
    List<Map<String, Object>> nodes =
        projections.collectWorkflowNodes("tenant-a", List.of(definition));
    assertThat(nodes)
        .singleElement()
        .extracting(row -> row.get("node_params"))
        .isEqualTo("{\"nested\":{\"clientSecret\":\"****\"}}");
  }
}

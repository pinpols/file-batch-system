package io.github.pinpols.batch.orchestrator.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.enums.BatchLifecycleStatus;
import io.github.pinpols.batch.common.enums.JobInstanceStatus;
import io.github.pinpols.batch.common.enums.WorkflowRunStatus;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("作业实例状态与映射文件的 SQL 契约,验证批量日门禁查询覆盖全部终态状态码")
class JobInstanceStatusSqlContractTest {

  @Test
  @DisplayName("批量日门禁相关统计查询在拼接条件后包含每一个终态状态码")
  void shouldRecognizeEveryTerminalStatus_whenBatchDayGateQueriesRun() {
    Configuration configuration = loadJobInstanceMapper();

    assertContainsEveryTerminalStatus(configuration, "countNonTerminalByJobCodeAndBizDate");
    assertContainsEveryTerminalStatus(configuration, "countNonTerminalByJobGroupAndBizDate");
  }

  @Test
  @DisplayName("批日 Workflow 指标分类与 WorkflowRun 生命周期枚举一致")
  void shouldClassifyEveryNonDryRunWorkflowStatus_whenBatchDayMetricsRun() {
    Configuration configuration = loadJobInstanceMapper();
    String id = JobInstanceMapper.class.getName() + ".selectBatchDayMetrics";
    String sql = configuration
        .getMappedStatement(id)
        .getBoundSql(Map.of(
            "tenantId", "tenant",
            "calendarCode", "calendar",
            "bizDate", LocalDate.of(2026, 10, 17)))
        .getSql();
    Set<WorkflowRunStatus> persistedStatuses = Arrays.stream(WorkflowRunStatus.values())
        .filter(status -> !status.isDryRunTerminal())
        .collect(Collectors.toSet());

    assertThat(statusesForSqlClause(sql, "wf_run\\.run_status\\s+in\\s*\\(([^)]*)\\)"))
        .as("active workflow_run statuses")
        .containsExactlyInAnyOrderElementsOf(
            codes(persistedStatuses, status -> !status.lifecycle().terminal()));
    assertThat(statusesForSqlClause(sql, "wf_run\\.run_status\\s*=\\s*'([A-Z][A-Z0-9_]*)'"))
        .as("successful workflow_run statuses")
        .containsExactlyInAnyOrderElementsOf(
            codes(persistedStatuses, status -> status.lifecycle() == BatchLifecycleStatus.SUCCESS));
    assertThat(statusesForSqlClause(sql, "wf_run\\.run_status\\s+in\\s*\\(([^)]*)\\)", 2))
        .as("non-success terminal workflow_run statuses")
        .containsExactlyInAnyOrderElementsOf(codes(
            persistedStatuses,
            status -> status.lifecycle().terminal()
                && status.lifecycle() != BatchLifecycleStatus.SUCCESS));
    assertThat(statusesForSqlClause(sql, "job_instance\\.instance_status\\s+in\\s*\\(([^)]*)\\)"))
        .as("active child JobInstance statuses")
        .containsExactlyInAnyOrderElementsOf(JobInstanceStatus.activeCodes());
    assertThat(sql).contains("wf_run.dry_run = false");
    assertThat(sql)
        .contains("node_run.workflow_run_id = wf_run.id")
        .contains("job_instance.biz_date is not distinct from wf_run.biz_date")
        .contains("job_instance.dry_run = wf_run.dry_run");
  }

  private static Set<String> codes(
      Set<WorkflowRunStatus> statuses, Predicate<WorkflowRunStatus> predicate) {
    return statuses.stream()
        .filter(predicate)
        .map(WorkflowRunStatus::code)
        .collect(Collectors.toSet());
  }

  private static Set<String> statusesForSqlClause(String sql, String pattern) {
    return statusesForSqlClause(sql, pattern, 1);
  }

  private static Set<String> statusesForSqlClause(String sql, String pattern, int occurrence) {
    Matcher matcher = Pattern.compile("(?is)" + pattern).matcher(sql);
    for (int index = 0; index < occurrence; index++) {
      assertThat(matcher.find()).as(pattern + " occurrence " + occurrence).isTrue();
    }
    Set<String> statuses = new HashSet<>();
    String capturedClause = matcher.group(1);
    Matcher status = Pattern.compile("'([A-Z][A-Z0-9_]*)'").matcher(capturedClause);
    while (status.find()) {
      statuses.add(status.group(1));
    }
    if (statuses.isEmpty() && capturedClause.matches("[A-Z][A-Z0-9_]*")) {
      statuses.add(capturedClause);
    }
    return statuses;
  }

  private static void assertContainsEveryTerminalStatus(
      Configuration configuration, String statementName) {
    String id = JobInstanceMapper.class.getName() + "." + statementName;
    MappedStatement statement = configuration.getMappedStatement(id);
    String sql = statement.getBoundSql(Map.of()).getSql();
    assertThat(statusesInNotInClause(sql))
        .as(id)
        .containsExactlyInAnyOrderElementsOf(JobInstanceStatus.terminalCodes());
  }

  private static Set<String> statusesInNotInClause(String sql) {
    Matcher clause =
        Pattern.compile("(?is)instance_status\\s+not\\s+in\\s*\\(([^)]*)\\)").matcher(sql);
    assertThat(clause.find()).as("instance_status NOT IN clause").isTrue();
    Set<String> statuses = new HashSet<>();
    Matcher status = Pattern.compile("'([A-Z][A-Z0-9_]*)'").matcher(clause.group(1));
    while (status.find()) {
      statuses.add(status.group(1));
    }
    assertThat(clause.find()).as("single instance_status NOT IN clause").isFalse();
    return statuses;
  }

  private static Configuration loadJobInstanceMapper() {
    String resource = "mapper/JobInstanceMapper.xml";
    Configuration configuration = new Configuration();
    try (InputStream inputStream =
        Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
      assertThat(inputStream).as(resource).isNotNull();
      new XMLMapperBuilder(inputStream, configuration, resource, configuration.getSqlFragments())
          .parse();
      return configuration;
    } catch (Exception ex) {
      throw new AssertionError("failed to validate " + resource, ex);
    }
  }
}

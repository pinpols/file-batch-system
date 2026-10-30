package io.github.pinpols.batch.orchestrator.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.enums.JobInstanceStatus;
import io.github.pinpols.batch.common.enums.WorkflowRunStatus;
import java.io.InputStream;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("重试调度扫描的暂停策略状态口径")
class RetryScheduleMapperXmlTest {

  @Test
  @DisplayName("过滤暂停 Workflow 时仅限可派发的活跃作业状态")
  void shouldUseActiveJobStatesExceptPaused_whenFilteringPausedWorkflow() throws Exception {
    String resource = "mapper/RetryScheduleMapper.xml";
    Configuration configuration = new Configuration();
    try (InputStream input =
        Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
      assertThat(input).as(resource).isNotNull();
      new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
    }
    String sql = configuration
        .getMappedStatement(RetryScheduleMapper.class.getName() + ".selectDueNotPaused")
        .getBoundSql(Map.of(
            "tenantId", "t", "retryStatus", "WAITING", "dueBefore", Instant.now(), "batchSize", 1))
        .getSql();
    Matcher clause = Pattern.compile("(?is)instance\\.instance_status\\s+in\\s*\\(([^)]*)\\)")
        .matcher(sql);
    assertThat(clause.find()).isTrue();
    Set<String> statuses = new HashSet<>();
    Matcher literal = Pattern.compile("'([A-Z][A-Z0-9_]*)'").matcher(clause.group(1));
    while (literal.find()) {
      statuses.add(literal.group(1));
    }
    Set<String> activeExceptPaused = new HashSet<>(JobInstanceStatus.activeCodes());
    activeExceptPaused.remove(JobInstanceStatus.PAUSED.code());
    assertThat(statuses).containsExactlyInAnyOrderElementsOf(activeExceptPaused);

    Matcher workflowClause = Pattern.compile(
            "(?is)latest_workflow\\.run_status\\s+in\\s*\\(([^)]*)\\)")
        .matcher(sql);
    assertThat(workflowClause.find()).isTrue();
    Set<String> workflowStatuses = new HashSet<>();
    Matcher workflowLiteral =
        Pattern.compile("'([A-Z][A-Z0-9_]*)'").matcher(workflowClause.group(1));
    while (workflowLiteral.find()) {
      workflowStatuses.add(workflowLiteral.group(1));
    }
    assertThat(workflowStatuses)
        .containsExactlyInAnyOrderElementsOf(WorkflowRunStatus.activeCodes());
  }
}

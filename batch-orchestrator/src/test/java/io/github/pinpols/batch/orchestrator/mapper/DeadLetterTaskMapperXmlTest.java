package io.github.pinpols.batch.orchestrator.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.enums.JobInstanceStatus;
import io.github.pinpols.batch.common.enums.WorkflowRunStatus;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("死信自动重放扫描的暂停状态分类")
class DeadLetterTaskMapperXmlTest {

  @Test
  @DisplayName("暂停过滤只涵盖可恢复的作业与 Workflow 状态")
  void shouldUseActiveLifecycleStates_whenFilteringPausedDeadLetters() throws Exception {
    String resource = "mapper/DeadLetterTaskMapper.xml";
    Configuration configuration = new Configuration();
    try (InputStream input =
        Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
      assertThat(input).as(resource).isNotNull();
      new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
    }
    String sql = configuration
        .getMappedStatement(
            DeadLetterTaskMapper.class.getName() + ".selectDueAutoRetriesByStatuses")
        .getBoundSql(Map.of("batchSize", 1, "replayStatuses", List.of("NEW", "FAILED")))
        .getSql();
    Set<String> activeExceptPaused = new HashSet<>(JobInstanceStatus.activeCodes());
    activeExceptPaused.remove(JobInstanceStatus.PAUSED.code());
    assertThat(statuses(sql, "instance\\.instance_status"))
        .containsExactlyInAnyOrderElementsOf(activeExceptPaused);
    assertThat(statuses(sql, "latest_workflow\\.run_status"))
        .containsExactlyInAnyOrderElementsOf(WorkflowRunStatus.activeCodes());
  }

  private static Set<String> statuses(String sql, String columnPattern) {
    Matcher clause =
        Pattern.compile("(?is)" + columnPattern + "\\s+in\\s*\\(([^)]*)\\)").matcher(sql);
    assertThat(clause.find()).as(columnPattern).isTrue();
    Matcher literal = Pattern.compile("'([A-Z][A-Z0-9_]*)'").matcher(clause.group(1));
    Set<String> values = new HashSet<>();
    while (literal.find()) {
      values.add(literal.group(1));
    }
    assertThat(clause.find()).as("single status clause for " + columnPattern).isFalse();
    return values;
  }
}

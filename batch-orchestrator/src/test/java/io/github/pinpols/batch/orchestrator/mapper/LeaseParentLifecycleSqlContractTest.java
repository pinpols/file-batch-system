package io.github.pinpols.batch.orchestrator.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.pinpols.batch.common.enums.JobInstanceStatus;
import io.github.pinpols.batch.common.enums.WorkflowRunStatus;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("任务与分片父级状态围栏的 SQL 生命周期契约")
class LeaseParentLifecycleSqlContractTest {

  @Test
  @DisplayName("租约回收允许暂停父级，新派发与普通重试则必须等待恢复")
  void shouldMatchActiveLifecycleAndKeepPausedDispatchFence() {
    assertParentStates("JobTaskMapper", "LeaseReclaimableParentPredicate", true);
    assertParentStates("JobPartitionMapper", "LeaseReclaimablePartitionParentPredicate", true);
    assertParentStates("JobTaskMapper", "ExecutableParentPredicate", false);
    assertParentStates("JobPartitionMapper", "DispatchablePartitionParentPredicate", false);
  }

  private static void assertParentStates(String mapper, String fragment, boolean allowPaused) {
    String resource = "mapper/" + mapper + ".xml";
    Configuration configuration = new Configuration();
    try (InputStream input =
        Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
      assertThat(input).as(resource).isNotNull();
      new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
    } catch (Exception ex) {
      throw new AssertionError("failed to parse " + resource, ex);
    }
    String namespace = "io.github.pinpols.batch.orchestrator.mapper." + mapper;
    String sql = configuration.getSqlFragments().get(namespace + "." + fragment).getStringBody();
    Set<String> jobStates = new HashSet<>(JobInstanceStatus.activeCodes());
    Set<String> workflowStates = new HashSet<>(WorkflowRunStatus.activeCodes());
    if (!allowPaused) {
      jobStates.remove(JobInstanceStatus.PAUSED.code());
      workflowStates.remove(WorkflowRunStatus.PAUSED.code());
    }
    assertThat(statuses(sql, "parent_instance.instance_status", "in"))
        .as(fragment + " job statuses")
        .containsExactlyInAnyOrderElementsOf(jobStates);
    assertThat(statuses(sql, "parent_workflow.run_status", "not\\s+in"))
        .as(fragment + " workflow statuses")
        .containsExactlyInAnyOrderElementsOf(workflowStates);
  }

  private static Set<String> statuses(String sql, String column, String operator) {
    Matcher clause = Pattern.compile(
            "(?is)" + Pattern.quote(column) + "\\s+" + operator + "\\s*\\(([^)]*)\\)")
        .matcher(sql);
    assertThat(clause.find()).as(column + " status clause").isTrue();
    Matcher values = Pattern.compile("'([A-Z][A-Z0-9_]*)'").matcher(clause.group(1));
    Set<String> statuses = new HashSet<>();
    while (values.find()) {
      statuses.add(values.group(1));
    }
    assertThat(clause.find()).as("single " + column + " status clause").isFalse();
    return statuses;
  }
}

package io.github.pinpols.batch.orchestrator.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * ASSESS-20261102-DEC-01：Task/Partition 回到 READY 是登记的受限例外，不是通用状态迁移。
 *
 * <p>本契约固定 SQL 层的边界：Task 只能从 READY/RUNNING/FAILED 复位，SUCCESS/CANCELLED 等终态永不复活；所有复位都带版本
 * CAS 和父级状态围栏。父级状态集合本身由 {@link LeaseParentLifecycleSqlContractTest} 校验；预期源状态在此独立声明，避免与实现同源漂移。
 */
@DisplayName("受限重试复位的 SQL 契约:源状态、版本 CAS 与父级围栏")
class BoundedRetryResetSqlContractTest {

  private static final Set<String> RESETTABLE_TASK_STATUSES = Set.of("READY", "RUNNING", "FAILED");

  @ParameterizedTest(name = "{0}")
  @CsvSource({
    "resetForRetry, ExecutableParentPredicate",
    "resetForLeaseReclaim, LeaseReclaimableParentPredicate"
  })
  @DisplayName("Task 复位只接受未成功收尾的尝试，并带版本 CAS 与父级围栏")
  void shouldOnlyReviveUnfinishedAttempts_whenResettingTask(
      String statementId, String parentPredicate) throws IOException {
    String statement = statement("JobTaskMapper", "update", statementId);

    assertThat(taskSourceStatuses(statement))
        .as(statementId + " source task statuses")
        .containsExactlyInAnyOrderElementsOf(RESETTABLE_TASK_STATUSES);
    assertThat(statement).contains("version = #{expectedVersion}");
    assertThat(statement).contains("<include refid=\"" + parentPredicate + "\"/>");
  }

  @Test
  @DisplayName("Partition 派发复位带版本 CAS 与可派发父级围栏")
  void shouldVersionAndFencePartitionReset_whenResettingForDispatch() throws IOException {
    String statement = statement("JobPartitionMapper", "select", "resetForDispatch");
    String candidate = fragment("JobPartitionMapper", "ResetForDispatchCandidateBase");

    assertThat(candidate).contains("version = #{expectedVersion}");
    assertThat(statement).contains("<include refid=\"DispatchablePartitionParentPredicate\"/>");
  }

  private static Set<String> taskSourceStatuses(String statement) {
    Matcher clause = Pattern.compile("(?is)task_status\\s+in\\s*\\(([^)]*)\\)").matcher(statement);
    assertThat(clause.find()).as("task_status source clause").isTrue();
    Matcher values = Pattern.compile("'([A-Z][A-Z0-9_]*)'").matcher(clause.group(1));
    Set<String> statuses = new HashSet<>();
    while (values.find()) {
      statuses.add(values.group(1));
    }
    assertThat(clause.find()).as("single task_status source clause").isFalse();
    return statuses;
  }

  private static String statement(String mapper, String tag, String id) throws IOException {
    return element(mapper, "<" + tag + "\\s+id=\"" + id + "\"[^>]*>(.*?)</" + tag + ">");
  }

  private static String fragment(String mapper, String id) throws IOException {
    return element(mapper, "<sql\\s+id=\"" + id + "\">(.*?)</sql>");
  }

  private static String element(String mapper, String regex) throws IOException {
    String resource = "mapper/" + mapper + ".xml";
    try (InputStream input =
        Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
      assertThat(input).as(resource).isNotNull();
      String xml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
      Matcher matcher = Pattern.compile("(?s)" + regex).matcher(xml);
      assertThat(matcher.find()).as(resource + " " + regex).isTrue();
      return matcher.group(1);
    }
  }
}

package io.github.pinpols.batch.orchestrator.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.redis.BatchRedisKeys;
import io.github.pinpols.batch.orchestrator.domain.view.JobDefinitionView;
import io.github.pinpols.batch.orchestrator.domain.view.WorkflowDefinitionView;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
@DisplayName("配置缓存版本读取的初始值与不可用边界")
class OrchestratorRedisSupportRevisionTest {

  @Mock
  private StringRedisTemplate redis;

  @Mock
  private ValueOperations<String, String> values;

  @Test
  @DisplayName("旧版 Workflow 定义缓存 JSON 可读取为新的查询视图")
  void shouldReadExistingWorkflowDefinitionCacheJson() {
    String cachedJson = """
        {"id":17,"tenantId":"t1","workflowCode":"WF1","workflowName":"Name",
         "workflowType":"STANDARD","version":2,"enabled":true,
         "singleJobDefinitionId":41,"calendarCode":"CN"}
        """;
    when(redis.opsForValue()).thenReturn(values);
    when(values.get("workflow-cache")).thenReturn(cachedJson);
    OrchestratorRedisSupport support = new OrchestratorRedisSupport(redis, new ObjectMapper());

    WorkflowDefinitionView cached = support.getJson("workflow-cache", WorkflowDefinitionView.class);

    assertThat(cached)
        .isEqualTo(new WorkflowDefinitionView(17L, "t1", "WF1", "Name", "STANDARD", 2, true, 41L, "CN"));
  }

  @Test
  @DisplayName("旧版 Job 定义缓存 JSON 保留执行参数与依赖策略")
  void shouldReadExistingJobDefinitionCacheJson() {
    String cachedJson = """
        {"id":23,"tenantId":"t1","jobCode":"JOB1","jobName":"Import",
         "jobType":"IMPORT","bizType":"PAYROLL","scheduleType":"MANUAL",
         "scheduleExpr":null,"timezone":"UTC","workerGroup":"workers",
         "queueCode":"queue","calendarCode":null,"windowCode":null,
         "triggerMode":"API","dagEnabled":false,"shardStrategy":"NONE",
         "retryPolicy":"FIXED","retryMaxCount":3,"timeoutSeconds":120,
         "executionHandler":"handler","paramSchema":{"type":"object"},
         "priority":5,"defaultParams":{"limit":100},"version":2,"enabled":true,
         "description":null,"executionMode":"FULL","watermarkField":null,
         "previousDayDependencyScope":"INHERIT","jobGroupCode":"GROUP1"}
        """;
    when(redis.opsForValue()).thenReturn(values);
    when(values.get("job-cache")).thenReturn(cachedJson);
    OrchestratorRedisSupport support = new OrchestratorRedisSupport(redis, new ObjectMapper());

    JobDefinitionView cached = support.getJson("job-cache", JobDefinitionView.class);

    assertThat(cached.id()).isEqualTo(23L);
    assertThat(cached.jobCode()).isEqualTo("JOB1");
    assertThat(cached.defaultParams()).containsEntry("limit", 100);
    assertThat(cached.previousDayDependencyScope()).isEqualTo("INHERIT");
    assertThat(cached.jobGroupCode()).isEqualTo("GROUP1");
  }

  @Test
  @DisplayName("不存在版本键表示初始版本，已有版本按实际数值返回")
  void shouldDistinguishInitialAndExistingRevisions() {
    when(redis.opsForValue()).thenReturn(values);
    when(values.get(wildcardKey())).thenReturn(null, "4");
    when(values.get(exactKey())).thenReturn(null, "5");
    OrchestratorRedisSupport support = new OrchestratorRedisSupport(redis, new ObjectMapper());

    assertThat(support.configInvalidationVersions("t1", "job-definition", "JOB1"))
        .isEqualTo("0:0");
    assertThat(support.configInvalidationVersions("t1", "job-definition", "JOB1"))
        .isEqualTo("4:5");
  }

  @Test
  @DisplayName("Redis 连接失败或版本格式损坏时不得当成初始版本")
  void shouldReportUnavailable_whenRevisionCannotBeRead() {
    when(redis.opsForValue()).thenReturn(values);
    when(values.get(wildcardKey()))
        .thenThrow(new RedisConnectionFailureException("connection unavailable"))
        .thenReturn("invalid", "0", "0");
    when(values.get(exactKey()))
        .thenThrow(new RedisConnectionFailureException("connection unavailable"))
        .thenThrow(new QueryTimeoutException("command timed out"));
    OrchestratorRedisSupport support = new OrchestratorRedisSupport(redis, new ObjectMapper());

    assertThat(support.configInvalidationVersions("t1", "job-definition", "JOB1"))
        .isNull();
    assertThat(support.configInvalidationVersions("t1", "job-definition", "JOB1"))
        .isNull();
    assertThat(support.configInvalidationVersions("t1", "job-definition", "JOB1"))
        .isNull();
    assertThat(support.configInvalidationVersions("t1", "job-definition", "JOB1"))
        .isNull();
  }

  @Test
  @DisplayName("Redis 命令超时时缓存读取回退并跳过缓存删除")
  void shouldDegradeCacheReadAndWrite_whenRedisCommandTimesOut() {
    when(redis.opsForValue()).thenReturn(values);
    when(values.get("cache-key")).thenThrow(new QueryTimeoutException("read timed out"));
    when(redis.delete("cache-key")).thenThrow(new QueryTimeoutException("delete timed out"));
    OrchestratorRedisSupport support = new OrchestratorRedisSupport(redis, new ObjectMapper());

    assertThat(support.getStringCache("cache-key")).isNull();
    assertThatCode(() -> support.evictCache("cache-key")).doesNotThrowAnyException();
  }

  @ParameterizedTest
  @ValueSource(strings = {"*", "JOB1"})
  @DisplayName("两次独立读取间发生通配或精确失效时，组合版本反映读取顺序")
  void shouldReadIndependentRevisions_whenInvalidationOccursBetweenReads(String changedCode) {
    AtomicReference<String> wildcard = new AtomicReference<>("0");
    AtomicReference<String> exact = new AtomicReference<>("0");
    AtomicBoolean first = new AtomicBoolean(true);
    when(redis.opsForValue()).thenReturn(values);
    when(values.get(wildcardKey())).thenAnswer(invocation -> {
      String selected = wildcard.get();
      if (first.compareAndSet(true, false)) {
        if ("*".equals(changedCode)) {
          wildcard.set("1");
        } else {
          exact.set("1");
        }
      }
      return selected;
    });
    when(values.get(exactKey())).thenAnswer(invocation -> exact.get());
    OrchestratorRedisSupport support = new OrchestratorRedisSupport(redis, new ObjectMapper());

    String during = support.configInvalidationVersions("t1", "job-definition", "JOB1");
    String after = support.configInvalidationVersions("t1", "job-definition", "JOB1");
    assertThat(during).isEqualTo("*".equals(changedCode) ? "0:0" : "0:1");
    assertThat(after).isEqualTo("*".equals(changedCode) ? "1:0" : "0:1");
  }

  @Test
  @DisplayName("直接失效先推进配置精确版本，再推进全局对账版本")
  void shouldAdvanceExactAndGlobalRevisions_whenDirectlyInvalidating() {
    when(redis.opsForValue()).thenReturn(values);
    OrchestratorRedisSupport support = new OrchestratorRedisSupport(redis, new ObjectMapper());

    support.advanceConfigInvalidationRevision("t1", "job-definition", "JOB1");

    InOrder order = inOrder(values);
    order.verify(values).increment(exactKey());
    order.verify(values).increment(BatchRedisKeys.configInvalidationGlobalRevision());
  }

  private static String wildcardKey() {
    return BatchRedisKeys.configInvalidationKeyRevision("t1", "job-definition", "*");
  }

  private static String exactKey() {
    return BatchRedisKeys.configInvalidationKeyRevision("t1", "job-definition", "JOB1");
  }
}

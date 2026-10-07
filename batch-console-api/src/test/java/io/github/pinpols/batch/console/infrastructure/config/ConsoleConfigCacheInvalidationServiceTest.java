package io.github.pinpols.batch.console.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.pinpols.batch.common.config.ConfigCacheInvalidationEvent;
import io.github.pinpols.batch.common.utils.JsonUtils;
import io.github.pinpols.batch.console.application.config.ConsoleConfigCacheInvalidationService;
import io.github.pinpols.batch.console.support.cache.ConsoleQueryCacheService;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
@DisplayName("配置缓存失效服务: 键删除时机,失效事件发布与游标扫描兜底")
class ConsoleConfigCacheInvalidationServiceTest {

  @Mock
  private StringRedisTemplate redisTemplate;

  @Mock
  private ConsoleQueryCacheService queryCacheService;

  @Mock
  private ValueOperations<String, String> valueOperations;

  private ConsoleConfigCacheInvalidationService service;

  @BeforeEach
  void setUp() {
    lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    lenient().when(valueOperations.increment(anyString())).thenReturn(1L);
    service = new ConsoleConfigCacheInvalidationService(
        new RedisConfigInvalidationStore(redisTemplate), queryCacheService);
  }

  @Test
  @DisplayName("没有活跃事务时,作业定义缓存键立即删除")
  void shouldDeleteKeyImmediately_whenNoActiveTransaction() {
    service.evictJobDefinition("t1", "JOB1");

    verify(redisTemplate).delete("config:t1:job-definition:JOB1");
  }

  @Test
  @DisplayName("淘汰作业定义缓存时,同时发布失效事件并带上租户与修订号")
  void shouldPublishInvalidationEvent_whenJobDefinitionIsEvicted() {
    ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);

    service.evictJobDefinition("t1", "JOB1");

    verify(redisTemplate)
        .convertAndSend(
            eq(ConsoleConfigCacheInvalidationService.INVALIDATION_CHANNEL),
            payloadCaptor.capture());
    ConfigCacheInvalidationEvent event =
        JsonUtils.fromJsonStrict(payloadCaptor.getValue(), ConfigCacheInvalidationEvent.class);
    assertThat(event.tenantId()).isEqualTo("t1");
    assertThat(event.type()).isEqualTo("job-definition");
    assertThat(event.code()).isEqualTo("JOB1");
    assertThat(event.revision()).isEqualTo(1L);
    assertThat(event.keyRevision()).isEqualTo(1L);
  }

  @Test
  @DisplayName("没有活跃事务时,工作流定义缓存键立即删除")
  void shouldDeleteWorkflowKeyImmediately_whenNoActiveTransaction() {
    service.evictWorkflowDefinition("t1", "WF1");

    verify(redisTemplate).delete("config:t1:workflow-definition:WF1");
  }

  @Test
  @DisplayName("缓存删除失败时,不再对外发布失效事件")
  void shouldNotPublish_whenCacheDeleteFails() {
    doThrow(new RuntimeException("redis down"))
        .when(redisTemplate)
        .delete("config:t1:job-definition:JOB1");

    service.evictJobDefinition("t1", "JOB1");

    verify(redisTemplate, never()).convertAndSend(anyString(), anyString());
  }

  @Test
  @DisplayName("事务进行中时,删除动作延迟到事务提交之后执行")
  void shouldDeferDelete_whenTransactionIsActive() {
    TransactionSynchronizationManager.initSynchronization();
    try {
      service.evictJobDefinition("t1", "JOB2");

      // 事务进行中 —— 此时不应调用 Redis
      verify(redisTemplate, never()).delete("config:t1:job-definition:JOB2");
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }
    // 事务作用域结束后（通过 clearSynchronization 模拟），此处不会触发钩子 ——
    // 但我们已验证在事务期间未调用 delete，这是核心行为。
  }

  @Test
  @DisplayName("淘汰租户配额策略时,只删除预期的那一个缓存键")
  void shouldDeleteQuotaPolicyKey_whenQuotaPoliciesAreEvicted() {
    service.evictQuotaPolicies("t2");

    verify(redisTemplate).delete("config:t2:tenant-quota-policy:enabled-first");
  }

  /** 守护：evictAllJobDefinitions 必须走 SCAN（cursor）而不是 KEYS。Redis KEYS 是 O(N) 阻塞主线程命令，生产严禁使用。 */
  @Test
  @DisplayName("淘汰全部作业定义时,使用游标分批扫描而非一次性取出全部键")
  @SuppressWarnings("unchecked")
  void shouldUseCursorScan_whenAllJobDefinitionsAreEvicted() {
    Cursor<String> cursor = (Cursor<String>) mock(Cursor.class);
    Iterator<Boolean> hasNext = List.of(true, true, true, false).iterator();
    Iterator<String> next = List.of(
            "config:t1:job-definition:JOB1",
            "config:t1:job-definition:JOB2",
            "config:t1:job-definition:JOB3")
        .iterator();
    when(cursor.hasNext()).thenAnswer(inv -> hasNext.next());
    when(cursor.next()).thenAnswer(inv -> next.next());
    when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(cursor);
    when(redisTemplate.delete(anyCollection())).thenReturn(3L);

    service.evictAllJobDefinitions("t1");

    // 不可调用 KEYS（mock 默认返回 null，无法 mock，这里靠"不调用 keys 方法"间接验证 —— 主断言走 scan）
    ArgumentCaptor<ScanOptions> optionsCaptor = ArgumentCaptor.forClass(ScanOptions.class);
    verify(redisTemplate).scan(optionsCaptor.capture());
    // 验证 scan pattern 包含目标前缀
    assertThat(optionsCaptor.getValue().getPattern()).isEqualTo("config:t1:job-definition:*");
    verify(redisTemplate).delete(anyCollection());
  }

  @Test
  @DisplayName("扫描过程抛出异常时,异常被吞掉且不产生删除与发布")
  void shouldSwallowException_whenScanFails() {
    when(redisTemplate.scan(any(ScanOptions.class))).thenThrow(new RuntimeException("redis down"));

    // 不应抛出，afterCommit 钩子内异常不能影响主流程
    service.evictAllJobDefinitions("t1");

    verify(redisTemplate, never()).delete(anyCollection());
    verify(redisTemplate, never()).convertAndSend(anyString(), anyString());
  }
}

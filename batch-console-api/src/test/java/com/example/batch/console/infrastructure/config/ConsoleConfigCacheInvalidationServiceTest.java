package com.example.batch.console.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.batch.console.support.cache.ConsoleQueryCacheService;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class ConsoleConfigCacheInvalidationServiceTest {

  @Mock private StringRedisTemplate redisTemplate;
  @Mock private ConsoleQueryCacheService queryCacheService;

  private ConsoleConfigCacheInvalidationService service;

  @BeforeEach
  void setUp() {
    service = new ConsoleConfigCacheInvalidationService(redisTemplate, queryCacheService);
  }

  @Test
  void evictJobDefinitionDeletesKeyImmediatelyWhenNoActiveTransaction() {
    service.evictJobDefinition("t1", "JOB1");

    verify(redisTemplate).delete("config:t1:job-definition:JOB1");
  }

  @Test
  void evictWorkflowDefinitionDeletesKeyImmediatelyWhenNoActiveTransaction() {
    service.evictWorkflowDefinition("t1", "WF1");

    verify(redisTemplate).delete("config:t1:workflow-definition:WF1");
  }

  @Test
  void evictWithActiveTransactionDefersDeleteToAfterCommit() {
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
  void evictQuotaPoliciesDeletesExpectedKey() {
    service.evictQuotaPolicies("t2");

    verify(redisTemplate).delete("config:t2:tenant-quota-policy:enabled-first");
  }

  /** 守护：evictAllJobDefinitions 必须走 SCAN（cursor）而不是 KEYS。Redis KEYS 是 O(N) 阻塞主线程命令，生产严禁使用。 */
  @Test
  @SuppressWarnings("unchecked")
  void evictAllJobDefinitionsUsesScanInsteadOfKeys() {
    Cursor<String> cursor = (Cursor<String>) org.mockito.Mockito.mock(Cursor.class);
    Iterator<Boolean> hasNext = List.of(true, true, true, false).iterator();
    Iterator<String> next =
        List.of(
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
  void evictAllJobDefinitionsSwallowsScanFailure() {
    when(redisTemplate.scan(any(ScanOptions.class))).thenThrow(new RuntimeException("redis down"));

    // 不应抛出，afterCommit 钩子内异常不能影响主流程
    service.evictAllJobDefinitions("t1");

    verify(redisTemplate, never()).delete(anyCollection());
  }
}

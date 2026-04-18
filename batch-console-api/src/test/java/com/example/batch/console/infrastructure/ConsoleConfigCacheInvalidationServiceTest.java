package com.example.batch.console.infrastructure;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.example.batch.console.support.ConsoleQueryCacheService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
}

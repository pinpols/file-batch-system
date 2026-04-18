package com.example.batch.console.infrastructure;

import com.example.batch.console.support.ConsoleQueryCacheService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class ConsoleConfigCacheInvalidationService {

  private final StringRedisTemplate redisTemplate;
  private final ConsoleQueryCacheService queryCacheService;

  public void evictJobDefinition(String tenantId, String jobCode) {
    evictAfterCommit(configKey(tenantId, "job-definition", jobCode));
  }

  public void evictAllJobDefinitions(String tenantId) {
    evictByPatternAfterCommit("config:%s:job-definition:*".formatted(safe(tenantId)));
  }

  public void evictWorkflowDefinition(String tenantId, String workflowCode) {
    evictAfterCommit(configKey(tenantId, "workflow-definition", workflowCode));
  }

  public void evictBusinessCalendar(String tenantId, String calendarCode) {
    evictAfterCommit(configKey(tenantId, "business-calendar", calendarCode));
  }

  public void evictBatchWindow(String tenantId, String windowCode) {
    evictAfterCommit(configKey(tenantId, "batch-window", windowCode));
  }

  public void evictQuotaPolicies(String tenantId) {
    evictAfterCommit(configKey(tenantId, "tenant-quota-policy", "enabled-first"));
  }

  /** 配置变更后同步清除 meta 查询缓存（队列/日历/窗口等下拉选项）。 */
  public void evictMetaOptions(String tenantId) {
    queryCacheService.evictMetaOptions(tenantId);
  }

  private void evictByPatternAfterCommit(String pattern) {
    if (!StringUtils.hasText(pattern)) {
      return;
    }
    Runnable evict =
        () -> {
          var keys = redisTemplate.keys(pattern);
          if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
          }
        };
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              evict.run();
            }
          });
      return;
    }
    evict.run();
  }

  private void evictAfterCommit(String key) {
    if (!StringUtils.hasText(key)) {
      return;
    }
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              redisTemplate.delete(key);
            }
          });
      return;
    }
    redisTemplate.delete(key);
  }

  private String configKey(String tenantId, String type, String code) {
    return "config:%s:%s:%s".formatted(safe(tenantId), safe(type), safe(code));
  }

  private String safe(String value) {
    if (value == null || value.isBlank()) {
      return "_";
    }
    return value.replace(':', '_');
  }
}

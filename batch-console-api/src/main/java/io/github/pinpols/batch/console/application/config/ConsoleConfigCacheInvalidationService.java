package io.github.pinpols.batch.console.application.config;

import io.github.pinpols.batch.common.config.ConfigCacheInvalidationEvent;
import io.github.pinpols.batch.common.logging.LogSanitizer;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.common.utils.Texts;
import io.github.pinpols.batch.console.support.cache.ConsoleQueryCacheService;
import io.github.pinpols.batch.console.mapper.ConfigCacheInvalidationOutboxMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Console 配置变更后的 Redis 缓存失效服务：把 console 的配置写操作与 orchestrator 读热点（ {@code
 * OrchestratorConfigCacheService}）之间的缓存一致性收敛到这里。
 *
 * <p>核心约束：
 *
 * <ul>
 *   <li><b>afterCommit 执行</b>：所有 evict 都注册为事务同步的 {@code afterCommit} 钩子，事务成功提交后才 DEL—— 事务未提交就 DEL
 *       会导致"缓存先空 → 被别的并发读请求用旧 DB 数据重新填回"，反而把异常数据写进缓存。 无事务上下文时退化为立即 DEL。
 *   <li><b>Key 约定</b>：{@code config:{tenantId}:{type}:{code}}。{@link #safe} 把 key 组件里的 {@code ':'}
 *       替换成 {@code '_'}，防止 tenantId / code 含冒号造成 key 分段歧义。
 *   <li><b>全租户清</b>：{@link #evictAllJobDefinitions} 走 <b>SCAN（非 KEYS）+ 分批 DEL</b>—— Redis {@code
 *       KEYS} 是 O(N) 阻塞主线程命令，生产严禁；SCAN 通过 cursor 增量扫描，每批最多 {@link #SCAN_BATCH_SIZE} 个 key 后立即 DEL，
 *       避免一次 DEL 大批量阻塞。batch toggle 等"不知道具体哪些 code 被动到"的场景下宁可全清，保证不漏。
 *   <li><b>Meta 选项另走一路</b>：下拉选项缓存由 {@link ConsoleQueryCacheService} 维护（非 orchestrator 读热点）， {@link
 *       #evictMetaOptions} 单独清除。
 * </ul>
 */
@Slf4j
@Service
public class ConsoleConfigCacheInvalidationService {

  public static final String INVALIDATION_CHANNEL = "batch:config:invalidation";

  /** SCAN 单次返回上限 + DEL 单批次上限：太小 → SCAN 轮次多；太大 → 单 DEL 阻塞。500 是经验折中。 */
  private static final int SCAN_BATCH_SIZE = 500;

  private final ConfigInvalidationStore invalidationStore;
  private final ConfigCacheInvalidationOutboxMapper outbox;
  private final ConsoleQueryCacheService queryCacheService;
  private final AtomicLong publishedRevision = new AtomicLong(0);
  private final Counter publishSuccessCounter;
  private final Counter publishFailureCounter;

  @Autowired
  public ConsoleConfigCacheInvalidationService(
      ConfigInvalidationStore invalidationStore,
      ConsoleQueryCacheService queryCacheService,
      ConfigCacheInvalidationOutboxMapper outbox,
      ObjectProvider<MeterRegistry> meterRegistryProvider) {
    this.invalidationStore = invalidationStore;
    this.outbox = outbox;
    this.queryCacheService = queryCacheService;
    MeterRegistry meterRegistry = meterRegistryProvider == null // empty-check: allow - Sonar S2259
        ? null
        : meterRegistryProvider.getIfAvailable();
    Metrics metrics = meterRegistry == null // empty-check: allow - Sonar S2259
        ? Metrics.empty()
        : createMetrics(meterRegistry, publishedRevision);
    this.publishSuccessCounter = metrics.successCounter();
    this.publishFailureCounter = metrics.failureCounter();
  }

  public ConsoleConfigCacheInvalidationService(
      ConfigInvalidationStore invalidationStore, ConsoleQueryCacheService queryCacheService) {
    this.invalidationStore = invalidationStore;
    this.outbox = null;
    this.queryCacheService = queryCacheService;
    Metrics metrics = Metrics.empty();
    this.publishSuccessCounter = metrics.successCounter();
    this.publishFailureCounter = metrics.failureCounter();
  }

  private static Metrics createMetrics(MeterRegistry meterRegistry, AtomicLong publishedRevision) {
    meterRegistry.gauge("batch.console.config.invalidation.published_revision", publishedRevision);
    return new Metrics(
        meterRegistry.counter(
            "batch.console.config.invalidation.publish.total", "result", "success"),
        meterRegistry.counter(
            "batch.console.config.invalidation.publish.total", "result", "failure"));
  }

  private record Metrics(Counter successCounter, Counter failureCounter) {
    private static Metrics empty() {
      return new Metrics(null, null);
    }
  }

  public void evictJobDefinition(String tenantId, String jobCode) {
    evictAfterCommit(tenantId, "job-definition", jobCode);
  }

  public void evictAllJobDefinitions(String tenantId) {
    String sanitizedTenantId = safe(tenantId);
    String jobDefinitionPattern = "config:%s:job-definition:*".formatted(sanitizedTenantId);
    evictByPatternAfterCommit(tenantId, "job-definition", "*", jobDefinitionPattern);
  }

  public void evictAllWorkflowDefinitions(String tenantId) {
    String sanitizedTenantId = safe(tenantId);
    String workflowDefinitionPattern =
        "config:%s:workflow-definition:*".formatted(sanitizedTenantId);
    evictByPatternAfterCommit(tenantId, "workflow-definition", "*", workflowDefinitionPattern);
  }

  public void evictAllBusinessCalendars(String tenantId) {
    String sanitizedTenantId = safe(tenantId);
    String businessCalendarPattern = "config:%s:business-calendar:*".formatted(sanitizedTenantId);
    evictByPatternAfterCommit(tenantId, "business-calendar", "*", businessCalendarPattern);
  }

  public void evictAllBatchWindows(String tenantId) {
    String sanitizedTenantId = safe(tenantId);
    String batchWindowPattern = "config:%s:batch-window:*".formatted(sanitizedTenantId);
    evictByPatternAfterCommit(tenantId, "batch-window", "*", batchWindowPattern);
  }

  public void evictWorkflowDefinition(String tenantId, String workflowCode) {
    evictAfterCommit(tenantId, "workflow-definition", workflowCode);
  }

  public void evictBusinessCalendar(String tenantId, String calendarCode) {
    evictAfterCommit(tenantId, "business-calendar", calendarCode);
  }

  public void evictBatchWindow(String tenantId, String windowCode) {
    evictAfterCommit(tenantId, "batch-window", windowCode);
  }

  public void evictQuotaPolicies(String tenantId) {
    evictAfterCommit(tenantId, "tenant-quota-policy", "enabled-first");
  }

  /** 手动失效没有配置表写入，须先保存待投递意图。 */
  public void evictManual(String tenantId, String type, String code) {
    outbox.insert(tenantId, type, code);
    if ("*".equals(code)) {
      String pattern = "config:%s:%s:*".formatted(safe(tenantId), safe(type));
      evictByPatternAfterCommit(tenantId, type, code, pattern);
    } else {
      evictAfterCommit(tenantId, type, code);
    }
  }

  /**
   * 配置变更后同步清除 meta 查询缓存（队列/日历/窗口等下拉选项）。
   *
   * <p>R3-P1-14：之前直接调 evict，事务回滚窗口内会让并发读者拿到 pre-rollback 数据回填缓存； 与其他 evictXxx 一致改走 afterCommit，DB
   * 提交后才删缓存。
   */
  public void evictMetaOptions(String tenantId) {
    Runnable evict = () -> queryCacheService.evictMetaOptions(tenantId);
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          evict.run();
        }
      });
      return;
    }
    evict.run();
  }

  private void evictByPatternAfterCommit(
      String tenantId, String type, String code, String pattern) {
    if (!Texts.hasText(pattern)) {
      return;
    }
    Runnable evict = () -> {
      if (scanAndDelete(pattern)) {
        publishInvalidation(tenantId, type, code);
      }
    };
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          evict.run();
        }
      });
      return;
    }
    evict.run();
  }

  /**
   * SCAN-based pattern 删除：走 {@link RedisKeyUtils#scanAndDelete} 通用工具，异常被工具层捕获并抑制避免影响 afterCommit
   * 钩子流程；残余 key 走 TTL（5min）自然清理。
   */
  private boolean scanAndDelete(String pattern) {
    try {
      long deleted = invalidationStore.scanAndDelete(pattern, SCAN_BATCH_SIZE);
      if (deleted > 0) {
        log.debug(
            "evicted {} redis keys matching pattern={}", deleted, LogSanitizer.value(pattern));
      }
      return true;
    } catch (RuntimeException exception) {
      increment(publishFailureCounter);
      String sanitizedPattern = LogSanitizer.value(pattern);
      String reasonSummary = SwallowedExceptionLogger.summary(exception);
      log.warn(
          "config cache redis pattern delete failed: pattern={}, reason={}",
          sanitizedPattern,
          reasonSummary);
      log.debug(
          "config cache redis pattern delete failure: pattern={}",
          LogSanitizer.value(pattern),
          exception);
      return false;
    }
  }

  private void evictAfterCommit(String tenantId, String type, String code) {
    String key = configKey(tenantId, type, code);
    if (!Texts.hasText(key)) {
      return;
    }
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          deleteAndPublish(key, tenantId, type, code);
        }
      });
      return;
    }
    deleteAndPublish(key, tenantId, type, code);
  }

  private void deleteAndPublish(String key, String tenantId, String type, String code) {
    try {
      invalidationStore.delete(key);
    } catch (RuntimeException exception) {
      increment(publishFailureCounter);
      String sanitizedKey = LogSanitizer.value(key);
      String reasonSummary = SwallowedExceptionLogger.summary(exception);
      log.warn("config cache redis delete failed: key={}, reason={}", sanitizedKey, reasonSummary);
      log.debug("config cache redis delete failure: key={}", LogSanitizer.value(key), exception);
      return;
    }
    publishInvalidation(tenantId, type, code);
  }

  private void publishInvalidation(String tenantId, String type, String code) {
    try {
      publishStrict(tenantId, type, code);
    } catch (RuntimeException exception) {
      increment(publishFailureCounter);
      String sanitizedTenantId = LogSanitizer.value(tenantId);
      String sanitizedType = LogSanitizer.value(type);
      String sanitizedCode = LogSanitizer.value(code);
      String reasonSummary = SwallowedExceptionLogger.summary(exception);
      log.warn(
          "config cache invalidation publish failed: tenantId={}, type={}, code={}, reason={}",
          sanitizedTenantId,
          sanitizedType,
          sanitizedCode,
          reasonSummary);
      log.debug(
          "config cache invalidation publish failure: tenantId={}, type={}, code={}",
          LogSanitizer.value(tenantId),
          LogSanitizer.value(type),
          LogSanitizer.value(code),
          exception);
    }
  }

  /** 严格投递供持久队列使用，失败必须传播以保留待重试记录。 */
  public void deliver(ConfigCacheInvalidationIntent intent) {
    String tenantId = intent.tenantId();
    String type = intent.configType();
    String code = intent.configCode();
    if ("*".equals(code)) {
      String pattern = "config:%s:%s:*".formatted(safe(tenantId), safe(type));
      invalidationStore.scanAndDelete(pattern, SCAN_BATCH_SIZE);
    } else {
      invalidationStore.delete(configKey(tenantId, type, code));
    }
    publishStrict(tenantId, type, code);
  }

  private void publishStrict(String tenantId, String type, String code) {
    Long keyRevision = Objects.requireNonNull(invalidationStore.nextKeyRevision(tenantId, type, code));
    Long revision = Objects.requireNonNull(invalidationStore.nextGlobalRevision());
    String safeTenantId = safe(tenantId);
    String safeType = safe(type);
    String safeCode = safe(code);
    Instant occurredAt = Instant.now();
    ConfigCacheInvalidationEvent event = new ConfigCacheInvalidationEvent(
        safeTenantId, safeType, safeCode, revision, keyRevision, occurredAt);
    invalidationStore.publish(event);
    publishedRevision.set(event.revision());
    increment(publishSuccessCounter);
  }

  private void increment(Counter counter) {
    if (EmptyChecks.isNotNull(counter)) {
      counter.increment();
    }
  }

  private String configKey(String tenantId, String type, String code) {
    String safeTenantId = safe(tenantId);
    String safeType = safe(type);
    String safeCode = safe(code);
    return "config:%s:%s:%s".formatted(safeTenantId, safeType, safeCode);
  }

  private String safe(String value) {
    if (EmptyChecks.isBlank(value)) {
      return "_";
    }
    return value.replace(':', '_');
  }
}

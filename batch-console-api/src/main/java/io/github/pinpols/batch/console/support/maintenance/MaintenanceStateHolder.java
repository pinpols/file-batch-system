package io.github.pinpols.batch.console.support.maintenance;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.logging.SwallowedExceptionLogger;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.console.config.ConsoleMaintenanceProperties;
import io.github.pinpols.batch.console.domain.ops.mapper.MaintenanceStateMapper;
import io.github.pinpols.batch.console.domain.ops.param.MaintenanceStateUpdateParam;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 运行时维护状态持有者。
 *
 * <p>启动时从 {@link ConsoleMaintenanceProperties} 取初始值,运行时由 admin 通过 {@code PUT
 * /api/console/admin/system/maintenance} 热更新,无须重启。
 *
 * <p>用 {@link AtomicReference}+不可变 record 持有,读路径 lock-free;数据库是跨副本唯一事实源,后台按版本轮询收敛。
 */
@Component
@Slf4j
public class MaintenanceStateHolder {

  private final ConsoleMaintenanceProperties properties;
  private final MaintenanceStateMapper mapper;
  private final ObjectMapper objectMapper;

  private final AtomicReference<MaintenanceState> state =
      new AtomicReference<>(MaintenanceState.disabled());
  private final ScheduledExecutorService refreshExecutor =
      Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "console-maintenance-state-refresh");
        thread.setDaemon(true);
        return thread;
      });

  public MaintenanceStateHolder(
      ConsoleMaintenanceProperties properties,
      MaintenanceStateMapper mapper,
      ObjectMapper objectMapper) {
    this.properties = properties;
    this.mapper = mapper;
    this.objectMapper = objectMapper;
  }

  @PostConstruct
  void initFromProperties() {
    List<String> affectedServices = EmptyChecks.isNull(properties.getAffectedServices())
        ? List.of()
        : List.copyOf(properties.getAffectedServices());
    MaintenanceState initialState = new MaintenanceState(
        properties.isEnabled(),
        properties.isReadOnly(),
        properties.getMessage(),
        properties.getEtaAt(),
        null,
        affectedServices,
        0L,
        Instant.now(),
        false);
    state.set(initialState);
  }

  @Order(Ordered.HIGHEST_PRECEDENCE)
  @EventListener(ApplicationReadyEvent.class)
  public void refreshAfterApplicationReady() {
    // 已解析的顶层 Workflow 启动请求，后续委派给 Trigger 处理。
    // 避免在 @PostConstruct 阶段数据库迁移尚未完成时查询状态。
    refreshFromStore();
    refreshExecutor.scheduleWithFixedDelay(this::refreshFromStore, 5, 5, TimeUnit.SECONDS);
  }

  public MaintenanceState current() {
    return state.get();
  }

  /** Admin 热更新入口。通过数据库 version CAS 完全替换当前状态。 */
  @Transactional
  public MaintenanceState update(MaintenanceState next) {
    return update(next, "console-admin");
  }

  /** Admin 热更新入口,使用当前操作员写入共享状态审计字段。 */
  @Transactional
  public MaintenanceState update(MaintenanceState next, String operator) {
    boolean enabled = next.enabled();
    boolean readOnly = next.readOnly();
    String message = next.message();
    Instant etaAt = next.etaAt();
    Instant startsAt = next.startsAt();
    List<String> affectedServices = normalizeServices(next.affectedServices());
    MaintenanceState normalized = new MaintenanceState(
        enabled,
        readOnly,
        message,
        etaAt,
        startsAt,
        affectedServices,
        state.get().version(),
        state.get().updatedAt(),
        true);
    MaintenanceState current = state.get();
    String servicesJson = writeServices(normalized.affectedServices());
    MaintenanceStateUpdateParam updateParam = MaintenanceStateUpdateParam.builder()
        .enabled(normalized.enabled())
        .readOnly(normalized.readOnly())
        .message(normalized.message())
        .etaAt(normalized.etaAt())
        .startsAt(normalized.startsAt())
        .affectedServicesJson(servicesJson)
        .updatedBy(EmptyChecks.isBlank(operator) ? "console-admin" : operator)
        .expectedVersion(current.version())
        .build();
    int updated = mapper.updateIfVersion(updateParam);
    if (updated != 1) {
      refreshFromStore();
      throw new ConcurrentModificationException("maintenance state changed; reload and retry");
    }
    MaintenanceState applied = new MaintenanceState(
        normalized.enabled(),
        normalized.readOnly(),
        normalized.message(),
        normalized.etaAt(),
        normalized.startsAt(),
        normalized.affectedServices(),
        current.version() + 1,
        Instant.now(),
        true);
    // 请求拦截和通知同步只能观察已提交状态；事务回滚不能留下虚假的维护窗口。
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          state.set(applied);
        }
      });
    } else {
      state.set(applied);
    }
    return applied;
  }

  /** 共享状态不可确认时保持写保护，直至当前副本重新确认状态。 */
  public boolean sharedStateAvailable() {
    return state.get().sharedStateAvailable();
  }

  @PreDestroy
  void shutdown() {
    refreshExecutor.shutdownNow();
  }

  private void refreshFromStore() {
    try {
      MaintenanceStateEntity entity = mapper.selectSingleton();
      if (EmptyChecks.isNull(entity)) {
        throw new IllegalStateException("maintenance state singleton is missing");
      }
      List<String> services = objectMapper.readValue(
          entity.getAffectedServicesJson(), new TypeReference<List<String>>() {});
      List<String> normalizedServices = normalizeServices(services);
      state.set(new MaintenanceState(
          entity.isEnabled(),
          entity.isReadOnly(),
          entity.getMessage(),
          entity.getEtaAt(),
          entity.getStartsAt(),
          normalizedServices,
          entity.getVersion(),
          entity.getUpdatedAt(),
          true));
    } catch (Exception ex) {
      state.updateAndGet(current -> current.withSharedStateAvailable(false));
      log.warn(
          "maintenance state refresh failed; writes remain blocked until recovery: {}",
          SwallowedExceptionLogger.summary(ex));
    }
  }

  private List<String> normalizeServices(List<String> services) {
    return List.copyOf(EmptyChecks.isNull(services) ? List.of() : services);
  }

  private String writeServices(List<String> services) {
    try {
      List<String> normalizedServices = normalizeServices(services);
      return objectMapper.writeValueAsString(normalizedServices);
    } catch (Exception ex) {
      throw new IllegalArgumentException("invalid maintenance affectedServices", ex);
    }
  }

  /**
   * 不可变维护状态快照。
   *
   * @param enabled 总开关
   * @param readOnly true=只读(GET 通过 / 写拒);false 且 enabled=true → 整站拒
   * @param message 用户可见原因(自由文本,前端 banner 展示)
   * @param etaAt 预计恢复时间(ISO-8601,可空)
   * @param startsAt 计划开始维护时间;空表示保存后立即生效
   * @param affectedServices 受影响子系统 code 列表(前端按 service 展示,空 list=整站)
   * @param version 共享状态版本,用于乐观 CAS
   * @param updatedAt 最近一次共享状态更新时间
   * @param sharedStateAvailable 当前副本是否已确认数据库共享状态
   */
  public record MaintenanceState(
      boolean enabled,
      boolean readOnly,
      String message,
      Instant etaAt,
      Instant startsAt,
      List<String> affectedServices,
      long version,
      Instant updatedAt,
      boolean sharedStateAvailable) {
    public MaintenanceState(
        boolean enabled,
        boolean readOnly,
        String message,
        Instant etaAt,
        List<String> affectedServices) {
      this(enabled, readOnly, message, etaAt, null, affectedServices, 0L, null, false);
    }

    public MaintenanceState(
        boolean enabled,
        boolean readOnly,
        String message,
        Instant etaAt,
        Instant startsAt,
        List<String> affectedServices) {
      this(enabled, readOnly, message, etaAt, startsAt, affectedServices, 0L, null, false);
    }

    public static MaintenanceState disabled() {
      return new MaintenanceState(false, false, null, null, null, List.of(), 0L, null, false);
    }

    private MaintenanceState withSharedStateAvailable(boolean available) {
      return new MaintenanceState(
          enabled,
          readOnly,
          message,
          etaAt,
          startsAt,
          affectedServices,
          version,
          updatedAt,
          available);
    }

    public boolean active(Instant now) {
      return enabled && (EmptyChecks.isNull(startsAt) || !startsAt.isAfter(now));
    }
  }
}

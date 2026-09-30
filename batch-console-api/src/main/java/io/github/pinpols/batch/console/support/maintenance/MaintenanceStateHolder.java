package io.github.pinpols.batch.console.support.maintenance;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import io.github.pinpols.batch.console.config.ConsoleMaintenanceProperties;
import io.github.pinpols.batch.console.domain.ops.mapper.MaintenanceStateMapper;
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
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

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
    state.set(new MaintenanceState(
        properties.isEnabled(),
        properties.isReadOnly(),
        properties.getMessage(),
        properties.getEtaAt(),
        List.copyOf(
            EmptyChecks.isNull(properties.getAffectedServices())
                ? List.of()
                : properties.getAffectedServices()),
        0L,
        Instant.now(),
        false));
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
    MaintenanceState normalized = new MaintenanceState(
        next.enabled(),
        next.readOnly(),
        next.message(),
        next.etaAt(),
        normalizeServices(next.affectedServices()),
        state.get().version(),
        state.get().updatedAt(),
        true);
    MaintenanceState current = state.get();
    String servicesJson = writeServices(normalized.affectedServices());
    int updated = mapper.updateIfVersion(
        normalized.enabled(),
        normalized.readOnly(),
        normalized.message(),
        normalized.etaAt(),
        servicesJson,
        EmptyChecks.isBlank(operator) ? "console-admin" : operator,
        current.version());
    if (updated != 1) {
      refreshFromStore();
      throw new ConcurrentModificationException("maintenance state changed; reload and retry");
    }
    MaintenanceState applied = new MaintenanceState(
        normalized.enabled(),
        normalized.readOnly(),
        normalized.message(),
        normalized.etaAt(),
        normalized.affectedServices(),
        current.version() + 1,
        Instant.now(),
        true);
    state.set(applied);
    return applied;
  }

  /** shared state unavailable means writes must fail closed until a replica can confirm it. */
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
      state.set(new MaintenanceState(
          entity.isEnabled(),
          entity.isReadOnly(),
          entity.getMessage(),
          entity.getEtaAt(),
          normalizeServices(services),
          entity.getVersion(),
          entity.getUpdatedAt(),
          true));
    } catch (Exception ex) {
      state.updateAndGet(current -> current.withSharedStateAvailable(false));
      log.warn(
          "maintenance state refresh failed; writes remain blocked until recovery: {}",
          ex.getMessage());
    }
  }

  private List<String> normalizeServices(List<String> services) {
    return List.copyOf(EmptyChecks.isNull(services) ? List.of() : services);
  }

  private String writeServices(List<String> services) {
    try {
      return objectMapper.writeValueAsString(normalizeServices(services));
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
      this(enabled, readOnly, message, etaAt, affectedServices, 0L, null, false);
    }

    public static MaintenanceState disabled() {
      return new MaintenanceState(false, false, null, null, List.of(), 0L, null, false);
    }

    private MaintenanceState withSharedStateAvailable(boolean available) {
      return new MaintenanceState(
          enabled, readOnly, message, etaAt, affectedServices, version, updatedAt, available);
    }
  }
}

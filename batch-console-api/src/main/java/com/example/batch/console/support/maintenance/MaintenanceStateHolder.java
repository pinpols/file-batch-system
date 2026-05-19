package com.example.batch.console.support.maintenance;

import com.example.batch.console.config.ConsoleMaintenanceProperties;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 运行时维护状态持有者。
 *
 * <p>启动时从 {@link ConsoleMaintenanceProperties} 取初始值,运行时由 admin 通过 {@code PUT
 * /api/console/admin/system/maintenance} 热更新,无须重启。
 *
 * <p>用 {@link AtomicReference}+不可变 record 持有,读路径 lock-free;写路径 admin 偶发调用 compareAndSet 即可。
 *
 * <p>不持久化:重启后回到 application.yml 配置(配合运维 SOP:紧急切换在 admin UI,长期配置仍写 yml)。
 */
@Component
@RequiredArgsConstructor
public class MaintenanceStateHolder {

  private final ConsoleMaintenanceProperties properties;

  private final AtomicReference<MaintenanceState> state =
      new AtomicReference<>(MaintenanceState.disabled());

  @PostConstruct
  void initFromProperties() {
    state.set(
        new MaintenanceState(
            properties.isEnabled(),
            properties.isReadOnly(),
            properties.getMessage(),
            properties.getEtaAt(),
            List.copyOf(
                properties.getAffectedServices() == null
                    ? List.of()
                    : properties.getAffectedServices())));
  }

  public MaintenanceState current() {
    return state.get();
  }

  /** Admin 热更新入口。完全替换当前状态。 */
  public MaintenanceState update(MaintenanceState next) {
    MaintenanceState normalized =
        new MaintenanceState(
            next.enabled(),
            next.readOnly(),
            next.message(),
            next.etaAt(),
            List.copyOf(next.affectedServices() == null ? List.of() : next.affectedServices()));
    state.set(normalized);
    return normalized;
  }

  /**
   * 不可变维护状态快照。
   *
   * @param enabled 总开关
   * @param readOnly true=只读(GET 通过 / 写拒);false 且 enabled=true → 整站拒
   * @param message 用户可见原因(自由文本,前端 banner 展示)
   * @param etaAt 预计恢复时间(ISO-8601,可空)
   * @param affectedServices 受影响子系统 code 列表(前端按 service 展示,空 list=整站)
   */
  public record MaintenanceState(
      boolean enabled,
      boolean readOnly,
      String message,
      Instant etaAt,
      List<String> affectedServices) {
    public static MaintenanceState disabled() {
      return new MaintenanceState(false, false, null, null, List.of());
    }
  }
}

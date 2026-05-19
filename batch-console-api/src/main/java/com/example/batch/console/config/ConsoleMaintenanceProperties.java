package com.example.batch.console.config;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Console maintenance / degradation 模式启动期配置(application.yml / env)。
 *
 * <p>使用场景:DB 灰度切换 / 上线滚动期 / 紧急回滚 / 数据修复等需要冻结写操作的窗口。开启后:
 *
 * <ul>
 *   <li>{@code enabled=true} 且 {@code readOnly=false}:除白名单 / admin 外整站 503,前端跳 /maintenance 降级页
 *   <li>{@code enabled=true} 且 {@code readOnly=true}:GET 放行,POST/PUT/PATCH/DELETE 返 503
 * </ul>
 *
 * <p>白名单永远放行:{@code /actuator/**}、{@code /api/console/auth/check}、{@code
 * /api/console/system/maintenance}。**ROLE_ADMIN 角色用户全程透传**(灰度期间运维仍可登 console 操作)。
 *
 * <p><b>启动期 vs 运行时</b>:本类只是冷启动初值,运行时状态走 {@link
 * com.example.batch.console.support.maintenance.MaintenanceStateHolder} 的 AtomicReference; admin 通过
 * PUT {@code /api/console/admin/system/maintenance} 热更新,无须重启。
 *
 * <p>SOP 见 {@code docs/runbook/maintenance-mode.md}。
 */
@Data
@ConfigurationProperties(prefix = "batch.console.maintenance")
public class ConsoleMaintenanceProperties {

  /** 维护模式总开关。默认 false。 */
  private boolean enabled = false;

  /** 用户可见的维护原因 / 提示语,前端 banner 直接展示。 */
  private String message;

  /** 预计恢复时间(ISO-8601)。前端展示 ETA 倒计时。允许为空。 */
  private Instant etaAt;

  /** 只读模式:GET 通过,写方法(POST/PUT/PATCH/DELETE)返 503。默认 false=整站拒绝。 */
  private boolean readOnly = false;

  /**
   * 受影响功能 / 子系统列表(前端按 service 展示哪些功能挂了)。 例如:{@code ["job-schedule", "file-download"]}。空 list 表示
   * "整站维护",前端 message 兜底显示。
   */
  private List<String> affectedServices = new ArrayList<>();
}

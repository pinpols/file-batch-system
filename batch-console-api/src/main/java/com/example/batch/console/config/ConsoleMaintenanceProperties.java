package com.example.batch.console.config;

import java.time.Instant;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Console maintenance / degradation 模式开关。
 *
 * <p>使用场景:DB 灰度切换 / 上线滚动期 / 紧急回滚 / 数据修复等需要冻结写操作的窗口。开启后:
 *
 * <ul>
 *   <li>{@code enabled=true} 且 {@code readOnly=false}:除白名单外整站 503,前端跳 /maintenance 降级页
 *   <li>{@code enabled=true} 且 {@code readOnly=true}:GET 放行,POST/PUT/PATCH/DELETE 返 503
 * </ul>
 *
 * <p>白名单永远放行:{@code /actuator/**}、{@code /api/console/auth/check}、{@code
 * /api/console/system/maintenance}。前端通过 maintenance 状态接口轮询,自动恢复后退出降级。
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
}

package io.github.pinpols.batch.orchestrator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 配额（quota）相关配置：
 *
 * <ul>
 *   <li>{@link #runtimeStore}：运行时状态后端选择，{@code redis}（默认）走 Lua 原子脚本， {@code database} 走 PG @Version
 *       乐观锁（遗留路径，故障降级用）。
 *   <li>{@link Snapshot}：Redis → PG 周期 snapshot 配置；仅 {@code runtimeStore=redis} 生效。
 *   <li>{@link BackendGuard}：状态后端切换的一次性 cutover 标记。
 * </ul>
 */
@Data
@ConfigurationProperties(prefix = "batch.quota")
public class QuotaProperties {

  private String runtimeStore = "redis";

  private Snapshot snapshot = new Snapshot();

  private BackendGuard backendGuard = new BackendGuard();

  @Data
  public static class Snapshot {
    private boolean enabled = true;
    private long intervalMillis = 300_000L;
  }

  @Data
  public static class BackendGuard {
    /** 后端或连接定位变化时必须换用新的非空值；首次基线登记不需要。 */
    private String cutoverId = "";
  }
}

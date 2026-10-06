package io.github.pinpols.batch.worker.atomic.runtime;

import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Atomic executor 生产守护配置（{@code batch.worker.executors.guard}）。
 *
 * <p>控制 {@link AtomicExecutorProductionGuard} 的强制拦截范围：{@code always-enforce} 任意环境强制，
 * {@code enforce-profiles} 追加需要 fail-closed 的 profile；两者都不影响 prod-like profile 的默认拦截。
 */
@Data
@ConfigurationProperties(prefix = "batch.worker.executors.guard")
public class AtomicExecutorGuardProperties {

  /** 任意环境强制启用 fail-closed 校验（默认 false，仅由 prod-like profile 触发）。 */
  private boolean alwaysEnforce = false;

  /** 除 prod-like profile 外，额外需要 fail-closed 校验的 profile 列表（大小写不敏感）。 */
  private List<String> enforceProfiles = List.of();
}

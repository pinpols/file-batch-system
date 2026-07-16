package io.github.pinpols.batch.common.rls;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Phase A · RLS 闭世界守护配置（{@code batch.rls}）。
 *
 * <p>{@link #startupFailFast} 默认 {@code true}：业务数据源无法证明所有租户表已启用 RLS 时，启动直接失败。 非生产测试库若没有业务表或
 * RLS，应显式关闭该守门并使用独立的测试配置；不能把默认安全策略降级为放行。
 *
 * <p>{@link #exemptTables} 默认只豁免 {@code __shard_identity} —— 它是本地/分片路由识别用的非租户元数据表。除这类 <b>非租户的 biz
 * 元数据表</b>外,业务表一律不豁免。
 */
@Data
@ConfigurationProperties(prefix = "batch.rls")
public class RlsProperties {

  /**
   * 启动期闭世界检查 fail-fast 开关。默认 {@code true}=缺 RLS 或业务库不可达时启动抛 {@code
   * IllegalStateException}；仅测试或明确的非业务上下文允许显式关闭。
   */
  private boolean startupFailFast = true;

  /**
   * biz 表豁免清单（带或不带 {@code biz.} 前缀均可）。
   *
   * <p>{@code __shard_identity} 是分片路由活体验证写入的单行元数据表,不承载租户业务数据；把它纳入闭世界 RLS 会让 import / export /
   * process worker readiness 误报 DOWN。
   */
  private List<String> exemptTables = new ArrayList<>(List.of("__shard_identity"));
}

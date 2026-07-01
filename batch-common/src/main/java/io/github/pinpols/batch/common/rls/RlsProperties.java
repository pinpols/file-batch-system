package io.github.pinpols.batch.common.rls;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Phase A · RLS 闭世界守护配置（{@code batch.rls}）。
 *
 * <p>{@link #startupFailFast} 默认 {@code false}：启动期<b>不阻断</b>,只靠 {@link RlsPolicyHealthIndicator} 报
 * DOWN 可见。运维确认部署一定跑过 rls-phase-a 脚本后,可设 {@code batch.rls.startup-fail-fast=true} 让缺 RLS 直接
 * fail-fast 拒绝启动。
 *
 * <p>{@link #exemptTables} 默认只豁免 {@code __shard_identity} —— 它是本地/分片路由识别用的非租户元数据表。除这类 <b>非租户的 biz
 * 元数据表</b>外,业务表一律不豁免。
 */
@Data
@ConfigurationProperties(prefix = "batch.rls")
public class RlsProperties {

  /**
   * 启动期闭世界检查 fail-fast 开关。默认 {@code false}=不阻断启动(只 health DOWN 可见)；{@code true}=缺 RLS 时启动抛 {@code
   * IllegalStateException}。
   */
  private boolean startupFailFast = false;

  /**
   * biz 表豁免清单（带或不带 {@code biz.} 前缀均可）。
   *
   * <p>{@code __shard_identity} 是分片路由活体验证写入的单行元数据表,不承载租户业务数据；把它纳入闭世界 RLS 会让 import / export /
   * process worker readiness 误报 DOWN。
   */
  private List<String> exemptTables = new ArrayList<>(List.of("__shard_identity"));
}

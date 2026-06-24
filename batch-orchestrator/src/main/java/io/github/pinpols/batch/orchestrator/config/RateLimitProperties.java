package io.github.pinpols.batch.orchestrator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "batch.rate-limit")
public class RateLimitProperties {

  /**
   * 总开关：关闭则不做限流。默认开启——防接口盗刷的第一道闸门，api_key 泄漏后靠它把"被打爆"挡在租户级。 生产紧急情况可经 {@code
   * BATCH_RATE_LIMIT_ENABLED=false} 关闸。
   */
  private boolean enabled = true;

  /**
   * 每租户每分钟最大新建（launch）请求数；<=0 表示关闭该项。 默认 3000（=50/s），远高于任何合法单租 launch 速率（launch 消费本身单线程、是吞吐瓶颈，
   * 正常远到不了），只拦截 runaway 滥用，不误伤压测/高峰。需更严可经 env 下调。
   */
  private long maxNewRequestsPerTenantPerMinute = 3000;

  /** 每租户每分钟最大释放（waiting partition dispatch release）请求数；<=0 表示关闭该项。默认 3000（=50/s）高水位。 */
  private long maxReleaseRequestsPerTenantPerMinute = 3000;

  /**
   * 每租户每分钟最大 worker 注册请求数；<=0 表示关闭该项。默认 300（=5/s）——worker 注册是低频动作，5/s 已是异常风暴水位。 只对 register
   * 入口生效，防注册风暴；claim / report / heartbeat 等高频热路径另见下方 TASK_* 限流。
   */
  private long maxRegisterRequestsPerTenantPerMinute = 300;

  /**
   * 每租户每分钟最大 task claim 请求数（含 claim-batch，按 HTTP 调用计）；<=0 表示关闭该项。 默认 12000（=200/s）——claim 是热路径，按绑定
   * api_key 的租户聚合（workerId 可伪造故不按 worker），阈值设在控制面合法峰值之上 （单机 ~20 jobs/s、PG 有 10-15× 余量），只拦截 api_key
   * 泄漏后的 runaway。需更严可经 env 下调。
   */
  private long maxClaimRequestsPerTenantPerMinute = 12000;

  /**
   * 每租户每分钟最大 task report 请求数（含 report-batch，按 HTTP 调用计）；<=0 表示关闭该项。默认 12000（=200/s）高水位，理由同 claim。
   */
  private long maxReportRequestsPerTenantPerMinute = 12000;
}

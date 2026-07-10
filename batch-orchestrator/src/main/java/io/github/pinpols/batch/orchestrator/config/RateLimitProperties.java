package io.github.pinpols.batch.orchestrator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

@Data
@ConfigurationProperties(prefix = "batch.rate-limit")
public class RateLimitProperties {

  /**
   * 总开关：关闭则不做限流。默认开启——防接口盗刷的第一道闸门，api_key 泄漏后靠它把"被打爆"挡在租户级。 生产紧急情况可经 {@code
   * BATCH_RATE_LIMIT_ENABLED=false} 关闸。
   */
  private boolean enabled = true;

  /**
   * Redis 短路熔断配置：Redis 长时间慢故障时，连续超时判定其不健康后，限流器直接 fail-open 放行不再发 Redis 命令， 省掉每请求叠加的 {@code
   * requestTimeout}(500ms) 阻塞（见 {@code RedisRateLimitCircuitBreaker}）。热路径 claim/report
   * (12000/min≈200/s) 在慢故障下不再被 Redis 拖垮线程池。
   */
  @NestedConfigurationProperty private CircuitBreaker redisCircuitBreaker = new CircuitBreaker();

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

  /**
   * Redis 限流交互的短路熔断参数。语义：连续 {@link #consecutiveFailures} 次 Redis 命令级故障 / bucket4j 超时后进入 OPEN 窗口，窗口内
   * {@code tryConsume} 直接 fail-open 放行且<b>不发 Redis 命令</b>（省掉 {@code requestTimeout} 阻塞）；窗口 {@link
   * #openWindowMillis} 到期后放 {@link #halfOpenProbes} 个探测请求，成功恢复 CLOSED、失败重开窗口。
   *
   * <p>底层用 resilience4j COUNT_BASED 滑动窗口（size=minCalls={@code
   * consecutiveFailures}、failureRate=100%） 表达"连续 N 次失败才熔断"——窗口内任一次成功即打断连续计数，不误熔断。fail-open
   * 方向不变：熔断打开=放行不是拒绝。
   */
  @Data
  public static class CircuitBreaker {

    /**
     * 短路熔断开关。关闭则退回 #782 的纯 {@code catch(RedisException|TimeoutException)} fail-open（每请求仍阻塞至多
     * requestTimeout）。
     */
    private boolean enabled = true;

    /** 触发熔断所需的连续失败次数（Redis 命令级故障或 bucket4j 超时）。默认 5：足够躲开偶发抖动，又能在持续慢故障下快速止血。 */
    private int consecutiveFailures = 5;

    /** OPEN 窗口时长（毫秒）：窗口内 tryConsume 短路直接 fail-open 不发 Redis 命令。默认 5s，到期转 HALF_OPEN 探测。 */
    private long openWindowMillis = 5_000L;

    /** HALF_OPEN 允许的探测请求数（真发 Redis 命令）：全成功→CLOSED，任一失败→重开 OPEN 窗口。默认 1，单探测最省。 */
    private int halfOpenProbes = 1;
  }
}

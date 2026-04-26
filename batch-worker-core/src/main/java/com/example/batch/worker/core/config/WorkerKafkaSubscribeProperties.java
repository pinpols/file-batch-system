package com.example.batch.worker.core.config;

import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * P2-5 worker 端 Kafka 订阅模式开关（{@code batch.worker.kafka}）。
 *
 * <p>{@link Mode#PATTERN}（默认）：宽松正则同时匹配 SINGLE / TENANT / PRIORITY 三种 producer 输出形态，新增
 * tenant/priority 后缀 topic 自动被 worker 拾取。
 *
 * <p>{@link Mode#FIXED}：仅订阅 base + node-direct，不订阅任何后缀 topic。海量 tenant 场景下 元数据刷新成本可控；也用作 PATTERN
 * 模式异常时的回退路径。Producer 端处于 SINGLE 模式时本模式等价。
 *
 * <p>{@link Mode#TENANT_SCOPED}：worker 物理隔离场景，只订阅 {@link #tenantAllowlist} 列出的租户 后缀 topic。例如 worker
 * pool A 只服务 t1/t2，pool B 服务 t3/t4。需要配合 producer 的 TENANT 模式使用；SINGLE / PRIORITY 模式下 base/priority
 * topic 也会被订阅以保持兼容。
 */
@Data
@ConfigurationProperties(prefix = "batch.worker.kafka")
public class WorkerKafkaSubscribeProperties {

  public enum Mode {
    PATTERN,
    FIXED,
    TENANT_SCOPED
  }

  private Mode subscribeMode = Mode.PATTERN;

  /** TENANT_SCOPED 模式生效；其他模式忽略。空列表等价于不订阅任何 tenant 后缀 topic。 */
  private List<String> tenantAllowlist = List.of();
}

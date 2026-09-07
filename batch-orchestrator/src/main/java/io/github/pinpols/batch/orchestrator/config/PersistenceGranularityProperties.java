package io.github.pinpols.batch.orchestrator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 根据任务规模选择分区持久化粒度的灰度参数。
 *
 * <p>该策略只决定一个实例生成多少个独立 partition/task，不省略任何运行时或审计表记录。调用方显式指定的每分区条数、字节数仍具有最高优先级。
 */
@Data
@ConfigurationProperties(prefix = "batch.orchestrator.persistence-granularity")
public class PersistenceGranularityProperties {

  /** 默认关闭，避免已有 DYNAMIC/AUTO 作业的分区数在升级后变化。 */
  private boolean enabled;

  /** 不超过该条数时使用单分区紧凑持久化。 */
  private long compactMaxItems = 100_000L;

  /** 不超过该字节数时使用单分区紧凑持久化。 */
  private long compactMaxBytes = 256L * 1024 * 1024;

  /** 不超过该条数时归入标准规模，超过后归入大规模。 */
  private long standardMaxItems = 10_000_000L;

  /** 不超过该字节数时归入标准规模，超过后归入大规模。 */
  private long standardMaxBytes = 8L * 1024 * 1024 * 1024;

  /** 标准规模每个分区的目标条数。 */
  private long standardTargetItems = 1_000_000L;

  /** 大规模每个分区的目标条数。 */
  private long largeTargetItems = 500_000L;

  /** 标准规模每个分区的目标字节数。 */
  private long standardTargetBytes = 512L * 1024 * 1024;

  /** 大规模每个分区的目标字节数。 */
  private long largeTargetBytes = 256L * 1024 * 1024;
}

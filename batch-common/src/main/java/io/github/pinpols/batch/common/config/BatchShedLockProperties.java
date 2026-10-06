package io.github.pinpols.batch.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ShedLock 共享配置（{@code batch.shedlock}）。
 *
 * <p>把原先 {@code BatchShedLockAutoConfiguration} 里的
 * {@code @Value("${batch.shedlock.auto-create:false}")} 与
 * {@code @Value("${batch.shedlock.redis.key-prefix-env:...}")} 字符串 key 读取收敛为类型安全配置。
 * 保留原有 key，避免 Helm / Compose / 文档三处漂移。
 */
@Data
@ConfigurationProperties(prefix = "batch.shedlock")
public class BatchShedLockProperties {

  /** 启动期回退建 {@code batch.shedlock} 表；仅 dev / 测试，prod 由 Flyway 迁移建表。 */
  private boolean autoCreate = false;

  private Redis redis = new Redis();

  /** Redis 锁后端参数。 */
  @Data
  public static class Redis {

    /** 锁命名空间前缀；留空时回退 {@code spring.application.name}。 */
    private String keyPrefixEnv = "";
  }
}

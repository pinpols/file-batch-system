package io.github.pinpols.batch.common.config;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 只读 Spring 基础设施配置探针。
 *
 * <p>把启动守护读取 {@code spring.datasource.url} / {@code spring.data.redis.*} 实际生效值的逻辑收敛到一处，
 * 避免同类读取散落在多个守护类里。
 *
 * <p>本类只读取 Spring 基础设施配置的生效值，<b>不</b>把这些 {@code spring.*} 复制成自定义 {@code
 * @ConfigurationProperties}，也不作为业务规则来源（配置 Key 治理文档 §5.3 / §7）。默认值仅在计算后端身份时
 * 作为回退，与 Spring Boot Redis 客户端一致。
 */
@Component
public class RuntimeInfrastructureInspector {

  /** 公开常量：供测试与其它只读消费方复用同一份 key，避免字面量在测试里再次漂移。 */
  public static final String DATASOURCE_URL_KEY = "spring.datasource.url";

  public static final String REDIS_HOST_KEY = "spring.data.redis.host";
  public static final String REDIS_PORT_KEY = "spring.data.redis.port";
  public static final String REDIS_DATABASE_KEY = "spring.data.redis.database";
  public static final String REDIS_SENTINEL_MASTER_KEY = "spring.data.redis.sentinel.master";
  public static final String REDIS_SENTINEL_NODES_KEY = "spring.data.redis.sentinel.nodes";

  private final Environment environment;

  public RuntimeInfrastructureInspector(Environment environment) {
    this.environment = environment;
  }

  /** 平台库 JDBC URL；缺失即 fail-fast，与直接读取 {@code getRequiredProperty} 语义一致。 */
  public String datasourceUrl() {
    return environment.getRequiredProperty(DATASOURCE_URL_KEY);
  }

  /** Redis 连接坐标，仅用于后端身份判定。 */
  public RedisCoordinates redisCoordinates() {
    return new RedisCoordinates(
        environment.getProperty(REDIS_HOST_KEY, "localhost"),
        environment.getProperty(REDIS_PORT_KEY, Integer.class, 6379),
        environment.getProperty(REDIS_DATABASE_KEY, Integer.class, 0),
        environment.getProperty(REDIS_SENTINEL_MASTER_KEY),
        environment.getProperty(REDIS_SENTINEL_NODES_KEY));
  }

  /** Redis 连接坐标快照。 */
  public record RedisCoordinates(
      String host, int port, int database, String sentinelMaster, String sentinelNodes) {}
}

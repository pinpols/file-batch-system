package io.github.pinpols.batch.common.config;

import io.github.pinpols.batch.common.stateful.StatefulBackendIdentity;
import io.github.pinpols.batch.common.utils.EmptyChecks;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 只读 Spring 基础设施配置探针。
 *
 * <p>把启动守护读取 {@code spring.datasource.url} / {@code spring.data.redis.*} 实际生效值的逻辑收敛到一处，
 * 避免同类读取散落在多个守护类里。
 *
 * <p>平台库地址从环境读取；Redis位置使用与框架客户端相同的 {@link DataRedisConnectionDetails}，
 * 保留URL、列表绑定与连接信息覆盖的优先级，不重复解析属性、不读取凭据或建立网络连接。
 * 这些基础设施属性不复制为自定义配置，也不作为业务规则来源。
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
  private final ObjectProvider<DataRedisConnectionDetails> redisConnectionDetails;

  public RuntimeInfrastructureInspector(
      Environment environment, ObjectProvider<DataRedisConnectionDetails> redisConnectionDetails) {
    this.environment = environment;
    this.redisConnectionDetails = redisConnectionDetails;
  }

  /** 平台库 JDBC URL；缺失即 fail-fast，与直接读取 {@code getRequiredProperty} 语义一致。 */
  public String datasourceUrl() {
    return environment.getRequiredProperty(DATASOURCE_URL_KEY);
  }

  /** Redis定位与Boot客户端模式选择一致；无有效连接信息时拒绝伪造默认位置。 */
  public String redisBackendIdentity() {
    DataRedisConnectionDetails details = redisConnectionDetails.getIfAvailable();
    if (EmptyChecks.isNull(details)) {
      throw new IllegalStateException(
          "Redis connection details are required for stateful backend identity");
    }
    DataRedisConnectionDetails.Sentinel sentinel = details.getSentinel();
    if (EmptyChecks.isNotNull(sentinel)) {
      String nodes = formatNodes(sentinel.getNodes());
      return StatefulBackendIdentity.redisSentinel(
          sentinel.getMaster(), nodes, sentinel.getDatabase());
    }
    DataRedisConnectionDetails.Cluster cluster = details.getCluster();
    if (EmptyChecks.isNotNull(cluster)) {
      String nodes = formatNodes(cluster.getNodes());
      return StatefulBackendIdentity.redisCluster(nodes);
    }
    DataRedisConnectionDetails.MasterReplica masterReplica = details.getMasterReplica();
    if (EmptyChecks.isNotNull(masterReplica)) {
      String nodes = formatNodes(masterReplica.getNodes());
      return StatefulBackendIdentity.redisMasterReplica(nodes);
    }
    DataRedisConnectionDetails.Standalone standalone = details.getStandalone();
    if (EmptyChecks.isNull(standalone)) {
      throw new IllegalStateException("Redis standalone connection details are unavailable");
    }
    return StatefulBackendIdentity.redisStandalone(
        standalone.getHost(), standalone.getPort(), standalone.getDatabase());
  }

  private static String formatNodes(List<DataRedisConnectionDetails.Node> nodes) {
    return nodes.stream()
        .map(node -> node.host() + ":" + node.port())
        .collect(Collectors.joining(","));
  }
}

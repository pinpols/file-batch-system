package io.github.pinpols.batch.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** 复用真实Boot配置绑定，不连接Redis；守卫必须读取与客户端相同的连接信息。 */
@DisplayName("基础设施探针:Redis位置跟随框架生效配置")
class RuntimeInfrastructureInspectorTest {

  private final ApplicationContextRunner runner = new ApplicationContextRunner()
      .withClassLoader(new FilteredClassLoader("io.lettuce", "redis.clients.jedis"))
      .withConfiguration(AutoConfigurations.of(
          DataRedisAutoConfiguration.class, BatchRuntimeIdentityAutoConfiguration.class));

  @Test
  @DisplayName("默认独立节点仍使用既有主机端口与数据库身份")
  void shouldKeepStandaloneDefaults_whenNoOverrides() {
    runner.run(context -> assertThat(
            context.getBean(RuntimeInfrastructureInspector.class).redisBackendIdentity())
        .isEqualTo("host=localhost|port=6379|db=0"));
  }

  @Test
  @DisplayName("URL覆盖单项主机端口和数据库，身份不得包含用户名密码")
  void shouldUseUrlCoordinatesWithoutCredentials_whenUrlOverridesProperties() {
    runner
        .withPropertyValues(
            "spring.data.redis.host=ignored",
            "spring.data.redis.port=6381",
            "spring.data.redis.database=1",
            "spring.data.redis.url=redis://alice:secret@bound-redis:6380/4")
        .run(context -> assertThat(
                context.getBean(RuntimeInfrastructureInspector.class).redisBackendIdentity())
            .isEqualTo("host=bound-redis|port=6380|db=4")
            .doesNotContain("alice", "secret"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"spring.data.redis.sentinel.nodes", "spring.data.redis.sentinel.nodes[0]"})
  @DisplayName("Sentinel逗号串与索引列表均保留实际节点及主名大小写")
  void shouldBindSentinelNodes_whenFlatOrIndexed(String property) {
    runner
        .withPropertyValues(
            "spring.data.redis.sentinel.master=Orders", property + "=sentinel-a:26379")
        .run(context -> assertThat(
                context.getBean(RuntimeInfrastructureInspector.class).redisBackendIdentity())
            .isEqualTo("sentinel=Orders|nodes=sentinel-a:26379|db=0"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"spring.data.redis.cluster.nodes", "spring.data.redis.cluster.nodes[0]"})
  @DisplayName("集群节点位置不能退化成默认独立节点")
  void shouldKeepClusterIdentity_whenNodesUseFlatOrIndexedProperties(String property) {
    runner
        .withPropertyValues(property + "=cluster-a:6379")
        .run(context -> assertThat(
                context.getBean(RuntimeInfrastructureInspector.class).redisBackendIdentity())
            .isEqualTo("cluster=cluster-a:6379"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"spring.data.redis.masterreplica.nodes", "spring.data.redis.masterreplica.nodes[0]"
      })
  @DisplayName("静态主副本节点位置不能退化成默认独立节点")
  void shouldKeepMasterReplicaIdentity_whenNodesUseFlatOrIndexedProperties(String property) {
    runner
        .withPropertyValues(property + "=primary-a:6379")
        .run(context -> assertThat(
                context.getBean(RuntimeInfrastructureInspector.class).redisBackendIdentity())
            .isEqualTo("master-replica=primary-a:6379"));
  }

  @Test
  @DisplayName("连接信息Bean覆盖属性时使用框架提供位置")
  void shouldUseConnectionDetailsOverride_whenPropertiesAreNotEffective() {
    runner
        .withPropertyValues("spring.data.redis.host=ignored")
        .withBean(DataRedisConnectionDetails.class, () -> new DataRedisConnectionDetails() {
          @Override
          public Standalone getStandalone() {
            return Standalone.of("service-redis", 6382, 7);
          }
        })
        .run(context -> assertThat(
                context.getBean(RuntimeInfrastructureInspector.class).redisBackendIdentity())
            .isEqualTo("host=service-redis|port=6382|db=7"));
  }

  @Test
  @DisplayName("没有有效连接信息时失败关闭，不能伪造默认位置放行")
  void shouldRejectMissingConnectionDetails_whenRedisIdentityIsRequired() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(BatchRuntimeIdentityAutoConfiguration.class))
        .run(context -> assertThatThrownBy(
                () -> context.getBean(RuntimeInfrastructureInspector.class).redisBackendIdentity())
            .isInstanceOf(IllegalStateException.class));
  }
}

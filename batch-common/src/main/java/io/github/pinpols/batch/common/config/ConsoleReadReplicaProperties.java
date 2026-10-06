package io.github.pinpols.batch.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Console 读写分离配置的跨模块只读视图（{@code batch.console.read-replica}）。
 *
 * <p>console-api 的完整连接池配置仍由该模块自己的 {@code ReadReplicaProperties} 持有；本类只暴露
 * batch-common 启动守护需要的开关与主 / 从库密码，避免在 {@link ProductionRuntimeConfigurationGuard} 和
 * {@link ReadReplicaCredentialGuard} 里散落字符串 key。两处绑定同一前缀、各自只取所需字段，取值同源不产生分歧。
 *
 * <p><b>与 {@code ReadReplicaProperties} 存在子键重叠，这是仓库内唯一的例外。</b>其余共享前缀的案例都是子键互不重叠
 * （见 {@code docs/standards/java-suppression-registry.md} 的 {@code ConfigurationProperties} 登记行），因此不适用
 * 该行的抑制约定。重叠的叶子键恰好三个：{@code enabled}、{@code primary.password}、{@code replica.password}。
 * 模块依赖方向（batch-common 不能引用 console-api）使该重叠无法靠合并类消除，所以改用测试钉住契约：
 * {@code ReadReplicaPropertiesOverlapConsistencyTest} 在重叠键集合变化、或 {@code enabled} 默认值分歧时失败。
 * 改动任一侧的这三个键之前，先看该测试。
 */
@Data
@ConfigurationProperties(prefix = "batch.console.read-replica")
public class ConsoleReadReplicaProperties {

  /** 读写分离总开关；关闭时守护不校验从库端点与密码。 */
  private boolean enabled = false;

  /** 主库连接凭据（只读取密码用于生产弱口令校验）。 */
  private Credentials primary = new Credentials();

  /** 从库连接凭据（只读取密码用于生产弱口令校验）。 */
  private Credentials replica = new Credentials();

  /** 连接凭据只读视图。 */
  @Data
  public static class Credentials {

    /** 连接密码；未配置时为 {@code null}，守护据此跳过校验。 */
    private String password;
  }
}

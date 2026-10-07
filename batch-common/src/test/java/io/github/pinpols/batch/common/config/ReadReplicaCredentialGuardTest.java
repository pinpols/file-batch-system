package io.github.pinpols.batch.common.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** {@link ReadReplicaCredentialGuard} 单测：生产 profile 弱口令 fail-fast，非生产仅 WARN 不阻断。 */
@DisplayName("只读副本凭据守卫:生产环境弱口令快速失败、口令缺失跳过,以及非生产环境仅告警不阻断")
class ReadReplicaCredentialGuardTest {

  private static ReadReplicaCredentialGuard guard(
      String activeProfile, String primaryPassword, String replicaPassword) {
    MockEnvironment environment = new MockEnvironment();
    if (activeProfile != null) {
      environment.setActiveProfiles(activeProfile);
    }
    ConsoleReadReplicaProperties props = new ConsoleReadReplicaProperties();
    props.getPrimary().setPassword(primaryPassword);
    props.getReplica().setPassword(replicaPassword);
    return new ReadReplicaCredentialGuard(props, environment);
  }

  @Test
  @DisplayName("生产环境主库口令命中已知弱口令时启动失败并指明对应配置项")
  void prodProfile_weakPrimaryPassword_throwsFatal() {
    ReadReplicaCredentialGuard guard = guard("prod", "batch_pass_123", null);

    assertThatThrownBy(guard::afterSingletonsInstantiated)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("batch.console.read-replica.primary.password")
        .hasMessageContaining("known weak password");
  }

  @Test
  @DisplayName("生产环境从库口令命中已知弱口令时启动失败并指明对应配置项")
  void prodProfile_weakReplicaPassword_throwsFatal() {
    ReadReplicaCredentialGuard guard = guard("prod", null, "batch_pass_123");

    assertThatThrownBy(guard::afterSingletonsInstantiated)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("batch.console.read-replica.replica.password")
        .hasMessageContaining("known weak password");
  }

  @Test
  @DisplayName("生产环境主从库口令均为强口令时启动校验通过")
  void prodProfile_strongPasswords_passes() {
    ReadReplicaCredentialGuard guard = guard("prod", "strong-primary-2026", "strong-replica-2026");

    assertThatCode(guard::afterSingletonsInstantiated).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("未引入相关配置前缀导致口令缺失时跳过校验,不影响应用启动")
  void prodProfile_absentPasswords_skips() {
    // 非 console 模块该前缀不存在 → 密码为 null → 守护跳过，不影响启动。
    ReadReplicaCredentialGuard guard = guard("prod", null, null);

    assertThatCode(guard::afterSingletonsInstantiated).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("非生产环境使用默认弱口令时仅记录告警,启动不被阻断")
  void nonProdProfile_weakPassword_warnsNotThrows() {
    // 非 prod + 默认弱口令 batch_pass_123 → 只 WARN，启动不被阻断。
    ReadReplicaCredentialGuard guard = guard("local", "batch_pass_123", "batch_pass_123");

    assertThatCode(guard::afterSingletonsInstantiated).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("未配置激活环境时按生产处理,弱口令必须快速失败")
  void missingProfile_failSecure_treatedAsProduction() {
    // 空激活集按生产对待 → 弱口令必须 fail-fast。
    ReadReplicaCredentialGuard guard = guard(null, "batch_pass_123", null);

    assertThatThrownBy(guard::afterSingletonsInstantiated)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("known weak password");
  }
}
